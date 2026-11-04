package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Base Station: one per claim or island. Sells Hopper Upgrades, upgrades nearby Agents and collects what they make.
 * It's a real barrel with a marker, so hoppers feed and drain it with no special code.
 */
public final class BaseStation implements Listener {
    private static final NamespacedKey ITEM = new NamespacedKey(BlackForgeCore.NS, "base_station");
    private static final int STORAGE = 11, HOPPERS = 13, AGENTS = 15, BACK = 22, BUY_ONE = 15, BUY_TEN = 16;
    private static final int[] TIER_SLOTS = {10, 11, 12};
    private static final int[] AGENT_SLOTS = {10, 11, 12, 13, 14, 15, 16, 4};

    private record Spot(String world, int x, int y, int z) {
        static Spot of(Location l) { return new Spot(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()); }
    }

    private static final class Menu implements InventoryHolder {
        final String kind; final Location at; Inventory inv; final Map<Integer, String> agents = new HashMap<>();
        Menu(String kind, Location at) { this.kind = kind; this.at = at; }
        public Inventory getInventory() { return inv; }
    }

    private final BlackForgeCore plugin;
    private final Robots robots;
    private final HopperUpgrades hoppers;
    private final File file;
    private final Map<String, Spot> stations = new HashMap<>();   // region -> station
    private final int price, essence, radius;
    private final boolean enabled;
    private BiPredicate<Player, Location> access = (p, l) -> false;
    private Function<Location, String> regionOf = l -> null;
    private Economy eco;

    BaseStation(BlackForgeCore plugin, Robots robots, HopperUpgrades hoppers) {
        this.plugin = plugin;
        this.robots = robots;
        this.hoppers = hoppers;
        this.file = new File(plugin.getDataFolder(), "base-stations.yml");
        var c = plugin.getConfig();
        price = c.getInt("base-station.price", 25_000);
        essence = c.getInt("base-station.essence", 128);
        radius = c.getInt("base-station.agent-radius", 16);
        enabled = c.getBoolean("base-station.enabled", true);
        StationUi.setArt(c.getBoolean("base-station.custom-ui", false));
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        robots.setStations(this);
    }

    void setAccess(BiPredicate<Player, Location> rule) { this.access = rule; }
    void setRegion(Function<Location, String> rule) { this.regionOf = rule; }
    void setEconomy(Economy e) { this.eco = e; }

    // ------------------------------------------------------------------ items

    public static ItemStack item() {
        ItemStack it = Ui.item(Material.BARREL, "<gradient:#ffb347:#ff5a1a><bold>Base Station</bold></gradient>",
                "<gray>Place one in your claim or island.", "<gray>Agents within range deliver to it, and",
                "<gray>it sells Hopper Upgrades.", "", "<dark_gray>One per claim or island");
        it.editMeta(m -> m.getPersistentDataContainer().set(ITEM, PersistentDataType.BYTE, (byte) 1));
        return it;
    }

