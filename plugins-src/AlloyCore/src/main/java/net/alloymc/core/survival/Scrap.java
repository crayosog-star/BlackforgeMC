package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * Scrap: BlackForgeMC's earned currency (like the shard shop on other servers). Earn it from player
 * kills, killing monsters, active playtime, KOTH, supply crates and voting; spend it in /scrap.
 */
public final class Scrap implements Listener {
    private final AlloyCore plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Long> lastMove = new HashMap<>();
    private final Map<String, Long> recentKills = new HashMap<>();   // killer|victim -> time
    private final List<Offer> offers = new ArrayList<>();
    private final Crates crates;
    private final NameTags tags;
    private final CustomEnchants enchants;
    private Ranks ranks;

    void setRanks(Ranks r) { this.ranks = r; }

    /** Robot and Fuel Cell offers (only on servers that run robots). */
    void addRobotOffers() {
        offers.add(new Offer("Supplies", Robots.robotItem(Robots.Job.MINER, 1, 0, 0, 1), 900, p -> give(p, Robots.robotItem(Robots.Job.MINER, 1, 0, 0, 1))));
        int[] cost = {60, 200, 500};
        for (int t = 1; t <= 3; t++) { int tt = t; offers.add(new Offer("Supplies", Robots.fuelCell(t, 1), cost[t - 1], p -> give(p, Robots.fuelCell(tt, 1)))); }
    }

    /** Jelly Legs (Survival only): a one-time unlock; buying it twice gives the Scrap back. */
    void addJellyLegs(JellyLegs jl) {
        offers.add(new Offer("Supplies", icon(Material.SLIME_BALL, "<#7ddc4a><bold>Jelly Legs", "<gray>No fall damage - toggle it with <white>/jl",
                "<gray>Unlocks for good (Survival)"), JellyLegs.SCRAP_PRICE, p -> {
            if (!jl.unlock(p)) { add(p, JellyLegs.SCRAP_PRICE, null); p.sendMessage(Ui.mm("<gray>You already have Jelly Legs - your Scrap was refunded.")); }
        }));
    }

    record Offer(String category, ItemStack icon, int cost, Consumer<Player> give) {}

    public static final int PER_KILL = 25, PER_MOB = 1, PER_PLAYTIME = 10, PLAYTIME_MINUTES = 10;

    Scrap(AlloyCore plugin, Crates crates, NameTags tags, CustomEnchants enchants) {
        this.plugin = plugin;
        this.crates = crates;
        this.tags = tags;
        this.enchants = enchants;
        this.file = new File(plugin.getDataFolder(), "scrap.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        defineShop();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::playtime, PLAYTIME_MINUTES * 1200L, PLAYTIME_MINUTES * 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
    }

    // ------------------------------------------------------------------ balance

    public long get(OfflinePlayer p) { return plugin.economy().scrap(p.getUniqueId()); }

    public void add(Player p, long n, String why) {
        if (ranks != null && why != null && !why.equals("gift")) n = Math.round(n * ranks.scrapMultiplier(p));
        plugin.economy().addScrap(p, n);
        if (why != null) p.sendActionBar(Ui.mm("<#c6a15b>⚙ +" + n + " Scrap <gray>(" + why + ")"));
    }

    private boolean take(Player p, long n) { return plugin.economy().takeScrap(p, n); }

    public void save() { try { data.save(file); } catch (Exception e) { plugin.getLogger().warning("scrap save failed: " + e.getMessage()); } }

    // ------------------------------------------------------------------ earning

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        if (e.getEntity() instanceof Player victim) {
            if (victim == killer) return;
            // no farming the same person: one payout per victim every 10 minutes
            String k = killer.getUniqueId() + "|" + victim.getUniqueId();
            long now = System.currentTimeMillis();
            if (recentKills.getOrDefault(k, 0L) > now - 600_000) { killer.sendActionBar(Ui.mm("<gray>No Scrap - you killed " + victim.getName() + " recently")); return; }
            recentKills.put(k, now);
            add(killer, PER_KILL, "killed " + victim.getName());
        } else if (e.getEntity() instanceof Monster) {
            add(killer, PER_MOB, "monster kill");
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) { if (e.hasChangedBlock()) lastMove.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()); }

