package net.blackforge.core.survival;

import io.papermc.paper.world.WeatheringCopperState;
import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * Robots: little copper golems that work your island while they have fuel. Four jobs (Farmer,
 * Miner, Grinder, Fisherman, switchable any time) and five tiers. Fuel Cells power them; every
 * minute of fuel burned also builds Charge, and a robot needs enough Charge (plus Essence and
 * money) to reach its next tier, so better fuel = faster progression.
 */
public final class Robots implements Listener {
    enum Job {
        FARMER("Farmer", "<#7ed957>", Material.GOLDEN_HOE, "Harvests and replants grown crops around it"),
        MINER("Miner", "<#c6a15b>", Material.IRON_PICKAXE, "Mines the block in front of it (generators, OneBlock)"),
        GRINDER("Grinder", "<#ff5e5e>", Material.IRON_SWORD, "Kills mobs around it and keeps the loot + XP"),
        FISHERMAN("Fisherman", "<#5ec8ff>", Material.FISHING_ROD, "Fishes from water next to it");
        final String label, color, desc; final Material icon;
        Job(String label, String color, Material icon, String desc) { this.label = label; this.color = color; this.icon = icon; this.desc = desc; }
    }

    static final int MAX_TIER = 5;
    static final int[] RADIUS = AgentStats.RADIUS;
    static final int[] STORAGE = {9, 18, 27, 36, 36};
    // to reach tier t+2 from tier t+1
    static final int[] UP_MONEY = {10_000, 35_000, 100_000, 300_000};
    static final int[] UP_ESSENCE = {64, 256, 768, 2_048};
    static final int[] UP_CHARGE_HOURS = {2, 8, 24, 72};
    // fuel cells I..V
    static final int[] FUEL_MINUTES = {30, 120, 360, 720, 1440};
    static final double[] FUEL_CHARGE = RobotRules.FUEL_CHARGE;
    static final int[] FUEL_MONEY = {500, 2_000, 6_000, 12_000, 25_000};
    static final int[] FUEL_ESSENCE = {8, 32, 96, 192, 400};
    static final Material[] FUEL_ICON = {Material.COAL, Material.BLAZE_POWDER, Material.GLOWSTONE_DUST, Material.PRISMARINE_CRYSTALS, Material.NETHER_STAR};
    static final String[] FUEL_COLOR = {"<gray>", "<gold>", "<yellow>", "<aqua>", "<light_purple>"};
    static final long MAX_TANK = 48L * 3600_000;
    public static final int ROBOT_PRICE = 7_500;
    static final long SANDBOX_START_FUEL = 3600_000L;

    private static final NamespacedKey ROBOT = new NamespacedKey(BlackForgeCore.NS, "robot_id");
    private static final NamespacedKey ITEM = new NamespacedKey(BlackForgeCore.NS, "robot_item");
    private static final NamespacedKey FUEL = new NamespacedKey(BlackForgeCore.NS, "fuel_tier");
    /** An Agent item's Academy record. Fresh items have none and get a new record when placed. */
    private static final NamespacedKey RECORD = new NamespacedKey(BlackForgeCore.NS, "agent_record");

    /** One robot's state; the entity is found by robot id each tick (entities can reload with new UUIDs). */
    final class Bot {
        final String id; UUID owner; Job job; int tier; long fuel, charge; int fuelTier = 1; int xp;
        /** Fuel-weighted charge multiplier of what's in the tank; fuelTier is now only the icon/colour of the best cell added. */
        double chargeRate;
        /** Sub-millisecond charge left over between ticks (blended and Fisherman rates aren't whole numbers). Not saved. */
        double chargeCarry;
        /** Seconds of work done toward the next cycle, so a fractional speed bonus isn't lost. Not saved. */
        double workProgress;
        /** The fraction of a Mastery point left over after a cycle. Not saved. */
        double pointsCarry;
        /** Seconds toward the next lifetime minute worked. Not saved. */
        int secondsWorked;
        Location loc; Inventory storage; UUID entity; int timer; String status = "";
        /** The name the player gave it, or null. */
        String name;
        /** The last Grinder log line, so an unchanged cycle isn't logged again. Not saved. */
        String lastGrindLog;
        /** What the Grinder's name tag says after its last cycle. Not saved. */
        String grindStatus = "";
        /** What's already been warned about, so one fault can't flood the log every second. Not saved. */
        final Set<String> warned = new HashSet<>();
        Bot(String id) { this.id = id; }
        /** "Agent Bob", or "Miner Agent" until it's named. */
        /** Academy data, including the UID that identifies this Agent. Foundry Agents have one too, but aren't saved. */
        AgentRecord record = withUid(new AgentRecord());
        String title() { return name != null ? "Agent " + name : job.label + " Agent"; }
        int interval() { return stats().intervalSeconds(); }
        /** Whenever the Academy is off, this is plain Tier stats whatever the record holds. */
        AgentStats stats() {
            String key = job.name().toLowerCase(Locale.ROOT);
            return academyOn ? AgentStats.of(key, tier, record, academy, tuning) : AgentStats.of(key, tier);
        }
        /** Old saves and robot items have no rate: fall back to the burning cell tier's rate. */
        void fixRate() { if (!(chargeRate > 0)) chargeRate = RobotRules.rateForTier(Math.max(1, Math.min(5, fuelTier))); }
    }

    private final BlackForgeCore plugin;
    private final File file;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private final Map<UUID, String> killedBy = new HashMap<>();   // mob -> robot that hit it
    private final Map<String, ConfigurationSection> pending = new HashMap<>();   // robots whose world isn't loaded yet
    private BiPredicate<Player, Location> canUse = (p, l) -> true;
    private BiPredicate<UUID, Location> workArea = (owner, l) -> true;   // blocks/animals a robot may touch
    private String crateOnly;   // non-null: robots can't be bought here, only won from these crates
    private Ranks ranks;
    private Economy eco;
    private BaseStation stations;
    private final Map<UUID, Naming> naming = new java.util.concurrent.ConcurrentHashMap<>();   // who we're waiting on for a name
    private record Naming(String botId, long until) {}
    private final Random rnd = new Random();

    /** The Arena's Foundry: upgrades are free, nothing is saved, there's no /agents shop, and only the owner can open an agent. */
    private final boolean sandbox;
    private int sandboxPerPlayer, sandboxTotal;
    /** Logs what each Grinder does when that changes. On by default in the Foundry only. */
    private final boolean grinderLog;
    /** Whether Agents earn Mastery here. See academyOn(). */
    private final boolean academyOn;
    private final AcademyRules academy;
    private final AgentStats.Tuning tuning;
    private Academy academyMenu;

    Robots(BlackForgeCore plugin) { this(plugin, false); }

    Robots(BlackForgeCore plugin, boolean sandbox) {
        this.plugin = plugin;
        this.sandbox = sandbox;
        this.grinderLog = plugin.getConfig().getBoolean("robots.grinder-debug-log", sandbox);
        this.academyOn = sandbox || plugin.getConfig().getStringList("academy.live-modes").contains(plugin.getConfig().getString("mode", "backend"));
        this.academy = new AcademyRules(plugin.getConfig().getDouble("academy.level-base", 20), plugin.getConfig().getDouble("academy.level-exponent", 2.0),
                plugin.getConfig().getInt("academy.max-level", 30));
        this.tuning = AgentStats.Tuning.read((key, fallback) -> plugin.getConfig().getDouble("academy." + key, fallback));
        if (academyOn) this.academyMenu = new Academy(plugin, this);
        this.file = new File(plugin.getDataFolder(), "robots.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
        registerCommand();
        if (sandbox) return;
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 1200L, 1200L);
    }

    void setSandboxLimits(int perPlayer, int total) { this.sandboxPerPlayer = perPlayer; this.sandboxTotal = total; }

    /** Removes this player's agents and whatever they held. */
    void removeOwnedBy(UUID owner) {
        for (Iterator<Bot> it = bots.values().iterator(); it.hasNext(); ) {
            Bot b = it.next();
            if (!b.owner.equals(owner)) continue;
            CopperGolem g = entity(b);
            if (g != null) g.remove();
            it.remove();
        }
        naming.remove(owner);
    }

    void setAccess(BiPredicate<Player, Location> rule) { this.canUse = rule; }
    void setCrateOnly(String crates) { this.crateOnly = crates; }
    void setWorkArea(BiPredicate<UUID, Location> rule) { this.workArea = rule; }
    void setRanks(Ranks r) { this.ranks = r; }
    void setEconomy(Economy e) { this.eco = e; }
    void setStations(BaseStation s) { this.stations = s; }

    int limit(Player p) {
        if (sandbox) return sandboxPerPlayer;
        Ranks.Rank r = ranks == null ? null : ranks.of(p);
        if (r == null) return 2;
        return Math.min(RobotRules.MAX_AGENTS, switch (r.id()) { case "copper" -> 3; case "iron" -> 4; default -> 5; });
    }

    int owned(UUID u) { int n = 0; for (Bot b : bots.values()) if (b.owner.equals(u)) n++; return n; }

    // ------------------------------------------------------------------ items

