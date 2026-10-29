package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.alloymc.core.build.Canvas;
import net.alloymc.core.build.Paster;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.util.*;

/**
 * The Skyblock Hub: everyone joining the Skyblock server lands here and picks a game through one of two portals:
 * <b>Skyblock</b> (the classic islands; its spawn is Sky Harbor) or <b>OneBlock</b> (its own spawn island, with its
 * own crates). The two games keep separate inventories, ender chests and auction houses ({@link InventoryGroups}),
 * so nothing carries over between them.
 *
 * Worlds: skyhub (this lobby) and oneblock_spawn, both empty void worlds made here; Sky Harbor stays in the main world.
 */
public final class SkyHub implements Listener {
    public static final String LOBBY = "lobby", SKYBLOCK = "skyblock", ONEBLOCK = "oneblock";
    static final int Y = 100;
    /** Gate openings: x from gx-1 to gx+1, y from Y+1 to Y+4, z = gz. */
    private static final int HUB_GATE_Z = -9, OB_GATE_Z = -18, LEFT_X = -7, RIGHT_X = 7;
    /** The OneBlock spawn's own crates, on the west side of its island. */
    static final List<SurvivalLayout.CrateSpot> OB_CRATES = List.of(
            new SurvivalLayout.CrateSpot("iron", -16, -6), new SurvivalLayout.CrateSpot("vote", -16, -2),
            new SurvivalLayout.CrateSpot("gold", -16, 2), new SurvivalLayout.CrateSpot("netherite", -16, 6));

    private final AlloyCore plugin;
    private final World mainWorld, hub, obSpawn;
    private final Location harborSpawn;
    private final List<TextDisplay> holos = new ArrayList<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private InventoryGroups groups;
    private java.util.function.BiConsumer<Player, String> firstVisit = (p, g) -> {};
    private final File visits;

    /** An empty world: the islands are pasted in. */
    public static final class Void extends ChunkGenerator {}

    SkyHub(AlloyCore plugin, World mainWorld, Location harborSpawn) {
        this.plugin = plugin;
        this.mainWorld = mainWorld;
        this.harborSpawn = harborSpawn;
        this.hub = voidWorld("skyhub");
        this.obSpawn = voidWorld("oneblock_spawn");
        this.visits = new File(plugin.getDataFolder(), "skyhub-visits.yml");
        hub.setSpawnLocation(hubSpawn());
        obSpawn.setSpawnLocation(oneblockSpawn());
        for (World w : List.of(hub, obSpawn)) for (int cx = -3; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) w.addPluginChunkTicket(cx, cz, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        build();
        Bukkit.getScheduler().runTaskLater(plugin, this::holograms, 60L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (holos.stream().anyMatch(t -> !t.isValid())) holograms(); }, 200L, 200L);
    }

    private World voidWorld(String name) {
        World w = Bukkit.getWorld(name);
        if (w != null) return w;
        w = new WorldCreator(name).generator(new Void()).environment(World.Environment.NORMAL).generateStructures(false).createWorld();
        for (String[] r : new String[][]{{"spawn_mobs", "false"}, {"advance_weather", "false"}, {"advance_time", "false"}}) {
            @SuppressWarnings("deprecation") boolean ignored = w.setGameRuleValue(r[0], r[1]);
        }
        w.setTime(6000);
        return w;
    }

    void setGroups(InventoryGroups g) { this.groups = g; }
    void onFirstVisit(java.util.function.BiConsumer<Player, String> f) { this.firstVisit = f; }

