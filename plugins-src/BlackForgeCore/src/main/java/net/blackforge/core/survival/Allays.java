package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Forge Allays: a base upgrade that stays at the Base Station and moves items. Vanilla AI is off, so they can't bond to a
 * player, follow anyone, pick items up by themselves or duplicate; this class flies them. The upgrade belongs to the base,
 * so there's no allay item to drop, sell or dupe. Bought from slot 34 of the Forge.
 * Staff test flight with {@code /allay start} and {@code /allay stop} in the Foundry. Plan: docs/plans/forge-allays.md
 */
public final class Allays implements Listener {
    private static final String NAME = "<gradient:#c8743a:#4fd1b5><bold>Forge Allay</bold></gradient>";
    private static final String[] TIER_NAME = {"Gatherer", "Courier", "Steward"};
    private static final String[] TIER_JOB = {"Carries loose drops to the Station", "Also sorts the Station into chests with that item in a frame, and refuels Agents from Fuel Cells in the Station", "Also sells from a chest with an emerald in a frame, less a fee, while the owner's online"};
    private static final int TRIP_BLOCKS = 12, MAX_TEST = 6, TEST_LIFE_TICKS = 5 * 60 * 20;
    /** The highest tier with its job built so far. Higher cards say so rather than sell nothing. */
    private static final int BUILT_TIER = 3;
    private static final int[] CARD_SLOTS = {11, 13, 15};
    private static final int BACK_SLOT = 22, MENU_SIZE = 27;
    private static final int RETRY_TICKS = 40, NEVER_PICKED_UP = 1000, WORK_CHECK_TICKS = 10;
    /** An item frame holding this marks a sell chest, not a sorting chest. */
    private static final Material SELL_MARK = Material.EMERALD;
    private static final double REACH_SQ = 9;
    /** The game itself draws a mob with this exact name upside down, as it does on a name tag. */
    private static final String FLIP_NAME = "Dinnerbone";
    private static final int NAME_REACH = 8;
    private static final int DRAIN_REACH = 20;
    /** Every bench on the Foundry's island, so refuelling can be tried at any of them. */
    private static final int SHARED_FUEL_REACH = 40;
    private static final long DRAIN_TO_MS = 60_000L;
    private static final double TAG_HEIGHT = 0.9;
    private static final int SAVE_TICKS = 1200, GRAB_HOLD_TICKS = 6;
    private static final float TAG_VIEW_RANGE = 0.2f;
    private static final Color COPPER = Color.fromRGB(0xC8743A), VERDIGRIS = Color.fromRGB(0x4FD1B5), ORANGE = Color.fromRGB(0xF28C2C), GOLD = Color.fromRGB(0xFFE096);
    private static final double SKIN_HEIGHT = 0.35, RAINBOW_SPEED = 0.01;
    private static final int FLAP_TICKS = 3, SPARKLE_TICKS = 12;

    /** A test allay flying back and forth between two points. */
    private static final class Flyer {
        final Allay entity; final double[] home, away;
        boolean out = true; int tick, ticks, rest, life;
        Flyer(Allay entity, double[] home, double[] away, int ticks) { this.entity = entity; this.home = home; this.away = away; this.ticks = ticks; }
    }

    /** One allay of a flock. The record outlives its entity, which is gone whenever the chunk unloads. */
    private static final class Worker {
        final int index; Allay entity;
        State state = State.IDLE;
        /** The trip under way. */
        double[] from, to; int tick, ticks;
        /** Its way there, or null while it waits its turn for a search. */
        AllayPath.Route route;
        /** No room for the circle round this Station, so it hovers just above it. */
        boolean tight;
        /** What it's fetching, and what it's holding. Holding is kept in memory, because the item has left the world. */
        UUID job; ItemStack carried;
        int rest, hold; String label; float trail = 0.8f;
        int tier = 1;
        /** The Tier II or III look: the real allay goes invisible and this flies with it. */
        ItemDisplay skin;
        int skinTier; boolean skinFlipped, wingsUp;
        /** Where a load is going, other than the Station: a chest or an Agent. */
        Block dest; Robots.Bot agent;
        /** Set when a load is from the sell chest, so the Station end of the trip is a sale. */
        Block sellFrom;
        /** What the player called it, or null. */
        String name;
        /** Shows the name of a flipped allay, whose real name has to be the one the game flips. */
        TextDisplay tag;
        Worker(int index) { this.index = index; }
    }

    private enum State { IDLE, FETCH, COLLECT, DELIVER, RETURN, PAUSE, WAIT }

    /** A chest that takes this material, from an item frame on it. */
    private record Link(Material material, Block chest) {}

    /** One claim or island's upgrade. */
    private static final class Base {
        final String region; final UUID buyer; int tier;
        final List<Worker> workers = new ArrayList<>();
        final List<AllayJobs.Job> jobs = new ArrayList<>();
        final List<Link> links = new ArrayList<>();
        final List<Block> sellChests = new ArrayList<>();
        final List<Robots.Bot> thirsty = new ArrayList<>();
        long lastScan = Long.MIN_VALUE / 2, lastLinkScan = Long.MIN_VALUE / 2;
        boolean tight;
        Base(String region, UUID buyer) { this.region = region; this.buyer = buyer; }
    }

    private static final class Menu implements InventoryHolder {
        final Location at; Inventory inv;
        Menu(Location at) { this.at = at; }
        public Inventory getInventory() { return inv; }
    }

    private final BlackForgeCore plugin;
    private final BaseStation station;
    private final NamespacedKey marker;
    private final Predicate<Location> zone;
    private final boolean sandbox;
    private final AllayRules.Table table;
    private final double speed;
    private final int pauseTicks, carry, minDropAge, scanTicks, maxScans, linkScanTicks;
    private final NamespacedKey deathDrop;
    private org.bukkit.World deathWorld;
    private double deathX, deathY, deathZ;
    private int deathTick = -1, scansThisTick, searchesThisTick;
    private final int pathMaxNodes, maxSearches;
    private final List<Flyer> flyers = new ArrayList<>();
    private final Map<String, Base> bases = new HashMap<>();
    private long now;
    private int sandboxLimit = Integer.MAX_VALUE;
    private final boolean sausageFlip;
    private Robots robots;
    private GroveTrader trader;
    private Function<String, UUID> ownerOf;
    /** The Foundry's Forge: its flock belongs to the island, not to whoever got there first. */
    private String sharedRegion;
    private java.util.function.Supplier<UUID> sharedOwner = () -> null;
    private final double sellFee;
    private final boolean enabled, previewStaff, customSkins;
    private final ItemStack[][] skinItems = buildSkinItems();
    private final File file;

