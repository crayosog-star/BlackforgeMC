package net.blackforge.core.abyss;

import net.blackforge.core.build.BlockFont;
import net.blackforge.core.build.Canvas;
import org.bukkit.entity.EntityType;
import org.bukkit.loot.LootTables;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static net.blackforge.core.abyss.AbyssGenerator.SEA;
import static net.blackforge.core.abyss.AbyssGenerator.floor;

/**
 * Every structure of the Abyssal Sea, planned from the world seed and a grid cell so a structure is the same whichever chunk
 * asks for it: shipwrecks, sunken ruins, drowned watchtowers, lighthouses on rock islets, bone islands, and Atlantis (one per
 * 512-block cell, about as rare as a sea temple), plus the dock at the origin. Blocks are bucketed per chunk for the populator.
 */
public final class AbyssStructures {
    private AbyssStructures() {}

    public record Block(int x, int y, int z, String data) {}
    public record Chest(int x, int y, int z, LootTables table) {}
    public record Spawn(int x, int y, int z, EntityType type) {}

    public static final int SMALL_CELL = 128, ATLANTIS_CELL = 512, ATLANTIS_R = 66, DOCK_R = 46;

    public static final class Instance {
        final String kind;
        public final int cx, cz, radius;
        final Canvas canvas = new Canvas();
        final List<Chest> chests = new ArrayList<>();
        final List<Spawn> spawns = new ArrayList<>();
        private Map<Long, List<Block>> blocks;
        private Map<Long, List<Chest>> chestMap;
        private Map<Long, List<Spawn>> spawnMap;

        Instance(String kind, int cx, int cz, int radius) { this.kind = kind; this.cx = cx; this.cz = cz; this.radius = radius; }

        public String kind() { return kind; }

        private static long ck(int x, int z) { return ((long) Math.floorDiv(x, 16) << 32) ^ (Math.floorDiv(z, 16) & 0xffffffffL); }

        private synchronized void bucket() {
            if (blocks != null) return;
            Map<Long, List<Block>> m = new HashMap<>();
            canvas.blocks().forEach((k, v) -> m.computeIfAbsent(ck(Canvas.kx(k), Canvas.kz(k)), q -> new ArrayList<>()).add(new Block(Canvas.kx(k), Canvas.ky(k), Canvas.kz(k), v)));
            canvas.lateBlocks().forEach((k, v) -> m.computeIfAbsent(ck(Canvas.kx(k), Canvas.kz(k)), q -> new ArrayList<>()).add(new Block(Canvas.kx(k), Canvas.ky(k), Canvas.kz(k), v)));
            Map<Long, List<Chest>> cm = new HashMap<>();
            for (Chest c : chests) cm.computeIfAbsent(ck(c.x(), c.z()), q -> new ArrayList<>()).add(c);
            Map<Long, List<Spawn>> sm = new HashMap<>();
            for (Spawn s : spawns) sm.computeIfAbsent(ck(s.x(), s.z()), q -> new ArrayList<>()).add(s);
            chestMap = cm; spawnMap = sm; blocks = m;
        }

