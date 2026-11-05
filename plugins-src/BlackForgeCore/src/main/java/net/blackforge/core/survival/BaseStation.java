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
 * Base Station: one per claim or island. Its Forge menu upgrades your agents and sells Hopper Upgrades. It also collects
 * what agents in range make.
 * It's a real barrel with a marker, so hoppers feed and drain it with no special code.
 */
public final class BaseStation implements Listener {
    private static final NamespacedKey ITEM = new NamespacedKey(BlackForgeCore.NS, "base_station");

    private record Spot(String world, int x, int y, int z) {
        static Spot of(Location l) { return new Spot(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()); }
    }

    private static final class Menu implements InventoryHolder {
        final String kind; final Location at; Inventory inv; final Map<Integer, String> agents = new HashMap<>(); int page, pages = 1;
        Menu(String kind, Location at) { this.kind = kind; this.at = at; }
        public Inventory getInventory() { return inv; }
    }

    private final BlackForgeCore plugin;
    private final boolean sandbox;
    private final Robots robots;
    private final HopperUpgrades hoppers;
    private final File file;
    private final Map<String, Spot> stations = new HashMap<>();   // region -> station
    private final int price, essence;
    private int radius;
    private final boolean enabled;
    private BiPredicate<Player, Location> access = (p, l) -> false;
    private Function<Location, String> regionOf = l -> null;
    private boolean requireLiveDeliveryRegion;
    private Economy eco;
    private Allays allays;
    private BaseLevels levels;

    BaseStation(BlackForgeCore plugin, Robots robots, HopperUpgrades hoppers) { this(plugin, robots, hoppers, false); }

    /** {@code sandbox}: the Arena's Foundry, where everything is free and nothing is saved. */
    BaseStation(BlackForgeCore plugin, Robots robots, HopperUpgrades hoppers, boolean sandbox) {
        this.sandbox = sandbox;
        this.plugin = plugin;
        this.robots = robots;
        this.hoppers = hoppers;
        this.file = new File(plugin.getDataFolder(), "base-stations.yml");
        var c = plugin.getConfig();
        price = c.getInt("base-station.price", 25_000);
        essence = c.getInt("base-station.essence", 128);
        radius = c.getInt("base-station.agent-radius", 16);
        enabled = c.getBoolean("base-station.enabled", true);
        StationUi.setArt(c.getBoolean("base-station.custom-ui", true));
        if (!sandbox) load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        robots.setStations(this);
    }

    void setAccess(BiPredicate<Player, Location> rule) { this.access = rule; }
    void setRegion(Function<Location, String> rule) { this.regionOf = rule; }
    /** How far an agent can deliver from. The Foundry's benches are spread out across one shared base. */
    void setRadius(int blocks) { this.radius = blocks; }
    void requireLiveDeliveryRegion() { requireLiveDeliveryRegion = true; }
    void setEconomy(Economy e) { this.eco = e; }
    void setAllays(Allays a) { this.allays = a; }
    void setLevels(BaseLevels l) { this.levels = l; }

    // ------------------------------------------------------------------ items

    public static ItemStack item() {
        ItemStack it = Ui.item(Material.BARREL, "<gradient:#ffb347:#ff5a1a><bold>Base Station</bold></gradient>",
                "<gray>Place one in your claim or island.", "<gray>Agents within range deliver to it, and",
                "<gray>it upgrades your agents and hoppers.", "", "<dark_gray>Sneak and right-click for its storage", "<dark_gray>One per claim or island");
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

    boolean pay(Player p, int money, int ess) {
        if (sandbox) return true;
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
            String region = en.getKey();
            if (!region.equals(regionOf.apply(at))) continue;
            Block block = w.getBlockAt(s.x(), s.y(), s.z());
            if (!isStation(block)) continue;
            // A marked barrel can outlive the claim it was placed in.
            if (requireLiveDeliveryRegion && !region.equals(regionOf.apply(block.getLocation()))) continue;
            return (Barrel) block.getState(false);
        }
        return null;
    }

    /** Would an agent here deliver to a station? */
    boolean delivers(Location agentAt) { return stationFor(agentAt) != null; }

    /** Puts what fits in the station. Returns what's left, or null if it all went in. */
    ItemStack deliver(Location agentAt, ItemStack item) {
        Barrel b = stationFor(agentAt);
        if (b == null) return item;
        int amount = item.getAmount();   // addItem can change the stack it's given
        var left = b.getInventory().addItem(item);
        ItemStack rest = left.isEmpty() ? null : left.values().iterator().next();
        // only Agents come through here, so hoppers and allays can't loop items to raise a base's level
        if (levels != null) levels.countDelivery(regionOf.apply(agentAt), amount - (rest == null ? 0 : rest.getAmount()));
        return rest;
    }

    boolean hasRoom(Location agentAt) {
        Barrel b = stationFor(agentAt);
        return b != null && b.getInventory().firstEmpty() >= 0;
    }