    static ItemStack fuelCell(int tier, int n) {
        int t = Math.max(1, Math.min(5, tier));
        ItemStack it = Ui.item(FUEL_ICON[t - 1], FUEL_COLOR[t - 1] + "<bold>Fuel Cell " + Spawners.roman(t),
                "<gray>Powers an agent for <white>" + time(FUEL_MINUTES[t - 1] * 60_000L),
                "<gray>Charge rate: <white>x" + FUEL_CHARGE[t - 1] + " <dark_gray>(levels agents up faster)",
                "", "<yellow>Open an agent and click its fuel slot");
        it.editMeta(m -> { m.getPersistentDataContainer().set(FUEL, PersistentDataType.INTEGER, t); m.setEnchantmentGlintOverride(true); });
        it.setAmount(Math.max(1, Math.min(64, n)));
        return it;
    }

    static int fuelTier(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        return it.getItemMeta().getPersistentDataContainer().getOrDefault(FUEL, PersistentDataType.INTEGER, 0);
    }

    /** A robot in item form. Carries its job, tier, charge and fuel so picking it up loses nothing. */
    static ItemStack robotItem(Job job, int tier, long charge, long fuel, int fuelTier) { return robotItem(job, tier, charge, fuel, fuelTier, null); }

    static ItemStack robotItem(Job job, int tier, long charge, long fuel, int fuelTier, String name) {
        ItemStack it = Ui.item(Material.COPPER_GOLEM_SPAWN_EGG, job.color + "<bold>⚙ " + (name != null ? "Agent " + name : job.label + " Agent") + " " + Spawners.roman(tier),
                "<gray>" + job.desc, "<gray>Tier: <white>" + Spawners.roman(tier) + " <dark_gray>| <gray>Fuel: <white>" + time(fuel),
                "<gray>Charge: <white>" + time(charge), "", "<yellow>Right-click a block on your island or claim to place");
        it.editMeta(m -> {
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            pdc.set(ITEM, PersistentDataType.STRING, job.name() + ";" + tier + ";" + charge + ";" + fuel + ";" + fuelTier + (name != null ? ";" + name : ""));
            m.setEnchantmentGlintOverride(true);
        });
        return it;
    }

    /** An Agent picked up with its record. The UID in the record stops it stacking with other Agents. */
    static ItemStack robotItem(Job job, int tier, long charge, long fuel, int fuelTier, String name, AgentRecord record) {
        ItemStack it = robotItem(job, tier, charge, fuel, fuelTier, name);
        it.editMeta(m -> m.getPersistentDataContainer().set(RECORD, PersistentDataType.STRING, record.encode()));
        return it;
    }

    /** Adds the Service Record to the item's lore. Untrained Agents have none, so their item looks the same as before. */
    private ItemStack withServiceRecord(ItemStack it, AgentRecord record) {
        List<String> lines = academy.serviceRecord(record);
        if (lines.isEmpty()) return it;
        it.editMeta(m -> {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(Optional.ofNullable(m.lore()).orElse(List.of()));
            for (String line : lines) lore.add(Ui.mm(line));
            m.lore(lore);
        });
        return it;
    }

    /** Old saves, fresh items and unreadable records have no UID yet, so this is where an Agent gets its identity. */
    private static AgentRecord withUid(AgentRecord record) {
        if (record.uid.isBlank()) record.uid = UUID.randomUUID().toString();
        return record;
    }

    /** Includes Agents whose world isn't loaded yet: placing a copy now would clash when that world loads. */
    private boolean uidInUse(String uid) {
        for (Bot b : bots.values()) if (uid.equals(b.record.uid)) return true;
        for (ConfigurationSection s : pending.values()) if (uid.equals(AgentRecord.decode(s.getString("academy")).uid)) return true;
        return false;
    }

    /**
     * The robot item only carries a fuel tier, and a placed robot burns at that tier's rate. Store the highest tier whose
     * rate doesn't exceed the blended rate, so pick up + place never raises the rate (it can only round down).
     */
    static int itemFuelTier(double chargeRate) {
        int t = 1;
        for (int i = 2; i <= 5; i++) if (RobotRules.rateForTier(i) <= chargeRate + 1e-9) t = i;
        return t;
    }

    static boolean isRobotItem(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(ITEM); }

    static String time(long ms) {
        long m = ms / 60_000;
        if (m <= 0) return ms > 0 ? "<1m" : "0m";
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    private static void give(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
    }

    // ------------------------------------------------------------------ placing / picking up

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !isRobotItem(e.getItem())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Block at = e.getClickedBlock().getRelative(e.getBlockFace());
        if (!at.isPassable() || !at.getRelative(BlockFace.UP).isPassable()) { p.sendMessage(Ui.mm("<red>Not enough room there.")); return; }
        if (!canUse.test(p, at.getLocation())) { p.sendMessage(Ui.mm("<red>Agents can only go on your own island or claimed land.")); return; }
        if (sandbox && bots.size() >= sandboxTotal) { p.sendMessage(Ui.mm("<red>The Foundry is at its limit of " + sandboxTotal + " agents right now.")); return; }
        if (owned(p.getUniqueId()) >= limit(p)) { p.sendMessage(Ui.mm("<red>You have " + owned(p.getUniqueId()) + "/" + limit(p) + " agents placed. Ranks raise the limit.")); return; }
        PersistentDataContainer pdc = e.getItem().getItemMeta().getPersistentDataContainer();
        AgentRecord record = withUid(AgentRecord.decode(pdc.get(RECORD, PersistentDataType.STRING)));
        // e.g. a crash restored an Agent item whose Agent is still placed: a second copy would dupe a trained Agent
        if (uidInUse(record.uid)) {
            p.sendMessage(Ui.mm("<red>This Agent is already placed."));
            plugin.getLogger().warning("Refused duplicate Agent " + record.uid + " placed by " + p.getName());
            return;
        }
        String[] d = pdc.get(ITEM, PersistentDataType.STRING).split(";");
        Bot b = new Bot(UUID.randomUUID().toString());
        b.record = record;
        b.owner = p.getUniqueId();
        b.job = Job.valueOf(d[0]);
        b.tier = Integer.parseInt(d[1]);
        b.charge = Long.parseLong(d[2]);
        b.fuel = Long.parseLong(d[3]);
        b.fuelTier = Integer.parseInt(d[4]);
        if (d.length > 5 && RobotRules.validName(d[5])) b.name = d[5];   // older items have no name
        b.fixRate();   // the item has no rate: derive it from fuelTier (see itemFuelTier)
        if (sandbox && b.fuel <= 0) b.fuel = SANDBOX_START_FUEL;   // the Foundry's chest agents are empty: placed ones work straight away
        // face the way the player is looking, snapped to N/E/S/W, so a Miner mines what you clicked toward
        float yaw = Math.round(p.getYaw() / 90f) * 90f;
        b.loc = at.getLocation().add(0.5, 0, 0.5);
        b.loc.setYaw(yaw);
        b.storage = Bukkit.createInventory(null, STORAGE[b.tier - 1]);
        bots.put(b.id, b);
        spawn(b);
        e.getItem().setAmount(e.getItem().getAmount() - 1);
        p.playSound(b.loc, Sound.ENTITY_COPPER_GOLEM_SPAWN, 1f, 1f);
        p.sendMessage(Ui.mm("<green>Agent placed! Right-click it to add fuel, switch its job or collect its loot."));
        save();
    }

    @EventHandler
    public void onDispense(BlockDispenseEvent e) { if (isRobotItem(e.getItem()) || fuelTier(e.getItem()) > 0) e.setCancelled(true); }

    private CopperGolem spawn(Bot b) {
        CopperGolem g = b.loc.getWorld().spawn(b.loc, CopperGolem.class, x -> {
            x.setAI(false);
            x.setInvulnerable(true);
            x.setSilent(true);
            x.setPersistent(!sandbox);
            x.setRemoveWhenFarAway(false);
            x.setCollidable(false);
            x.setOxidizing(CopperGolem.Oxidizing.waxed());
            x.getPersistentDataContainer().set(ROBOT, PersistentDataType.STRING, b.id);
            x.setCustomNameVisible(true);
        });
        b.entity = g.getUniqueId();
        style(b, g);
        return g;
    }

    /** Copper polishes up as the robot tiers up: oxidized I -> shiny IV, glowing V. */
    private void style(Bot b, CopperGolem g) {
        WeatheringCopperState[] look = {WeatheringCopperState.OXIDIZED, WeatheringCopperState.WEATHERED, WeatheringCopperState.EXPOSED,
                WeatheringCopperState.UNAFFECTED, WeatheringCopperState.UNAFFECTED};
        String st = b.fuel <= 0 ? "<red>No fuel" : b.status.isEmpty() ? "<white>" + time(b.fuel) : b.status;
        // each part on its own: a failure in one must not cost the agent its name tag or stop the others
        stylePart(b, "name", () -> g.setCustomNameVisible(true));
        stylePart(b, "name", () -> g.customName(Ui.mm(b.job.color + "⚙ " + (b.name != null ? "Agent " + b.name + " <dark_gray>• " + b.job.color + b.job.label : b.title()) + " " + Spawners.roman(b.tier) + rankTag(b) + " <dark_gray>• " + st)));
        stylePart(b, "hand", () -> g.getEquipment().setItemInMainHand(new ItemStack(b.job.icon)));
        stylePart(b, "copper", () -> g.setWeatheringState(look[b.tier - 1]));
        stylePart(b, "glow", () -> g.setGlowing(b.tier >= 5));
    }