        List<Block> blocksIn(int chunkX, int chunkZ) { bucket(); return blocks.getOrDefault(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL), List.of()); }
        List<Chest> chestsIn(int chunkX, int chunkZ) { bucket(); return chestMap.getOrDefault(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL), List.of()); }
        List<Spawn> spawnsIn(int chunkX, int chunkZ) { bucket(); return spawnMap.getOrDefault(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL), List.of()); }
    }

    private static final Map<String, Instance> CACHE = new ConcurrentHashMap<>();

    private static long mix(long seed, long a, long b, long salt) {
        long h = seed * 0x9E3779B97F4A7C15L ^ a * 0xC2B2AE3D27D4EB4FL ^ b * 0x165667B19E3779F9L ^ salt * 0x27D4EB2F165667C5L;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return h;
    }

    /** Where the Atlantis of a 512-block cell is, or its centre only (cheap, for keeping small structures away). */
    public static int[] atlantisAt(long seed, int ax, int az) {
        Random r = new Random(mix(seed, ax, az, 7));
        return new int[]{ax * ATLANTIS_CELL + 100 + r.nextInt(ATLANTIS_CELL - 200), az * ATLANTIS_CELL + 100 + r.nextInt(ATLANTIS_CELL - 200)};
    }

    /** Nearest Atlantis to a point (searching the surrounding cells). */
    public static int[] nearestAtlantis(long seed, int x, int z) {
        int ax = Math.floorDiv(x, ATLANTIS_CELL), az = Math.floorDiv(z, ATLANTIS_CELL);
        int[] best = null; double bd = Double.MAX_VALUE;
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            int[] p = atlantisAt(seed, ax + i, az + j);
            double d = Math.hypot(p[0] - x, p[1] - z);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Instances that may touch the chunk whose corner is (x0, z0). */
    static List<Instance> touching(long seed, int x0, int z0) {
        List<Instance> out = new ArrayList<>();
        if (Math.abs(x0 + 8) < DOCK_R + 16 && Math.abs(z0 + 8) < DOCK_R + 16) out.add(CACHE.computeIfAbsent("dock", k -> dock()));
        int ax = Math.floorDiv(x0, ATLANTIS_CELL), az = Math.floorDiv(z0, ATLANTIS_CELL);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            int[] p = atlantisAt(seed, ax + i, az + j);
            if (Math.abs(p[0] - (x0 + 8)) < ATLANTIS_R + 24 && Math.abs(p[1] - (z0 + 8)) < ATLANTIS_R + 24)
                out.add(cached("A" + seed + "_" + (ax + i) + "_" + (az + j), () -> atlantis(seed, p[0], p[1])));
        }
        int sx = Math.floorDiv(x0, SMALL_CELL), sz = Math.floorDiv(z0, SMALL_CELL);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            int cellX = sx + i, cellZ = sz + j;
            Instance in = cached("S" + seed + "_" + cellX + "_" + cellZ, () -> small(seed, cellX, cellZ));
            if (in != null && Math.abs(in.cx - (x0 + 8)) < in.radius + 24 && Math.abs(in.cz - (z0 + 8)) < in.radius + 24) out.add(in);
        }
        return out;
    }

    private static Instance cached(String key, java.util.function.Supplier<Instance> make) {
        if (CACHE.size() > 160) CACHE.keySet().removeIf(k -> !k.equals("dock"));
        Instance in = CACHE.get(key);
        if (in == null && !CACHE.containsKey(key)) {
            in = make.get();
            if (in != null) { CACHE.put(key, in); org.bukkit.Bukkit.getLogger().info("[Abyss] " + in.kind() + " at " + in.cx + ", " + in.cz); }
        }
        return in;
    }

    // ------------------------------------------------------------------ planning

    private static Instance small(long seed, int cellX, int cellZ) {
        Random r = new Random(mix(seed, cellX, cellZ, 1));
        int x = cellX * SMALL_CELL + 24 + r.nextInt(SMALL_CELL - 48), z = cellZ * SMALL_CELL + 24 + r.nextInt(SMALL_CELL - 48);
        if (Math.hypot(x, z) < 170) return null;
        int[] at = nearestAtlantis(seed, x, z);
        if (Math.hypot(at[0] - x, at[1] - z) < ATLANTIS_R + 50) return null;
        int roll = r.nextInt(100);
        if (roll < 30) return shipwreck(r, x, z);
        if (roll < 56) return sunkenRuin(r, x, z);
        if (roll < 68) return tower(r, x, z);
        if (roll < 78) return lighthouse(r, x, z);
        if (roll < 86) return boneIsland(r, x, z);
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static void set(Instance in, int x, int y, int z, String b) { in.canvas.set(x, y, z, b.startsWith("minecraft:") ? b : "minecraft:" + b); }
    private static void late(Instance in, int x, int y, int z, String b) { in.canvas.setLate(x, y, z, b.startsWith("minecraft:") ? b : "minecraft:" + b); }
    private static String wl(int y, String b) {
        if (y >= SEA) return b;
        return b.endsWith("]") ? b.substring(0, b.length() - 1) + ",waterlogged=true]" : b + "[waterlogged=true]";
    }

    private static void chest(Instance in, int x, int y, int z, String facing, LootTables t) {
        set(in, x, y, z, "chest[facing=" + facing + (y < SEA ? ",waterlogged=true" : "") + "]");
        in.chests.add(new Chest(x, y, z, t));
    }

    private static final String[] RUIN = {"deepslate_bricks", "cracked_deepslate_bricks", "mossy_stone_bricks", "deepslate_bricks", "prismarine_bricks", "polished_deepslate"};
    private static String ruin(Random r) { return RUIN[r.nextInt(RUIN.length)]; }

    private static void column(Instance in, Random r, int x, int z, int baseY, int h, boolean broken) {
        int top = baseY + h - (broken ? r.nextInt(3) : 0);
        for (int y = baseY; y <= top; y++) set(in, x, y, z, y == top && broken && r.nextBoolean() ? "air" : (y == baseY || y == top ? "chiseled_deepslate" : ruin(r)));
    }

    private static void rock(Instance in, Random r, int cx, int cz, int rad, int topY) {
        for (int x = -rad; x <= rad; x++) for (int z = -rad; z <= rad; z++) {
            double d = Math.hypot(x, z);
            if (d > rad) continue;
            int top = topY - (int) Math.round(d / rad * 5) + r.nextInt(2);
            int f = floor(cx + x, cz + z);
            for (int y = Math.min(f, top - 3); y <= top; y++) set(in, cx + x, y, cz + z, y == top ? (r.nextInt(3) == 0 ? "gravel" : "blackstone") : (r.nextInt(5) == 0 ? "deepslate" : "blackstone"));
        }
    }

    // ------------------------------------------------------------------ the dock

    private static Instance dock() {
        Instance in = new Instance("dock", 0, 0, DOCK_R);
        Random r = new Random(11);
        // the pier: planks flush with the sea, posts down to the floor, lanterns, a wide landing at the end
        for (int x = 8; x <= 39; x++) for (int z = -2; z <= 2; z++) set(in, x, SEA, z, x % 4 == 0 ? "dark_oak_planks" : "spruce_planks");
        for (int x = 40; x <= 46; x++) for (int z = -5; z <= 5; z++) set(in, x, SEA, z, (x + z) % 3 == 0 ? "dark_oak_planks" : "spruce_planks");
        for (int x = 10; x <= 46; x += 4) for (int z : new int[]{-2, 2}) {
            int f = floor(x, z);
            for (int y = f; y < SEA; y++) set(in, x, y, z, "dark_oak_log");
            set(in, x, SEA + 1, z, "dark_oak_fence"); late(in, x, SEA + 2, z, "lantern");
        }
        for (int z : new int[]{-5, 5}) for (int x = 40; x <= 46; x += 6) { int f = floor(x, z); for (int y = f; y < SEA; y++) set(in, x, y, z, "dark_oak_log"); set(in, x, SEA + 1, z, "dark_oak_fence"); late(in, x, SEA + 2, z, "soul_lantern"); }
        // a stone hut on the islet with a hearth, and a sign of warning in burning letters
        for (int x = -5; x <= 5; x++) for (int z = -4; z <= 4; z++) {
            boolean edge = Math.abs(x) == 5 || Math.abs(z) == 4;
            for (int y = SEA + 1; y <= SEA + 5; y++) set(in, x, y, z, edge ? (y == SEA + 5 ? "deepslate_tiles" : "deepslate_bricks") : "air");
            set(in, x, SEA, z, "spruce_planks"); set(in, x, SEA + 6, z, "deepslate_tiles");
        }
        for (int y = SEA + 1; y <= SEA + 3; y++) set(in, 5, y, 0, "air");
        late(in, -3, SEA + 1, 0, "campfire[lit=true]"); late(in, 3, SEA + 4, -3, "lantern[hanging=true]");
        set(in, 4, SEA + 1, 3, "barrel[facing=up]"); set(in, 3, SEA + 1, 3, "barrel[facing=up]");
        String text = "TURN BACK";
        int w = BlockFont.width(text, 1);
        BlockFont.draw(text, 1, (col, row, gr) -> { int x = 44, z = -w / 2 + col; set(in, x, SEA + 12 + row, z, "shroomlight"); for (int q = SEA + 2; q <= SEA + 11; q++) if (col == 0 || col == w - 1) set(in, x, q, z, "dark_oak_fence"); });
        return in;
    }

    // ------------------------------------------------------------------ shipwreck

    private static Instance shipwreck(Random r, int cx, int cz) {
        Instance in = new Instance("shipwreck", cx, cz, 22);
        double a = r.nextDouble() * Math.PI * 2, cs = Math.cos(a), sn = Math.sin(a);
        boolean sunk = r.nextInt(100) < 40;
        int f = floor(cx, cz);
        int base = sunk ? Math.max(f + 2, 20) : SEA - 5;
        String plank = r.nextBoolean() ? "dark_oak_planks" : "spruce_planks", log = r.nextBoolean() ? "stripped_dark_oak_log" : "stripped_spruce_log";
        int L = 13, depth = 5;
        for (int u = -L; u <= L; u++) {
            double hw = 5.2 * Math.sqrt(Math.max(0, 1 - Math.pow(u / (double) (L + 1), 2))) + 0.8;
            for (int v = (int) -Math.ceil(hw); v <= Math.ceil(hw); v++) {
                if (Math.abs(v) > hw) continue;
                int x = cx + (int) Math.round(u * cs - v * sn), z = cz + (int) Math.round(u * sn + v * cs);
                int rise = u > 4 ? (u - 4) / 3 : 0;                       // the bow lifts
                for (int dy = 0; dy <= depth + rise; dy++) {
                    boolean hull = dy == 0 || Math.abs(v) >= hw - 1;
                    if (!hull) continue;
                    if (r.nextInt(100) < (dy > 2 ? 18 : 7)) continue;     // rotten through
                    set(in, x, base + dy, z, dy == 0 ? log : (u % 4 == 0 ? log : plank));
                }
                if (r.nextInt(100) < 70) set(in, x, base + 1, z, plank);        // deck boards
            }
        }
        for (int[] m : new int[][]{{0, 12}, {7, 9}}) {                    // broken masts
            int h = m[1] - r.nextInt(5);
            for (int y = 1; y <= h; y++) { int x = cx + (int) Math.round(m[0] * cs), z = cz + (int) Math.round(m[0] * sn); set(in, x, base + y, z, y % 4 == 0 ? "dark_oak_log" : "oak_log"); }
        }
        int sx = cx + (int) Math.round(-9 * cs), sz = cz + (int) Math.round(-9 * sn);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (Math.abs(dx) == 2 || Math.abs(dz) == 2) for (int y = 2; y <= 4; y++) if (r.nextInt(5) != 0) set(in, sx + dx, base + y, sz + dz, plank);
        chest(in, sx, base + 2, sz, "north", LootTables.SHIPWRECK_TREASURE);
        chest(in, sx + 1, base + 2, sz, "north", LootTables.SHIPWRECK_SUPPLY);
        set(in, sx, base + 2, sz + 1, wl(base + 2, "barrel[facing=up]"));
        late(in, sx - 1, base + 3, sz, wl(base + 3, "soul_lantern"));
        if (sunk && r.nextBoolean()) in.spawns.add(new Spawn(cx, base + 6, cz, EntityType.GUARDIAN));
        return in;
    }

    // ------------------------------------------------------------------ sunken ruin

    private static Instance sunkenRuin(Random r, int cx, int cz) {
        Instance in = new Instance("ruin", cx, cz, 24);
        int f = floor(cx, cz);
        for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) if (Math.hypot(x, z) < 9.4 && r.nextInt(10) < 8) set(in, cx + x, floor(cx + x, cz + z), cz + z, (x + z) % 3 == 0 ? "deepslate_tiles" : "polished_deepslate");
        for (int i = 0; i < 12; i++) {
            double ang = r.nextDouble() * Math.PI * 2, rad = 4 + r.nextDouble() * 13;
            int x = cx + (int) Math.round(Math.cos(ang) * rad), z = cz + (int) Math.round(Math.sin(ang) * rad);
            column(in, r, x, z, floor(x, z) + 1, 3 + r.nextInt(8), true);
        }
        int ax = cx - 5;                                                   // a broken arch
        for (int y = 1; y <= 9; y++) { set(in, ax, floor(ax, cz) + y, cz - 4, "deepslate_bricks"); set(in, ax, floor(ax, cz) + y, cz + 4, y > 7 ? "air" : "deepslate_bricks"); }
        for (int z = -4; z <= 1; z++) set(in, ax, floor(ax, cz) + 9, cz + z, "chiseled_deepslate");
        chest(in, cx, f + 1, cz, "south", LootTables.UNDERWATER_RUIN_BIG);
        chest(in, cx + 6, floor(cx + 6, cz + 5) + 1, cz + 5, "west", LootTables.UNDERWATER_RUIN_SMALL);
        for (int i = 0; i < 5; i++) { int x = cx + r.nextInt(17) - 8, z = cz + r.nextInt(17) - 8; set(in, x, floor(x, z) + 1, z, "sea_lantern"); }
        in.spawns.add(new Spawn(cx + 3, f + 5, cz, EntityType.GUARDIAN));
        in.spawns.add(new Spawn(cx - 4, f + 6, cz + 3, EntityType.GUARDIAN));
        if (r.nextInt(5) == 0) in.spawns.add(new Spawn(cx, f + 8, cz - 4, EntityType.ELDER_GUARDIAN));
        return in;
    }

    // ------------------------------------------------------------------ drowned watchtower

    private static Instance tower(Random r, int cx, int cz) {
        Instance in = new Instance("tower", cx, cz, 10);
        int f = floor(cx, cz) - 2, top = SEA + 7 + r.nextInt(7), rad = 5;
        for (int y = f; y <= top; y++)
            for (int x = -rad; x <= rad; x++) for (int z = -rad; z <= rad; z++) {
                double d = Math.hypot(x, z);
                if (d > rad + 0.4) continue;
                boolean wall = d > rad - 1.3;
                int rel = y - f;
                if (!wall && rel % 9 != 8 && y != f) continue;
                if (!wall && (Math.abs(x) <= 1 && Math.abs(z) <= 1)) continue;           // a stairwell
                if (wall && y > top - 3 && r.nextInt(3) == 0) continue;                  // the broken crown
                if (wall && rel % 9 >= 4 && rel % 9 <= 6 && (Math.abs(x) <= 1 || Math.abs(z) <= 1)) continue;   // windows
                if (wall && rel < 6 && x > 0 && Math.abs(z) <= 1) continue;               // the door, under the water
                set(in, cx + x, y, cz + z, ruin(r));
            }
        chest(in, cx + 3, f + 10, cz, "west", LootTables.UNDERWATER_RUIN_BIG);
        int cy = Math.min(top - 1, SEA + 2);
        set(in, cx + 3, cy, cz + 2, "deepslate_bricks"); chest(in, cx - 3, cy + 1, cz, "east", LootTables.BURIED_TREASURE);
        late(in, cx, top + 1, cz, "soul_lantern");
        for (int i = 0; i < 3; i++) in.spawns.add(new Spawn(cx + 7, f + 4 + i * 2, cz, EntityType.GUARDIAN));
        return in;
    }

    // ------------------------------------------------------------------ lighthouse

    private static Instance lighthouse(Random r, int cx, int cz) {
        Instance in = new Instance("lighthouse", cx, cz, 14);
        rock(in, r, cx, cz, 12, SEA + 3);
        int base = SEA + 4, h = 30, rad = 4;
        for (int y = base; y <= base + h; y++)
            for (int x = -rad; x <= rad; x++) for (int z = -rad; z <= rad; z++) {
                double d = Math.hypot(x, z), rr = rad - (y - base) * 0.04;
                if (d > rr + 0.4 || d < rr - 1.2) continue;
                String b = ((y - base) / 4) % 2 == 0 ? "bone_block" : "blackstone";
                if (y - base == 1 && x > 0 && Math.abs(z) <= 1) { set(in, cx + x, y, cz + z, "air"); continue; }
                if ((y - base) % 9 == 5 && Math.abs(x) <= 1 && z > 0) b = "black_stained_glass";
                set(in, cx + x, y, cz + z, b);
            }
        for (int y = base + 1; y <= base + h - 1; y++) for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) if (Math.hypot(x, z) < rad - 1.2) set(in, cx + x, y, cz + z, "air");
        for (int y = base + 1; y <= base + h - 1; y++) set(in, cx, y, cz + 2, "ladder[facing=north]");
        set(in, cx - 2, base + 1, cz, "air");
        chest(in, cx - 2, base + 1, cz, "east", LootTables.BURIED_TREASURE);
        int top = base + h;
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) if (Math.hypot(x, z) <= 5.4) set(in, cx + x, top, cz + z, "deepslate_tiles");
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) { double d = Math.hypot(x, z); if (d > 3.2 && d <= 4.4) for (int y = top + 1; y <= top + 4; y++) set(in, cx + x, y, cz + z, y % 2 == 0 ? "glass" : "black_stained_glass"); }
        for (int dy = 1; dy <= 3; dy++) set(in, cx, top + dy, cz, dy == 2 ? "shroomlight" : "sea_lantern");
        for (int k = 0; k <= 7; k++) { double rr = 5.5 * (1 - k / 8.0); for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) { double d = Math.hypot(x, z); if (d <= rr && d > rr - 1.4) set(in, cx + x, top + 5 + k, cz + z, "deepslate_tiles"); } }
        late(in, cx, top + 13, cz, "lightning_rod");
        return in;
    }

    // ------------------------------------------------------------------ bone island (the serpent's victims)

    private static Instance boneIsland(Random r, int cx, int cz) {
        Instance in = new Instance("bones", cx, cz, 20);
        rock(in, r, cx, cz, 15, SEA + 4);
        for (int arch = -1; arch <= 1; arch++) {
            int ax = cx + arch * 7;
            for (int a = 0; a <= 180; a += 6) {
                double rad = Math.toRadians(a);
                int z = cz + (int) Math.round(Math.cos(rad) * 8), y = SEA + 5 + (int) Math.round(Math.sin(rad) * (11 - Math.abs(arch) * 2));
                for (int t = 0; t < 2; t++) set(in, ax + t, y, z, a % 12 == 0 ? "calcite" : "bone_block");
            }
        }
        int sy = SEA + 6;                                                   // the skull, on its side
        for (int dx = -5; dx <= 5; dx++) for (int dy = 0; dy <= 7; dy++) for (int dz = -4; dz <= 4; dz++) {
            double d = (dx * dx) / 30.0 + Math.pow(dy - 3.5, 2) / 14.0 + (dz * dz) / 18.0;
            if (d > 1 || d < 0.45) continue;
            set(in, cx + dx - 14, sy + dy - 4, cz + dz + 8, (dx + dy) % 4 == 0 ? "calcite" : "bone_block");
        }
        for (int s : new int[]{-2, 2}) { set(in, cx - 14 + 3, sy + 1, cz + 8 + s, "sea_lantern"); }
        chest(in, cx - 4, SEA + 5, cz - 3, "south", LootTables.ANCIENT_CITY);
        for (int i = 0; i < 20; i++) { int x = cx - 12 + r.nextInt(25), z = cz - 12 + r.nextInt(25); if (Math.hypot(x - cx, z - cz) < 14) set(in, x, SEA + 5, z, "bone_block"); }
        return in;
    }

    // ------------------------------------------------------------------ Atlantis

    private static Instance atlantis(long seed, int cx, int cz) {
        Instance in = new Instance("atlantis", cx, cz, ATLANTIS_R);
        Random r = new Random(mix(seed, cx, cz, 99));
        int sum = 0, n = 0;
        for (int x = -50; x <= 50; x += 10) for (int z = -50; z <= 50; z += 10) { sum += floor(cx + x, cz + z); n++; }
        int y0 = Math.max(22, Math.min(46, sum / n));
        // the plateau, with a skirt down to the sea floor round the edge
        for (int x = -62; x <= 62; x++) for (int z = -62; z <= 62; z++) {
            double d = Math.hypot(x, z);
            if (d > 62) continue;
            int f = floor(cx + x, cz + z);
            String top = d < 22 ? ((x + z) % 4 == 0 ? "gold_block" : "dark_prismarine") : ((Math.floorDiv(x, 3) + Math.floorDiv(z, 3)) & 1) == 0 ? "prismarine_bricks" : "dark_prismarine";
            set(in, cx + x, y0, cz + z, top);
            if (f < y0 && d > 58) for (int y = f; y < y0; y++) set(in, cx + x, y, cz + z, "prismarine_bricks");
            if (f > y0) for (int y = y0 + 1; y <= f; y++) set(in, cx + x, y, cz + z, "water");                // dig out anything in the way
        }
        // avenues: gold inlay out from the temple to eight gates
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            for (double d = 20; d <= 58; d += 0.5) for (int w = -1; w <= 1; w++) {
                int x = cx + (int) Math.round(Math.cos(a) * d - Math.sin(a) * w), z = cz + (int) Math.round(Math.sin(a) * d + Math.cos(a) * w);
                set(in, x, y0, z, w == 0 ? "gold_block" : "polished_blackstone");
            }
            for (double d = 24; d <= 52; d += 7) for (int s : new int[]{-3, 3}) {
                int x = cx + (int) Math.round(Math.cos(a) * d - Math.sin(a) * s), z = cz + (int) Math.round(Math.sin(a) * d + Math.cos(a) * s);
                for (int y = 1; y <= 8; y++) set(in, x, y0 + y, z, y == 8 ? "gold_block" : y == 1 ? "chiseled_stone_bricks" : "prismarine_bricks");
                set(in, x, y0 + 9, z, "sea_lantern");
            }
        }
        // the outer wall with eight gate towers
        for (int x = -60; x <= 60; x++) for (int z = -60; z <= 60; z++) {
            double d = Math.hypot(x, z);
            if (d < 56 || d > 58.5) continue;
            double dev = ((Math.atan2(z, x) % (Math.PI / 4)) + Math.PI / 4) % (Math.PI / 4), gate = Math.min(dev, Math.PI / 4 - dev);
            boolean gap = gate < 0.07;
            for (int y = 1; y <= 10; y++) { if (gap && y <= 8) continue; set(in, cx + x, y0 + y, cz + z, y == 10 ? "gold_block" : r.nextInt(14) == 0 ? "sea_lantern" : y % 4 == 0 ? "dark_prismarine" : "prismarine_bricks"); }
        }
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4 + Math.PI / 8;
            int tx = cx + (int) Math.round(Math.cos(a) * 57), tz = cz + (int) Math.round(Math.sin(a) * 57);
            for (int y = 1; y <= 26; y++) for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 3.4 || (d < 2.2 && y < 26)) continue;
                set(in, tx + dx, y0 + y, tz + dz, y == 26 ? "gold_block" : y % 6 == 0 ? "gold_block" : "prismarine_bricks");
            }
            for (int y = 27; y <= 31; y++) set(in, tx, y0 + y, tz, y == 31 ? "sea_lantern" : "gold_block");
            in.spawns.add(new Spawn(tx, y0 + 12, tz, EntityType.GUARDIAN));
        }
        // domes: small houses, shell only, each with a door and a chest in some
        for (int i = 0; i < 14; i++) {
            double ang = r.nextDouble() * Math.PI * 2, rad = 24 + r.nextDouble() * 28;
            int hx = cx + (int) Math.round(Math.cos(ang) * rad), hz = cz + (int) Math.round(Math.sin(ang) * rad);
            if (Math.abs(Math.sin(ang * 4)) * rad < 5) continue;                                  // keep the avenues clear
            int hr = 4;
            for (int dx = -hr; dx <= hr; dx++) for (int dy = 0; dy <= hr; dy++) for (int dz = -hr; dz <= hr; dz++) {
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > hr + 0.4 || d < hr - 1) continue;
                if (dy <= 2 && dz == hr && Math.abs(dx) <= 1) continue;
                set(in, hx + dx, y0 + 1 + dy, hz + dz, d > hr - 0.2 && (dx + dy + dz) % 3 == 0 ? "sea_lantern" : "prismarine_bricks");
            }
            if (i % 2 == 0) chest(in, hx, y0 + 1, hz, "south", LootTables.UNDERWATER_RUIN_SMALL);
            set(in, hx + 2, y0 + 1, hz - 2, "gold_block");
        }
        temple(in, r, cx, cz, y0);
        for (int i = 0; i < 6; i++) { double ang = i * Math.PI / 3; in.spawns.add(new Spawn(cx + (int) Math.round(Math.cos(ang) * 30), y0 + 6, cz + (int) Math.round(Math.sin(ang) * 30), EntityType.GUARDIAN)); }
        return in;
    }

    private static void temple(Instance in, Random r, int cx, int cz, int y0) {
        // stepped base, a great hall with a gold dome, and the vault under it
        for (int step = 0; step < 4; step++) for (int x = -20 + step * 2; x <= 20 - step * 2; x++) for (int z = -20 + step * 2; z <= 20 - step * 2; z++) if (Math.hypot(x, z) <= 21 - step * 2) set(in, cx + x, y0 + 1 + step, cz + z, step % 2 == 0 ? "dark_prismarine" : "gold_block");
        int fy = y0 + 5;
        for (int x = -13; x <= 13; x++) for (int z = -13; z <= 13; z++) {
            double d = Math.hypot(x, z);
            if (d > 13.4) continue;
            set(in, cx + x, fy, cz + z, ((x + z) & 1) == 0 ? "gold_block" : "polished_blackstone");
            for (int y = 1; y <= 14; y++) {
                if (d < 11.6 && y < 14) { set(in, cx + x, fy + y, cz + z, "water"); continue; }
                if (d < 11.6) continue;
                if (y <= 8 && z > 0 && Math.abs(x) <= 2) continue;                                   // the great door
                set(in, cx + x, fy + y, cz + z, y % 5 == 0 ? "gold_block" : r.nextInt(18) == 0 ? "sea_lantern" : "prismarine_bricks");
            }
        }
        for (int k = 0; k <= 12; k++) { double rr = 12.5 * Math.cos(k / 12.0 * Math.PI / 2); for (int x = -13; x <= 13; x++) for (int z = -13; z <= 13; z++) { double d = Math.hypot(x, z); if (d <= rr && d > rr - 1.5) set(in, cx + x, fy + 15 + k, cz + z, k % 4 == 3 ? "gold_block" : "prismarine_bricks"); } }
        for (int y = 28; y <= 34; y++) set(in, cx, fy + y - 12, cz, y > 32 ? "sea_lantern" : "gold_block");
        for (int k = 0; k < 8; k++) { double a = k * Math.PI / 4; int px = cx + (int) Math.round(Math.cos(a) * 8), pz = cz + (int) Math.round(Math.sin(a) * 8); for (int y = 1; y <= 13; y++) set(in, px, fy + y, pz, y % 4 == 0 ? "gold_block" : "chiseled_stone_bricks"); }
        // the vault: a gold cube in the middle, a netherite-grade treasure inside, guarded by elders
        for (int x = -3; x <= 3; x++) for (int y = 1; y <= 7; y++) for (int z = -3; z <= 3; z++) {
            boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3 || y == 1 || y == 7;
            if (edge && !(z == 3 && Math.abs(x) <= 1 && y <= 4)) set(in, cx + x, fy + y, cz + z, (x + y + z) % 3 == 0 ? "gold_block" : "dark_prismarine");
        }
        chest(in, cx, fy + 2, cz, "south", LootTables.BASTION_TREASURE);
        chest(in, cx - 1, fy + 2, cz - 1, "south", LootTables.END_CITY_TREASURE);
        chest(in, cx + 1, fy + 2, cz - 1, "south", LootTables.END_CITY_TREASURE);
        chest(in, cx + 6, fy + 1, cz + 6, "north", LootTables.ANCIENT_CITY);
        chest(in, cx - 6, fy + 1, cz + 6, "north", LootTables.UNDERWATER_RUIN_BIG);
        for (int i = 0; i < 4; i++) { double a = i * Math.PI / 2 + Math.PI / 4; in.spawns.add(new Spawn(cx + (int) Math.round(Math.cos(a) * 8), fy + 4, cz + (int) Math.round(Math.sin(a) * 8), EntityType.ELDER_GUARDIAN)); }
    }
}
