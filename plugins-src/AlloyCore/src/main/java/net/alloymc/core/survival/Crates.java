package net.alloymc.core.survival;

import net.alloymc.core.Hooks;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;

/** Crates at /warp crates: vault blocks opened with key items, with a roulette animation. */
public final class Crates implements Listener {
    record Reward(String name, ItemStack display, List<ItemStack> items, List<String> commands, double weight, boolean announce) {}
    record Crate(String id, String title, String color, Material keyMat, List<Reward> rewards) {
        double total() { return rewards.stream().mapToDouble(Reward::weight).sum(); }
    }

    private final AlloyCore plugin;
    private final World world;
    private final NamespacedKey keyTag;
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Set<UUID> opening = new HashSet<>();
    private final List<TextDisplay> holos = new ArrayList<>();
    private final Random rnd = new Random();

    private final List<SurvivalLayout.CrateSpot> spots;
    private final int vaultY;

    Crates(AlloyCore plugin, World world) { this(plugin, world, SurvivalLayout.CRATES, SurvivalLayout.Y + 2); }

    /** spots: where the crate vaults stand; vaultY: the y of the vault blocks. */
    Crates(AlloyCore plugin, World world, List<SurvivalLayout.CrateSpot> spots, int vaultY) {
        this.plugin = plugin;
        this.world = world;
        this.spots = spots;
        this.vaultY = vaultY;
        this.keyTag = new NamespacedKey(plugin, "crate_key");
        File f = new File(plugin.getDataFolder(), "crates.yml");
        if (!f.exists()) plugin.saveResource("crates.yml", false);
        load(YamlConfiguration.loadConfiguration(f));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void load(YamlConfiguration y) {
        for (String id : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(id);
            List<Reward> rewards = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("rewards")) {
                String name = String.valueOf(m.get("name"));
                Material mat = Material.matchMaterial(String.valueOf(m.get("item")));
                if (mat == null) { plugin.getLogger().warning("crate " + id + ": unknown item " + m.get("item")); continue; }
                int amount = m.containsKey("amount") ? ((Number) m.get("amount")).intValue() : 1;
                ItemStack it = new ItemStack(mat, amount);
                Object ench = m.get("enchants");
                if (ench instanceof Map<?, ?> em) it.editMeta(meta -> em.forEach((k, v) -> {
                    Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(String.valueOf(k).toLowerCase()));
                    if (e != null) meta.addEnchant(e, ((Number) v).intValue(), true);
                }));
                if (m.containsKey("custom-name")) it.editMeta(meta -> meta.displayName(Ui.mm(String.valueOf(m.get("custom-name")))));
                @SuppressWarnings("unchecked") List<String> cmds = m.containsKey("commands") ? (List<String>) m.get("commands") : List.of();
                boolean giveItem = !Boolean.FALSE.equals(m.get("give-item"));
                ItemStack display = it.clone();
                display.editMeta(meta -> meta.displayName(Ui.mm(name)));
                rewards.add(new Reward(name, display, giveItem ? List.of(it) : List.of(), cmds,
                        ((Number) m.get("weight")).doubleValue(), Boolean.TRUE.equals(m.get("announce"))));
            }
            crates.put(id, new Crate(id, s.getString("title", id), s.getString("color", "<white>"),
                    Material.matchMaterial(s.getString("key", "TRIPWIRE_HOOK")), rewards));
        }
        plugin.getLogger().info("Loaded " + crates.size() + " crates");
    }

    public Set<String> ids() { return crates.keySet(); }

    // ------------------------------------------------------------------ keys

    /** Keys handed out before a crate was renamed get their current name and colour back on join. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        for (ItemStack it : e.getPlayer().getInventory().getContents()) {
            if (it == null || !it.hasItemMeta()) continue;
            String id = it.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING);
            if (id == null || !crates.containsKey(id)) continue;
            ItemStack fresh = key(id, 1);
            it.editMeta(m -> { m.displayName(fresh.getItemMeta().displayName()); m.lore(fresh.getItemMeta().lore()); });
        }
    }

    /** "<colour>Molten Key" - the player-facing key name for a crate id. */
    public String keyName(String id) {
        Crate c = crates.get(resolve(id));
        return c == null ? id + " Key" : c.color() + c.title() + " Key";
    }

    public ItemStack key(String id, int amount) {
        Crate c = crates.get(id);
        ItemStack it = new ItemStack(c.keyMat(), amount);
        it.editMeta(meta -> {
            meta.displayName(Ui.mm(c.color() + "<bold>" + c.title() + " Key"));
            meta.lore(List.of(Ui.mm("<gray>Right-click the " + c.color() + c.title() + " Crate"), Ui.mm("<gray>at <white>/warp crates <gray>to open it.")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, id);
        });
        return it;
    }

    /** Accepts a crate id or its title ("molten" = "gold"), so staff and store commands can use either. */
    public String resolve(String id) {
        if (crates.containsKey(id)) return id;
        for (Crate c : crates.values()) if (c.title().equalsIgnoreCase(id)) return c.id();
        return id;
    }

    public boolean give(OfflinePlayer target, String id, int amount) {
        id = resolve(id);
        if (!crates.containsKey(id) || !(target instanceof Player p)) return false;
        p.getInventory().addItem(key(id, amount)).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<gold>✦ <gray>You received <white>" + amount + "x " + crates.get(id).color() + crates.get(id).title() + " Key<gray>!"));
        return true;
    }

    /** Key All: every online player gets the key (store community-goal reward - it goes to everyone, not the buyer). */
    public int giveAll(String id, int amount, String reason) {
        id = resolve(id);
        if (!crates.containsKey(id)) return -1;
        Crate c = crates.get(id);
        Bukkit.broadcast(Ui.mm("<gold><bold>KEY ALL!</bold> <gray>Everyone online gets <white>" + amount + "x " + c.color() + c.title() + " Key"
                + (reason.isEmpty() ? "" : " <gray>- " + reason) + "<gray>!"));
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            give(p, id, amount);
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            n++;
        }
        return n;
    }

    private String keyId(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ crate blocks

    private String crateAt(Block b) {
        for (SurvivalLayout.CrateSpot s : spots)
            if (b.getWorld() == world && b.getX() == s.x() && b.getZ() == s.z() && b.getY() == vaultY) return s.crate();
        return null;
    }

    public void spawnHolograms() {
        holos.forEach(TextDisplay::remove);
        holos.clear();
        for (SurvivalLayout.CrateSpot s : spots) {
            Crate c = crates.get(s.crate());
            if (c == null) continue;
            holos.add(Ui.hologram(new Location(world, s.x() + 0.5, vaultY + 1.6, s.z() + 0.5),
                    c.color() + "<bold>" + c.title().toUpperCase() + " CRATE</bold><newline><gray>Right-click with a key<newline><gray>Left-click to preview", 1.0f));
        }
    }

    public boolean hologramsMissing() { return holos.isEmpty() || holos.stream().anyMatch(t -> !t.isValid()); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        String id = crateAt(e.getClickedBlock());
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Crate c = crates.get(id);
        if (c == null) return;
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) { preview(p, c); return; }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!id.equals(keyId(hand))) {
            p.sendMessage(Ui.mm("<red>You need a " + c.color() + c.title() + " Key<red> to open this crate. <gray>Left-click to see the rewards."));
            p.playSound(p.getLocation(), Sound.BLOCK_VAULT_INSERT_ITEM_FAIL, 1f, 1f);
            p.setVelocity(p.getLocation().toVector().subtract(e.getClickedBlock().getLocation().add(0.5, 0, 0.5).toVector()).normalize().multiply(0.6).setY(0.25));
            return;
        }
        if (opening.contains(p.getUniqueId())) return;
        if (p.getInventory().firstEmpty() == -1) { p.sendMessage(Ui.mm("<red>Make some room in your inventory first.")); return; }
        hand.setAmount(hand.getAmount() - 1);
        open(p, c, e.getClickedBlock().getLocation().add(0.5, 1.2, 0.5));
    }

    // ------------------------------------------------------------------ preview

    private static final class PreviewHolder implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    private void preview(Player p, Crate c) {
        PreviewHolder h = new PreviewHolder();
        int rows = Math.min(6, (c.rewards().size() + 8) / 9 + 1);
        Inventory inv = Bukkit.createInventory(h, rows * 9, Ui.mm(c.color() + c.title() + " Crate <dark_gray>rewards"));
        h.inv = inv;
        double total = c.total();
        for (Reward r : c.rewards()) {
            ItemStack d = r.display().clone();
            double pct = 100.0 * r.weight() / total;
            d.editMeta(meta -> meta.lore(List.of(Ui.mm("<gray>Chance: <white>" + (pct < 1 ? String.format("%.2f", pct) : String.format("%.1f", pct)) + "%"))));
            inv.addItem(d);
        }
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ opening animation

    private static final class SpinHolder implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    private Reward roll(Crate c) {
        double r = rnd.nextDouble() * c.total();
        for (Reward rw : c.rewards()) { r -= rw.weight(); if (r < 0) return rw; }
        return c.rewards().getLast();
    }

    private void open(Player p, Crate c, Location fx) {
        opening.add(p.getUniqueId());
        SpinHolder h = new SpinHolder();
        Inventory inv = Bukkit.createInventory(h, 27, Ui.mm(c.color() + "Opening " + c.title() + " Crate..."));
        h.inv = inv;
        ItemStack frame = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack pointer = Ui.item(Material.HOPPER, "<gold>▼");
        for (int i = 0; i < 27; i++) inv.setItem(i, frame);
        inv.setItem(4, pointer);
        inv.setItem(22, Ui.item(Material.LIGHTNING_ROD, "<gold>▲"));
        Reward win = roll(c);
        Hooks.stat(p, "crate", 1);
        // the reel: random fillers, with the winner landing in the middle slot on the last step
        int steps = 34;
        List<Reward> reel = new ArrayList<>();
        for (int i = 0; i < steps + 9; i++) reel.add(roll(c));
        reel.set(steps + 4, win);
        p.openInventory(inv);
        world.playSound(fx, Sound.BLOCK_VAULT_OPEN_SHUTTER, 1f, 1f);
        int[] delays = new int[steps + 1];
        for (int i = 0; i <= steps; i++) delays[i] = 1 + (int) Math.pow(i / (double) steps, 3) * 9;
        new Object() {
            int step = 0;
            void next() {
                for (int s = 0; s < 9; s++) inv.setItem(9 + s, reel.get(step + s).display());
                for (int s : new int[]{0, 1, 2, 3, 5, 6, 7, 8, 18, 19, 20, 21, 23, 24, 25, 26})
                    inv.setItem(s, Ui.item(step % 2 == 0 ? Material.ORANGE_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE, " "));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f + step * 0.02f);
                if (step >= steps) { Bukkit.getScheduler().runTaskLater(plugin, () -> finish(p, c, win, fx), 25L); return; }
                Bukkit.getScheduler().runTaskLater(plugin, this::next, delays[step++]);
            }
        }.next();
    }

    private void finish(Player p, Crate c, Reward r, Location fx) {
        opening.remove(p.getUniqueId());
        if (!p.isOnline()) { pending(p.getUniqueId(), c, r); return; }
        grant(p, r);
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof SpinHolder) p.closeInventory();
        p.sendMessage(Ui.mm("<gold>✦ <gray>You won " + r.name() + " <gray>from the " + c.color() + c.title() + " Crate<gray>!"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, fx, 40, 0.4, 0.5, 0.4, 0.3);
        world.playSound(fx, Sound.BLOCK_VAULT_EJECT_ITEM, 1f, 1f);
        if (r.announce())
            Bukkit.broadcast(Ui.mm("<gold>✦ <white>" + p.getName() + " <gray>won " + r.name() + " <gray>from a " + c.color() + c.title() + " Crate<gray>!"));
    }

    private void grant(Player p, Reward r) {
        for (ItemStack it : r.items())
            p.getInventory().addItem(it.clone()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        for (String cmd : r.commands()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
    }

    private final Map<UUID, List<Reward>> owed = new HashMap<>();
    private void pending(UUID id, Crate c, Reward r) { owed.computeIfAbsent(id, k -> new ArrayList<>()).add(r); }

    public void onJoin(Player p) {
        List<Reward> l = owed.remove(p.getUniqueId());
        if (l != null) for (Reward r : l) { grant(p, r); p.sendMessage(Ui.mm("<gold>✦ <gray>Delivered your crate reward: " + r.name())); }
    }

    // the roulette and preview are read-only
    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        var holder = e.getInventory().getHolder();
        if (holder instanceof SpinHolder || holder instanceof PreviewHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        var holder = e.getInventory().getHolder();
        if (holder instanceof SpinHolder || holder instanceof PreviewHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onInvClose(InventoryCloseEvent e) {
        // closing early just hides the spin; the reward still arrives when it finishes
        if (e.getInventory().getHolder() instanceof SpinHolder && e.getPlayer() instanceof Player p && opening.contains(p.getUniqueId()))
            p.sendActionBar(Ui.mm("<gray>Your crate is still rolling..."));
    }
}