    /** {@code zone}: where staff may test flight. {@code sandbox}: the Arena's Foundry, where everything is free and nothing is saved. */
    Allays(BlackForgeCore plugin, BaseStation station, Predicate<Location> zone, boolean sandbox) {
        this.plugin = plugin;
        this.station = station;
        this.zone = zone;
        this.sandbox = sandbox;
        this.marker = new NamespacedKey(BlackForgeCore.NS, "forge_allay");
        this.deathDrop = new NamespacedKey(BlackForgeCore.NS, "death_drop");
        var c = plugin.getConfig();
        this.table = table(c);
        this.sausageFlip = c.getBoolean("allays.sausage-flip", true);
        this.carry = Math.max(1, c.getInt("allays.carry", 16));
        this.minDropAge = c.getInt("allays.min-drop-age-ticks", 60);
        // the Foundry looks more often, so a tester isn't left wondering if it worked
        this.scanTicks = Math.max(20, (sandbox ? c.getInt("foundry.allay-scan-seconds", 2) : c.getInt("allays.scan-seconds", 5)) * 20);
        this.maxScans = Math.max(1, c.getInt("allays.max-scans-per-tick", 4));
        this.sellFee = c.getDouble("allays.sell-fee", 0.10);
        this.customSkins = c.getBoolean("resource-pack-live", false) && c.getBoolean("allays.custom-skins", true);
        this.pathMaxNodes = Math.max(100, c.getInt("allays.path-max-nodes", 2000));
        this.maxSearches = Math.max(1, c.getInt("allays.max-path-searches-per-tick", 2));
        this.enabled = c.getBoolean("allays.enabled", false);
        this.previewStaff = c.getBoolean("allays.preview-staff", false);
        this.file = new File(plugin.getDataFolder(), "allays.yml");
        if (!sandbox) load();
        this.linkScanTicks = Math.max(20, (sandbox ? c.getInt("foundry.allay-link-scan-seconds", 5) : c.getInt("allays.link-scan-seconds", 30)) * 20);
        this.speed = Math.max(0.5, c.getDouble("allays.speed", 6.0));
        this.pauseTicks = (int) Math.max(0, Math.round(c.getDouble("allays.pause-seconds", 1.0) * 20));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        if (sandbox) registerCommand();
    }

    void setSandboxLimit(int total) { this.sandboxLimit = total; }
    void setRobots(Robots r) { this.robots = r; }
    void setTrader(GroveTrader t) { this.trader = t; }
    /** Who a region's sales pay. Without this, the Foundry's case, it's whoever bought the upgrade. */
    void setOwnerOf(Function<String, UUID> rule) { this.ownerOf = rule; }
    void setShared(String region, java.util.function.Supplier<UUID> owner) { this.sharedRegion = region; this.sharedOwner = owner; }
    private boolean shared(Base b) { return b.region.equals(sharedRegion); }

    private static AllayRules.Table table(org.bukkit.configuration.file.FileConfiguration c) {
        AllayRules.Table d = AllayRules.Table.DEFAULT;
        return new AllayRules.Table(list(c, "allays.flock", d.flock()), list(c, "allays.leash", d.leash()),
                list(c, "allays.price", d.price()), list(c, "allays.essence", d.essence()));
    }

    private static int[] list(org.bukkit.configuration.file.FileConfiguration c, String key, int[] fallback) {
        List<Integer> v = c.getIntegerList(key);
        return v.size() == AllayRules.MAX_TIER ? v.stream().mapToInt(Integer::intValue).toArray() : fallback;
    }

    // ------------------------------------------------------------------ test command