    // ------------------------------------------------------------------ lookups

    /** Which claim or island a spot is in, or null if it's in neither. */
    String regionAt(Location l) { return regionOf.apply(l); }

    /** This region's station, if it's loaded and really there. */
    Location stationIn(String region) {
        Spot s = region == null ? null : stations.get(region);
        World w = s == null ? null : Bukkit.getWorld(s.world());
        if (w == null || !w.isChunkLoaded(s.x() >> 4, s.z() >> 4)) return null;
        Block b = w.getBlockAt(s.x(), s.y(), s.z());
        return isStation(b) ? b.getLocation() : null;
    }

    /** The storage of this region's station, or null if {@link #stationIn} finds none. */
    Inventory storageIn(String region) {
        Location at = stationIn(region);
        return at != null && at.getBlock().getState(false) instanceof Barrel b ? b.getInventory() : null;
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
                : standing(region) ? (sandbox ? "<red>In the Foundry, Base Stations go on the Build Plot, one per marked square." : "<red>This claim or island already has a Base Station.") : null;
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
        if (OwnerView.viewing(e.getPlayer(), e.getClickedBlock().getLocation())) return;   // OwnerView shows its storage read-only
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Location l = e.getClickedBlock().getLocation();
        if (!access.test(p, l)) { p.sendActionBar(Ui.mm("<red>That's someone else's Base Station.")); return; }
        if (p.isSneaking()) openStorage(p, l);   // the forge menu is upgrades only
        else forge(p, l);
    }

    /** Card columns: an agent's card is its well on the top row plus the two rows of text under it. */
    private static final int[] CARD_COLS = {0, 2, 4, 6, 8};
    private static final int[] CARD_X = {18, 52, 88, 124, 158};
    private static final int HOPPER_INFO = 31, FORGE_SIZE = 36;
    private static final int[] BUY_SLOTS = {32, 33};
    private static final int NEXT_PAGE = 27, ALLAY_SLOT = 34;

    /** The Foundry's prebuilt Forge: marks the barrel a station. It's in its own chunk's region, like any other. */
    void adopt(Block b) {
        if (!isStation(b)) {
            if (!(b.getState() instanceof Barrel br)) return;
            br.getPersistentDataContainer().set(ITEM, PersistentDataType.BYTE, (byte) 1);
            br.update(true, false);
        }
        stations.put(regionOf.apply(b.getLocation()), Spot.of(b.getLocation()));
    }

    private void forge(Player p, Location at) { forge(p, at, 0); }

    void openForge(Player p, Location at) { forge(p, at); }

    /** Is it still a station, and can this player still use it? A menu can outlast either. */
    boolean usable(Player p, Location at) { return isStation(at.getBlock()) && access.test(p, at); }

