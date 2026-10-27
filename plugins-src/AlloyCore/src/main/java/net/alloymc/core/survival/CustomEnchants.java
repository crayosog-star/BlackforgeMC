package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * BlackForgeMC custom enchants. Stored on items as persistent data and shown as lore.
 * Apply one by dragging its book onto a piece of gear in your inventory.
 */
public final class CustomEnchants implements Listener {
    public record CE(String id, String name, String color, int max, String targets, String desc) {}

    public static final List<CE> ALL = List.of(
        new CE("lifesteal", "Lifesteal", "<#ff5f7a>", 3, "sword", "Heal for 8% / 14% / 20% of the damage you deal"),
        new CE("thunderstrike", "Thunderstrike", "<#ffe066>", 3, "axe,trident", "6% / 10% / 14% chance to call lightning on hit"),
        new CE("bleed", "Bleed", "<#c0392b>", 3, "axe", "25% / 35% / 45% chance to make the target bleed for 3 / 4 / 5s"),
        new CE("overload", "Overload", "<#ff9f43>", 3, "chestplate", "+2 / +4 / +6 max health"),
        new CE("springs", "Springs", "<#7ddc4a>", 2, "boots", "Permanent Jump Boost I / II"),
        new CE("autosmelt", "Autosmelt", "<#ff7a1a>", 1, "pickaxe", "Ores drop already smelted"),
        new CE("excavator", "Excavator", "<#c6a15b>", 1, "pickaxe,shovel", "Mines a 3x3 area"));

    private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V"};

    private final AlloyCore plugin;
    private final NamespacedKey bookKey;
    private final Map<String, NamespacedKey> keys = new HashMap<>();
    private final NamespacedKey overloadKey;
    private final Map<UUID, BlockFace> lastFace = new HashMap<>();
    private final Set<UUID> excavating = new HashSet<>();
    private final Random rnd = new Random();

