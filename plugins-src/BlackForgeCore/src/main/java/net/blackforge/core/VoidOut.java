package net.blackforge.core;

import net.blackforge.core.build.Canvas;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.io.File;
import java.util.*;
import java.util.function.Function;

/**
 * Leaves a spawn build floating in empty sky: every block in the area (all the way down) that isn't part of the build
 * is removed, so players can't see land around a hub. Natural terrain that grew into the build's own box goes too;
 * anything else inside the build's box is kept (blocks the game places at runtime).
 *
 * Runs once per area (marker file voided-&lt;id&gt; in the plugin folder), a few chunks per tick within a time budget,
 * then catches anyone who falls into the void there and sends them back. /rtp should skip {@link #inside}.
 */
public final class VoidOut implements Listener {
    public record Area(int x1, int z1, int x2, int z2) {
        boolean contains(double x, double z) { return x >= x1 && x <= x2 + 1 && z >= z1 && z <= z2 + 1; }
    }

    private static final Set<String> NATURAL_WORDS = Set.of("stone", "deepslate", "dirt", "grass", "podzol", "mud", "clay", "gravel", "sand", "terracotta",
            "granite", "diorite", "andesite", "tuff", "calcite", "dripstone", "_ore", "water", "lava", "snow", "ice", "moss", "leaves", "_log", "_wood",
            "fern", "flower", "poppy", "dandelion", "orchid", "allium", "bluet", "tulip", "daisy", "cornflower", "lily", "rose", "peony", "lilac",
            "berry", "kelp", "seagrass", "sugar_cane", "bamboo", "cactus", "vine", "lichen", "mushroom", "azalea", "dripleaf", "roots", "spore",
            "sculk", "amethyst", "basalt", "magma", "obsidian", "bedrock", "pumpkin", "melon", "dead_bush", "petals", "bush", "cobweb", "infested", "coral");

    private final BlackForgeCore plugin;
    private final World world;
    private final String id;
    private final List<Area> areas;
    private final Set<Long> keep = new HashSet<>();
    private final List<int[]> boxes = new ArrayList<>();   // build bounding boxes: x1 y1 z1 x2 y2 z2
    private final int voidY;
    private final Function<Player, Location> rescue;