    /** Active playtime: AFK players (no movement for 5 minutes) earn nothing. */
    private void playtime() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers())
            if (lastMove.getOrDefault(p.getUniqueId(), 0L) > now - 300_000) add(p, PER_PLAYTIME, PLAYTIME_MINUTES + " min played");
        save();
    }

    // ------------------------------------------------------------------ shop

    private ItemStack icon(Material m, String name, String... lore) { return Ui.item(m, name, lore); }

    private void defineShop() {
        // keys
        offers.add(new Offer("Keys", icon(Material.TRIPWIRE_HOOK, "<#e0895a><bold>Cinder Key"), 150, p -> crates.give(p, "iron", 1)));
        offers.add(new Offer("Keys", icon(Material.TRIPWIRE_HOOK, "<#ffe45c><bold>Spark Key"), 120, p -> crates.give(p, "vote", 1)));
        offers.add(new Offer("Keys", icon(Material.TRIPWIRE_HOOK, "<#ffb52e><bold>Molten Key"), 450, p -> crates.give(p, "gold", 1)));
        offers.add(new Offer("Keys", icon(Material.TRIPWIRE_HOOK, "<#ff4422><bold>Forgeheart Key"), 1400, p -> crates.give(p, "netherite", 1)));
        // custom enchant books
        String[][] books = {{"springs", "250"}, {"autosmelt", "350"}, {"lifesteal", "450"}, {"bleed", "450"}, {"overload", "450"}, {"thunderstrike", "500"}, {"excavator", "700"}};
        for (String[] b : books) {
            CustomEnchants.CE ce = CustomEnchants.byId(b[0]);
            offers.add(new Offer("Enchants", icon(Material.ENCHANTED_BOOK, ce.color() + "<bold>" + ce.name() + " I Book", "<gray>" + ce.desc()), Integer.parseInt(b[1]), p -> enchants.give(p, b[0], 1)));
        }
        // name colours
        offers.add(new Offer("Cosmetics", icon(Material.NAME_TAG, "<gradient:#ffd27a:#ff4d00><bold>Molten Name Tag"), 600, p -> tags.give(p, "molten")));
        offers.add(new Offer("Cosmetics", icon(Material.NAME_TAG, "<gradient:#7ff0c8:#2a8f6b><bold>Oxidized Name Tag"), 600, p -> tags.give(p, "oxidized")));
        offers.add(new Offer("Cosmetics", icon(Material.NAME_TAG, "<gradient:#b9a7ff:#4a4043><bold>Netherite Name Tag"), 1500, p -> tags.give(p, "netherite")));
        // spawners
        Object[][] spawners = {{EntityType.ZOMBIE, 2500}, {EntityType.SKELETON, 2500}, {EntityType.SPIDER, 2500}, {EntityType.COW, 2000},
                {EntityType.PIG, 1500}, {EntityType.CHICKEN, 1500}, {EntityType.IRON_GOLEM, 9000}, {EntityType.BLAZE, 6000}};
        for (Object[] s : spawners) {
            EntityType t = (EntityType) s[0];
            offers.add(new Offer("Spawners", spawner(t), (Integer) s[1], p -> give(p, spawner(t))));
        }
        // supplies
        offers.add(new Offer("Supplies", icon(Material.GOLDEN_APPLE, "<white>8x Golden Apple"), 120, p -> give(p, new ItemStack(Material.GOLDEN_APPLE, 8))));
        offers.add(new Offer("Supplies", icon(Material.ENDER_PEARL, "<white>16x Ender Pearl"), 120, p -> give(p, new ItemStack(Material.ENDER_PEARL, 16))));
        offers.add(new Offer("Supplies", icon(Material.EXPERIENCE_BOTTLE, "<white>32x Bottle o' Enchanting"), 200, p -> give(p, new ItemStack(Material.EXPERIENCE_BOTTLE, 32))));
        offers.add(new Offer("Supplies", icon(Material.SHULKER_BOX, "<white>Shulker Box"), 500, p -> give(p, new ItemStack(Material.SHULKER_BOX))));
        offers.add(new Offer("Supplies", icon(Material.TOTEM_OF_UNDYING, "<white>Totem of Undying"), 650, p -> give(p, new ItemStack(Material.TOTEM_OF_UNDYING))));
        offers.add(new Offer("Supplies", icon(Material.ENCHANTED_GOLDEN_APPLE, "<light_purple>Enchanted Golden Apple"), 900, p -> give(p, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE))));
        offers.add(new Offer("Supplies", icon(Material.ELYTRA, "<light_purple>Elytra"), 2500, p -> give(p, new ItemStack(Material.ELYTRA))));
        offers.add(new Offer("Supplies", icon(Material.BEACON, "<aqua>Beacon"), 3000, p -> give(p, new ItemStack(Material.BEACON))));
        offers.add(new Offer("Supplies", Spawners.essence(16), 150, p -> Spawners.giveEssence(p, 16)));
    }

    static ItemStack spawner(EntityType t) { return SilkSpawners.item(t); }

    private static void give(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private static final class Menu implements InventoryHolder {
        final String category; final List<Offer> shown = new ArrayList<>(); Inventory inv;
        Menu(String category) { this.category = category; }
        public Inventory getInventory() { return inv; }
    }

    private static final String[][] CATS = {
        {"Keys", "TRIPWIRE_HOOK", "<gold>"}, {"Enchants", "ENCHANTED_BOOK", "<aqua>"}, {"Cosmetics", "NAME_TAG", "<light_purple>"},
        {"Spawners", "SPAWNER", "<red>"}, {"Supplies", "CHEST", "<yellow>"}};

    public void open(Player p, String category) {
        Menu m = new Menu(category);
        m.inv = Bukkit.createInventory(m, 54, Ui.mm("<dark_gray>⚙ Scrap Shop <gray>(" + get(p) + " Scrap)"));
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) m.inv.setItem(i, pane);
        for (int i = 45; i < 54; i++) m.inv.setItem(i, pane);
        for (int i = 0; i < CATS.length; i++) {
            boolean sel = CATS[i][0].equals(category);
            ItemStack c = Ui.item(Material.valueOf(CATS[i][1]), CATS[i][2] + "<bold>" + CATS[i][0], sel ? "<green>▶ Viewing" : "<yellow>» Click to view");
            if (sel) c.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(2 + i, c);
        }
        int slot = 9;
        for (Offer o : offers) {
            if (!o.category().equals(category) || slot >= 45) continue;
            ItemStack it = o.icon().clone();
            boolean afford = get(p) >= o.cost();
            it.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
                lore.add(Ui.mm(""));
                lore.add(Ui.mm("<gray>Cost: <#c6a15b>⚙ " + o.cost() + " Scrap"));
                lore.add(Ui.mm(afford ? "<green>» Click to buy" : "<red>You need " + (o.cost() - get(p)) + " more Scrap"));
                meta.lore(lore);
            });
            m.shown.add(o);
            m.inv.setItem(slot++, it);
        }
        m.inv.setItem(49, Ui.item(Material.IRON_NUGGET, "<#c6a15b><bold>⚙ " + get(p) + " Scrap",
                "<gray>Earn Scrap by:", "<white>• Killing players <gray>(+" + PER_KILL + ")", "<white>• Killing monsters <gray>(+" + PER_MOB + ")",
                "<white>• Playing <gray>(+" + PER_PLAYTIME + " every " + PLAYTIME_MINUTES + " min)", "<white>• Winning KOTH <gray>(+200)",
                "<white>• Smashing supply crates <gray>(+5)", "<white>• Voting <gray>(+20)"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (slot >= 2 && slot < 2 + CATS.length) { open(p, CATS[slot - 2][0]); return; }
        int idx = slot - 9;
        if (idx < 0 || idx >= m.shown.size()) return;
        Offer o = m.shown.get(idx);
        if (!take(p, o.cost())) { p.sendMessage(Ui.mm("<red>You need ⚙ " + o.cost() + " Scrap (you have " + get(p) + ").")); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f); return; }
        o.give().accept(p);
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1.1f);
        p.sendMessage(Ui.mm("<#c6a15b>⚙ <gray>Bought " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(o.icon().getItemMeta().displayName())
                + " for <#c6a15b>" + o.cost() + " Scrap<gray>."));
        open(p, m.category);
    }

    public void command(Player p, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("bal")) { p.sendMessage(Ui.mm("<#c6a15b>⚙ <gray>You have <white>" + get(p) + " Scrap")); return; }
        open(p, "Keys");
    }
}