    private void forge(Player p, Location at, int page) {
        List<Robots.Bot> all = robots.agentsOf(p.getUniqueId());
        int pages = Math.max(1, (all.size() + CARD_COLS.length - 1) / CARD_COLS.length);
        page = Math.floorMod(page, pages);
        // only the Foundry goes past one page: everywhere else the limit is one page's worth
        List<Robots.Bot> agents = sandbox ? all.subList(page * CARD_COLS.length, Math.min(all.size(), (page + 1) * CARD_COLS.length)) : all;
        int allowed = robots.limit(p);
        int shown = Math.min(agents.size(), RobotRules.MAX_AGENTS);
        Menu m = new Menu("forge", at);
        m.page = page;
        m.pages = pages;
        StationUi.Canvas title = new StationUi.Canvas().art(StationUi.Screen.FORGE);
        title.text("title", 40, "THE FORGE", 0xFFD9A0);
        title.pips(142, shown, RobotRules.MAX_AGENTS, StationUi.COUNT_ON, StationUi.COUNT_OFF, 8);
        title.text("title", 104, shown + "/" + RobotRules.MAX_AGENTS, 0xFFB347);
        for (int i = 0; i < CARD_X.length; i++) {
            if (i < shown) {
                Robots.Bot b = agents.get(i);
                title.pips(CARD_X[i], b.tier, Robots.MAX_TIER, StationUi.PIP_ON, StationUi.PIP_OFF, 4);
                title.text("gui_l1", CARD_X[i], "T " + Spawners.roman(b.tier), 0xFFD25E);
                if (b.tier < Robots.MAX_TIER && sandbox) title.text("gui_l2", CARD_X[i], "FREE", 0x7ED957);
                else if (b.tier < Robots.MAX_TIER) {
                    title.text("gui_l2", CARD_X[i], StationText.money(Robots.UP_MONEY[b.tier - 1]), 0x7ED957);
                    title.text("gui_l3", CARD_X[i], String.valueOf(Robots.UP_ESSENCE[b.tier - 1]), 0x9D7BFF);
                } else title.text("gui_l2", CARD_X[i], "MAX", 0xFF7A1A);
            } else title.text("gui_l1", CARD_X[i], i < allowed ? "EMPTY" : "RANK", i < allowed ? 0x8A8078 : 0x6E3A1C);
        }
        title.text("gui_l4", 40, "HOPPERS", 0xFFD9A0);
        title.text("gui_l4", 143, sandbox ? "FREE" : StationText.money(hoppers.price) + " EACH", 0x7ED957);
        m.inv = Bukkit.createInventory(m, FORGE_SIZE, StationUi.art() ? title.done() : Ui.mm("<dark_gray>The Forge " + shown + "/" + RobotRules.MAX_AGENTS));
        if (!StationUi.art()) {
            ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < FORGE_SIZE; i++) m.inv.setItem(i, pane);
        }
        for (int i = 0; i < CARD_COLS.length; i++) {
            int col = CARD_COLS[i];
            if (i < shown) {
                m.inv.setItem(col, robots.forgeIcon(agents.get(i)));
                for (int slot : new int[]{col, col + 9, col + 18}) m.agents.put(slot, agents.get(i).id);
            } else m.inv.setItem(col, i < allowed
                    ? Ui.item(Material.LIGHT_GRAY_DYE, "<gray><bold>Empty Slot", sandbox ? "<gray>Take an agent from the Agents chest and place it." : "<gray>Get an agent at <white>/agents<gray> and place it.")
                    : Ui.item(Material.IRON_BARS, "<dark_gray><bold>Locked", "<gray>Ranks unlock more agents, up to <white>" + RobotRules.MAX_AGENTS + "<gray>."));
        }
        m.inv.setItem(HOPPER_INFO, Ui.item(Material.HOPPER, "<gold><bold>Hopper Tiers",
                "<gray>Tier I: <white>" + hoppers.rate(1) + "<gray> items a second",
                "<gray>Tier II: <white>" + hoppers.rate(2) + "<gray> a second, takes <white>" + hoppers.needed(1) + "<gray> upgrade",
                "<gray>Tier III: <white>" + hoppers.rate(3) + "<gray> a second, takes <white>" + hoppers.needed(2) + "<gray> upgrades",
                "", "<dark_gray>Sneak and right-click a hopper to apply"));
        if (pages > 1) m.inv.setItem(NEXT_PAGE, Ui.item(Material.ARROW, "<yellow><bold>Next Agents", "<gray>Page <white>" + (page + 1) + "<gray> of <white>" + pages));
        m.inv.setItem(BUY_SLOTS[0], buyIcon(1));
        m.inv.setItem(BUY_SLOTS[1], buyIcon(10));
        if (allays != null && allays.available(p)) m.inv.setItem(ALLAY_SLOT, allays.forgeIcon(regionOf.apply(at)));
        p.openInventory(m.inv);
    }

    private ItemStack buyIcon(int n) {
        ItemStack it = HopperUpgrades.upgradeItem();
        it.setAmount(n);
        it.editMeta(m -> m.lore(List.of(
                Ui.mm(sandbox ? "<green>Free in the Foundry" : "<green>" + NetEconomy.money((double) hoppers.price * n) + " <dark_gray>+ <#9d7bff>" + (hoppers.essence * n) + " Mob Essence"),
                Ui.mm("<yellow>Click to buy " + n))));
        return it;
    }

    private void openStorage(Player p, Location at) {
        if (!(at.getBlock().getState() instanceof Barrel b) || !b.getPersistentDataContainer().has(ITEM)) { p.closeInventory(); return; }
        b.customName(StationUi.title(StationUi.Screen.STORAGE, "Station Storage"));
        b.update(true, false);
        p.openInventory(((Barrel) at.getBlock().getState(false)).getInventory());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && denyStorage(p, e.getInventory())) { e.setCancelled(true); return; }
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        // the station can be broken, or the player's access lost, while a menu stays open
        if (!usable(p, m.at)) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        if (slot == NEXT_PAGE && m.pages > 1) { forge(p, m.at, m.page + 1); return; }
        if (slot == ALLAY_SLOT && allays != null && allays.available(p)) { allays.openMenu(p, m.at); return; }
        if (slot == BUY_SLOTS[0] || slot == BUY_SLOTS[1]) { buyUpgrades(p, slot == BUY_SLOTS[0] ? 1 : 10); return; }
        String id = m.agents.get(slot);
        if (id == null) return;
        robots.upgradeFromStation(p, id);
        forge(p, m.at, m.page);
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
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu
                || e.getWhoClicked() instanceof Player p && denyStorage(p, e.getInventory())) e.setCancelled(true);
    }

    private boolean denyStorage(Player p, Inventory inventory) {
        if (!(inventory.getHolder() instanceof Barrel b) || !b.getPersistentDataContainer().has(ITEM)) return false;
        Location at = b.getLocation();
        if (p.getWorld() == at.getWorld() && isStation(at.getBlock()) && access.test(p, at)) return false;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.getOpenInventory().getTopInventory().equals(inventory)) p.closeInventory();
        });
        return true;
    }

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
        if (sandbox) return;
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