    public VoidOut(BlackForgeCore plugin, World world, String id, List<Area> areas, List<Canvas> builds, int voidY, Function<Player, Location> rescue) {
        this.plugin = plugin;
        this.world = world;
        this.id = id;
        this.areas = areas;
        this.voidY = voidY;
        this.rescue = rescue;
        for (Canvas c : builds) {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (Map<Long, String> m : List.of(c.blocks(), c.lateBlocks()))
                for (long k : m.keySet()) {
                    keep.add(k);
                    int x = Canvas.kx(k), y = Canvas.ky(k), z = Canvas.kz(k);
                    b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.min(b[2], z);
                    b[3] = Math.max(b[3], x); b[4] = Math.max(b[4], y); b[5] = Math.max(b[5], z);
                }
            if (b[0] != Integer.MAX_VALUE) boxes.add(new int[]{b[0] - 2, b[1] - 2, b[2] - 2, b[3] + 2, b[4] + 6, b[5] + 2});
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** The square around {@code builds} plus {@code margin} blocks each way. */
    public static Area around(List<Canvas> builds, int margin) {
        int x1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE;
        for (Canvas c : builds)
            for (Map<Long, String> m : List.of(c.blocks(), c.lateBlocks()))
                for (long k : m.keySet()) { x1 = Math.min(x1, Canvas.kx(k)); z1 = Math.min(z1, Canvas.kz(k)); x2 = Math.max(x2, Canvas.kx(k)); z2 = Math.max(z2, Canvas.kz(k)); }
        return new Area(x1 - margin, z1 - margin, x2 + margin, z2 + margin);
    }

    /** Start once the server has settled, if this area hasn't been cleared yet. */
    public VoidOut autoStart() {
        if (!done()) Bukkit.getScheduler().runTaskLater(plugin, () -> start(Bukkit.getConsoleSender()), 600L);
        return this;
    }

    private File marker() { return new File(plugin.getDataFolder(), "voided-" + id); }

    public boolean done() { return marker().exists(); }

    public boolean inside(double x, double z) {
        for (Area a : areas) if (a.contains(x, z)) return true;
        return false;
    }

    private boolean inBuildBox(int x, int y, int z) {
        for (int[] b : boxes) if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return true;
        return false;
    }

    static boolean natural(Material m) {
        String n = m.name().toLowerCase(Locale.ROOT);
        if (n.contains("brick") || n.contains("polished") || n.contains("smooth") || n.contains("chiseled") || n.contains("cut_") || n.startsWith("stripped")) return false;
        for (String w : NATURAL_WORDS) if (n.contains(w)) return true;
        return false;
    }

    /** Start clearing (once): the chunks are worked through over the next minutes; {@code who} hears the progress. */
    public void start(org.bukkit.command.CommandSender who) {
        if (done()) { who.sendMessage(Ui.mm("<gray>" + id + " is already cleared.")); return; }
        Deque<long[]> todo = new ArrayDeque<>();
        for (Area a : areas)
            for (int cx = a.x1 >> 4; cx <= a.x2 >> 4; cx++)
                for (int cz = a.z1 >> 4; cz <= a.z2 >> 4; cz++) todo.add(new long[]{cx, cz});
        int total = todo.size();
        plugin.getLogger().info("Clearing the land around " + id + ": " + total + " chunks");
        BlockData air = Material.AIR.createBlockData();
        int minY = world.getMinHeight(), maxY = world.getMaxHeight();
        int[] lastPct = {-1};
        long[] removed = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            long until = System.nanoTime() + 25_000_000L;   // 25 ms per tick, leaving the rest for the game
            while (!todo.isEmpty() && System.nanoTime() < until) {
                long[] c = todo.poll();
                int cx = (int) c[0], cz = (int) c[1];
                ChunkSnapshot snap = world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false);
                for (int y = minY; y < maxY; y++) {
                    if (snap.isSectionEmpty((y - minY) >> 4)) { y |= 15; continue; }
                    for (int lx = 0; lx < 16; lx++)
                        for (int lz = 0; lz < 16; lz++) {
                            Material m = snap.getBlockType(lx, y, lz);
                            if (m.isAir()) continue;
                            int x = (cx << 4) + lx, z = (cz << 4) + lz;
                            if (!inside(x, z) || keep.contains(Canvas.key(x, y, z))) continue;
                            if (inBuildBox(x, y, z) && !natural(m)) continue;   // runtime-placed parts of a build
                            world.getBlockAt(x, y, z).setBlockData(air, false);
                            removed[0]++;
                        }
                }
                int pct = (int) (100L * (total - todo.size()) / total);
                if (pct / 10 != lastPct[0] / 10) { lastPct[0] = pct; plugin.getLogger().info("Clearing " + id + ": " + pct + "%"); }
            }
            if (todo.isEmpty()) {
                task.cancel();
                try { marker().createNewFile(); } catch (Exception ignored) { }
                String msg = "Cleared the land around " + id + ": " + removed[0] + " blocks removed.";
                plugin.getLogger().info(msg);
                who.sendMessage(Ui.mm("<green>" + msg));
            }
        }, 1L, 1L);
    }

    /** Fell off the build into the cleared void: straight back to safety, no death. */
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo().getY() > voidY || e.getTo().getWorld() != world || !inside(e.getTo().getX(), e.getTo().getZ())) return;
        Player p = e.getPlayer();
        p.setFallDistance(0);
        p.teleport(rescue.apply(p));
        p.sendActionBar(Ui.mm("<gray>Careful - there's nothing down there!"));
    }
}