    private void arrived(Player p, String game) {
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(visits);
        String key = p.getUniqueId() + "." + game;
        if (y.getBoolean(key)) return;
        y.set(key, true);
        try { y.save(visits); } catch (Exception ignored) { }
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) firstVisit.accept(p, game); }, 20L);
    }

    public Location hubSpawn() { return new Location(hub, 0.5, Y + 1, 7.5, 180, 0); }
    public Location oneblockSpawn() { return new Location(obSpawn, 0.5, Y + 1, 14.5, 180, 0); }
    public World oneblockSpawnWorld() { return obSpawn; }
    public World hubWorld() { return hub; }

    /** Which game a place belongs to. */
    public String groupOf(Location l) {
        World w = l.getWorld();
        if (w == null) return SKYBLOCK;
        String n = w.getName().toLowerCase();
        if (w == hub) return LOBBY;
        if (w == obSpawn || n.startsWith("oneblock")) return ONEBLOCK;
        return SKYBLOCK;
    }

    /** /spawn takes you to the spawn of the game you're playing. */
    public Location spawnFor(Player p) {
        return switch (groupOf(p.getLocation())) {
            case LOBBY -> hubSpawn();
            case ONEBLOCK -> oneblockSpawn();
            default -> harborSpawn;
        };
    }

    // ------------------------------------------------------------------ joining and portals

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.teleport(hubSpawn());
            p.sendMessage(Ui.mm("<#4fd1c5>Welcome to the <bold>Skyblock Hub</bold>! <gray>Walk through a portal to pick your game: "
                    + "<#4fd1c5>Skyblock <gray>or <#b06bff>OneBlock<gray>. Each keeps its own items."));
        }, 5L);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Location to = e.getTo();
        Player p = e.getPlayer();
        World w = to.getWorld();
        if (w != hub && w != obSpawn) return;
        if (to.getY() < Y - 40) { p.teleport(w == hub ? hubSpawn() : oneblockSpawn()); return; }
        int gz = w == hub ? HUB_GATE_Z : OB_GATE_Z;
        if (Math.abs(to.getBlockZ() - gz) > 0 || to.getY() < Y + 1 || to.getY() > Y + 5) return;
        boolean left = Math.abs(to.getBlockX() - LEFT_X) <= 1, right = Math.abs(to.getBlockX() - RIGHT_X) <= 1;
        if (!left && !right) return;
        long now = System.currentTimeMillis();
        if (cooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        cooldown.put(p.getUniqueId(), now + 2500);
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.3f, 1.6f);
        if (w == hub) {
            if (left) { p.teleport(harborSpawn); p.sendMessage(Ui.mm("<#4fd1c5>Welcome to <bold>Sky Harbor</bold>! <gray>Your island: <white>/is <gray>or the Island Gate")); arrived(p, SKYBLOCK); }
            else { p.teleport(oneblockSpawn()); p.sendMessage(Ui.mm("<#b06bff>Welcome to <bold>OneBlock</bold>! <gray>Your island: <white>/ob <gray>or the portal ahead")); arrived(p, ONEBLOCK); }
        } else {
            if (left) p.performCommand("ob");
            else p.teleport(hubSpawn());
        }
    }

    /** The portals are only for show: no Nether trips out of the hub worlds. */
    @EventHandler
    public void onPortal(PlayerPortalEvent e) { if (e.getFrom().getWorld() == hub || e.getFrom().getWorld() == obSpawn) e.setCancelled(true); }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent e) { if (e.getFrom().getWorld() == hub || e.getFrom().getWorld() == obSpawn) e.setCancelled(true); }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && (e.getEntity().getWorld() == hub || e.getEntity().getWorld() == obSpawn)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ the islands

    private void build() {
        if (!new File(plugin.getDataFolder(), "skyhub-built").exists())
            Paster.paste(plugin, hub, hubIsland(), 4000, () -> mark("skyhub-built"));
        if (!new File(plugin.getDataFolder(), "oneblock-spawn-built").exists())
            Paster.paste(plugin, obSpawn, oneblockIsland(), 4000, () -> mark("oneblock-spawn-built"));
    }

    private void mark(String f) {
        try { new File(plugin.getDataFolder(), f).createNewFile(); } catch (Exception ignored) { }
        plugin.getLogger().info("Built " + f.replace("-built", ""));
    }

    private static void island(Canvas c, int r, String top, String edge, long seed) {
        Random rnd = new Random(seed);
        for (int x = -r - 2; x <= r + 2; x++)
            for (int z = -r - 2; z <= r + 2; z++) {
                double d = Math.hypot(x, z), R = r + 1.5 * Math.sin(Math.atan2(z, x) * 5 + seed);
                if (d > R) continue;
                c.set(x, Y, z, "minecraft:" + (R - d < 1.2 ? edge : top));
                int depth = (int) Math.round(Math.pow(1 - d / R, 0.8) * r * 0.9 + 2 + rnd.nextInt(2));
                for (int y = 1; y <= depth; y++) c.set(x, Y - y, z, "minecraft:" + (y <= 2 ? "dirt" : y < depth - 1 ? "stone" : "andesite"));
            }
    }

    private static void gate(Canvas c, int gx, int gz, String frame, String trim) {
        for (int x = gx - 2; x <= gx + 2; x++)
            for (int y = Y + 1; y <= Y + 6; y++) {
                boolean edge = x == gx - 2 || x == gx + 2 || y == Y + 6;
                c.set(x, y, gz, edge ? "minecraft:" + (y == Y + 6 || y == Y + 1 ? trim : frame) : "minecraft:nether_portal[axis=x]");
                if (y == Y + 5 && !edge) c.set(x, y, gz, "minecraft:" + frame);
            }
        for (int x = gx - 3; x <= gx + 3; x++) c.set(x, Y, gz + 1, "minecraft:polished_andesite");
        c.setLate(gx - 2, Y + 7, gz, "minecraft:lantern");
        c.setLate(gx + 2, Y + 7, gz, "minecraft:lantern");
    }

    private static void path(Canvas c, int x1, int z1, int x2, int z2) {
        int n = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= n; i++) {
            int x = x1 + (x2 - x1) * i / Math.max(1, n), z = z1 + (z2 - z1) * i / Math.max(1, n);
            for (int dx = -1; dx <= 1; dx++) c.set(x + dx, Y, z, "minecraft:" + ((x + z + dx) % 3 == 0 ? "gravel" : "dirt_path"));
        }
    }

    static Canvas hubIsland() {
        Canvas c = new Canvas();
        island(c, 16, "grass_block", "moss_block", 3);
        path(c, 0, 9, 0, -2);
        path(c, 0, -2, LEFT_X, HUB_GATE_Z + 1);
        path(c, 0, -2, RIGHT_X, HUB_GATE_Z + 1);
        gate(c, LEFT_X, HUB_GATE_Z, "prismarine_bricks", "dark_prismarine");
        gate(c, RIGHT_X, HUB_GATE_Z, "purpur_block", "purpur_pillar");
        // a little fountain in the middle
        c.ring(0, Y, 2, 1.5, 3.2, "minecraft:stone_bricks");
        c.disc(0, Y, 2, 1.6, "minecraft:water");
        for (int[] l : new int[][]{{-4, 10}, {4, 10}, {-11, 0}, {11, 0}}) { c.set(l[0], Y + 1, l[1], "minecraft:oak_fence"); c.setLate(l[0], Y + 2, l[1], "minecraft:lantern"); }
        return c;
    }

    static Canvas oneblockIsland() {
        Canvas c = new Canvas();
        island(c, 24, "grass_block", "moss_block", 11);
        path(c, 0, 16, 0, -5);
        path(c, 0, -5, LEFT_X, OB_GATE_Z + 1);
        path(c, 0, -5, RIGHT_X, OB_GATE_Z + 1);
        path(c, 0, 4, -13, 0);
        gate(c, LEFT_X, OB_GATE_Z, "purpur_block", "purpur_pillar");
        gate(c, RIGHT_X, OB_GATE_Z, "prismarine_bricks", "dark_prismarine");
        // the giant one block
        for (int x = -1; x <= 1; x++) for (int y = 1; y <= 3; y++) for (int z = -1; z <= 1; z++) c.set(x, Y + y, z, "minecraft:" + (y == 3 ? "grass_block" : "dirt"));
        // crates: vaults on a stone dais
        for (SurvivalLayout.CrateSpot s : OB_CRATES) {
            c.set(s.x(), Y + 1, s.z(), "minecraft:vault[facing=east]");
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(s.x() + dx, Y, s.z() + dz, "minecraft:polished_deepslate");
        }
        for (int[] l : new int[][]{{-5, 17}, {5, 17}, {-20, 0}, {20, 0}}) { c.set(l[0], Y + 1, l[1], "minecraft:birch_fence"); c.setLate(l[0], Y + 2, l[1], "minecraft:lantern"); }
        return c;
    }

    private void holograms() {
        holos.forEach(TextDisplay::remove);
        holos.clear();
        holos.add(Ui.hologram(new Location(hub, LEFT_X + 0.5, Y + 8.5, HUB_GATE_Z + 0.5), "<#4fd1c5><bold>SKYBLOCK</bold><newline><gray>Classic islands • Sky Harbor", 1.5f));
        holos.add(Ui.hologram(new Location(hub, RIGHT_X + 0.5, Y + 8.5, HUB_GATE_Z + 0.5), "<#b06bff><bold>ONEBLOCK</bold><newline><gray>Start on a single block", 1.5f));
        holos.add(Ui.hologram(new Location(hub, 0.5, Y + 4, 2.5), "<white><bold>SKYBLOCK HUB</bold><newline><gray>Pick a game - each keeps its own items", 1.2f));
        holos.add(Ui.hologram(new Location(obSpawn, LEFT_X + 0.5, Y + 8.5, OB_GATE_Z + 0.5), "<#b06bff><bold>YOUR ONEBLOCK</bold><newline><gray>Go to your island • <white>/ob", 1.5f));
        holos.add(Ui.hologram(new Location(obSpawn, RIGHT_X + 0.5, Y + 8.5, OB_GATE_Z + 0.5), "<#4fd1c5><bold>SKYBLOCK HUB</bold><newline><gray>Back to the game picker", 1.5f));
        holos.add(Ui.hologram(new Location(obSpawn, 0.5, Y + 5.2, 0.5), "<#b06bff><bold>ONEBLOCK</bold><newline><gray>Top islands: <white>/istop", 1.4f));
    }

    void shutdown() { holos.forEach(TextDisplay::remove); }
}