    private void registerCommand() {
        Bukkit.getCommandMap().register("blackforge", new Command("allay", "Test Forge Allays (staff)", "/allay <start|stop>", List.of()) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!s.hasPermission("blackforge.admin")) { s.sendMessage(Ui.mm("<gray>Forge Allay tests are staff-only.")); return true; }
                String what = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.ROOT);
                if (what.equals("stop")) {
                    int n = stop();
                    s.sendMessage(Ui.mm(n == 0 ? "<gray>No test allays were running." : "<green>Removed " + n + " test allay" + (n == 1 ? "" : "s") + "."));
                } else if (what.equals("start") && s instanceof Player p) start(p);
                else if (what.equals("drain") && s instanceof Player p) drain(p);
                else if (what.equals("name") && a.length > 1 && s instanceof Player p) nameNearest(p, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
                else s.sendMessage(Ui.mm("<gray>Stand in the Foundry and run <white>/allay start<gray>, then <white>/allay stop<gray>. <white>/allay name <text><gray> names the nearest summoned allay, and <white>/allay drain<gray> runs your nearby Agents low on fuel."));
                return true;
            }
        });
    }

    private void start(Player p) {
        if (!zone.test(p.getLocation())) { p.sendMessage(Ui.mm("<gray>Go to the Foundry first: <white>/arena<gray>, then over the bridge.")); return; }
        if (flyers.size() >= MAX_TEST) { p.sendMessage(Ui.mm("<gray>That's " + MAX_TEST + " test allays already. <white>/allay stop<gray> clears them.")); return; }
        Location home = p.getLocation().add(0, 2, 0);
        Vector look = p.getLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) look = new Vector(1, 0, 0);
        look.normalize();
        int reach = TRIP_BLOCKS;
        while (reach > 2 && !zone.test(home.clone().add(look.clone().multiply(reach)))) reach--;
        Location away = home.clone().add(look.clone().multiply(reach));
        flyers.add(new Flyer(spawn(home), xyz(home), xyz(away), AllayFlight.ticks(reach, speed)));
        p.sendMessage(Ui.mm("<green>Test allay started, flying " + reach + " blocks and back. <gray>Try right-clicking it, holding items near it and playing a jukebox. It should ignore all of that. <white>/allay stop<gray> removes it."));
    }

    /** Staff test for refuelling, so nobody waits an hour: your nearby Foundry Agents drop to one minute of fuel. */
    private void drain(Player p) {
        if (robots == null || !zone.test(p.getLocation())) { p.sendMessage(Ui.mm("<gray>Go to the Foundry first: <white>/arena<gray>, then over the bridge.")); return; }
        int n = 0;
        for (Robots.Bot agent : robots.agentsNear(p.getLocation(), DRAIN_REACH, p.getUniqueId())) {
            agent.fuel = Math.min(agent.fuel, DRAIN_TO_MS);
            n++;
        }
        p.sendMessage(Ui.mm(n == 0 ? "<gray>You have no Agents within " + DRAIN_REACH + " blocks." : "<green>" + n + " Agent" + (n == 1 ? "" : "s") + " down to a minute of fuel. <gray>A Courier should come within a few seconds if the Forge has Fuel Cells."));
    }

    /** Staff test for naming, so nobody needs an anvil: the nearest summoned allay in reach. */
    private void nameNearest(Player p, String name) {
        Worker best = null;
        double bestDist = NAME_REACH * NAME_REACH;
        for (Base b : bases.values()) for (Worker w : b.workers) {
            if (w.entity == null || !w.entity.isValid() || w.entity.getWorld() != p.getWorld()) continue;
            double d = w.entity.getLocation().distanceSquared(p.getLocation());
            if (d <= bestDist) { bestDist = d; best = w; }
        }
        if (best == null) { p.sendMessage(Ui.mm("<gray>No summoned allay within " + NAME_REACH + " blocks. Summon one from slot 34 of the Forge.")); return; }
        rename(p, best, name);
    }

    /** Refuses a bad name out loud and returns false, so a name tag isn't used up on it. */
    private boolean rename(Player p, Worker w, String name) {
        if (!AllayRules.validName(name)) { p.sendActionBar(Ui.mm("<red>Allay names are up to 16 letters, numbers and spaces.")); return false; }
        w.name = name;
        w.label = null;
        save(false);
        p.playSound(w.entity.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1f);
        p.sendActionBar(Ui.mm("<#4fd1b5>Named your allay <white>" + name + "<#4fd1b5>."));
        return true;
    }

    /** Removes every test allay. Returns how many, and is safe when there are none. */
    int stop() {
        int n = flyers.size();
        for (Flyer f : flyers) f.entity.remove();
        flyers.clear();
        return n;
    }

    void shutdown() {
        stop();
        if (sandbox) {
            for (Base b : bases.values()) sleep(b);
            bases.clear();
            return;
        }
        // a clean stop saves what they're holding, so nothing has to land on the ground
        save(true);
        if (trader != null) trader.saveSales();
        for (Base b : bases.values()) for (Worker w : b.workers) {
            if (w.entity != null) w.entity.remove();
            removeTag(w);
            removeSkin(w);
        }
    }

    // ------------------------------------------------------------------ saving

    /**
     * Loads are only written on a clean stop. The minute-by-minute save leaves them out, because a load delivered after that
     * save would come back after a crash and be doubled. A crash can lose a load instead.
     */
    private void save(boolean withLoads) {
        if (sandbox) return;
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Base b : bases.values()) {
            String k = "bases." + i++ + ".";
            y.set(k + "region", b.region);
            y.set(k + "tier", b.tier);
            y.set(k + "buyer", b.buyer.toString());
            for (Worker w : b.workers) {
                if (w.name != null) y.set(k + "names." + w.index, w.name);
                if (withLoads && w.carried != null) y.set(k + "loads." + w.index, w.carried);
            }
        }
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("allay save failed: " + e.getMessage()); }
    }

    private void load() {
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("bases");
        if (all == null) return;
        for (String i : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(i);
            if (s == null) continue;
            String region = s.getString("region"), buyer = s.getString("buyer");
            int tier = s.getInt("tier");
            if (region == null || buyer == null || tier < 1 || tier > AllayRules.MAX_TIER) continue;
            Base b = new Base(region, UUID.fromString(buyer));
            b.tier = tier;
            for (int n = 0; n < table.flock(tier); n++) {
                Worker w = new Worker(n);
                w.name = s.getString("names." + n);
                w.carried = s.getItemStack("loads." + n);
                b.workers.add(w);
            }
            bases.put(region, b);
        }
    }

    /** The Foundry, when someone leaves: their purchases go with them. */
    void clear(UUID buyer) {
        bases.values().removeIf(b -> {
            if (!b.buyer.equals(buyer) || shared(b)) return false;
            sleep(b);
            return true;
        });
    }

    /** The island is empty: the shared flock goes, and the next tester starts fresh. */
    void resetShared() {
        Base b = sharedRegion == null ? null : bases.remove(sharedRegion);
        if (b != null) sleep(b);
    }

    int sharedTier() {
        Base b = sharedRegion == null ? null : bases.get(sharedRegion);
        return b == null ? 0 : b.tier;
    }

    /** Sets the shared flock's tier for free, from /foundry. 0 sends it away. False if that would pass the Foundry's limit. */
    boolean setSharedTier(UUID by, int tier) {
        if (tier == 0) { resetShared(); return true; }
        if (tier < 0 || tier > BUILT_TIER) return false;
        int others = 0;
        for (Base o : bases.values()) if (!shared(o)) others += o.workers.size();
        if (others + table.flock(tier) > sandboxLimit) return false;
        Base b = bases.get(sharedRegion);
        if (b == null) { b = new Base(sharedRegion, by); bases.put(sharedRegion, b); }
        else { sleep(b); b.workers.clear(); }
        b.tier = tier;
        b.lastScan = b.lastLinkScan = Long.MIN_VALUE / 2;
        return true;
    }

    /** "Tier II Courier", and what it does. */
    String tierText(int tier) { return tier == 0 ? "off" : "Tier " + Spawners.roman(tier) + " " + TIER_NAME[tier - 1] + ": " + TIER_JOB[tier - 1] + "."; }

    /** The shared flock, for /foundry status. */
    List<String> sharedStatus() {
        Base b = sharedRegion == null ? null : bases.get(sharedRegion);
        if (b == null) return List.of("<gray>Allays: <white>off <dark_gray>· <white>/foundry allays 1<gray> starts them");
        Map<String, Integer> doing = new java.util.TreeMap<>();
        int flying = 0, carrying = 0;
        for (Worker w : b.workers) {
            if (w.entity != null && w.entity.isValid()) flying++;
            if (w.carried != null) carrying++;
            doing.merge(w.state.name().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
        }
        return List.of("<gray>Allays: <white>Tier " + Spawners.roman(b.tier) + " " + TIER_NAME[b.tier - 1] + "<gray>, <white>" + flying + "/" + table.flock(b.tier) + "<gray> flying, <white>" + carrying + "<gray> carrying " + doing,
                "<gray>They see: <white>" + b.jobs.size() + "<gray> loose drops, <white>" + b.links.size() + "<gray> framed chests, <white>" + b.sellChests.size() + "<gray> sell chests, <white>" + b.thirsty.size() + "<gray> Agents low on fuel");
    }

    private static double[] xyz(Location l) { return new double[]{l.getX(), l.getY(), l.getZ()}; }

    // ------------------------------------------------------------------ the entity

    private Allay spawn(Location at) {
        return at.getWorld().spawn(at, Allay.class, x -> {
            x.setAI(false);              // no bond, no following, no pickups, no dancing
            x.setGravity(false);
            x.setInvulnerable(true);
            x.setSilent(true);
            x.setCollidable(false);
            x.setCanPickupItems(false);
            x.setCanDuplicate(false);
            x.setPersistent(false);      // a crash or restart can't leave one behind
            x.setRemoveWhenFarAway(false);
            x.customName(Ui.mm(NAME));
            x.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private boolean isOurs(Entity e) { return e.getPersistentDataContainer().has(marker); }

    private boolean isLive(Entity e) {
        for (Flyer f : flyers) if (f.entity.equals(e)) return true;
        for (Base b : bases.values()) for (Worker w : b.workers) if (e.equals(w.entity)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTouch(PlayerInteractEntityEvent e) {
        if (!isOurs(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType() == Material.NAME_TAG) useNameTag(p, held, e.getRightClicked());
    }

    private void useNameTag(Player p, ItemStack tag, Entity allay) {
        for (Base b : bases.values()) for (Worker w : b.workers) {
            if (!allay.equals(w.entity)) continue;
            Location at = station.stationIn(b.region);
            if (at == null || !station.usable(p, at)) { p.sendActionBar(Ui.mm("<red>That's someone else's allay.")); return; }
            if (!tag.hasItemMeta() || !tag.getItemMeta().hasCustomName()) { p.sendActionBar(Ui.mm("<gray>Rename this Name Tag in an anvil first.")); return; }
            String name = PlainTextComponentSerializer.plainText().serialize(tag.getItemMeta().customName()).trim();
            if (rename(p, w, name)) tag.setAmount(tag.getAmount() - 1);
            return;
        }
        p.sendActionBar(Ui.mm("<gray>This one's only a test. Name a summoned allay."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent e) { if (isOurs(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) { if (isOurs(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) { if (isOurs(e.getEntity())) e.setCancelled(true); }

    /** Anything that comes back from disk isn't one we're flying. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity x : e.getEntities()) if (isOurs(x) && !isLive(x)) x.remove();
    }

    // ------------------------------------------------------------------ the Forge's Allay menu

    /** Should the Forge offer this player the Allay menu? */
    boolean available(Player p) { return sandbox || enabled || previewStaff && p.hasPermission("blackforge.admin"); }

    ItemStack forgeIcon(String region) {
        Base b = bases.get(region);
        int tier = b == null ? 0 : b.tier;
        ItemStack it = Ui.item(Material.ALLAY_SPAWN_EGG, NAME, "<gray>A crew that stays at your Station", "<gray>and moves items for you.", "",
                tier == 0 ? "<gray>Not summoned yet" : "<gray>Tier: <white>" + Spawners.roman(tier) + " <gray>" + TIER_NAME[tier - 1],
                "<dark_gray>Use a Name Tag on one to name it", "", "<yellow>Click to open");
        it.setAmount(Math.max(1, tier));
        return it;
    }

    void openMenu(Player p, Location at) {
        Base b = bases.get(station.regionAt(at));
        int tier = b == null ? 0 : b.tier;
        Menu m = new Menu(at);
        m.inv = Bukkit.createInventory(m, MENU_SIZE, Ui.mm("<dark_gray>Forge Allays"));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < MENU_SIZE; i++) m.inv.setItem(i, pane);
        for (int t = 1; t <= AllayRules.MAX_TIER; t++) m.inv.setItem(CARD_SLOTS[t - 1], card(t, tier));
        m.inv.setItem(BACK_SLOT, Ui.item(Material.ARROW, "<yellow>Back to the Forge"));
        p.openInventory(m.inv);
    }

    private ItemStack card(int t, int have) {
        AllayRules.Cost cost = table.cost(t);
        List<String> lore = new ArrayList<>(List.of("<gray>" + TIER_JOB[t - 1] + ".", "",
                "<gray>Allays: <white>" + table.flock(t), "<gray>Reach: <white>" + table.leash(t) + " blocks", ""));
        if (have >= t) lore.add("<green><bold>Summoned");
        else if (t > BUILT_TIER) lore.add("<dark_gray>Not ready yet");
        else if (t > have + 1) lore.add("<dark_gray>Needs Tier " + Spawners.roman(t - 1) + " first");
        else {
            lore.add(sandbox ? "<green>Free in the Foundry" : "<green>" + NetEconomy.money(cost.money()) + " <dark_gray>+ <#9d7bff>" + cost.essence() + " Mob Essence");
            lore.add("");
            lore.add("<yellow>Click to summon");
        }
        Material icon = have >= t ? Material.ALLAY_SPAWN_EGG : t == have + 1 && t <= BUILT_TIER ? Material.AMETHYST_SHARD : Material.GRAY_DYE;
        return Ui.item(icon, "<#4fd1b5><bold>Tier " + Spawners.roman(t) + ": " + TIER_NAME[t - 1], lore.toArray(String[]::new));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        // the station can be broken, or the player's access lost, while a menu stays open
        if (!station.usable(p, m.at)) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        if (slot == BACK_SLOT) { station.openForge(p, m.at); return; }
        for (int t = 1; t <= AllayRules.MAX_TIER; t++) if (slot == CARD_SLOTS[t - 1]) buy(p, m.at, t);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    private void buy(Player p, Location at, int t) {
        String region = station.regionAt(at);
        Base b = bases.get(region);
        // read from the base, not the card: two members can have this menu open at once
        if ((b == null ? 0 : b.tier) != t - 1 || t > BUILT_TIER) return;
        if (sandbox) {
            int all = table.flock(t) - (b == null ? 0 : b.workers.size());
            for (Base o : bases.values()) all += o.workers.size();
            if (all > sandboxLimit) { p.sendActionBar(Ui.mm("<red>The Foundry has enough allays flying right now. Try again soon.")); return; }
        }
        AllayRules.Cost cost = table.cost(t);
        if (!station.pay(p, cost.money(), cost.essence())) return;
        if (b == null) { b = new Base(region, p.getUniqueId()); bases.put(region, b); }
        b.tier = t;
        save(false);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
        p.sendMessage(Ui.mm("<#4fd1b5>Your base now has a Tier " + Spawners.roman(t) + " " + TIER_NAME[t - 1] + ". <gray>" + TIER_JOB[t - 1] + "."));
        p.sendMessage(Ui.mm(switch (t) {
            case 1 -> "<gray>It works while the base is loaded, within <white>" + table.leash(t) + "<gray> blocks of your Station and inside your claim.";
            case 2 -> "<gray>Hang an item frame on a chest and put an item in it. Couriers bring that item there, and keep Agents fuelled from Fuel Cells in the Station.";
            default -> "<gray>Hang a frame on a chest and put an <white>emerald<gray> in it. Stewards sell plain goods from that chest at Trader prices, less <white>" + Math.round(sellFee * 100) + "%<gray>, while the owner's online. It shares the Trader's daily limit.";
        }));
        openMenu(p, at);
    }

    // ------------------------------------------------------------------ flying

    private void tick() {
        now++;
        scansThisTick = 0;
        searchesThisTick = 0;
        flyers.removeIf(f -> {
            if (!f.entity.isValid()) return true;
            if (++f.life > TEST_LIFE_TICKS) { f.entity.remove(); return true; }   // nobody has to remember to clean up
            step(f);
            return false;
        });
        if (!sandbox && now % SAVE_TICKS == 0) {
            save(false);
            if (trader != null) trader.saveSales();
        }
        if (!sandbox && !enabled && !previewStaff) return;   // switched off: no allays fly, and every tier is kept
        for (Base b : bases.values()) tickBase(b);
    }

    private void step(Flyer f) {
        if (f.rest > 0) { f.rest--; return; }
        double[] from = f.out ? f.home : f.away, to = f.out ? f.away : f.home;
        f.tick++;
        double[] p = AllayFlight.at(from, to, f.tick, f.ticks);
        // face where it's going, turning from the motion and never from a player's camera
        moveTo(f.entity, p, to[0] - from[0], to[2] - from[2]);
        if (f.tick >= f.ticks) { f.tick = 0; f.out = !f.out; f.rest = pauseTicks; }
    }

    private static void moveTo(Allay a, double[] p, double headX, double headZ) {
        Location now = a.getLocation(), next = new Location(now.getWorld(), p[0], p[1], p[2]);
        Vector dir = new Vector(headX, 0, headZ);
        next.setDirection(dir.lengthSquared() < 1e-4 ? now.getDirection() : dir);
        next.setPitch(0);
        a.teleport(next);
    }

    private void tickBase(Base b) {
        Location at = station.stationIn(b.region);
        if (at == null) { sleep(b); return; }
        int flock = table.flock(b.tier);
        while (b.workers.size() < flock) b.workers.add(new Worker(b.workers.size()));
        scan(b, at);
        scanLinks(b, at);
        Set<UUID> taken = new HashSet<>();
        for (Worker w : b.workers) if (w.job != null) taken.add(w.job);
        for (Worker w : b.workers) {
            if (w.entity == null || !w.entity.isValid()) wake(w, at, flock);
            w.trail = 0.6f + 0.2f * b.tier;   // a higher tier burns brighter
            w.tier = b.tier;
            w.tight = b.tight;
            work(b, w, at, flock, taken);
            label(w, b.tier, w.state == State.WAIT ? "<red>Station full" : null);
            moveTag(w);
            dress(w);
        }
    }

    /** A new entity for this allay. If it was carrying something when its last one went, it takes that home first. */
    private void wake(Worker w, Location at, int flock) {
        double[] spot = hover(at, w, flock, now);
        w.entity = spawn(new Location(at.getWorld(), spot[0], spot[1], spot[2]));
        w.label = null;
        w.job = null;
        w.dest = null;
        w.agent = null;
        w.route = null;
        if (w.carried != null) goHome(w, at, flock);   // a load from the sell chest stays one, so it's still sold
        else w.state = State.IDLE;
    }

    private void work(Base b, Worker w, Location at, int flock, Set<UUID> taken) {
        switch (w.state) {
            case IDLE -> {
                idle(w, at, flock);
                if ((now + w.index) % WORK_CHECK_TICKS != 0) return;
                Inventory store = station.storageIn(b.region);
                if (store == null) return;
                if (AllayRules.canRefuel(b.tier) && startRefuel(b, w, store)) return;
                if (AllayRules.canSort(b.tier) && startSort(b, w, store)) return;
                if (AllayRules.canSell(b.tier) && startSell(b, w)) return;
                var job = store.firstEmpty() < 0 ? Optional.<AllayJobs.Job>empty()
                        : AllayJobs.nearest(xyz(w.entity.getLocation()), b.jobs, taken);
                if (job.isPresent()) {
                    w.job = job.get().id();
                    taken.add(w.job);
                    w.state = State.FETCH;
                    fly(w, new double[]{job.get().x(), job.get().y() + 0.4, job.get().z()});
                }
            }
            case COLLECT -> {
                if (!advance(w, at, flock)) return;
                collect(b, w);
                goHome(w, at, flock);
            }
            case DELIVER -> {
                if (!advance(w, at, flock)) return;
                if (w.agent != null) fuelAgent(w);
                else dropOff(w);
                goHome(w, at, flock);
            }
            case FETCH -> {
                if (!advance(w, at, flock)) return;
                if (++w.hold < GRAB_HOLD_TICKS) return;   // a short pause over the drop, so the pickup reads
                w.hold = 0;
                grab(b, w);
                goHome(w, at, flock);
            }
            case RETURN -> {
                if (!advance(w, at, flock)) return;
                if (w.carried == null) { w.sellFrom = null; rest(w); return; }
                if (w.sellFrom != null) sell(b, w, at);
                else unload(b, w, at);
            }
            case PAUSE -> {
                idle(w, at, flock);
                if (--w.rest <= 0) w.state = State.IDLE;
            }
            case WAIT -> {
                idle(w, at, flock);
                if (--w.rest <= 0) unload(b, w, at);
            }
        }
    }

    private void idle(Worker w, Location at, int flock) { moveTo(w.entity, hover(at, w, flock, now), 0, 0); }

    /** Sets off for a spot. A trip needing a search may wait a tick or two for its turn: see {@link #plan}. */
    private void fly(Worker w, double[] to) {
        w.from = xyz(w.entity.getLocation());
        World world = w.entity.getWorld();
        w.to = AllayPath.nudge(to, (x, y, z) -> free(world, x, y, z));
        w.tick = 0;
        w.route = null;
        plan(w);
    }

    /**
     * Works out the way there. A clear line costs almost nothing. A search is limited per tick across the server, so a busy
     * moment means a short wait. If there's no way at all, it flies straight: a sealed room still gets its goods.
     */
    private boolean plan(Worker w) {
        World world = w.entity.getWorld();
        AllayPath.Free open = (x, y, z) -> free(world, x, y, z);
        if (AllayPath.needsSearch(w.from, w.to, open)) {
            if (searchesThisTick >= maxSearches) return false;
            searchesThisTick++;
            AllayPath.Route found = AllayPath.route(w.from, w.to, open, pathMaxNodes);
            w.route = found != null ? found : new AllayPath.Route(List.of(w.from, w.to));
        } else w.route = new AllayPath.Route(List.of(w.from, w.to));
        w.ticks = AllayFlight.ticks(w.route.length, speed);
        return true;
    }

    /** Back to its place in the circle. The trip is timed first, so it can aim for where that place will be when it lands and not snap. */
    private void goHome(Worker w, Location at, int flock) {
        w.job = null;
        w.dest = null;
        w.agent = null;   // a trip that gave up mustn't keep its claim on an Agent
        w.state = State.RETURN;
        double[] spot = hover(at, w, flock, now);
        fly(w, spot);
        if (w.route != null) landOnTime(w, at, flock);
    }

    private void landOnTime(Worker w, Location at, int flock) {
        if (w.state != State.RETURN) return;
        double[] landing = hover(at, w, flock, now + w.ticks);
        double[][] p = w.route.points;
        if (p.length == 2) w.route = new AllayPath.Route(List.of(w.from, landing));
        else {
            List<double[]> pts = new ArrayList<>(List.of(p));
            pts.set(pts.size() - 1, landing);
            w.route = new AllayPath.Route(pts);
        }
        w.to = landing;
    }

    /** One tick of the trip. True on arrival. If the way ahead isn't loaded, it gives up and turns for home, so it never loads a chunk. */
    private boolean advance(Worker w, Location at, int flock) {
        if (w.route == null) {
            if (!plan(w)) return false;   // still waiting its turn
            landOnTime(w, at, flock);
        }
        w.tick++;
        double along = AllayFlight.ease((double) w.tick / w.ticks) * w.route.length;
        double[] p = w.route.at(along);
        if (w.route.straight()) p[1] += AllayFlight.lift((double) w.tick / w.ticks, w.route.length);
        World world = w.entity.getWorld();
        if (!world.isChunkLoaded((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[2]) >> 4)) {
            if (w.state == State.FETCH || w.state == State.DELIVER || w.state == State.COLLECT) goHome(w, at, flock);
            else { w.tick = w.ticks; return true; }   // home is the Station's own area: being unloaded here means we're about to sleep anyway
            return false;
        }
        double[] here = xyz(w.entity.getLocation());
        moveTo(w.entity, p, p[0] - here[0], p[2] - here[2]);   // faces the way it's moving, including round a corner
        if (w.tick % 2 == 0 && w.tick < w.ticks) {
            Color start = w.tier >= 3 ? Color.fromRGB(AllayFlight.rainbow(now * RAINBOW_SPEED)) : w.tier == 2 ? ORANGE : COPPER;
            Color end = w.tier >= 3 ? Color.fromRGB(AllayFlight.rainbow(now * RAINBOW_SPEED + 0.15)) : w.tier == 2 ? GOLD : VERDIGRIS;
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p[0], p[1] + 0.3, p[2], 1, 0.08, 0.08, 0.08, 0, new Particle.DustTransition(start, end, w.trail));
        }
        return w.tick >= w.ticks;
    }

    /** Takes the cheapest Fuel Cell in the Station to an Agent that's running low and that no other allay is already going to. */
    private boolean startRefuel(Base b, Worker w, Inventory store) {
        if (robots == null || b.thirsty.isEmpty()) return false;
        ItemStack[] all = store.getContents();
        int cell = -1;
        for (int i = 0; i < all.length; i++) {
            int t = Robots.fuelTier(all[i]);
            if (t > 0 && (cell < 0 || t < Robots.fuelTier(all[cell]))) cell = i;
        }
        if (cell < 0) return false;
        for (Robots.Bot agent : b.thirsty) {
            Location l = agent.loc;
            if (!robots.isPlaced(agent) || !robots.wantsFuel(agent) || l.getWorld() != w.entity.getWorld()
                    || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            boolean claimed = false;
            for (Worker o : b.workers) if (o.agent == agent) claimed = true;
            if (claimed) continue;
            ItemStack stack = all[cell], one = stack.clone();
            one.setAmount(1);
            if (stack.getAmount() <= 1) store.setItem(cell, null);
            else { stack.setAmount(stack.getAmount() - 1); store.setItem(cell, stack); }
            w.carried = one;
            w.agent = agent;
            w.state = State.DELIVER;
            fly(w, new double[]{l.getX(), l.getY() + 1.6, l.getZ()});
            return true;
        }
        return false;
    }

    /** A cell that won't go in, because the Agent was picked up or filled meanwhile, goes back to the Station. */
    private void fuelAgent(Worker w) {
        Robots.Bot agent = w.agent;
        w.agent = null;
        if (w.carried == null || !robots.isPlaced(agent) || !robots.fuelFromItem(agent, w.carried)) return;
        w.carried = null;
        w.entity.getWorld().playSound(agent.loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 0.9f);
        w.entity.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, agent.loc.clone().add(0, 1, 0), 8, 0.3, 0.3, 0.3, 0.02);
    }

    /** Takes a load out of the Station for the nearest linked chest with room. False if there's nothing to sort. */
    private boolean startSort(Base b, Worker w, Inventory store) {
        ItemStack[] all = store.getContents();
        for (int i = 0; i < all.length; i++) {
            ItemStack it = all[i];
            if (it == null || it.getType().isAir() || Robots.fuelTier(it) > 0) continue;   // Fuel Cells stay for the Agents
            Block chest = chestFor(b, w, it);
            if (chest == null) continue;
            int n = Math.min(it.getAmount(), carry);
            ItemStack load = it.clone();
            load.setAmount(n);
            if (n >= it.getAmount()) store.setItem(i, null);
            else { it.setAmount(it.getAmount() - n); store.setItem(i, it); }
            w.carried = load;
            w.dest = chest;
            w.state = State.DELIVER;
            Location c = chest.getLocation();
            fly(w, new double[]{c.getX() + 0.5, c.getY() + 1.4, c.getZ() + 0.5});
            return true;
        }
        return false;
    }

    /** The nearest chest linked to this item that still has room for some of it. */
    private Block chestFor(Base b, Worker w, ItemStack it) {
        Map<double[], Block> places = new HashMap<>();
        for (Link l : b.links) {
            if (l.material() != it.getType() || !l.chest().getWorld().isChunkLoaded(l.chest().getX() >> 4, l.chest().getZ() >> 4)) continue;
            if (!(l.chest().getState(false) instanceof Container c) || !hasRoom(c.getInventory(), it)) continue;
            Location at = l.chest().getLocation();
            places.put(new double[]{at.getX(), at.getY(), at.getZ()}, l.chest());
        }
        return AllayJobs.nearestPlace(xyz(w.entity.getLocation()), places.keySet()).map(places::get).orElse(null);
    }

    private static boolean hasRoom(Inventory inv, ItemStack it) {
        if (inv.firstEmpty() >= 0) return true;
        for (ItemStack s : inv.getContents()) if (s != null && s.isSimilar(it) && s.getAmount() < s.getMaxStackSize()) return true;
        return false;
    }

    /** Puts the load in its chest. Whatever doesn't fit, or a load whose chest has gone, goes back to the Station. */
    private void dropOff(Worker w) {
        Block chest = w.dest;
        w.dest = null;
        if (w.carried == null || chest == null || !(chest.getState(false) instanceof Container c)) return;
        Map<Integer, ItemStack> left = c.getInventory().addItem(w.carried);
        w.carried = left.isEmpty() ? null : left.values().iterator().next();
        chest.getWorld().playSound(chest.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.4f, 1.3f);
    }

    // ------------------------------------------------------------------ selling

    private UUID owner(Base b) {
        if (shared(b)) return sharedOwner.get();
        return ownerOf == null ? b.buyer : ownerOf.apply(b.region);
    }

    private static boolean plain(ItemStack it) { return it != null && !it.getType().isAir() && it.isSimilar(new ItemStack(it.getType())); }

    /** Does the Trader buy this? The Foundry has no Trader, so it goes by the catalogue. */
    private boolean sellable(ItemStack it) {
        return plain(it) && (trader != null ? trader.buys(it.getType()) : catalogPrice(it.getType()) > 0);
    }

    private static double catalogPrice(Material m) {
        for (SellCatalog.Item i : SellCatalog.ITEMS) if (i.id().equals(m.getKey().getKey())) return i.price();
        return 0;
    }

    /** Heads for a sell chest with something the Trader buys. It only sells with the owner online, so nothing moves otherwise. */
    private boolean startSell(Base b, Worker w) {
        UUID owner = owner(b);
        if (owner == null || Bukkit.getPlayer(owner) == null) return false;
        for (Block chest : b.sellChests) {
            if (!chest.getWorld().isChunkLoaded(chest.getX() >> 4, chest.getZ() >> 4) || !(chest.getState(false) instanceof Container c)) continue;
            boolean taken = false;
            for (Worker o : b.workers) if (chest.equals(o.dest)) taken = true;
            if (taken) continue;
            for (ItemStack it : c.getInventory().getContents()) {
                if (!sellable(it)) continue;
                w.dest = chest;
                w.state = State.COLLECT;
                Location l = chest.getLocation();
                fly(w, new double[]{l.getX() + 0.5, l.getY() + 1.4, l.getZ() + 0.5});
                return true;
            }
        }
        return false;
    }

    /** Takes one load of one sellable item from the chest. It may have gone by now, and then the allay just comes back empty. */
    private void collect(Base b, Worker w) {
        Block chest = w.dest;
        w.dest = null;
        if (chest == null || !(chest.getState(false) instanceof Container c)) return;
        Inventory inv = c.getInventory();
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) {
            if (!sellable(all[i])) continue;
            int n = Math.min(all[i].getAmount(), carry);
            ItemStack load = all[i].clone();
            load.setAmount(n);
            if (n >= all[i].getAmount()) inv.setItem(i, null);
            else { all[i].setAmount(all[i].getAmount() - n); inv.setItem(i, all[i]); }
            w.carried = load;
            w.sellFrom = chest;
            return;
        }
    }

    /** Pays the owner at the Station, once. If that can't happen, the load goes back where it came from. */
    private void sell(Base b, Worker w, Location at) {
        Block from = w.sellFrom;
        w.sellFrom = null;
        UUID ownerId = owner(b);
        Player owner = ownerId == null ? null : Bukkit.getPlayer(ownerId);
        ItemStack load = w.carried;
        double gross = owner == null ? 0 : trader != null ? trader.saleValue(ownerId, load.getType(), load.getAmount())
                : SellCatalog.payout(catalogPrice(load.getType()), 0, load.getAmount(), SellCatalog.DEFAULT_SOFT_CAP, SellCatalog.DEFAULT_OVER_CAP_RATE);
        double net = AllayRules.netSale(gross, sellFee);
        // the Foundry shows what it would have paid and pays nothing
        boolean paid = owner != null && net > 0 && (sandbox || plugin.economy().deposit(owner, net));
        if (!paid) {
            if (from != null && from.getState(false) instanceof Container) {
                w.dest = from;
                w.state = State.DELIVER;
                Location l = from.getLocation();
                fly(w, new double[]{l.getX() + 0.5, l.getY() + 1.4, l.getZ() + 0.5});
            } else unload(b, w, at);
            return;
        }
        if (trader != null) trader.recordSale(ownerId, load.getType(), load.getAmount());
        plugin.getLogger().info("allay sale: " + owner.getName() + " sold " + load.getAmount() + " " + load.getType().getKey().getKey() + " for " + net);
        w.carried = null;
        at.getWorld().playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        at.getWorld().spawnParticle(Particle.WAX_ON, at.clone().add(0.5, 1.4, 0.5), 12, 0.4, 0.3, 0.4, 0.02);
        owner.sendActionBar(Ui.mm("<#ffd27a>Your allay sold " + load.getAmount() + " for <gold>" + NetEconomy.money(net) + (sandbox ? " <gray>(not really: Foundry)" : "")));
        rest(w);
    }

    /** Takes up to a load from the drop, if it's still about where it was. The items leave the world here, so a crash can lose them but can't double them. */
    private void grab(Base b, Worker w) {
        UUID id = w.job;
        b.jobs.removeIf(j -> j.id().equals(id));
        if (id == null || !(Bukkit.getEntity(id) instanceof Item drop) || !drop.isValid() || drop.getWorld() != w.entity.getWorld()
                || drop.getLocation().distanceSquared(w.entity.getLocation()) > REACH_SQ) return;
        ItemStack stack = drop.getItemStack();
        int n = Math.min(stack.getAmount(), carry);
        ItemStack load = stack.clone();
        load.setAmount(n);
        if (n >= stack.getAmount()) drop.remove();
        else { stack.setAmount(stack.getAmount() - n); drop.setItemStack(stack); }
        w.carried = load;
        w.entity.getWorld().playSound(w.entity.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.4f);
    }

    private void unload(Base b, Worker w, Location at) {
        Inventory store = station.storageIn(b.region);
        Map<Integer, ItemStack> left = store == null ? Map.of(0, w.carried) : store.addItem(w.carried);
        if (left.isEmpty()) {
            w.carried = null;
            at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.4f, 1.6f);
            at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0.5, 1.2, 0.5), 6, 0.25, 0.2, 0.25, 0.01);
            rest(w);
        } else {
            w.carried = left.values().iterator().next();
            w.state = State.WAIT;
            w.rest = RETRY_TICKS;
        }
    }

    private void rest(Worker w) {
        w.state = State.PAUSE;
        w.rest = pauseTicks;
    }

    private void label(Worker w, int tier, String status) {
        String title = w.name == null ? NAME : "<#4fd1b5><bold>" + w.name + "</bold>";   // validName keeps tags out
        String text = title + " <gray>" + TIER_NAME[tier - 1] + (status == null ? "" : " " + status);
        if (text.equals(w.label)) return;
        w.label = text;
        if (sausageFlip && AllayRules.flipsUpsideDown(w.name)) {
            // the game flips it by its real name, so that's hidden and a floating tag shows the name the player gave it
            w.entity.customName(Component.text(FLIP_NAME));
            w.entity.setCustomNameVisible(false);
            if (w.tag == null || !w.tag.isValid()) {
                w.tag = Ui.hologram(w.entity.getLocation().add(0, TAG_HEIGHT, 0), text, 1f);
                w.tag.setTeleportDuration(1);
                w.tag.setViewRange(TAG_VIEW_RANGE);
            } else w.tag.text(Ui.mm(text));
        } else {
            removeTag(w);
            w.entity.customName(Ui.mm(text));
            w.entity.setCustomNameVisible(status != null);   // otherwise it shows only when you look right at it, so a base isn't covered in tags
        }
    }

    /**
     * Tier II and III wear a skin, since vanilla can't colour one allay and not another. The real allay stays, invisible, for
     * its hitbox and name, and a display of the pack's model follows it and flaps by swapping between two models. Tier I
     * keeps the plain allay. With no pack on this server, they all do, because the models would show purple.
     */
    private void dress(Worker w) {
        if (!customSkins || w.tier < 2) {
            removeSkin(w);
            if (w.entity.isInvisible()) w.entity.setInvisible(false);
            return;
        }
        if (!w.entity.isInvisible()) w.entity.setInvisible(true);
        Location at = w.entity.getLocation().add(0, SKIN_HEIGHT, 0);
        at.setPitch(0);
        if (w.skin == null || !w.skin.isValid() || w.skinTier != w.tier) {
            removeSkin(w);
            w.skinTier = w.tier;
            w.skin = at.getWorld().spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setTeleportDuration(1);
                d.setInterpolationDuration(0);
                d.setBrightness(new Display.Brightness(15, 15));   // the skin is the light here: it glows even in a dark base
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setItemStack(skinItem(w.tier, 0));
            });
            w.skinFlipped = false;
        } else w.skin.teleport(at);
        boolean flip = sausageFlip && AllayRules.flipsUpsideDown(w.name);
        if (flip != w.skinFlipped) {
            w.skinFlipped = flip;
            // the game's own upside-down rule only touches the real allay, so a skin turns itself over to match
            w.skin.setTransformation(new Transformation(new Vector3f(), flip ? new AxisAngle4f((float) Math.PI, 0, 0, 1) : new AxisAngle4f(),
                    new Vector3f(1, 1, 1), new AxisAngle4f()));
        }
        if ((now + w.index) % FLAP_TICKS == 0) {
            w.wingsUp = !w.wingsUp;
            w.skin.setItemStack(skinItem(w.tier, w.wingsUp ? 1 : 0));
        }
        // idle or not, the upgraded ones shimmer a little
        if ((now + w.index * 7) % SPARKLE_TICKS == 0) {
            if (w.tier == 2) at.getWorld().spawnParticle(Particle.WAX_ON, at, 1, 0.3, 0.3, 0.3, 0);
            else at.getWorld().spawnParticle(Particle.DUST, at, 2, 0.35, 0.35, 0.35, 0, new Particle.DustOptions(Color.fromRGB(AllayFlight.rainbow(now * RAINBOW_SPEED + w.index * 0.3)), 0.7f));
        }
    }

    /** One of a tier's two wing positions. Both tiers carry the enchantment shine. */
    private ItemStack skinItem(int tier, int frame) {
        return skinItems[tier - 2][frame];
    }

    private ItemStack[][] buildSkinItems() {
        String[] names = {"courier", "steward"}, frames = {"a", "b"};
        ItemStack[][] out = new ItemStack[names.length][frames.length];
        for (int t = 0; t < names.length; t++) for (int f = 0; f < frames.length; f++) {
            NamespacedKey model = new NamespacedKey("blackforge", "allay/" + names[t] + "_" + frames[f]);
            ItemStack it = new ItemStack(Material.PAPER);
            it.editMeta(m -> {
                m.setItemModel(model);
                m.setEnchantmentGlintOverride(true);
            });
            out[t][f] = it;
        }
        return out;
    }

    private static void removeSkin(Worker w) {
        if (w.skin != null) w.skin.remove();
        w.skin = null;
    }

    private void moveTag(Worker w) {
        if (w.tag == null) return;
        if (!w.tag.isValid()) { w.tag = null; w.label = null; return; }   // it went with its chunk: the next label makes a new one
        w.tag.teleport(w.entity.getLocation().add(0, TAG_HEIGHT, 0));
    }

    private static void removeTag(Worker w) {
        if (w.tag != null) w.tag.remove();
        w.tag = null;
    }

    // ------------------------------------------------------------------ finding work

    /** Looks for loose drops, a few bases a tick across the server, so many bases can't all search at once. */
    private void scan(Base b, Location at) {
        if (scansThisTick >= maxScans || !AllayJobs.due(b.lastScan, now, scanTicks)) return;
        scansThisTick++;
        b.lastScan = now;
        b.tight = !roomToCircle(at);
        b.jobs.clear();
        int leash = table.leash(b.tier);
        for (Entity e : at.getWorld().getNearbyEntities(at, leash, leash, leash, x -> x instanceof Item)) {
            Item drop = (Item) e;
            if (!inBase(drop.getLocation(), b.region) || !collectable(drop, at)) continue;
            Location l = drop.getLocation();
            b.jobs.add(new AllayJobs.Job(drop.getUniqueId(), l.getX(), l.getY(), l.getZ()));
        }
    }

    /** Finds the chests with an item in a frame on them. It's slow work that rarely changes, so a Courier's base does it now and then. */
    private void scanLinks(Base b, Location at) {
        if (!AllayRules.canSort(b.tier) || scansThisTick >= maxScans || !AllayJobs.due(b.lastLinkScan, now, linkScanTicks)) return;
        scansThisTick++;
        b.lastLinkScan = now;
        b.links.clear();
        b.sellChests.clear();
        int leash = table.leash(b.tier);
        for (Entity e : at.getWorld().getNearbyEntities(at, leash, leash, leash, x -> x instanceof ItemFrame)) {
            ItemFrame frame = (ItemFrame) e;
            Material m = frame.getItem().getType();
            if (m.isAir()) continue;
            Block chest = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
            if (chest.equals(at.getBlock()) || !inBase(chest.getLocation(), b.region)) continue;
            if (!(chest.getState(false) instanceof Chest) && !(chest.getState(false) instanceof Barrel)) continue;
            if (m == SELL_MARK) b.sellChests.add(chest);
            else b.links.add(new Link(m, chest));
        }
        b.thirsty.clear();
        if (robots != null && AllayRules.canRefuel(b.tier))
            for (Robots.Bot agent : robots.agentsNear(at, shared(b) ? Math.max(leash, SHARED_FUEL_REACH) : leash, sandbox && !shared(b) ? b.buyer : null))
                if (inBase(agent.loc, b.region) && robots.wantsFuel(agent)) b.thirsty.add(agent);
    }

    private boolean inBase(Location l, String region) {
        return sandbox ? zone.test(l) : region.equals(station.regionAt(l));   // the Foundry's regions are a chunk each, too small to work in
    }

    private boolean collectable(Item drop, Location at) {
        if (!drop.canMobPickup() || !drop.canPlayerPickup() || drop.getPickupDelay() >= NEVER_PICKED_UP) return false;   // displays, not loot
        UUID thrower = drop.getThrower();
        Player who = thrower == null ? null : Bukkit.getPlayer(thrower);
        boolean visitor = thrower != null && (who == null || !station.usable(who, at));
        return AllayRules.skipDrop(drop.getTicksLived(), minDropAge, drop.getPersistentDataContainer().has(deathDrop), visitor).isEmpty();
    }

    /** Remembers where a player just died, so {@link #onItemSpawn} can mark what falls. It marks the drop, not the stack, so the items stay normal. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Location l = e.getEntity().getLocation();
        deathWorld = l.getWorld(); deathX = l.getX(); deathY = l.getY(); deathZ = l.getZ();
        deathTick = Bukkit.getCurrentTick();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (Bukkit.getCurrentTick() != deathTick) return;
        Location l = e.getLocation();
        if (l.getWorld() == deathWorld && Math.abs(l.getX() - deathX) < 4 && Math.abs(l.getY() - deathY) < 4 && Math.abs(l.getZ() - deathZ) < 4)
            e.getEntity().getPersistentDataContainer().set(deathDrop, PersistentDataType.BYTE, (byte) 1);
    }

    private static double[] hover(Location station, Worker w, int flock, long tick) {
        if (w.tight) return new double[]{station.getX() + 0.5, station.getY() + 1.3, station.getZ() + 0.5};
        double[] h = AllayFlight.hover(w.index, flock, tick);
        return new double[]{station.getX() + 0.5 + h[0], station.getY() + 2.2 + h[1], station.getZ() + 0.5 + h[2]};
    }

    /** Can an allay fly through this block? Never into an unloaded chunk, which would load it, and never lava. */
    private static boolean free(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
        Block b = world.getBlockAt(x, y, z);
        return b.isPassable() && b.getType() != Material.LAVA;
    }

    /** Is there room for the flock's circle above the Station? Checked now and then, since building nearby changes it. */
    private static boolean roomToCircle(Location at) {
        World world = at.getWorld();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 1; dy <= 3; dy++)
            if (!free(world, at.getBlockX() + dx, at.getBlockY() + dy, at.getBlockZ() + dz)) return false;
        return true;
    }

    /** No Station, or it's unloaded: the flock goes away until it's back, dropping what it holds. The tier stays. */
    private void sleep(Base b) {
        b.jobs.clear();
        b.thirsty.clear();
        for (Worker w : b.workers) {
            if (w.entity != null && w.entity.isValid()) {
                if (w.carried != null) { w.entity.getWorld().dropItem(w.entity.getLocation(), w.carried); w.carried = null; w.sellFrom = null; }
                w.entity.remove();
            }
            removeTag(w);
            removeSkin(w);
            // an entity that's already gone went with its chunk, so a load stays held until it's back
            w.entity = null;
            w.job = null;
            w.dest = null;
            w.agent = null;
            w.state = State.IDLE;
        }
    }
}