    CustomEnchants(AlloyCore plugin) {
        this.plugin = plugin;
        this.bookKey = new NamespacedKey(plugin, "ce_book");
        this.overloadKey = new NamespacedKey(plugin, "overload");
        for (CE ce : ALL) keys.put(ce.id(), new NamespacedKey(plugin, "ce_" + ce.id()));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::armorTick, 20L, 20L);
    }

    public static CE byId(String id) { return ALL.stream().filter(c -> c.id().equalsIgnoreCase(id)).findFirst().orElse(null); }

    public int level(ItemStack it, String id) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer v = it.getItemMeta().getPersistentDataContainer().get(keys.get(id), PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    private static boolean fits(CE ce, Material m) {
        String n = m.name().toLowerCase();
        for (String t : ce.targets().split(",")) {
            if (t.equals("axe") && n.endsWith("_axe")) return true;
            if (!t.equals("axe") && n.endsWith(t)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ books

    public ItemStack book(CE ce, int lvl) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        it.editMeta(m -> {
            m.displayName(Ui.mm(ce.color() + "<bold>" + ce.name() + " " + ROMAN[lvl] + "</bold> <gray>Book"));
            m.lore(List.of(Ui.mm("<gray>" + ce.desc()), Ui.mm("<gray>Works on: <white>" + ce.targets().replace(",", ", ")), Ui.mm(""),
                    Ui.mm("<yellow>Drag onto your gear to apply"), Ui.mm("<dark_gray>BlackForgeMC custom enchant")));
            m.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, ce.id() + ":" + lvl);
        });
        return it;
    }

    public boolean give(Player p, String id, int lvl) {
        CE ce = byId(id);
        if (ce == null) return false;
        lvl = Math.max(1, Math.min(ce.max(), lvl));
        p.getInventory().addItem(book(ce, lvl)).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<gold>✦ <gray>You received a " + ce.color() + ce.name() + " " + ROMAN[lvl] + " <gray>book! Drag it onto your gear."));
        return true;
    }

    /** A random book for loot tables: level 1 mostly, sometimes 2, rarely 3. */
    public ItemStack randomBook() {
        CE ce = ALL.get(rnd.nextInt(ALL.size()));
        int r = rnd.nextInt(100), lvl = r < 70 ? 1 : r < 94 ? 2 : 3;
        return book(ce, Math.min(lvl, ce.max()));
    }

    /** Drag a book onto an item: applies the enchant (or levels it up by combining). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onApply(InventoryClickEvent e) {
        ItemStack cursor = e.getCursor(), target = e.getCurrentItem();
        if (cursor == null || cursor.getType() != Material.ENCHANTED_BOOK || !cursor.hasItemMeta() || target == null || target.getType().isAir()) return;
        String tag = cursor.getItemMeta().getPersistentDataContainer().get(bookKey, PersistentDataType.STRING);
        if (tag == null || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        CE ce = byId(tag.split(":")[0]);
        int lvl = Integer.parseInt(tag.split(":")[1]);
        if (ce == null) return;
        if (!fits(ce, target.getType())) { p.sendMessage(Ui.mm("<red>" + ce.name() + " only works on: " + ce.targets().replace(",", ", "))); return; }
        if (target.getAmount() > 1) { p.sendMessage(Ui.mm("<red>Apply it to a single item.")); return; }
        int cur = level(target, ce.id());
        int next = cur == lvl ? lvl + 1 : Math.max(cur, lvl);
        if (cur >= ce.max() || next > ce.max() || next <= cur) { p.sendMessage(Ui.mm("<red>That item already has " + ce.name() + " " + ROMAN[cur] + ".")); return; }
        target.editMeta(m -> m.getPersistentDataContainer().set(keys.get(ce.id()), PersistentDataType.INTEGER, next));
        relore(target);
        cursor.setAmount(cursor.getAmount() - 1);
        e.getView().setCursor(cursor.getAmount() > 0 ? cursor : null);
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.5f, 1.6f);
        p.sendMessage(Ui.mm("<green>✔ Applied " + ce.color() + ce.name() + " " + ROMAN[next] + "<green>!"));
    }

    /** Rewrite the custom enchant lines at the top of the lore, keeping any other lore. */
    private void relore(ItemStack it) {
        it.editMeta(m -> {
            Set<String> ours = new HashSet<>();
            for (CE ce : ALL) for (int i = 1; i <= ce.max(); i++) ours.add(ce.name() + " " + ROMAN[i]);
            List<Component> keep = new ArrayList<>();
            if (m.hasLore()) for (Component c : m.lore())
                if (!ours.contains(PlainTextComponentSerializer.plainText().serialize(c))) keep.add(c);
            List<Component> lore = new ArrayList<>();
            for (CE ce : ALL) {
                Integer v = m.getPersistentDataContainer().get(keys.get(ce.id()), PersistentDataType.INTEGER);
                if (v != null && v > 0) lore.add(Ui.mm(ce.color() + ce.name() + " " + ROMAN[v]));
            }
            lore.addAll(keep);
            m.lore(lore);
            m.setEnchantmentGlintOverride(true);
        });
    }

    // ------------------------------------------------------------------ combat: lifesteal, thunderstrike, bleed

    private ItemStack weaponOf(EntityDamageByEntityEvent e, Player[] out) {
        if (e.getDamager() instanceof Player p) { out[0] = p; return p.getInventory().getItemInMainHand(); }
        if (e.getDamager() instanceof Trident t && t.getShooter() instanceof Player p) { out[0] = p; return t.getItemStack(); }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        Player[] who = new Player[1];
        ItemStack weapon = weaponOf(e, who);
        Player p = who[0];
        if (p == null || weapon == null || e.getFinalDamage() <= 0) return;

        int ls = level(weapon, "lifesteal");
        if (ls > 0) {
            double heal = e.getFinalDamage() * (0.02 + 0.06 * ls);
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(Math.min(max == null ? 20 : max.getValue(), p.getHealth() + heal));
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2.1, 0), 1);
        }

        int th = level(weapon, "thunderstrike");
        if (th > 0 && rnd.nextDouble() < 0.02 + 0.04 * th) {
            target.getWorld().strikeLightningEffect(target.getLocation());
            Bukkit.getScheduler().runTask(plugin, () -> { if (target.isValid()) target.damage(3 + th, p); });
            p.sendActionBar(Ui.mm("<#ffe066>⚡ Thunderstrike!"));
        }

        int bl = level(weapon, "bleed");
        if (bl > 0 && rnd.nextDouble() < 0.15 + 0.10 * bl) {
            int seconds = 2 + bl;
            p.sendActionBar(Ui.mm("<#c0392b>🩸 Your target is bleeding!"));
            if (target instanceof Player tp) tp.sendActionBar(Ui.mm("<#c0392b>🩸 You're bleeding!"));
            final int[] n = {0};
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                if (!target.isValid() || target.isDead() || ++n[0] > seconds) { task.cancel(); return; }
                target.setNoDamageTicks(0);
                target.damage(1.0);
                target.getWorld().spawnParticle(Particle.BLOCK, target.getLocation().add(0, 1, 0), 12, 0.25, 0.4, 0.25, Bukkit.createBlockData(Material.REDSTONE_BLOCK));
            }, 20L, 20L);
        }
    }

    // ------------------------------------------------------------------ armour: overload, springs

    private void armorTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            int ov = level(p.getInventory().getChestplate(), "overload");
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) {
                AttributeModifier cur = max.getModifier(overloadKey);
                double want = 2.0 * ov;
                if (cur != null && cur.getAmount() != want) { max.removeModifier(overloadKey); cur = null; }
                if (cur == null && ov > 0) max.addModifier(new AttributeModifier(overloadKey, want, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                if (p.getHealth() > max.getValue()) p.setHealth(max.getValue());
            }
            int sp = level(p.getInventory().getBoots(), "springs");
            if (sp > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 60, sp - 1, true, false, true));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastFace.remove(e.getPlayer().getUniqueId());
        AttributeInstance max = e.getPlayer().getAttribute(Attribute.MAX_HEALTH);
        if (max != null && max.getModifier(overloadKey) != null) max.removeModifier(overloadKey);
    }

    // ------------------------------------------------------------------ mining: autosmelt, excavator

    private static final Map<Material, Material> SMELT = Map.of(
        Material.RAW_IRON, Material.IRON_INGOT, Material.RAW_GOLD, Material.GOLD_INGOT, Material.RAW_COPPER, Material.COPPER_INGOT,
        Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP, Material.IRON_ORE, Material.IRON_INGOT, Material.GOLD_ORE, Material.GOLD_INGOT,
        Material.DEEPSLATE_IRON_ORE, Material.IRON_INGOT, Material.DEEPSLATE_GOLD_ORE, Material.GOLD_INGOT, Material.NETHER_GOLD_ORE, Material.GOLD_INGOT);

    private static ItemStack smelt(ItemStack it) {
        Material to = SMELT.get(it.getType());
        return to == null ? it : new ItemStack(to, it.getAmount());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrops(BlockDropItemEvent e) {
        if (level(e.getPlayer().getInventory().getItemInMainHand(), "autosmelt") <= 0) return;
        boolean any = false;
        for (Item drop : e.getItems()) {
            ItemStack s = smelt(drop.getItemStack());
            if (s.getType() != drop.getItemStack().getType()) { drop.setItemStack(s); any = true; }
        }
        if (any) {
            e.getBlock().getWorld().spawnParticle(Particle.FLAME, e.getBlock().getLocation().add(0.5, 0.5, 0.5), 8, 0.25, 0.25, 0.25, 0.01);
            e.getPlayer().giveExp(1);
        }
    }

    /** Which face of the block the player is mining: raytrace their view, falling back to their pitch/yaw. */
    private BlockFace faceMined(Player p, Block center) {
        var hit = p.rayTraceBlocks(7, FluidCollisionMode.NEVER);
        if (hit != null && center.equals(hit.getHitBlock()) && hit.getHitBlockFace() != null) return hit.getHitBlockFace();
        BlockFace remembered = lastFace.get(p.getUniqueId());
        if (remembered != null) return remembered;
        float pitch = p.getLocation().getPitch();
        if (Math.abs(pitch) > 50) return pitch > 0 ? BlockFace.UP : BlockFace.DOWN;
        return p.getFacing().getOppositeFace();
    }

    @EventHandler
    public void onAim(PlayerInteractEvent e) {
        if (e.getAction() == Action.LEFT_CLICK_BLOCK && e.getBlockFace() != null) lastFace.put(e.getPlayer().getUniqueId(), e.getBlockFace());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (level(tool, "excavator") <= 0 || p.isSneaking() || !excavating.add(p.getUniqueId())) return;
        try {
            Block center = e.getBlock();
            BlockFace face = faceMined(p, center);
            boolean smelt = level(tool, "autosmelt") > 0;
            for (int a = -1; a <= 1; a++)
                for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) continue;
                    Block blk = switch (face) {
                        case UP, DOWN -> center.getRelative(a, 0, b);
                        case NORTH, SOUTH -> center.getRelative(a, b, 0);
                        default -> center.getRelative(0, a, b);
                    };
                    Material t = blk.getType();
                    if (t.isAir() || blk.isLiquid() || t.getHardness() < 0 || t.getHardness() > 50 || !blk.isPreferredTool(tool)) continue;
                    // let claims / spawn protection veto each extra block
                    BlockBreakEvent check = new BlockBreakEvent(blk, p);
                    Bukkit.getPluginManager().callEvent(check);
                    if (check.isCancelled()) continue;
                    Collection<ItemStack> drops = blk.getDrops(tool, p);
                    blk.setType(Material.AIR);
                    for (ItemStack d : drops) blk.getWorld().dropItemNaturally(blk.getLocation().add(0.5, 0.5, 0.5), smelt ? smelt(d) : d);
                    if (tool.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm && !tool.getItemMeta().isUnbreakable()) {
                        int unb = tool.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING);
                        if (rnd.nextInt(unb + 1) == 0) {
                            dm.setDamage(dm.getDamage() + 1);
                            tool.setItemMeta(dm);
                            if (dm.getDamage() >= tool.getType().getMaxDurability()) { p.getInventory().setItemInMainHand(null); p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f); return; }
                        }
                    }
                }
        } finally {
            excavating.remove(p.getUniqueId());
        }
    }

    // ------------------------------------------------------------------ /enchants

    public void info(Player p) {
        p.sendMessage(Ui.mm("<gold><bold>BlackForgeMC Custom Enchants</bold> <gray>- find books in crates and supply drops"));
        for (CE ce : ALL)
            p.sendMessage(Ui.mm(ce.color() + "<bold>" + ce.name() + "</bold> <dark_gray>(" + ce.targets().replace(",", "/") + ", max " + ROMAN[ce.max()] + ") <gray>" + ce.desc()));
        p.sendMessage(Ui.mm("<yellow>Drag a book onto gear in your inventory to apply it. Apply a book of the same level again to upgrade it."));
    }
}