    public static boolean isStationItem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(ITEM);
    }

    private static boolean isStation(Block b) {
        return b.getType() == Material.BARREL && b.getState(false) instanceof Barrel br && br.getPersistentDataContainer().has(ITEM);
    }

    /** The shop's listing: the station with its price. */
    ItemStack shopIcon() {
        ItemStack it = item();
        it.editMeta(m -> {
            var lore = new java.util.ArrayList<>(java.util.Optional.ofNullable(m.lore()).orElse(List.of()));
            lore.add(Ui.mm(""));
            lore.add(Ui.mm("<green>" + NetEconomy.money(price) + " <dark_gray>+ <#9d7bff>" + essence + " Mob Essence"));
            lore.add(Ui.mm("<yellow>Click to buy"));
            m.lore(lore);
        });
        return it;
    }

    void buy(Player p) {
        if (!enabled) { p.sendMessage(Ui.mm("<gray>Base Stations are switched off right now.")); return; }
        if (!pay(p, price, essence)) return;
        p.getInventory().addItem(item()).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        p.sendMessage(Ui.mm("<green>You bought a Base Station. Place it in your claim or island."));
    }

    private boolean pay(Player p, int money, int ess) {
        if (Spawners.countEssence(p) < ess) { p.sendMessage(Ui.mm("<red>You need " + ess + " Mob Essence (you have " + Spawners.countEssence(p) + ").")); return false; }
        if (eco == null || eco.getBalance(p) < money) { p.sendMessage(Ui.mm("<red>You need " + NetEconomy.money(money) + ".")); return false; }
        if (!eco.withdrawPlayer(p, money).transactionSuccess()) { p.sendMessage(Ui.mm("<red>That payment didn't go through.")); return false; }
        Spawners.takeEssence(p, ess);
        return true;
    }

    // ------------------------------------------------------------------ agent delivery

    /** The station an agent at this spot delivers to: same claim or island, in range, loaded. */
    private Barrel stationFor(Location at) {
        if (!enabled || stations.isEmpty()) return null;
        World w = at.getWorld();
        for (Map.Entry<String, Spot> en : stations.entrySet()) {
            Spot s = en.getValue();
            if (!w.getName().equals(s.world())) continue;
            int dx = s.x() - at.getBlockX(), dy = s.y() - at.getBlockY(), dz = s.z() - at.getBlockZ();
            if (dx * dx + dy * dy + dz * dz > radius * radius || !w.isChunkLoaded(s.x() >> 4, s.z() >> 4)) continue;
            if (en.getKey().equals(regionOf.apply(at)) && isStation(w.getBlockAt(s.x(), s.y(), s.z())))
                return (Barrel) w.getBlockAt(s.x(), s.y(), s.z()).getState(false);
        }
        return null;
    }

    /** Puts what fits in the station. Returns what's left, or null if it all went in. */
    ItemStack deliver(Location agentAt, ItemStack item) {
        Barrel b = stationFor(agentAt);
        if (b == null) return item;
        var left = b.getInventory().addItem(item);
        return left.isEmpty() ? null : left.values().iterator().next();
    }

    boolean hasRoom(Location agentAt) {
        Barrel b = stationFor(agentAt);
        return b != null && b.getInventory().firstEmpty() >= 0;
    }

    // ------------------------------------------------------------------ placing and breaking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCheck(BlockPlaceEvent e) {
        if (!isStationItem(e.getItemInHand())) return;
        Player p = e.getPlayer();
        Location l = e.getBlockPlaced().getLocation();
        String region = regionOf.apply(l);
        String refuse = !enabled ? "<gray>Base Stations are switched off right now."
                : region == null || !access.test(p, l) ? "<red>A Base Station can only go in your own claim or island."
                : standing(region) ? "<red>This claim or island already has a Base Station." : null;
        if (refuse == null) return;
        e.setCancelled(true);
        p.sendActionBar(Ui.mm(refuse));
    }

    /** Is the saved station for this region still really there? Unloaded counts as there. */
    private boolean standing(String region) {
        Spot s = stations.get(region);
        if (s == null) return false;
        World w = Bukkit.getWorld(s.world());
        if (w == null || !w.isChunkLoaded(s.x() >> 4, s.z() >> 4)) return true;
        if (isStation(w.getBlockAt(s.x(), s.y(), s.z()))) return true;
        stations.remove(region);
        save();
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!isStationItem(e.getItemInHand()) || !(e.getBlockPlaced().getState() instanceof Barrel b)) return;
        b.getPersistentDataContainer().set(ITEM, PersistentDataType.BYTE, (byte) 1);
        b.update(true, false);
        stations.put(regionOf.apply(e.getBlockPlaced().getLocation()), Spot.of(e.getBlockPlaced().getLocation()));
        save();
        e.getPlayer().playSound(e.getBlockPlaced().getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1f);
        e.getPlayer().sendMessage(Ui.mm("<green>Base Station placed. Right-click it to open. Agents within " + radius + " blocks will deliver here."));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!isStation(e.getBlock())) return;
        Spot here = Spot.of(e.getBlock().getLocation());
        if (stations.values().remove(here)) save();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        if (!(e.getBlockState() instanceof Barrel b) || !b.getPersistentDataContainer().has(ITEM)) return;
        for (Item drop : e.getItems()) {
            ItemStack it = drop.getItemStack();
            if (it.getType() == Material.BARREL && it.getAmount() == 1 && !isStationItem(it) && (!it.hasItemMeta() || it.getItemMeta().hasCustomName())) {
                drop.setItemStack(item());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(BaseStation::isStation); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(BaseStation::isStation); }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (isStation(e.getBlock())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) { if (isStationItem(e.getItem())) e.setCancelled(true); }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (isStationItem(it)) { e.getInventory().setResult(null); return; }
    }

    // ------------------------------------------------------------------ menus

    @EventHandler(priority = EventPriority.HIGH)
    public void onOpen(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !isStation(e.getClickedBlock())) return;
        if (e.getPlayer().isSneaking() && e.getItem() != null) return;   // building against it
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Location l = e.getClickedBlock().getLocation();
        if (!access.test(p, l)) { p.sendActionBar(Ui.mm("<red>That's someone else's Base Station.")); return; }
        home(p, l);
    }

    private Menu menu(String kind, Location at, StationUi.Screen screen, String title) {
        Menu m = new Menu(kind, at);
        m.inv = Bukkit.createInventory(m, 27, StationUi.title(screen, title));
        if (!StationUi.art()) {
            ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < 27; i++) m.inv.setItem(i, pane);
        }
        return m;
    }

    private void home(Player p, Location at) {
        Menu m = menu("home", at, StationUi.Screen.HOME, "Base Station");
        m.inv.setItem(STORAGE, Ui.item(Material.BARREL, "<gold><bold>Storage", "<gray>What your Agents delivered.", "<gray>Hoppers can feed and drain it too."));
        m.inv.setItem(HOPPERS, Ui.item(Material.HOPPER, "<gold><bold>Hopper Upgrades", "<gray>Make your hoppers faster."));
        m.inv.setItem(AGENTS, Ui.item(Material.COPPER_GOLEM_SPAWN_EGG, "<gold><bold>Agents", "<gray>Upgrade Agents within " + radius + " blocks."));
        p.openInventory(m.inv);
    }

    private void hopperScreen(Player p, Location at) {
        Menu m = menu("hoppers", at, StationUi.Screen.HOPPERS, "Hopper Upgrades");
        String[] names = {"I", "II", "III"};
        for (int t = 1; t <= HopperTiers.MAX_TIER; t++) {
            int needed = t == 1 ? 0 : hoppers.needed(t - 1);
            m.inv.setItem(TIER_SLOTS[t - 1], Ui.item(t == 1 ? Material.IRON_INGOT : t == 2 ? Material.GOLD_INGOT : Material.NETHERITE_INGOT,
                    "<gold><bold>Tier " + names[t - 1], "<gray>Moves <white>" + hoppers.rate(t) + "<gray> items a second",
                    t == 1 ? "<dark_gray>A normal hopper" : "<gray>Takes <white>" + needed + "<gray> Hopper Upgrade" + (needed == 1 ? "" : "s")));
        }
        m.inv.setItem(BUY_ONE, buyIcon(1));
        m.inv.setItem(BUY_TEN, buyIcon(10));
        m.inv.setItem(BACK, Ui.item(Material.ARROW, "<white>Back"));
        p.openInventory(m.inv);
    }

    private ItemStack buyIcon(int n) {
        ItemStack it = HopperUpgrades.upgradeItem();
        it.setAmount(n);
        it.editMeta(m -> m.lore(List.of(
                Ui.mm("<green>" + NetEconomy.money((double) hoppers.price * n) + " <dark_gray>+ <#9d7bff>" + (hoppers.essence * n) + " Mob Essence"),
                Ui.mm("<yellow>Click to buy " + n))));
        return it;
    }

    private void agentScreen(Player p, Location at) {
        Menu m = menu("agents", at, StationUi.Screen.AGENTS, "Agents");
        List<Robots.Bot> near = robots.near(at, radius);
        if (near.isEmpty()) m.inv.setItem(13, Ui.item(Material.BARRIER, "<red>No Agents nearby", "<gray>Place one within " + radius + " blocks."));
        for (int i = 0; i < near.size() && i < AGENT_SLOTS.length; i++) {
            m.inv.setItem(AGENT_SLOTS[i], robots.stationIcon(near.get(i)));
            m.agents.put(AGENT_SLOTS[i], near.get(i).id);
        }
        m.inv.setItem(BACK, Ui.item(Material.ARROW, "<white>Back"));
        p.openInventory(m.inv);
    }

    private void openStorage(Player p, Location at) {
        if (!(at.getBlock().getState() instanceof Barrel b) || !b.getPersistentDataContainer().has(ITEM)) { p.closeInventory(); return; }
        b.customName(StationUi.title(StationUi.Screen.STORAGE, "Station Storage"));
        b.update(true, false);
        p.openInventory(((Barrel) at.getBlock().getState(false)).getInventory());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        // the station can be broken, or the player's access lost, while a menu stays open
        if (!isStation(m.at.getBlock()) || !access.test(p, m.at)) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        switch (m.kind) {
            case "home" -> {
                if (slot == STORAGE) openStorage(p, m.at);
                else if (slot == HOPPERS) hopperScreen(p, m.at);
                else if (slot == AGENTS) agentScreen(p, m.at);
            }
            case "hoppers" -> {
                if (slot == BACK) home(p, m.at);
                else if (slot == BUY_ONE || slot == BUY_TEN) buyUpgrades(p, slot == BUY_ONE ? 1 : 10);
            }
            case "agents" -> {
                if (slot == BACK) { home(p, m.at); return; }
                String id = m.agents.get(slot);
                if (id == null) return;
                if (e.isRightClick()) robots.openFromStation(p, id);
                else { robots.upgradeFromStation(p, id); agentScreen(p, m.at); }
            }
            default -> { }
        }
    }

    private void buyUpgrades(Player p, int n) {
        if (!hoppers.enabled()) { p.sendMessage(Ui.mm("<gray>Hopper upgrades are switched off right now.")); return; }
        if (!pay(p, hoppers.price * n, hoppers.essence * n)) return;
        ItemStack it = HopperUpgrades.upgradeItem();
        it.setAmount(n);
        p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        p.sendMessage(Ui.mm("<green>Bought " + n + " Hopper Upgrade" + (n == 1 ? "" : "s") + "."));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    // ------------------------------------------------------------------ saving

    private void load() {
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(file).getConfigurationSection("stations");
        if (sec == null) return;
        for (String i : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(i);
            stations.put(s.getString("region"), new Spot(s.getString("world"), s.getInt("x"), s.getInt("y"), s.getInt("z")));
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<String, Spot> en : stations.entrySet()) {
            String k = "stations." + i++ + ".";
            y.set(k + "region", en.getKey());
            y.set(k + "world", en.getValue().world());
            y.set(k + "x", en.getValue().x()); y.set(k + "y", en.getValue().y()); y.set(k + "z", en.getValue().z());
        }
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("base station save failed: " + e.getMessage()); }
    }
}
