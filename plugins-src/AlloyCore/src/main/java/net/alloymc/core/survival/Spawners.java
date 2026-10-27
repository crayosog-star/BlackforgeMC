package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Spawners, paid for with Mob Essence (BlackForgeMC's take on "souls"):
 *  - Spawner Fragments (rare OneBlock drops) craft an Empty Spawner; right-click it to buy a mob type
 *    with Essence. On OneBlock the choice is every mob from every biome phase the island has reached.
 *  - Every spawner has a Tier (I-V) that rises as its own mobs are killed. The tier caps how far its
 *    Essence upgrades go (spawn time, mob count, stack, range, essence yield) and which mob tiers it can hold.
 *  - Mobs from spawners drop Essence. Upgrades, tier and kills stay on the spawner when Silk-Touched.
 */
public final class Spawners implements Listener {
    static final NamespacedKey ESSENCE = new NamespacedKey("alloycore", "mob_essence");
    static final NamespacedKey FROM_SPAWNER = new NamespacedKey("alloycore", "from_spawner");
    static final NamespacedKey SPAWNER_AT = new NamespacedKey("alloycore", "spawner_at");
    static final NamespacedKey FRAGMENT = new NamespacedKey("alloycore", "spawner_fragment");
    static final NamespacedKey EMPTY = new NamespacedKey("alloycore", "empty_spawner");
    private static final String[] UPGRADES = {"speed", "amount", "stack", "range", "yield"};
    private static final String[] STORED = {"speed", "amount", "stack", "range", "yield", "tier", "kills"};
    static final int MAX_LEVEL = 5;
    static final int[] ESSENCE_COST = {32, 96, 256, 640, 1600};
    static final int[] MONEY_COST = {1_000, 3_000, 8_000, 20_000, 50_000};
    /** Kills of a spawner's own mobs needed to reach tier II, III, IV, V. */
    static final int[] TIER_KILLS = {64, 256, 768, 2000};
    /** Essence to set a mob type, by mob tier 1-5. */
    static final int[] TYPE_COST = {24, 64, 192, 512, 1500};
    private static final double[] SPEED = {1.0, 0.8, 0.65, 0.5, 0.4, 0.3};

    /** Default mob list for spawners outside OneBlock (and the tier every mob belongs to). */
    static final EntityType[] DEFAULT_MOBS = {EntityType.PIG, EntityType.COW, EntityType.CHICKEN, EntityType.SHEEP, EntityType.ZOMBIE, EntityType.SKELETON,
            EntityType.SPIDER, EntityType.CREEPER, EntityType.CAVE_SPIDER, EntityType.WITCH, EntityType.ENDERMAN, EntityType.MAGMA_CUBE, EntityType.BLAZE, EntityType.IRON_GOLEM};

    static int mobTier(EntityType t) {
        return switch (t.name()) {
            case "ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "DROWNED", "HUSK", "STRAY", "ZOMBIE_VILLAGER", "SLIME", "SULFUR_CUBE", "ZOMBIFIED_PIGLIN", "BOGGED" -> 2;
            case "CAVE_SPIDER", "WITCH", "ENDERMAN", "MAGMA_CUBE", "PILLAGER", "ENDERMITE", "PIGLIN", "HOGLIN", "GUARDIAN", "BREEZE" -> 3;
            case "BLAZE", "WITHER_SKELETON", "GHAST", "SHULKER" -> 4;
            case "IRON_GOLEM" -> 5;
            default -> 1;
        };
    }

    private final AlloyCore plugin;
    private BiPredicate<Player, Location> canUse = (p, l) -> true;
    /** Where mob types are limited (OneBlock: mobs of the biomes reached); null = the default list. */
    private Function<Location, Set<EntityType>> allowedAt = l -> null;
    private OneBlockPhases phases;
    private Economy eco;

    Spawners(AlloyCore plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        NamespacedKey rk = new NamespacedKey(plugin, "empty_spawner");
        if (Bukkit.getRecipe(rk) == null) {
            ShapedRecipe r = new ShapedRecipe(rk, emptySpawner());
            r.shape("FFF", "FFF", "FFF");
            r.setIngredient('F', new RecipeChoice.ExactChoice(fragment(1)));
            Bukkit.addRecipe(r);
        }
    }

    void setAccess(BiPredicate<Player, Location> rule) { this.canUse = rule; }
    void setEconomy(Economy e) { this.eco = e; }
    void setBiomeMobs(Function<Location, Set<EntityType>> f, OneBlockPhases p) { this.allowedAt = f; this.phases = p; }

    // ------------------------------------------------------------------ items

    static ItemStack essence(int n) {
        ItemStack it = Ui.item(Material.ECHO_SHARD, "<#9d7bff><bold>Mob Essence",
                "<gray>Dropped by mobs from spawners.", "<gray>Buys spawner mob types and upgrades,", "<gray>robot upgrades and fuel cells.");
        it.editMeta(m -> m.getPersistentDataContainer().set(ESSENCE, PersistentDataType.BYTE, (byte) 1));
        it.setAmount(Math.max(1, Math.min(64, n)));
        return it;
    }

    static ItemStack fragment(int n) {
        ItemStack it = Ui.item(Material.IRON_NUGGET, "<#c6a15b><bold>Spawner Fragment", "<gray>A rare piece of a broken spawner.", "<gray>9 in a crafting table make", "<gray>an <white>Empty Spawner<gray>.");
        it.editMeta(m -> { m.getPersistentDataContainer().set(FRAGMENT, PersistentDataType.BYTE, (byte) 1); m.setEnchantmentGlintOverride(true); });
        it.setAmount(Math.max(1, Math.min(64, n)));
        return it;
    }

    static ItemStack emptySpawner() {
        ItemStack it = Ui.item(Material.SPAWNER, "<#c6a15b><bold>Empty Spawner", "<gray>Place it, then right-click it to", "<gray>choose a mob with <#9d7bff>Mob Essence<gray>.", "<gray>On OneBlock: any mob from a biome", "<gray>your island has reached.");
        it.editMeta(m -> m.getPersistentDataContainer().set(EMPTY, PersistentDataType.BYTE, (byte) 1));
        return it;
    }

    static boolean isEssence(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(ESSENCE); }

    static int countEssence(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (isEssence(it)) n += it.getAmount();
        return n;
    }

    static boolean takeEssence(Player p, int n) {
        if (countEssence(p) < n) return false;
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && n > 0; i++) {
            if (!isEssence(inv[i])) continue;
            int take = Math.min(n, inv[i].getAmount());
            inv[i].setAmount(inv[i].getAmount() - take);
            n -= take;
        }
        p.getInventory().setStorageContents(inv);
        return true;
    }

    static void giveEssence(Player p, int n) {
        while (n > 0) {
            int k = Math.min(64, n);
            p.getInventory().addItem(essence(k)).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
            n -= k;
        }
    }

    // ------------------------------------------------------------------ levels stored on the spawner

    private static NamespacedKey key(String up) { return new NamespacedKey("alloycore", "spawner_" + up); }

    static int level(PersistentDataContainer pdc, String up) { return pdc.getOrDefault(key(up), PersistentDataType.INTEGER, up.equals("tier") ? 1 : 0); }

    /** Push the stored levels into the spawner's vanilla settings. */
    static void apply(CreatureSpawner cs) {
        PersistentDataContainer pdc = cs.getPersistentDataContainer();
        double sp = SPEED[Math.min(5, level(pdc, "speed"))];
        int amt = level(pdc, "amount"), stack = level(pdc, "stack");
        cs.setMaxSpawnDelay(800);      // keep min <= max while changing
        cs.setMinSpawnDelay((int) (200 * sp));
        cs.setMaxSpawnDelay((int) (800 * sp));
        cs.setSpawnCount(Math.min(24, (4 + amt) * (1 + stack)));
        cs.setMaxNearbyEntities(6 + 3 * amt + 6 * stack);
        cs.setRequiredPlayerRange(16 + 8 * level(pdc, "range"));
    }

    /** Copy upgrades, tier and kills from a spawner block onto the item it drops (Silk Touch). */
    static void toItem(CreatureSpawner cs, ItemStack item) {
        item.editMeta(m -> {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(Optional.ofNullable(m.lore()).orElse(List.of()));
            lore.add(Ui.mm("<gray>Tier <white>" + roman(level(cs.getPersistentDataContainer(), "tier")) + " <dark_gray>(" + level(cs.getPersistentDataContainer(), "kills") + " kills)"));
            for (String up : STORED) {
                int lv = level(cs.getPersistentDataContainer(), up);
                if (lv <= 0) continue;
                m.getPersistentDataContainer().set(key(up), PersistentDataType.INTEGER, lv);
                if (!up.equals("tier") && !up.equals("kills")) lore.add(Ui.mm("<gray> • " + label(up) + " <white>" + roman(lv)));
            }
            m.lore(lore);
        });
    }

    /** Copy upgrades, tier and kills from a placed spawner item onto the new block. */
    static void fromItem(ItemStack item, CreatureSpawner cs) {
        if (!item.hasItemMeta()) return;
        PersistentDataContainer src = item.getItemMeta().getPersistentDataContainer();
        for (String up : STORED) {
            Integer lv = src.get(key(up), PersistentDataType.INTEGER);
            if (lv != null && lv > 0) cs.getPersistentDataContainer().set(key(up), PersistentDataType.INTEGER, lv);
        }
        apply(cs);
    }

    static String roman(int n) { return new String[]{"0", "I", "II", "III", "IV", "V"}[Math.max(0, Math.min(5, n))]; }

    private static String label(String up) {
        return switch (up) { case "speed" -> "Spawn Time"; case "amount" -> "Mob Count"; case "stack" -> "Spawner Stack"; case "range" -> "Activation Range"; default -> "Essence Yield"; };
    }

    static String mobName(EntityType t) {
        StringBuilder b = new StringBuilder();
        for (String w : t.name().toLowerCase().split("_")) b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ empty spawners

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceEmpty(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        if (it.getType() != Material.SPAWNER || !it.hasItemMeta() || !it.getItemMeta().getPersistentDataContainer().has(EMPTY)) return;
        if (e.getBlockPlaced().getState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(null);
            cs.getPersistentDataContainer().set(EMPTY, PersistentDataType.BYTE, (byte) 1);
            cs.update(true, false);
            e.getPlayer().sendMessage(Ui.mm("<gray>Right-click the <#c6a15b>Empty Spawner<gray> to choose its mob."));
        }
    }

    /** Silk Touch keeps an empty spawner (it has no mob type for the normal spawner drop). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakEmpty(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.SPAWNER || !(e.getBlock().getState() instanceof CreatureSpawner cs) || cs.getSpawnedType() != null) return;
        if (!cs.getPersistentDataContainer().has(EMPTY)) return;
        e.setExpToDrop(0);
        e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), emptySpawner());
    }

    // ------------------------------------------------------------------ essence drops and kill tiers

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) return;
        int yield = 0;
        Block near = findSpawner(e.getLocation());
        if (near != null && near.getState() instanceof CreatureSpawner cs) {
            yield = level(cs.getPersistentDataContainer(), "yield");
            e.getEntity().getPersistentDataContainer().set(SPAWNER_AT, PersistentDataType.STRING, near.getX() + "," + near.getY() + "," + near.getZ());
        }
        e.getEntity().getPersistentDataContainer().set(FROM_SPAWNER, PersistentDataType.INTEGER, yield);
    }

    private static Block findSpawner(Location l) {
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -5; dz <= 5; dz++) {
            Block b = l.getBlock().getRelative(dx, dy, dz);
            if (b.getType() == Material.SPAWNER) { double d = dx * dx + dy * dy + dz * dz; if (d < bd) { bd = d; best = b; } }
        }
        return best;
    }

    /** Essence a spawner mob is worth when it dies (0 for natural mobs). */
    static int essenceFor(Entity dead) {
        Integer yield = dead.getPersistentDataContainer().get(FROM_SPAWNER, PersistentDataType.INTEGER);
        if (yield == null) return 0;
        double chance = 0.4 + 0.12 * yield;   // 40% base, guaranteed at Yield V
        int n = Math.random() < Math.min(1.0, chance) ? 1 : 0;
        if (yield >= 3 && Math.random() < 0.1 * (yield - 2)) n++;
        return n;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity() instanceof Player) return;
        int n = essenceFor(e.getEntity());
        if (n > 0) e.getDrops().add(essence(n));
        // natural mobs on OneBlock islands sometimes leave a little Essence, so the first spawner is reachable
        else if (e.getEntity().getKiller() != null && e.getEntity().getWorld().getName().startsWith("oneblock") && Math.random() < 0.12) e.getDrops().add(essence(1));
        String at = e.getEntity().getPersistentDataContainer().get(SPAWNER_AT, PersistentDataType.STRING);
        if (at != null) countKill(e.getEntity().getWorld(), at);
    }

    /** Each kill of a spawner's own mob counts towards its next tier. */
    private void countKill(World w, String at) {
        String[] p = at.split(",");
        int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        Block b = w.getBlockAt(x, y, z);
        if (!(b.getState() instanceof CreatureSpawner cs)) return;
        PersistentDataContainer pdc = cs.getPersistentDataContainer();
        int kills = level(pdc, "kills") + 1, tier = level(pdc, "tier");
        pdc.set(key("kills"), PersistentDataType.INTEGER, kills);
        if (tier < MAX_LEVEL && kills >= TIER_KILLS[tier - 1]) {
            pdc.set(key("tier"), PersistentDataType.INTEGER, tier + 1);
            for (Player pl : w.getPlayers()) if (pl.getLocation().distanceSquared(b.getLocation()) < 48 * 48) {
                pl.sendMessage(Ui.mm("<gold><bold>SPAWNER TIER UP!</bold> <gray>Your " + (cs.getSpawnedType() == null ? "" : mobName(cs.getSpawnedType()) + " ") + "spawner is now <white>Tier " + roman(tier + 1) + "<gray> - higher upgrades unlocked."));
                pl.playSound(b.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
            }
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, b.getLocation().add(0.5, 1, 0.5), 40, 0.5, 0.5, 0.5, 0.2);
        }
        cs.update(true, false);
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        final Location at; final boolean mobs; final List<EntityType> shown = new ArrayList<>(); Inventory inv;
        Menu(Location at, boolean mobs) { this.at = at; this.mobs = mobs; }
        public Inventory getInventory() { return inv; }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClickSpawner(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (Robots.isRobotItem(hand)) return;   // placing a robot on top of it
        if (p.isSneaking() && hand.getType().isBlock() && hand.getType() != Material.AIR) return;   // sneak-place against it
        e.setCancelled(true);   // also stops spawn eggs changing the type for free
        if (!canUse.test(p, b.getLocation())) { p.sendMessage(Ui.mm("<red>That spawner isn't on your land.")); return; }
        if (b.getState() instanceof CreatureSpawner cs && cs.getSpawnedType() == null) openMobs(p, b.getLocation());
        else open(p, b.getLocation());
    }

    private static String bar(int have, int need) {
        int filled = need <= 0 ? 20 : (int) Math.min(20, 20L * have / need);
        return "<green>" + "|".repeat(filled) + "<dark_gray>" + "|".repeat(20 - filled);
    }

    void open(Player p, Location at) {
        if (!(at.getBlock().getState() instanceof CreatureSpawner cs)) return;
        Menu m = new Menu(at, false);
        EntityType type = cs.getSpawnedType();
        PersistentDataContainer pdc = cs.getPersistentDataContainer();
        int tier = level(pdc, "tier"), kills = level(pdc, "kills");
        m.inv = Bukkit.createInventory(m, 36, Ui.mm("<dark_gray>" + (type == null ? "Empty" : mobName(type)) + " Spawner <gray>(Tier " + roman(tier) + ")"));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 36; i++) m.inv.setItem(i, pane);
        // tier progress
        List<String> tl = new ArrayList<>(List.of("<gray>Kills from this spawner: <white>" + kills));
        if (tier < MAX_LEVEL) { tl.add(bar(kills, TIER_KILLS[tier - 1]) + " <white>" + kills + "/" + TIER_KILLS[tier - 1]); tl.add("<gray>Kill its mobs to reach <white>Tier " + roman(tier + 1)); }
        else tl.add("<gold>Max tier!");
        tl.add("");
        tl.add("<gray>Tier caps upgrades at level <white>" + roman(tier) + "<gray> and mob types at tier <white>" + roman(tier) + "<gray>.");
        m.inv.setItem(4, Ui.item(Material.NETHER_STAR, "<gold><bold>Spawner Tier " + roman(tier), tl.toArray(String[]::new)));
        Material[] icons = {Material.CLOCK, Material.ZOMBIE_HEAD, Material.SPAWNER, Material.SPYGLASS, Material.ECHO_SHARD};
        String[] what = {
            "<gray>Mobs spawn every <white>" + (int) (10 * SPEED[level(pdc, "speed")]) + "-" + (int) (40 * SPEED[level(pdc, "speed")]) + "s",
            "<gray>Spawns <white>" + (4 + level(pdc, "amount")) + "<gray> mobs per stacked spawner",
            "<gray>Stack <white>x" + (1 + level(pdc, "stack")) + " <gray>- multiplies the mob count",
            "<gray>Works when you're within <white>" + (16 + 8 * level(pdc, "range")) + " blocks",
            "<gray><white>" + Math.min(100, (int) Math.round(100 * (0.4 + 0.12 * level(pdc, "yield")))) + "%<gray> chance of Essence per kill"};
        int[] slots = {19, 20, 21, 23, 24};
        for (int i = 0; i < UPGRADES.length; i++) {
            int lv = level(pdc, UPGRADES[i]);
            List<String> lore = new ArrayList<>(List.of("<gray>Level: <white>" + roman(lv) + " <dark_gray>/ " + roman(MAX_LEVEL), what[i], ""));
            if (lv >= MAX_LEVEL) lore.add("<green><bold>MAXED");
            else if (lv >= tier) lore.add("<red>Needs spawner Tier " + roman(lv + 1) + " <gray>(kill more of its mobs)");
            else { lore.add("<gray>Next level:"); lore.add("<#9d7bff> " + ESSENCE_COST[lv] + " Mob Essence"); lore.add("<green> " + net.alloymc.core.NetEconomy.money(MONEY_COST[lv])); lore.add(""); lore.add("<yellow>Click to upgrade"); }
            ItemStack icon = Ui.item(icons[i], (lv >= MAX_LEVEL ? "<gold>" : "<aqua>") + "<bold>" + label(UPGRADES[i]), lore.toArray(String[]::new));
            icon.setAmount(Math.max(1, lv));
            m.inv.setItem(slots[i], icon);
        }
        m.inv.setItem(31, Ui.item(Material.SPAWNER, "<gold><bold>Change Mob Type", "<gray>Currently: <white>" + (type == null ? "none" : mobName(type)), "<gray>Keeps tier and upgrades.", "", "<yellow>Click to browse"));
        m.inv.setItem(27, Ui.item(Material.ECHO_SHARD, "<#9d7bff>Your Essence: <white>" + countEssence(p), "<gray>Kill mobs from spawners to get more."));
        p.openInventory(m.inv);
    }

    private List<EntityType> choices(Location at) {
        Set<EntityType> allowed = allowedAt.apply(at);
        List<EntityType> out = new ArrayList<>(allowed != null ? allowed : List.of(DEFAULT_MOBS));
        out.sort(Comparator.comparingInt(Spawners::mobTier).thenComparing(Enum::name));
        return out;
    }

    private void openMobs(Player p, Location at) {
        if (!(at.getBlock().getState() instanceof CreatureSpawner cs)) return;
        Menu m = new Menu(at, true);
        int tier = level(cs.getPersistentDataContainer(), "tier");
        boolean oneblock = allowedAt.apply(at) != null;
        m.inv = Bukkit.createInventory(m, 54, Ui.mm("<dark_gray>" + (cs.getSpawnedType() == null ? "Choose a mob" : "Change mob type") + (oneblock ? " <gray>(biomes reached)" : "")));
        List<EntityType> list = choices(at);
        for (int i = 0; i < list.size() && i < 45; i++) {
            EntityType t = list.get(i);
            int mt = mobTier(t), cost = TYPE_COST[mt - 1];
            Material egg = Material.matchMaterial(t.name() + "_SPAWN_EGG");
            boolean locked = mt > tier;
            List<String> lore = new ArrayList<>(List.of("<gray>Mob tier: <white>" + roman(mt), "<#9d7bff>" + cost + " Mob Essence", ""));
            lore.add(locked ? "<red>Needs spawner Tier " + roman(mt) : t == cs.getSpawnedType() ? "<green>Current mob" : "<yellow>Click to set");
            m.inv.setItem(i, Ui.item(locked ? Material.GRAY_DYE : egg == null ? Material.SPAWNER : egg, (locked ? "<gray>" : "<white><bold>") + mobName(t), lore.toArray(String[]::new)));
            m.shown.add(t);
        }
        if (oneblock && phases != null && phases.loaded())
            m.inv.setItem(49, Ui.item(Material.GRASS_BLOCK, "<green>More mobs unlock as your OneBlock", "<gray>reaches new biome phases.", "<gray>Every biome you've reached adds its mobs."));
        if (cs.getSpawnedType() != null) m.inv.setItem(53, Ui.item(Material.ARROW, "<gray>Back"));
        p.openInventory(m.inv);
    }

    private boolean pay(Player p, int essence, int money) {
        if (countEssence(p) < essence) { p.sendMessage(Ui.mm("<red>You need " + essence + " Mob Essence (you have " + countEssence(p) + ").")); return false; }
        if (money > 0 && (eco == null || eco.getBalance(p) < money)) { p.sendMessage(Ui.mm("<red>You need " + net.alloymc.core.NetEconomy.money(money) + ".")); return false; }
        if (money > 0) eco.withdrawPlayer(p, money);
        takeEssence(p, essence);
        return true;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (!(m.at.getBlock().getState() instanceof CreatureSpawner cs) || !canUse.test(p, m.at)) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        int tier = level(cs.getPersistentDataContainer(), "tier");
        if (m.mobs) {
            if (slot == 53 && cs.getSpawnedType() != null) { open(p, m.at); return; }
            if (slot < 0 || slot >= m.shown.size()) return;
            EntityType t = m.shown.get(slot);
            if (!choices(m.at).contains(t)) { p.closeInventory(); return; }   // re-check server-side
            if (mobTier(t) > tier) { p.sendMessage(Ui.mm("<red>" + mobName(t) + " needs a Tier " + roman(mobTier(t)) + " spawner. Kill more of this spawner's mobs to tier it up.")); return; }
            if (t == cs.getSpawnedType()) { p.sendMessage(Ui.mm("<gray>It already spawns " + mobName(t) + ".")); return; }
            if (!pay(p, TYPE_COST[mobTier(t) - 1], 0)) return;
            cs.setSpawnedType(t);
            cs.getPersistentDataContainer().remove(EMPTY);
            apply(cs);
            cs.update(true, false);
            p.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.8f, 1.2f);
            p.sendMessage(Ui.mm("<green>Your spawner now spawns <white>" + mobName(t) + "<green>."));
            open(p, m.at);
            return;
        }
        if (slot == 31) { openMobs(p, m.at); return; }
        int idx = switch (slot) { case 19 -> 0; case 20 -> 1; case 21 -> 2; case 23 -> 3; case 24 -> 4; default -> -1; };
        if (idx < 0) return;
        String up = UPGRADES[idx];
        int lv = level(cs.getPersistentDataContainer(), up);
        if (lv >= MAX_LEVEL) { p.sendMessage(Ui.mm("<gold>Already maxed.")); return; }
        if (lv >= tier) { p.sendMessage(Ui.mm("<red>" + label(up) + " " + roman(lv + 1) + " needs a Tier " + roman(lv + 1) + " spawner. <gray>Kill " + Math.max(0, TIER_KILLS[tier - 1] - level(cs.getPersistentDataContainer(), "kills")) + " more of its mobs.")); return; }
        if (!pay(p, ESSENCE_COST[lv], MONEY_COST[lv])) return;
        cs.getPersistentDataContainer().set(key(up), PersistentDataType.INTEGER, lv + 1);
        apply(cs);
        cs.update(true, false);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
        p.sendMessage(Ui.mm("<green>" + label(up) + " upgraded to <white>" + roman(lv + 1) + "<green>!"));
        open(p, m.at);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    /** Keep the empty-spawner recipe's result un-stackable with real spawners (and stop fragments being used in other recipes). */
    @EventHandler
    public void onPrepare(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        if (e.getRecipe() == null) return;
        boolean hasFragment = Arrays.stream(e.getInventory().getMatrix()).anyMatch(i -> i != null && i.hasItemMeta() && i.getItemMeta().getPersistentDataContainer().has(FRAGMENT));
        boolean isOurs = e.getRecipe() instanceof ShapedRecipe r && r.getKey().getKey().equals("empty_spawner");
        if (hasFragment && !isOurs) e.getInventory().setResult(null);
    }
}
