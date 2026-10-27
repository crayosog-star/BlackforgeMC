package net.alloymc.core.arcade.core;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.LobbyRules;
import net.alloymc.core.build.Canvas;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * World management for the arcade:
 *  - arcade_templates: one immutable copy of every map (built-in maps are generated here; staff build custom maps here)
 *  - arcade_matches:   instance slots 1024 blocks apart; every match gets its own slot and its own fresh copy
 * Copies use chunk snapshots (read async-safe) and paste a bounded number of blocks per tick on the main thread.
 */
public final class Worlds {
    public static final String TEMPLATES = "arcade_templates", MATCHES = "arcade_matches";
    private final AlloyCore plugin;
    private World templates, matches;
    private final Map<String, List<long[]>> blockCache = new HashMap<>();   // map key -> packed (x,y,z,paletteIndex)
    private final Map<String, List<BlockData>> paletteCache = new HashMap<>();
    private final int blocksPerTick;

    public Worlds(AlloyCore plugin, int blocksPerTick) { this.plugin = plugin; this.blocksPerTick = blocksPerTick; }

    public static final class Void extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public Location getFixedSpawnLocation(World w, Random r) { return new Location(w, 0, 100, 0); }
    }

    public void load() {
        templates = create(TEMPLATES);
        matches = create(MATCHES);
        // crash recovery: nothing in the match world survives a restart - entities go now, blocks are wiped
        // before every paste (each load clears its slot first), so a half-finished match can never leak.
        for (Entity e : matches.getEntities()) if (!(e instanceof Player)) e.remove();
    }

    private World create(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) w = new WorldCreator(name).generator(new Void()).environment(World.Environment.NORMAL)
                .type(WorldType.FLAT).generateStructures(false).createWorld();
        LobbyRules.setupWorld(plugin, w, 6000);
        w.setAutoSave(name.equals(TEMPLATES));
        w.setDifficulty(Difficulty.NORMAL);
        w.setSpawnFlags(false, false);
        return w;
    }

    public World templates() { return templates; }
    public World matches() { return matches; }

    // ------------------------------------------------------------------ templates

    private static File marker(File dataDir, MapDef m) { return new File(new File(dataDir, "arcade/templates"), m.game + "_" + m.id + ".v" + m.builderVersion); }

    /** Generate a built-in map's template if it isn't there yet (or its builder changed). */
    public boolean ensureTemplate(BuiltMap built, Runnable done) {
        MapDef m = built.def();
        File mk = marker(plugin.getDataFolder(), m);
        if (mk.exists()) { done.run(); return false; }
        plugin.getLogger().info("[Arcade] generating template " + m + " (" + built.canvas().size() + " blocks) at " + m.templateX + ", " + m.templateY + ", " + m.templateZ);
        Canvas shifted = new Canvas();
        for (var e : built.canvas().blocks().entrySet()) { long k = e.getKey(); shifted.set(Canvas.kx(k) + m.templateX, Canvas.ky(k) + m.templateY, Canvas.kz(k) + m.templateZ, e.getValue()); }
        for (var e : built.canvas().lateBlocks().entrySet()) { long k = e.getKey(); shifted.setLate(Canvas.kx(k) + m.templateX, Canvas.ky(k) + m.templateY, Canvas.kz(k) + m.templateZ, e.getValue()); }
        Box r = m.copyRegion();
        clearRegion(templates, m.templateX, m.templateY, m.templateZ, r, () ->
            net.alloymc.core.build.Paster.paste(plugin, templates, shifted, blocksPerTick, () -> {
                try { mk.getParentFile().mkdirs(); for (File old : Objects.requireNonNull(mk.getParentFile().listFiles((d, n) -> n.startsWith(m.game + "_" + m.id + ".v")))) old.delete(); mk.createNewFile(); }
                catch (Exception ignored) { }
                invalidate(m);
                done.run();
            }));
        return true;
    }

    /** Forget the cached copy of a template (after staff edited it). */
    public void invalidate(MapDef m) { blockCache.remove(m.toString()); paletteCache.remove(m.toString()); }

    /** Read the template's non-air blocks once (via chunk snapshots) and keep them for fast instance pastes. */
    private void snapshot(MapDef m, Consumer<Boolean> done) {
        if (blockCache.containsKey(m.toString())) { done.accept(true); return; }
        Box r = m.copyRegion();
        int bx1 = m.templateX + r.x1(), bx2 = m.templateX + r.x2(), bz1 = m.templateZ + r.z1(), bz2 = m.templateZ + r.z2();
        int by1 = Math.max(templates.getMinHeight(), m.templateY + r.y1()), by2 = Math.min(templates.getMaxHeight() - 1, m.templateY + r.y2());
        List<ChunkSnapshot> snaps = new ArrayList<>();
        for (int cx = bx1 >> 4; cx <= bx2 >> 4; cx++)
            for (int cz = bz1 >> 4; cz <= bz2 >> 4; cz++)
                snaps.add(templates.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<long[]> blocks = new ArrayList<>();
            List<BlockData> palette = new ArrayList<>();
            Map<BlockData, Integer> index = new HashMap<>();
            for (ChunkSnapshot s : snaps) {
                int cx0 = s.getX() << 4, cz0 = s.getZ() << 4;
                for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                    int x = cx0 + lx, z = cz0 + lz;
                    if (x < bx1 || x > bx2 || z < bz1 || z > bz2) continue;
                    for (int y = by1; y <= by2; y++) {
                        if (s.getBlockType(lx, y, lz).isAir()) continue;
                        BlockData bd = s.getBlockData(lx, y, lz);
                        int pi = index.computeIfAbsent(bd, k -> { palette.add(k); return palette.size() - 1; });
                        blocks.add(new long[]{x - m.templateX, y - m.templateY, z - m.templateZ, pi});
                    }
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                blockCache.put(m.toString(), blocks);
                paletteCache.put(m.toString(), palette);
                done.accept(!blocks.isEmpty());
            });
        });
    }

    // ------------------------------------------------------------------ instances

    /** Fresh copy of a map into a slot: wipe the slot area, then paste the template. done(false) = failed. */
    public void loadInstance(MapDef m, int slot, Consumer<Boolean> done) {
        int ox = SlotAllocator.originX(slot), oy = m.templateY, oz = 0;
        Box r = m.copyRegion();
        snapshot(m, ok -> {
            if (!ok) { plugin.getLogger().warning("[Arcade] template for " + m + " is empty - has it been built?"); done.accept(false); return; }
            clearSlot(slot, r.grow(4), oy, () -> {
                List<long[]> blocks = blockCache.get(m.toString());
                List<BlockData> palette = paletteCache.get(m.toString());
                if (blocks == null) { done.accept(false); return; }
                Iterator<long[]> it = blocks.iterator();
                Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                    try {
                        for (int n = 0; n < blocksPerTick && it.hasNext(); n++) {
                            long[] b = it.next();
                            matches.getBlockAt(ox + (int) b[0], oy + (int) b[1], oz + (int) b[2]).setBlockData(palette.get((int) b[3]), false);
                        }
                    } catch (Exception ex) {
                        task.cancel();
                        plugin.getLogger().warning("[Arcade] paste of " + m + " into slot " + slot + " failed: " + ex);
                        done.accept(false);
                        return;
                    }
                    if (!it.hasNext()) { task.cancel(); done.accept(true); }
                }, 1L, 1L);
            });
        });
    }

    /**
     * Put back the template's blocks of certain kinds inside a running instance (between rounds: spleef
     * snow, tnt-run floors, block-party grids...). Only touches positions where the template has such a block.
     */
    public void restore(MapDef m, int slot, java.util.function.Predicate<BlockData> which, Runnable done) {
        snapshot(m, ok -> {
            List<long[]> blocks = blockCache.get(m.toString());
            List<BlockData> palette = paletteCache.get(m.toString());
            if (!ok || blocks == null) { done.run(); return; }
            int ox = SlotAllocator.originX(slot), oy = m.templateY;
            List<long[]> todo = new ArrayList<>();
            for (long[] b : blocks) if (which.test(palette.get((int) b[3]))) todo.add(b);
            Iterator<long[]> it = todo.iterator();
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                for (int n = 0; n < blocksPerTick && it.hasNext(); n++) {
                    long[] b = it.next();
                    matches.getBlockAt(ox + (int) b[0], oy + (int) b[1], (int) b[2]).setBlockData(palette.get((int) b[3]), false);
                }
                if (!it.hasNext()) { task.cancel(); done.run(); }
            }, 1L, 1L);
        });
    }

    /** Template blocks (relative x, y, z and data) matching a filter - e.g. to find every chest on a map. */
    public List<Map.Entry<int[], BlockData>> find(MapDef m, java.util.function.Predicate<BlockData> which) {
        List<Map.Entry<int[], BlockData>> out = new ArrayList<>();
        List<long[]> blocks = blockCache.get(m.toString());
        List<BlockData> palette = paletteCache.get(m.toString());
        if (blocks == null) return out;
        for (long[] b : blocks) { BlockData d = palette.get((int) b[3]); if (which.test(d)) out.add(Map.entry(new int[]{(int) b[0], (int) b[1], (int) b[2]}, d)); }
        return out;
    }

    /** Remove every block and non-player entity a match left in its slot. */
    public void clearSlot(int slot, Box area, int oy, Runnable done) {
        int ox = SlotAllocator.originX(slot);
        for (Entity e : matches.getNearbyEntities(new Location(matches, ox + (area.x1() + area.x2()) / 2.0, oy + (area.y1() + area.y2()) / 2.0, (area.z1() + area.z2()) / 2.0),
                area.sizeX() / 2.0 + 8, area.sizeY() / 2.0 + 64, area.sizeZ() / 2.0 + 8))
            if (!(e instanceof Player)) e.remove();
        clearRegion(matches, ox, oy, 0, area, done);
    }

    /** Find non-air blocks with snapshots off-thread, then set them to air a batch per tick. */
    private void clearRegion(World w, int ox, int oy, int oz, Box area, Runnable done) {
        int bx1 = ox + area.x1(), bx2 = ox + area.x2(), bz1 = oz + area.z1(), bz2 = oz + area.z2();
        int by1 = Math.max(w.getMinHeight(), oy + area.y1()), by2 = Math.min(w.getMaxHeight() - 1, oy + area.y2() + 32);
        List<ChunkSnapshot> snaps = new ArrayList<>();
        for (int cx = bx1 >> 4; cx <= bx2 >> 4; cx++)
            for (int cz = bz1 >> 4; cz <= bz2 >> 4; cz++)
                snaps.add(w.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<int[]> solid = new ArrayList<>();
            for (ChunkSnapshot s : snaps) {
                int cx0 = s.getX() << 4, cz0 = s.getZ() << 4;
                for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
                    int x = cx0 + lx, z = cz0 + lz;
                    if (x < bx1 || x > bx2 || z < bz1 || z > bz2) continue;
                    for (int y = by1; y <= by2; y++) if (!s.getBlockType(lx, y, lz).isAir()) solid.add(new int[]{x, y, z});
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (solid.isEmpty()) { done.run(); return; }
                Iterator<int[]> it = solid.iterator();
                BlockData air = Bukkit.createBlockData(Material.AIR);
                Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                    for (int n = 0; n < blocksPerTick && it.hasNext(); n++) { int[] b = it.next(); w.getBlockAt(b[0], b[1], b[2]).setBlockData(air, false); }
                    if (!it.hasNext()) { task.cancel(); done.run(); }
                }, 1L, 1L);
            });
        });
    }
}