    /** Empty unless the Academy is on, so the name tag stays as it is everywhere else. */
    private String rankTag(Bot b) {
        if (!academyOn()) return "";
        int level = jobLevel(b);
        return " <dark_gray>• " + Academy.BRASS + AcademyRules.rankFor(level) + " " + level;
    }

    private int jobLevel(Bot b) {
        AgentRecord.JobTrack t = b.record.jobs.get(jobKey(b));
        return academy.levelFor(t == null ? 0 : t.points);
    }

    /** Effects for a level change. The Agent's chunk may be unloaded, and the owner offline; the level still counts either way. */
    private void celebrate(Bot b, int before, int after) {
        if (after <= before) return;
        boolean graduated = AcademyRules.graduated(before, after);
        World w = b.loc.getWorld();
        if (w != null && w.isChunkLoaded(b.loc.getBlockX() >> 4, b.loc.getBlockZ() >> 4)) {
            Location at = b.loc.clone().add(0, 1, 0);
            w.spawnParticle(Particle.WAX_ON, at, 20, 0.35, 0.45, 0.35, 0.02);
            w.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.25f, 1.7f);
            if (graduated) {
                Particle.DustOptions brass = new Particle.DustOptions(org.bukkit.Color.fromRGB(0xC9A227), 1.2f);
                for (int i = 0; i < 24; i++) {
                    double angle = 2 * Math.PI * i / 24;
                    w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(angle), 0.1, Math.sin(angle)), 1, 0, 0, 0, 0, brass);
                }
            }
            CopperGolem g = entity(b);
            if (g != null) style(b, g);
        }
        Player owner = Bukkit.getPlayer(b.owner);
        if (owner == null) return;
        String rank = AcademyRules.rankFor(after);
        if (graduated) {
            owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1f);
            owner.sendMessage(Ui.mm(Academy.BRASS + "<bold>" + b.title() + " graduated!</bold> <gray>It's now a <white>" + rank + "<gray>, level <white>" + after + "<gray>."));
        } else owner.sendActionBar(Ui.mm(Academy.BRASS + b.title() + " <gray>reached level <white>" + after));
    }

    private void stylePart(Bot b, String part, Runnable apply) {
        try { apply.run(); } catch (Exception ex) { warnOnce(b, "style " + part, ex); }
    }

    private void warnOnce(Bot b, String where, Exception ex) {
        if (b.warned.add(where + ":" + ex)) plugin.getLogger().warning("agent " + b.id + " " + b.job + " " + where + ": " + ex);
    }

    /** One line when a Grinder's result changes; the fuel left doesn't count as a change. */
    private void logGrinder(Bot b, CopperGolem g, String what) {
        if (!grinderLog || b.job != Job.GRINDER) return;
        String line = "T" + b.tier + " r=" + RADIUS[b.tier - 1] + " at " + b.loc.getBlockX() + "," + b.loc.getBlockY() + "," + b.loc.getBlockZ() + " in " + b.loc.getWorld().getName()
                + ": " + what + " | tagShown=" + g.isCustomNameVisible() + " hand=" + g.getEquipment().getItemInMainHand().getType();
        if (line.equals(b.lastGrindLog)) return;
        b.lastGrindLog = line;
        plugin.getLogger().info("Grinder " + b.id.substring(0, 8) + " fuel=" + time(b.fuel) + " " + line);
    }

    private CopperGolem entity(Bot b) {
        if (b.entity != null && Bukkit.getEntity(b.entity) instanceof CopperGolem g && g.isValid()) return g;
        return null;
    }

    private Bot botOf(Entity e) {
        String id = e.getPersistentDataContainer().get(ROBOT, PersistentDataType.STRING);
        return id == null ? null : bots.get(id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClickRobot(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof CopperGolem g) || !g.getPersistentDataContainer().has(ROBOT)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Bot b = botOf(g);
        if (b == null) { if (!pending.containsKey(g.getPersistentDataContainer().get(ROBOT, PersistentDataType.STRING))) g.remove(); return; }   // orphan
        b.entity = g.getUniqueId();
        Player p = e.getPlayer();
        if (!b.owner.equals(p.getUniqueId()) && net.blackforge.core.Titles.isOwner(p)
                && (OwnerView.viewing(p, g.getLocation()) || !canUse.test(p, g.getLocation()))) {
            open(p, b, true);   // the OWNER title looks but doesn't touch, even with admin permission
            return;
        }
        if (!b.owner.equals(p.getUniqueId()) && (sandbox || !canUse.test(p, g.getLocation())) && !p.hasPermission("blackforge.admin")) {
            p.sendMessage(Ui.mm("<red>That's " + Bukkit.getOfflinePlayer(b.owner).getName() + "'s agent.")); return;
        }
        open(p, b);
    }

    /** When a chunk's entities load, adopt our robots and remove orphans/duplicates (e.g. after a crash). */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        for (Entity x : e.getEntities()) {
            String id = x.getPersistentDataContainer().get(ROBOT, PersistentDataType.STRING);
            if (id == null || pending.containsKey(id)) continue;
            Bot b = bots.get(id);
            CopperGolem current = b == null ? null : entity(b);
            if (b == null || current != null && !current.getUniqueId().equals(x.getUniqueId())) { x.remove(); continue; }
            b.entity = x.getUniqueId();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurtRobot(EntityDamageEvent e) { if (e.getEntity().getPersistentDataContainer().has(ROBOT)) e.setCancelled(true); }

    // ------------------------------------------------------------------ work loop

    private void tick() {
        for (Bot b : new ArrayList<>(bots.values())) {
            try { tickBot(b); } catch (Exception ex) { warnOnce(b, "tick", ex); }   // one bad agent must not stop the rest
        }
    }

    private void tickBot(Bot b) {
        World w = b.loc.getWorld();
        if (w == null || !w.isChunkLoaded(b.loc.getBlockX() >> 4, b.loc.getBlockZ() >> 4)) return;
        Chunk ch = b.loc.getChunk();
        if (!ch.isEntitiesLoaded()) return;
        CopperGolem g = entity(b);
        if (g == null) {
            for (Entity x : w.getNearbyEntities(b.loc, 1.5, 1.5, 1.5))
                if (b.id.equals(x.getPersistentDataContainer().get(ROBOT, PersistentDataType.STRING)) && x instanceof CopperGolem cg) { g = cg; b.entity = cg.getUniqueId(); break; }
            if (g == null) { plugin.getLogger().info("Robot " + b.id + " had no entity (was " + b.entity + ", lookup=" + Bukkit.getEntity(b.entity == null ? new UUID(0, 0) : b.entity) + ") - respawning it"); g = spawn(b); }
        }
        if (b.fuel <= 0) { logGrinder(b, g, "idle: no fuel"); if (++b.timer % 5 == 0) style(b, g); b.workProgress = b.timer; return; }
        boolean full = b.storage.firstEmpty() < 0 && (stations == null || !stations.hasRoom(b.loc));
        boolean dry = !full && b.job == Job.FISHERMAN && !nearWater(b);
        b.status = full ? "<red>Storage full" : dry ? "<red>Needs water" : b.job == Job.GRINDER ? b.grindStatus : "";
        if (full) logGrinder(b, g, "idle: storage full");
        if (!full && !dry) {
            // Fishermen burn fuel and build Charge at a quarter of the normal rate
            AgentStats stats = b.stats();
            long burn = Math.min(b.fuel, b.job == Job.FISHERMAN ? (long) (1000 * RobotRules.FISHER_BURN) : 1000);
            b.fuel -= stats.fuelSpent(burn);
            b.chargeCarry += burn * b.chargeRate * stats.chargeMult();   // from the full burn, so saving fuel doesn't cost Charge
            long whole = (long) b.chargeCarry;
            b.charge += whole;
            b.chargeCarry -= whole;
            if (academyOn() && ++b.secondsWorked >= 60) { b.secondsWorked = 0; b.record.minutesWorked++; }
            b.timer++;   // only for the name tag refresh now
            b.workProgress += stats.speedMult();
            if (b.workProgress >= stats.intervalSeconds()) {
                b.timer = 0;
                b.workProgress %= stats.intervalSeconds();   // keeps a fraction, but one cycle at most after a job switch or idle time
                try { work(b, g, stats); } catch (Exception ex) { plugin.getLogger().warning("robot " + b.id + " " + b.job + ": " + ex); }
            }
        }
        else if (dry) {   // no burn, no fishing progress; keep refreshing the name tag
            if (++b.timer >= 5) b.timer = 0;
            b.workProgress = b.timer;
        }
        if (b.timer % 5 == 0) style(b, g);
    }

    private void work(Bot b, CopperGolem g, AgentStats stats) {
        onWorked(b, switch (b.job) {
            case MINER -> mine(b, g, stats);
            case FARMER -> farm(b, g, stats);
            case GRINDER -> grind(b, g, stats);
            case FISHERMAN -> fish(b, g, stats);
        });
    }

    /** The one switch for the Academy: on in the Foundry, and in any server mode listed in academy.live-modes. */
    boolean academyOn() { return academyOn; }

    /** Called after every cycle with how much real work was done. A job's Mastery only grows here and through the staff command. */
    private void onWorked(Bot b, int done) {
        if (done <= 0 || !academyOn()) return;
        String job = jobKey(b);
        int perWork = plugin.getConfig().getInt("academy.points-per-work." + job, b.job == Job.GRINDER ? 2 : b.job == Job.FISHERMAN ? 12 : 1);
        Player owner = Bukkit.getPlayer(b.owner);
        double radius = plugin.getConfig().getDouble("academy.coach-radius", 16);
        boolean coached = owner != null && owner.getWorld() == b.loc.getWorld() && owner.getLocation().distanceSquared(b.loc) <= radius * radius;
        b.pointsCarry += AcademyRules.workPoints(done, perWork, coached ? plugin.getConfig().getDouble("academy.coach-mult", 1.25) : 1);
        long whole = (long) b.pointsCarry;
        b.pointsCarry -= whole;
        b.record.workDone += done;
        int before = jobLevel(b);
        academy.earn(b.record.jobs.computeIfAbsent(job, k -> new AgentRecord.JobTrack()), whole, academyToday(),
                academySoftCap(), plugin.getConfig().getDouble("academy.over-cap-rate", 0.25));
        celebrate(b, before, jobLevel(b));
    }

    static String jobKey(Bot b) { return b.job.name().toLowerCase(Locale.ROOT); }

    static String academyToday() { return java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(); }

    AcademyRules academyRules() { return academy; }

    long academySoftCap() { return plugin.getConfig().getLong("academy.daily-soft-cap", 1500); }

    Bot agent(String id) { return bots.get(id); }

    AgentStats.Tuning tuning() { return tuning; }

    boolean isSandbox() { return sandbox; }

    /** The Foundry's fast-level button. It refuses outside the sandbox, whatever calls it. */
    void addLevels(Bot b, int levels) {
        if (!sandbox) return;
        AgentRecord.JobTrack track = b.record.jobs.computeIfAbsent(jobKey(b), k -> new AgentRecord.JobTrack());
        int before = academy.levelFor(track.points);
        track.points = Math.max(track.points, academy.pointsFor(before + levels));
        celebrate(b, before, academy.levelFor(track.points));
    }

    /** The Forge's way in: a list of this player's Agents. Does nothing if the Academy is off. */
    void openAcademyList(Player p, Runnable back, java.util.function.BooleanSupplier stillAllowed) {
        if (academyOn()) academyMenu.openList(p, back, stillAllowed);
    }

    /** Staff: /agents academy points <player> <amount> adds to each placed Agent's current job, skipping the daily limit. 0 just reads them. */
    private void academyPoints(CommandSender s, String[] a) {
        if (!academyOn()) { s.sendMessage(Ui.mm("<red>The Academy is off on this server.")); return; }
        Player t = a.length == 4 ? Bukkit.getPlayerExact(a[2]) : null;
        long amount;
        try { amount = a.length == 4 ? Long.parseLong(a[3]) : 0; } catch (NumberFormatException ex) { t = null; amount = 0; }
        if (t == null) { s.sendMessage(Ui.mm("<red>/agents academy points <online player> <whole number>")); return; }
        List<Bot> agents = agentsOf(t.getUniqueId());
        if (agents.isEmpty()) { s.sendMessage(Ui.mm("<gray>" + t.getName() + " has no placed Agents.")); return; }
        long cap = academy.pointsFor(Integer.MAX_VALUE);
        for (Bot b : agents) {
            AgentRecord.JobTrack track = b.record.jobs.computeIfAbsent(jobKey(b), k -> new AgentRecord.JobTrack());
            int before = academy.levelFor(track.points);
            track.points = Math.clamp(track.points + amount, 0, cap);
            celebrate(b, before, academy.levelFor(track.points));
            s.sendMessage(Ui.mm("<gray>" + b.title() + " <dark_gray>(" + jobKey(b) + ")<gray>: <white>" + track.points + " <gray>points, level <white>"
                    + academy.levelFor(track.points) + "<gray>, today <white>" + track.todayPoints + " <dark_gray>raw"));
        }
        save();
    }

    private void store(Bot b, Collection<ItemStack> items) {
        double doubling = b.stats().doubleDropChance();
        for (ItemStack it : items) if (it != null && !it.getType().isAir())
        {
            ItemStack copy = isPlainDrop(it) && doubling > 0 && rnd.nextDouble() < doubling ? it.clone() : null;
            deliver(b, it);
            if (copy != null) deliver(b, copy);
        }
    }

    private void deliver(Bot b, ItemStack it) {
        ItemStack left = stations == null ? it : stations.deliver(b.loc, it);   // the Base Station first, if there's one in range
        if (left != null) b.storage.addItem(left).values().forEach(l -> b.loc.getWorld().dropItemNaturally(b.loc, l));
    }

    /** Stores the drops, and a copy of the plain ones when a capstone's chance came up. */
    private void storeBonus(Bot b, Collection<ItemStack> items, boolean doubled) {
        List<ItemStack> all = new ArrayList<>(items);
        if (doubled) for (ItemStack it : items) if (it != null && !it.getType().isAir() && isPlainDrop(it)) all.add(it.clone());
        store(b, all);
    }

    /** Bonuses only copy these, so Mob Essence and named or custom items are never copied. */
    private static boolean isPlainDrop(ItemStack it) { return !it.hasItemMeta(); }

    private static final Set<Material> NO_MINE = EnumSet.of(Material.BEDROCK, Material.SPAWNER, Material.END_PORTAL_FRAME, Material.END_PORTAL,
            Material.NETHER_PORTAL, Material.BARRIER, Material.COMMAND_BLOCK, Material.REINFORCED_DEEPSLATE);

    /** Returns the blocks mined. */
    private int mine(Bot b, CopperGolem g, AgentStats stats) {
        Block target = b.loc.getBlock().getRelative(facing(b.loc));
        Material[] picks = {Material.STONE_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE, Material.NETHERITE_PICKAXE};
        ItemStack tool = new ItemStack(picks[b.tier - 1]);
        if (stats.fortune() > 0) tool.addUnsafeEnchantment(Enchantment.FORTUNE, stats.fortune());
        boolean motherlode = rnd.nextDouble() < stats.cycleDoubleChance();   // the whole cycle's drops, once per cycle
        if (!mineBlock(b, target, tool, motherlode)) return 0;
        int mined = 1;
        // the blocks above are extras: each passes the same checks, and one being refused doesn't undo the rest
        for (int up = 1; up <= stats.mineHeight(); up++) if (mineBlock(b, target.getRelative(0, up, 0), tool, motherlode)) mined++;
        g.swingMainHand();
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_STONE_BREAK, 0.4f, 1f);
        return mined;
    }

    /** Mines one block if every safety check allows it. Containers, claim edges and protected blocks are left alone. */
    private boolean mineBlock(Bot b, Block target, ItemStack tool, boolean doubled) {
        Material t = target.getType();
        if (t.isAir() || target.isLiquid() || NO_MINE.contains(t) || t.getHardness() < 0 || target.getState() instanceof Container) return false;
        if (!workArea.test(b.owner, target.getLocation())) return false;
        Player owner = Bukkit.getPlayer(b.owner);
        if (owner != null) {
            // Let other plugins (OneBlock, island protection) see the break as the owner's
            BlockBreakEvent ev = new BlockBreakEvent(target, owner);
            ev.setDropItems(false);
            Collection<ItemStack> drops = target.getDrops(tool);
            Material before = target.getType();
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled()) return false;
            storeBonus(b, drops, doubled);
            if (target.getType() == before) target.setType(Material.AIR);
        } else {
            if (isMagicBlock(target)) return false;   // OneBlock needs its owner online to roll the next block
            storeBonus(b, target.getDrops(tool), doubled);
            target.setType(Material.AIR);
        }
        return true;
    }

    private boolean isMagicBlock(Block bl) {
        return bl.getWorld().getName().startsWith("oneblock") && Math.floorMod(bl.getX(), 400) == 0 && Math.floorMod(bl.getZ(), 400) == 0;
    }

    private static BlockFace facing(Location l) {
        float y = ((l.getYaw() % 360) + 360) % 360;
        return y < 45 || y >= 315 ? BlockFace.SOUTH : y < 135 ? BlockFace.WEST : y < 225 ? BlockFace.NORTH : BlockFace.EAST;
    }

    private static final Map<Material, Material> SEEDS = Map.of(Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO, Material.BEETROOTS, Material.BEETROOT_SEEDS, Material.NETHER_WART, Material.NETHER_WART,
            Material.COCOA, Material.COCOA_BEANS, Material.TORCHFLOWER_CROP, Material.TORCHFLOWER_SEEDS);

    /** Returns the crops harvested. */
    private int farm(Bot b, CopperGolem g, AgentStats stats) {
        int r = stats.radius(), limit = stats.harvests(), done = 0;
        Block c = b.loc.getBlock();
        for (int dy = -1; dy <= 2 && done < limit; dy++)
            for (int dx = -r; dx <= r && done < limit; dx++)
                for (int dz = -r; dz <= r && done < limit; dz++) {
                    Block bl = c.getRelative(dx, dy, dz);
                    Material t = bl.getType();
                    if (!workArea.test(b.owner, bl.getLocation())) continue;
                    if (bl.getBlockData() instanceof Ageable a && SEEDS.containsKey(t)) {
                        if (a.getAge() < a.getMaximumAge()) continue;
                        List<ItemStack> drops = new ArrayList<>(bl.getDrops());
                        Material seed = SEEDS.get(t);
                        for (ItemStack d : drops) if (d.getType() == seed) { d.setAmount(d.getAmount() - 1); break; }
                        storeBonus(b, drops, rnd.nextDouble() < stats.harvestDoubleChance());
                        a.setAge(0);
                        bl.setBlockData(a);
                        done++;
                    } else if (t == Material.MELON || t == Material.PUMPKIN) {
                        storeBonus(b, bl.getDrops(), rnd.nextDouble() < stats.harvestDoubleChance());
                        bl.setType(Material.AIR);
                        done++;
                    } else if ((t == Material.SUGAR_CANE || t == Material.CACTUS || t == Material.BAMBOO) && bl.getRelative(BlockFace.DOWN).getType() == t
                            && bl.getRelative(BlockFace.UP).getType() != t) {
                        storeBonus(b, bl.getDrops(), rnd.nextDouble() < stats.harvestDoubleChance());
                        bl.setType(Material.AIR);
                        done++;
                    }
                }
        int grown = growCrops(b, stats, r);
        if (done + grown > 0) g.swingMainHand();
        return done + grown;
    }

    /** The Botanist: moves random immature crops in the work area on by one stage. */
    private int growCrops(Bot b, AgentStats stats, int r) {
        if (stats.growCrops() <= 0) return 0;
        Block c = b.loc.getBlock();
        List<Block> young = new ArrayList<>();
        for (int dy = -1; dy <= 2; dy++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    Block bl = c.getRelative(dx, dy, dz);
                    if (SEEDS.containsKey(bl.getType()) && bl.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge() && workArea.test(b.owner, bl.getLocation())) young.add(bl);
                }
        Collections.shuffle(young, rnd);
        int grown = Math.min(stats.growCrops(), young.size());
        for (Block bl : young.subList(0, grown)) {
            Ageable a = (Ageable) bl.getBlockData();
            a.setAge(a.getAge() + 1);
            bl.setBlockData(a);
            bl.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, bl.getLocation().add(0.5, 0.5, 0.5), 4, 0.25, 0.25, 0.25);
        }
        return grown;
    }

    /** Returns the hits that landed. */
    private int grind(Bot b, CopperGolem g, AgentStats stats) {
        int r = stats.radius(), maxHits = stats.hits(), mobs = 0, hits = 0, noEffect = 0, kills = 0;
        Map<RobotRules.GrindSkip, Integer> skipped = new EnumMap<>(RobotRules.GrindSkip.class);
        Map<EntityType, Integer> count = new HashMap<>();
        Collection<Entity> near = b.loc.getWorld().getNearbyEntities(b.loc, r, 3, r);
        for (Entity e : near) count.merge(e.getType(), 1, Integer::sum);
        for (Entity e : near) {
            if (!(e instanceof Mob m) || e instanceof CopperGolem || e.getPersistentDataContainer().has(ROBOT)) continue;
            mobs++;
            if (hits >= maxHits) continue;
            boolean hostile = m instanceof Enemy;
            Optional<RobotRules.GrindSkip> skip = RobotRules.grindSkip(new RobotRules.GrindTarget(
                    m instanceof Tameable t && t.isTamed(), hostile, hostile || workArea.test(b.owner, m.getLocation()),
                    m instanceof AbstractVillager, m.customName() != null, m.getPersistentDataContainer().has(Spawners.FROM_SPAWNER),
                    m instanceof Animals, m instanceof Animals an && an.isAdult(), count.get(e.getType())));
            if (skip.isPresent()) { skipped.merge(skip.get(), 1, Integer::sum); continue; }
            double before = m.getHealth();
            killedBy.put(m.getUniqueId(), b.id);
            m.damage(stats.damage());
            if (!m.isDead() && m.getHealth() >= before) {
                noEffect++;   // something cancelled or absorbed the hit
                if (sandbox) m.setHealth(Math.max(0, before - stats.damage()));   // the Foundry's bench must always work
            }
            if (m.isDead()) kills++;
            else killedBy.remove(m.getUniqueId());
            hits++;
            m.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, m.getLocation().add(0, 1, 0), 6, 0.25, 0.3, 0.25, 0.05);
            m.getWorld().playSound(m.getLocation(), Sound.ENTITY_HUSK_HURT, 0.6f, 1f);
        }
        b.grindStatus = RobotRules.grindStatus(mobs, hits);
        logGrinder(b, g, RobotRules.grindSummary(mobs, skipped, hits, noEffect, kills));
        if (hits > 0) { g.swingMainHand(); b.loc.getWorld().playSound(b.loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.2f); }
        return hits - noEffect;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKill(EntityDeathEvent e) {
        String id = killedBy.remove(e.getEntity().getUniqueId());
        Bot b = id == null ? null : bots.get(id);
        if (b == null) return;
        List<ItemStack> drops = new ArrayList<>(e.getDrops());
        double copy = b.stats().copyDropChance();
        if (copy > 0 && rnd.nextDouble() < copy) {
            // the Butcher: one random normal drop, never Mob Essence or anything else with custom data
            List<ItemStack> plain = drops.stream().filter(d -> d != null && !d.getType().isAir() && isPlainDrop(d)).toList();
            if (!plain.isEmpty()) drops.add(plain.get(rnd.nextInt(plain.size())).clone());
        }
        store(b, drops);
        e.getDrops().clear();
        b.xp += e.getDroppedExp();
        e.setDroppedExp(0);
    }

    private static boolean nearWater(Bot b) {
        Block c = b.loc.getBlock();
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 0; dy++) for (int dz = -2; dz <= 2; dz++)
            if (c.getRelative(dx, dy, dz).getType() == Material.WATER) return true;
        return false;
    }

    /** Returns the catches: one, or none away from water. */
    private int fish(Bot b, CopperGolem g, AgentStats stats) {
        if (!nearWater(b)) { b.status = "<red>Needs water"; return 0; }
        double roll = rnd.nextDouble(), treasureChance = 0.02 * b.tier * stats.treasureMult();
        ItemStack catchIt;
        if (roll < treasureChance) {
            Material[] treasure = {Material.NAME_TAG, Material.SADDLE, Material.NAUTILUS_SHELL, Material.ENCHANTED_BOOK, Material.BOW};
            catchIt = new ItemStack(treasure[rnd.nextInt(treasure.length)]);
            if (catchIt.getType() == Material.ENCHANTED_BOOK)
                catchIt.editMeta(org.bukkit.inventory.meta.EnchantmentStorageMeta.class, m -> m.addStoredEnchant(
                        List.of(Enchantment.MENDING, Enchantment.UNBREAKING, Enchantment.LUCK_OF_THE_SEA, Enchantment.LURE, Enchantment.SHARPNESS).get(rnd.nextInt(5)), 1 + rnd.nextInt(3), true));
        } else if (roll < treasureChance + 0.05) {
            Material[] junk = {Material.STICK, Material.STRING, Material.LEATHER, Material.BOWL, Material.INK_SAC, Material.BONE};
            catchIt = new ItemStack(junk[rnd.nextInt(junk.length)]);
        } else {
            double f = rnd.nextDouble();
            catchIt = new ItemStack(f < 0.6 ? Material.COD : f < 0.85 ? Material.SALMON : f < 0.98 ? Material.PUFFERFISH : Material.TROPICAL_FISH);
        }
        store(b, List.of(catchIt));
        b.xp += 1 + rnd.nextInt(3);
        g.swingMainHand();
        b.loc.getWorld().playSound(b.loc, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.3f, 1.2f);
        return 1;
    }

    // ------------------------------------------------------------------ robot menu

    private static final class Menu implements InventoryHolder {
        final String id; final String kind; Inventory inv; boolean readOnly;
        Menu(String id, String kind) { this.id = id; this.kind = kind; }
        public Inventory getInventory() { return inv; }
    }

    // row 1: job selector; row 2: actions; rows 3-6: storage
    private static final int[] JOB_SLOTS = {1, 3, 5, 7};
    private static final int SLOT_COLLECT = 9, SLOT_XP = 10, SLOT_FUEL = 11, SLOT_INFO = 13, SLOT_ACADEMY = 14, SLOT_UPGRADE = 15, SLOT_RENAME = 16, SLOT_PICKUP = 17;
    private static final int STORAGE_START = 18;

    /** "▮▮▮▮▯▯▯": lit segments coloured red/yellow/green by position, unlit ones dark grey. */
    static String fuelMeter(long fuelMs) {
        int lit = RobotRules.litSegments(fuelMs);
        StringBuilder sb = new StringBuilder();
        for (int seg = 1; seg <= RobotRules.SEGMENT_MS.length; seg++) {
            if (seg > lit) { sb.append("<dark_gray>▯"); continue; }
            sb.append(switch (RobotRules.segmentColor(seg)) { case RED -> "<red>"; case YELLOW -> "<yellow>"; case GREEN -> "<green>"; }).append('▮');
        }
        return sb.toString();
    }

    void open(Player p, Bot b) { open(p, b, false); }

    private void open(Player p, Bot b, boolean readOnly) {
        Menu m = new Menu(b.id, "robot");
        m.readOnly = readOnly;
        m.inv = Bukkit.createInventory(m, 54, StationUi.title(StationUi.Screen.AGENT, "⚙ " + b.title() + " " + Spawners.roman(b.tier)));
        if (!StationUi.art()) {
            ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < STORAGE_START; i++) m.inv.setItem(i, pane);
        }
        int used = 0;
        for (ItemStack it : b.storage.getContents()) if (it != null) used++;
        m.inv.setItem(SLOT_COLLECT, Ui.item(Material.CHEST, "<green><bold>Collect All", "<gray>Storage: <white>" + used + "/" + b.storage.getSize() + " slots", "<gray>Click an item below to take just that stack."));
        m.inv.setItem(SLOT_XP, Ui.item(Material.EXPERIENCE_BOTTLE, "<green><bold>Collect XP", "<gray>Stored: <white>" + b.xp + " XP"));
        m.inv.setItem(SLOT_FUEL, Ui.item(b.fuel > 0 ? FUEL_ICON[b.fuelTier - 1] : Material.BUCKET, "<gold><bold>Fuel <dark_gray>— " + fuelMeter(b.fuel),
                "<white>" + time(b.fuel) + " <gray>left <dark_gray>(tank holds " + time(MAX_TANK) + ")",
                "<gray>Charge rate: <white>" + String.format(Locale.ROOT, "%.2f", b.chargeRate) + "x",
                "<yellow>[Shift-click to add fuel from inventory]", "<dark_gray>Click: add 1 Fuel Cell"));
        List<String> info = new ArrayList<>(List.of("<gray>Owner: <white>" + Bukkit.getOfflinePlayer(b.owner).getName(),
                "<gray>Job: " + b.job.color + b.job.label + " <dark_gray>- <gray>" + b.job.desc,
                "<gray>Works every <white>" + b.interval() + "s" + (b.job == Job.MINER ? "" : " <gray>in a <white>" + RADIUS[b.tier - 1] + "<gray>-block radius"),
                "<gray>Charge: <white>" + time(b.charge)));
        if (!b.status.isEmpty()) info.add(b.status);
        m.inv.setItem(SLOT_INFO, Ui.item(Material.COPPER_GOLEM_SPAWN_EGG, b.job.color + "<bold>" + b.title() + " " + Spawners.roman(b.tier), info.toArray(String[]::new)));
        if (b.tier >= MAX_TIER) m.inv.setItem(SLOT_UPGRADE, Ui.item(Material.NETHER_STAR, "<gold><bold>MAX TIER"));
        else if (sandbox) m.inv.setItem(SLOT_UPGRADE, Ui.item(Material.ANVIL, "<aqua><bold>Upgrade to Tier " + Spawners.roman(b.tier + 1), "<green>Free in the Foundry", "", "<yellow>Click to upgrade"));
        else {
            int t = b.tier - 1;
            long need = UP_CHARGE_HOURS[t] * 3600_000L;
            int pct = (int) Math.min(100, 100 * b.charge / need);
            String bar = "<green>" + "|".repeat(pct / 5) + "<dark_gray>" + "|".repeat(20 - pct / 5);
            m.inv.setItem(SLOT_UPGRADE, Ui.item(Material.ANVIL, "<aqua><bold>Upgrade to Tier " + Spawners.roman(b.tier + 1),
                    "<gray>Faster, bigger radius, more storage.", "",
                    "<gray>Charge: " + bar + " <white>" + pct + "%", "<dark_gray>(" + time(b.charge) + " / " + UP_CHARGE_HOURS[t] + "h - burn fuel to charge)",
                    "<#9d7bff>" + UP_ESSENCE[t] + " Mob Essence", "<green>" + NetEconomy.money(UP_MONEY[t]), "", "<yellow>Click to upgrade"));
        }
        m.inv.setItem(SLOT_RENAME, Ui.item(Material.NAME_TAG, "<aqua><bold>Rename", "<gray>Name: <white>" + (b.name != null ? b.name : "none"), "<gray>Type a name and it becomes <white>Agent Name<gray>.", "", "<yellow>Click to rename"));
        m.inv.setItem(SLOT_PICKUP, Ui.item(Material.HOPPER, "<red><bold>Pick Up Agent", "<gray>Gives you the agent item (keeps tier,", "<gray>charge and fuel) and its storage."));
        for (int i = 0; i < 4; i++) {
            Job j = Job.values()[i];
            ItemStack icon = Ui.item(j.icon, j.color + "<bold>" + j.label, "<gray>" + j.desc, "", j == b.job ? "<green>✔ Current job" : "<yellow>Click to switch");
            if (j == b.job) icon.editMeta(x -> x.setEnchantmentGlintOverride(true));
            m.inv.setItem(JOB_SLOTS[i], icon);
        }
        for (int i = 0; i < b.storage.getSize() && STORAGE_START + i < 54; i++) m.inv.setItem(STORAGE_START + i, b.storage.getItem(i));
        if (academyOn()) m.inv.setItem(SLOT_ACADEMY, Ui.item(Material.BOOK, Academy.BRASS + "<bold>The Academy", "<gray>Mastery, rank and lifetime work.", "", "<yellow>Click to view"));
        if (readOnly) {
            ItemStack locked = Ui.item(Material.BARRIER, "<red><bold>Read-only", "<gray>You can look, but not change anything.");
            m.inv.setItem(SLOT_COLLECT, Ui.item(Material.CHEST, "<green><bold>Storage", "<gray>" + used + "/" + b.storage.getSize() + " slots used"));
            m.inv.setItem(SLOT_RENAME, locked);
            m.inv.setItem(SLOT_PICKUP, locked);
        }
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        // Cancel everything while a robot/shop menu is open, including shift-clicks, number keys and double-clicks in
        // the player's own inventory, so nothing can be moved into the menu (the menu is a copy, not the real storage).
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        // before the read-only check, because looking is all this does
        if (academyOn() && m.kind.equals("robot") && e.getRawSlot() == SLOT_ACADEMY) {
            Bot viewed = bots.get(m.id);
            if (viewed != null && (m.readOnly || mayUse(p, viewed)))
                academyMenu.openAgent(p, viewed, m.readOnly, () -> open(p, viewed, m.readOnly), () -> m.readOnly || mayUse(p, viewed));
            return;
        }
        if (m.readOnly) { p.sendActionBar(Ui.mm("<red>Read-only: you can look, but not change anything.")); return; }
        if (m.kind.equals("shop")) { shopClick(p, e.getRawSlot()); return; }
        Bot b = bots.get(m.id);
        if (b == null || !mayUse(p, b)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.getOpenInventory().getTopInventory().equals(m.inv)) p.closeInventory();
            });
            return;
        }
        int slot = e.getRawSlot();
        if (slot >= STORAGE_START) {
            int i = slot - STORAGE_START;
            if (i >= b.storage.getSize()) return;
            ItemStack it = b.storage.getItem(i);
            if (it == null) return;
            b.storage.setItem(i, null);
            give(p, it);
            open(p, b);
            return;
        }
        switch (slot) {
            case SLOT_COLLECT -> {
                for (int i = 0; i < b.storage.getSize(); i++) {
                    ItemStack it = b.storage.getItem(i);
                    if (it == null) continue;
                    Map<Integer, ItemStack> left = p.getInventory().addItem(it);
                    b.storage.setItem(i, left.isEmpty() ? null : left.values().iterator().next());
                }
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1f);
                open(p, b);
            }
            case SLOT_XP -> { if (b.xp > 0) { p.giveExp(b.xp); p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1f); b.xp = 0; } open(p, b); }
            case SLOT_FUEL -> {
                // shift-click: every Fuel Cell in the inventory until the tank is full; plain click: one cell
                ClickType c = e.getClick();
                addFuel(p, b, c == ClickType.SHIFT_LEFT || c == ClickType.SHIFT_RIGHT);
                open(p, b);
            }
            case SLOT_UPGRADE -> { upgrade(p, b); open(p, b); }
            case SLOT_RENAME -> {
                naming.put(p.getUniqueId(), new Naming(b.id, System.currentTimeMillis() + 60_000L));
                p.closeInventory();
                p.sendMessage(Ui.mm("<aqua>Type your agent's name in chat: <gray>1-16 letters or numbers. <white>clear<gray> removes the name, <white>cancel<gray> stops."));
            }
            case SLOT_PICKUP -> pickUp(p, b);
            default -> {
                for (int i = 0; i < 4; i++) if (JOB_SLOTS[i] == slot && b.job != Job.values()[i]) {
                    b.job = Job.values()[i];
                    b.timer = 0;
                    b.workProgress = 0;
                    b.lastGrindLog = null;
                    b.grindStatus = "";
                    CopperGolem g = entity(b);
                    if (g != null) style(b, g);
                    p.sendMessage(Ui.mm("<green>Your agent is now a " + b.job.color + b.job.label + "<green>."));
                    open(p, b);
                }
            }
        }
    }

    private void addFuel(Player p, Bot b, boolean all) {
        int added = 0;
        ItemStack[] inv = p.getInventory().getStorageContents();
        b.fixRate();
        while (true) {
            // cells that still fit in the tank, as plain values; the lowest tier goes in first
            List<RobotRules.FuelCell> fits = new ArrayList<>();
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < inv.length; i++) {
                int t = fuelTier(inv[i]);
                if (t == 0 || inv[i].getAmount() <= 0 || b.fuel + FUEL_MINUTES[t - 1] * 60_000L > MAX_TANK) continue;
                fits.add(new RobotRules.FuelCell(t, FUEL_MINUTES[t - 1] * 60_000L));
                slots.add(i);
            }
            Optional<RobotRules.FuelCell> pick = RobotRules.lowestTierCell(fits);
            if (pick.isEmpty()) break;
            RobotRules.FuelCell c = pick.get();
            int i = slots.get(fits.indexOf(c));
            // blend the tank's current rate with only the newly accepted fuel
            b.chargeRate = RobotRules.blendRate(b.fuel, b.chargeRate, c.chargeMs(), RobotRules.rateForTier(c.tier()));
            b.fuelTier = b.fuel > 0 ? Math.max(b.fuelTier, c.tier()) : c.tier();   // icon/colour only now
            b.fuel += c.chargeMs();
            inv[i].setAmount(inv[i].getAmount() - 1);
            added++;
            if (!all) break;
        }
        p.getInventory().setStorageContents(inv);
        if (added == 0) p.sendMessage(Ui.mm(b.fuel >= MAX_TANK - 30 * 60_000L ? "<red>The tank is full." : sandbox ? "<red>You have no Fuel Cells. Take some from the Fuel chest." : "<red>You have no Fuel Cells. Buy them at <white>/agents<red> or the Scrap Shop."));
        else { p.sendMessage(Ui.mm("<green>Added " + added + " Fuel Cell" + (added > 1 ? "s" : "") + ". Fuel: <white>" + time(b.fuel))); p.playSound(p.getLocation(), Sound.ITEM_BUCKET_FILL_LAVA, 0.6f, 1.2f); }
    }

    private void upgrade(Player p, Bot b) {
        if (b.tier >= MAX_TIER) return;
        int t = b.tier - 1;
        if (!sandbox) {
        if (b.charge < UP_CHARGE_HOURS[t] * 3600_000L) { p.sendMessage(Ui.mm("<red>Not enough Charge yet - keep it fuelled (" + time(b.charge) + " / " + UP_CHARGE_HOURS[t] + "h). Better fuel charges faster.")); return; }
        if (Spawners.countEssence(p) < UP_ESSENCE[t]) { p.sendMessage(Ui.mm("<red>You need " + UP_ESSENCE[t] + " Mob Essence.")); return; }
        if (eco == null || eco.getBalance(p) < UP_MONEY[t]) { p.sendMessage(Ui.mm("<red>You need " + NetEconomy.money(UP_MONEY[t]) + ".")); return; }
        if (!eco.withdrawPlayer(p, UP_MONEY[t]).transactionSuccess()) { p.sendMessage(Ui.mm("<red>The payment failed.")); return; }
        Spawners.takeEssence(p, UP_ESSENCE[t]);
        b.charge -= UP_CHARGE_HOURS[t] * 3600_000L;
        }
        b.tier++;
        Inventory bigger = Bukkit.createInventory(null, STORAGE[b.tier - 1]);
        bigger.setContents(Arrays.copyOf(b.storage.getContents(), bigger.getSize()));
        b.storage = bigger;
        CopperGolem g = entity(b);
        if (g != null) { style(b, g); g.getWorld().spawnParticle(Particle.WAX_OFF, g.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4); }
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        p.sendMessage(Ui.mm("<gold><bold>Agent upgraded to Tier " + Spawners.roman(b.tier) + "!"));
        save();
    }

    private void pickUp(Player p, Bot b) {
        p.closeInventory();
        CopperGolem g = entity(b);
        if (g != null) g.remove();
        bots.remove(b.id);
        for (ItemStack it : b.storage.getContents()) if (it != null) give(p, it);
        if (b.xp > 0) p.giveExp(b.xp);
        give(p, withServiceRecord(robotItem(b.job, b.tier, b.charge, b.fuel, itemFuelTier(b.chargeRate), b.name, b.record), b.record));
        p.sendMessage(Ui.mm("<green>Agent picked up."));
        save();
    }

    // This message is a name, so it never reaches chat. The chat filter still sees it, as it does all typed text.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatName(io.papermc.paper.event.player.AsyncChatEvent e) {
        Naming n = naming.remove(e.getPlayer().getUniqueId());
        if (n == null || n.until() < System.currentTimeMillis()) return;
        e.setCancelled(true);
        String typed = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> applyName(p, n.botId(), typed));
    }

    @EventHandler
    public void onQuitNaming(org.bukkit.event.player.PlayerQuitEvent e) { naming.remove(e.getPlayer().getUniqueId()); }

    private void applyName(Player p, String botId, String typed) {
        Bot b = bots.get(botId);
        if (b == null || !mayUse(p, b) || typed.equalsIgnoreCase("cancel")) { p.sendMessage(Ui.mm("<gray>Rename cancelled.")); return; }
        if (typed.equalsIgnoreCase("clear")) b.name = null;
        else if (!RobotRules.validName(typed)) { p.sendMessage(Ui.mm("<red>Names are 1-16 letters or numbers, with no spaces.")); return; }
        else if (net.blackforge.core.moderation.AutoMod.blocks(typed)) { p.sendMessage(Ui.mm("<red>That name isn't allowed.")); return; }
        else b.name = typed;
        CopperGolem g = entity(b);
        if (g != null) style(b, g);
        save();
        p.sendMessage(Ui.mm("<green>Your agent is now <white>" + b.title() + "<green>."));
    }

    // ------------------------------------------------------------------ Base Station

    /** This player's agents, the highest tier first. */
    List<Bot> agentsOf(UUID owner) {
        List<Bot> out = new ArrayList<>();
        for (Bot b : bots.values()) if (b.owner.equals(owner)) out.add(b);
        out.sort(Comparator.comparingInt((Bot b) -> b.tier).reversed());
        return out;
    }

    private boolean mayUse(Player p, Bot b) {
        return p.hasPermission("blackforge.admin") || (sandbox ? b.owner.equals(p.getUniqueId()) : canUse.test(p, b.loc));
    }

    /** An agent's card in the Forge: its tier and the price of the next one, and nothing from its storage. */
    ItemStack forgeIcon(Bot b) {
        List<String> lore = new ArrayList<>(List.of("<gray>Job: " + b.job.color + b.job.label, "<gray>Tier: <white>" + Spawners.roman(b.tier), ""));
        if (b.tier >= MAX_TIER) lore.add("<gold><bold>Max tier");
        else {
            int t = b.tier - 1;
            lore.add("<gray>Upgrade to Tier <white>" + Spawners.roman(b.tier + 1) + "<gray>:");
            if (sandbox) lore.add("<green>Free in the Foundry");
            else {
            lore.add("<green>" + NetEconomy.money(UP_MONEY[t]));
            lore.add("<#9d7bff>" + UP_ESSENCE[t] + " Mob Essence");
            lore.add("<gray>Charge: <white>" + time(b.charge) + " <dark_gray>/ " + UP_CHARGE_HOURS[t] + "h");
            }
            lore.add("");
            lore.add("<yellow>Click to upgrade");
        }
        ItemStack it = Ui.item(Material.COPPER_GOLEM_SPAWN_EGG, b.job.color + "<bold>" + b.title(), lore.toArray(String[]::new));
        it.setAmount(b.tier);
        return it;
    }

    void upgradeFromStation(Player p, String id) {
        Bot b = bots.get(id);
        if (b == null) return;
        if (!mayUse(p, b)) { p.sendMessage(Ui.mm("<red>That's " + Bukkit.getOfflinePlayer(b.owner).getName() + "'s agent.")); return; }
        upgrade(p, b);   // same rules and costs as the agent's own menu
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    // ------------------------------------------------------------------ /robots workshop

    void openShop(Player p) {
        Menu m = new Menu("", "shop");
        m.inv = Bukkit.createInventory(m, 36, StationUi.title(StationUi.Screen.WORKSHOP, "⚙ Agent Workshop"));
        if (!StationUi.art()) {
            ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < 36; i++) m.inv.setItem(i, pane);
        }
        m.inv.setItem(4, Ui.item(Material.BOOK, "<gold><bold>How agents work",
                crateOnly == null ? "<gray>1. Buy an agent and place it on your island" : "<gray>1. Win an agent from a crate, place it in your claim", "<gray>2. Right-click it and add Fuel Cells",
                "<gray>3. Pick a job: Farmer, Miner, Grinder, Fisherman", "<gray>4. Burning fuel builds Charge - with Charge,",
                "<gray>   Essence and money it can reach the next tier", "", "<gray>Your agents: <white>" + owned(p.getUniqueId()) + "/" + limit(p) + " <dark_gray>(ranks raise this)"));
        ItemStack r = robotItem(Job.MINER, 1, 0, 0, 1);
        r.editMeta(x -> { List<net.kyori.adventure.text.Component> l = new ArrayList<>(Optional.ofNullable(x.lore()).orElse(List.of())); l.add(Ui.mm(""));
            if (crateOnly == null) { l.add(Ui.mm("<green>Price: " + NetEconomy.money(ROBOT_PRICE))); l.add(Ui.mm("<yellow>Click to buy")); }
            else { l.add(Ui.mm("<red>Not for sale here")); l.add(Ui.mm("<gray>Win agents from " + crateOnly + " <gray>crates")); }
            x.lore(l); });
        m.inv.setItem(13, r);
        for (int t = 1; t <= 5; t++) {
            ItemStack f = fuelCell(t, 1);
            int tt = t;
            f.editMeta(x -> { List<net.kyori.adventure.text.Component> l = new ArrayList<>(Optional.ofNullable(x.lore()).orElse(List.of())); l.add(Ui.mm(""));
                l.add(Ui.mm("<green>" + NetEconomy.money(FUEL_MONEY[tt - 1]) + " <dark_gray>+ <#9d7bff>" + FUEL_ESSENCE[tt - 1] + " Mob Essence")); l.add(Ui.mm("<yellow>Click to buy")); x.lore(l); });
            m.inv.setItem(19 + 2 * (t - 1), f);
        }
        if (stations != null) m.inv.setItem(31, stations.shopIcon());
        p.openInventory(m.inv);
    }

    private void shopClick(Player p, int slot) {
        if (slot == 31 && stations != null) stations.buy(p);
        else if (slot == 13) {
            if (crateOnly != null) { p.sendMessage(Ui.mm("<gray>Agents can only be won from " + crateOnly + " <gray>crates.")); return; }
            if (eco == null || !eco.withdrawPlayer(p, ROBOT_PRICE).transactionSuccess()) { p.sendMessage(Ui.mm("<red>An agent costs " + NetEconomy.money(ROBOT_PRICE) + ".")); return; }
            give(p, robotItem(Job.MINER, 1, 0, 0, 1));
            p.sendMessage(Ui.mm("<green>You bought an agent! Place it on your island."));
        } else if (slot >= 19 && slot <= 27 && (slot - 19) % 2 == 0) {
            int t = (slot - 19) / 2 + 1;
            if (Spawners.countEssence(p) < FUEL_ESSENCE[t - 1]) { p.sendMessage(Ui.mm("<red>You need " + FUEL_ESSENCE[t - 1] + " Mob Essence.")); return; }
            if (eco == null || eco.getBalance(p) < FUEL_MONEY[t - 1]) { p.sendMessage(Ui.mm("<red>You need " + NetEconomy.money(FUEL_MONEY[t - 1]) + ".")); return; }
            if (!eco.withdrawPlayer(p, FUEL_MONEY[t - 1]).transactionSuccess()) { p.sendMessage(Ui.mm("<red>The payment failed.")); return; }
            Spawners.takeEssence(p, FUEL_ESSENCE[t - 1]);
            give(p, fuelCell(t, 1));
            p.sendMessage(Ui.mm("<green>Bought a Fuel Cell " + Spawners.roman(t) + "."));
        } else return;
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        openShop(p);
    }

    private void registerCommand() {
        Bukkit.getCommandMap().register("blackforge", new Command("agents", "Agent workshop", "/agents", List.of("agent", "robots", "robot", "fuel")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (a.length >= 2 && a[0].equalsIgnoreCase("academy") && a[1].equalsIgnoreCase("points") && s.hasPermission("blackforge.admin")) {
                    academyPoints(s, a);
                    return true;
                }
                if (sandbox) {   // the Foundry only has this for the Academy's staff command: no shop, and no giving items
                    s.sendMessage(Ui.mm("<gray>Agents here come from the Foundry's chests."));
                    return true;
                }
                if (a.length >= 3 && a[0].equalsIgnoreCase("give") && s.hasPermission("blackforge.admin")) {   // robots give <player> <robot|fuel|essence> [tier/amount] [job]
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) { s.sendMessage(Ui.mm("<red>Not online.")); return true; }
                    int n = a.length > 3 ? Integer.parseInt(a[3]) : 1;
                    switch (a[2].toLowerCase()) {
                        case "agent", "robot" -> give(t, robotItem(a.length > 4 ? Job.valueOf(a[4].toUpperCase()) : Job.MINER, Math.max(1, Math.min(5, n)), 0, 0, 1));
                        case "fuel" -> give(t, fuelCell(n, 1));
                        case "essence" -> Spawners.giveEssence(t, n);
                        case "fragment", "fragments" -> give(t, Spawners.fragment(n));
                        case "emptyspawner" -> { for (int i = 0; i < n; i++) give(t, Spawners.emptySpawner()); }
                        default -> { s.sendMessage(Ui.mm("<red>agent|fuel|essence|fragment|emptyspawner")); return true; }
                    }
                    s.sendMessage(Ui.mm("<green>Given."));
                    return true;
                }
                if (a.length >= 3 && a[0].equalsIgnoreCase("charge") && s.hasPermission("blackforge.admin")) {   // robots charge <player> <hours>
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) { s.sendMessage(Ui.mm("<red>Not online.")); return true; }
                    for (Bot b : bots.values()) if (b.owner.equals(t.getUniqueId())) b.charge += Long.parseLong(a[2]) * 3600_000L;
                    s.sendMessage(Ui.mm("<green>Charged " + t.getName() + "'s agents.")); return true;
                }
                if (s instanceof Player p) openShop(p);
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("robots");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) pending.put(id, sec.getConfigurationSection(id));
        loadPending();
        // island worlds (BentoBox) are created after BlackForgeCore enables
        Bukkit.getScheduler().runTask(plugin, this::loadPending);
    }

    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent e) { if (!pending.isEmpty()) loadPending(); }

    private void loadPending() {
        int before = bots.size();
        for (Iterator<Map.Entry<String, ConfigurationSection>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ConfigurationSection> en = it.next();
            String id = en.getKey();
            ConfigurationSection s = en.getValue();
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) continue;
            it.remove();
            Bot b = new Bot(id);
            b.owner = UUID.fromString(s.getString("owner"));
            b.job = Job.valueOf(s.getString("job"));
            b.tier = s.getInt("tier", 1);
            b.fuel = s.getLong("fuel");
            b.fuelTier = Math.max(1, s.getInt("fuel-tier", 1));
            b.chargeRate = s.getDouble("charge-rate", 0);
            b.fixRate();
            b.charge = s.getLong("charge");
            b.xp = s.getInt("xp");
            b.name = s.getString("name");
            b.record = withUid(AgentRecord.decode(s.getString("academy")));
            b.loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), 0);
            b.storage = Bukkit.createInventory(null, STORAGE[b.tier - 1]);
            List<?> items = s.getList("storage", List.of());
            for (int i = 0; i < items.size() && i < b.storage.getSize(); i++) if (items.get(i) instanceof ItemStack st) b.storage.setItem(i, st);
            String ent = s.getString("entity");
            if (ent != null) b.entity = UUID.fromString(ent);
            bots.put(id, b);
        }
        if (bots.size() > before) plugin.getLogger().info("Loaded " + (bots.size() - before) + " agents" + (pending.isEmpty() ? "" : " (" + pending.size() + " waiting for their world)"));
    }

    void save() {
        if (sandbox) return;
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, ConfigurationSection> en : pending.entrySet()) y.set("robots." + en.getKey(), en.getValue());
        for (Bot b : bots.values()) {
            String k = "robots." + b.id + ".";
            y.set(k + "owner", b.owner.toString());
            y.set(k + "job", b.job.name());
            y.set(k + "tier", b.tier);
            y.set(k + "fuel", b.fuel);
            y.set(k + "fuel-tier", b.fuelTier);
            y.set(k + "charge-rate", b.chargeRate);
            y.set(k + "charge", b.charge);
            y.set(k + "xp", b.xp);
            y.set(k + "name", b.name);
            y.set(k + "academy", b.record.encode());
            y.set(k + "world", b.loc.getWorld().getName());
            y.set(k + "x", b.loc.getX()); y.set(k + "y", b.loc.getY()); y.set(k + "z", b.loc.getZ()); y.set(k + "yaw", b.loc.getYaw());
            y.set(k + "entity", b.entity == null ? null : b.entity.toString());
            y.set(k + "storage", Arrays.asList(b.storage.getContents()));
        }
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("robots save failed: " + e.getMessage()); }
    }
}
