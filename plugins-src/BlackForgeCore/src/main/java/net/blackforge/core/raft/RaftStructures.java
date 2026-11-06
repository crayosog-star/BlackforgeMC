package net.blackforge.core.raft;

import net.blackforge.core.build.BlockFont;
import net.blackforge.core.build.Canvas;
import net.blackforge.core.survival.SkyHubBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything solid in the Raft ocean, planned from the world seed so any chunk can ask for it: small palm islands, jungle islands
 * with crops and ore, half-sunk wrecks, and the story: three radio towers and Utopia, each a fixed distance from a team's raft origin
 * in a direction that is the same for that team's ocean (a team's ocean is a 20,000 block wide cell, see {@link #cellOf}).
 */
public final class RaftStructures {
    private RaftStructures() {}

    public static final int SEA = 63, CELL = 20000, GRID = 150;
    /** Distance of radio tower 1, 2, 3 and of Utopia from the raft origin. */
    public static final int[] STORY_DIST = {1500, 3200, 5200, 7500};
    public static final int[] STORY_RADIUS = {16, 16, 16, 78};
    public static final int TOWER_TOP = SEA + 32;

    public record Block(int x, int y, int z, String data) {}
    public record Chest(int x, int y, int z, String kind) {}

    public static int cellOf(int x) { return Math.floorDiv(x + CELL / 2, CELL); }
    public static int originX(int cell) { return cell * CELL; }

    /** y of the sea floor at x, z: a sandy, gently rolling bottom 15 to 40 blocks down. */
    public static int floor(int x, int z) {
        double base = SEA - 16 - 20 * SkyHubBuilder.fbm(x * 0.005, z * 0.005, 201) - 5 * SkyHubBuilder.fbm(x * 0.03, z * 0.03, 202);
        return (int) Math.round(base);
    }

    public static final class Instance {
        final String kind;
        public final int cx, cz, radius;
        final Canvas canvas = new Canvas();
        final List<Chest> chests = new ArrayList<>();
        private Map<Long, List<Block>> blocks;
        private Map<Long, List<Chest>> chestMap;

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
            chestMap = cm; blocks = m;
        }

        List<Block> blocksIn(int chunkX, int chunkZ) { bucket(); return blocks.getOrDefault(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL), List.of()); }
        List<Chest> chestsIn(int chunkX, int chunkZ) { bucket(); return chestMap.getOrDefault(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL), List.of()); }
    }

    private static final Map<String, Instance> CACHE = new ConcurrentHashMap<>();

    private static long mix(long seed, long a, long b, long salt) {
        long h = seed * 0x9E3779B97F4A7C15L ^ a * 0xC2B2AE3D27D4EB4FL ^ b * 0x165667B19E3779F9L ^ salt * 0x27D4EB2F165667C5L;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return h;
    }

    // ------------------------------------------------------------------ the story map of a team's ocean

    /** x, z of radio tower k (0..2) or of Utopia (3) in the ocean cell of {@code cell}. */
    public static int[] storyPos(long seed, int cell, int k) {
        double a = new Random(mix(seed, cell, 0, 31)).nextDouble() * Math.PI * 2;
        for (int i = 1; i <= k; i++) a += (new Random(mix(seed, cell, i, 32)).nextDouble() - 0.5) * 1.3;
        return new int[]{originX(cell) + (int) Math.round(Math.cos(a) * STORY_DIST[k]), (int) Math.round(Math.sin(a) * STORY_DIST[k])};
    }

    /** The first island a new raft can sail to, about 220 blocks away. */
    public static int[] starterPos(long seed, int cell) {
        double a = new Random(mix(seed, cell, 0, 33)).nextDouble() * Math.PI * 2;
        return new int[]{originX(cell) + (int) Math.round(Math.cos(a) * 230), (int) Math.round(Math.sin(a) * 230)};
    }

    /** Where the radio of a tower (k 0..2) or Utopia's beacon (3) is, a jukebox: story position, top of the structure. */
    public static int[] radioAt(long seed, int cell, int k) {
        int[] p = storyPos(seed, cell, k);
        return k < 3 ? new int[]{p[0] + 2, TOWER_TOP, p[1]} : new int[]{p[0], SEA + 8, p[1]};
    }

    /** Instances that may touch the chunk whose corner is (x0, z0). */
    static List<Instance> touching(long seed, int x0, int z0) {
        List<Instance> out = new ArrayList<>();
        int cell = cellOf(x0 + 8), ox = originX(cell);
        for (int k = 0; k < 4; k++) {
            int[] p = storyPos(seed, cell, k);
            if (Math.abs(p[0] - (x0 + 8)) < STORY_RADIUS[k] + 24 && Math.abs(p[1] - (z0 + 8)) < STORY_RADIUS[k] + 24) {
                int kk = k;
                out.add(cached("T" + seed + "_" + cell + "_" + k, () -> kk < 3 ? tower(seed, p[0], p[1], kk) : utopia(seed, p[0], p[1])));
            }
        }
        int[] st = starterPos(seed, cell);
        if (Math.abs(st[0] - (x0 + 8)) < 40 && Math.abs(st[1] - (z0 + 8)) < 40) out.add(cached("S" + seed + "_" + cell, () -> smallIsland(new Random(mix(seed, cell, 0, 34)), st[0], st[1], 13, true)));
        int sx = Math.floorDiv(x0 - ox, GRID), sz = Math.floorDiv(z0, GRID);
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) {
            int gx = sx + i, gz = sz + j;
            Instance in = cached("G" + seed + "_" + cell + "_" + gx + "_" + gz, () -> grid(seed, cell, gx, gz));
            if (in != null && Math.abs(in.cx - (x0 + 8)) < in.radius + 24 && Math.abs(in.cz - (z0 + 8)) < in.radius + 24) out.add(in);
        }
        return out;
    }

    private static Instance cached(String key, java.util.function.Supplier<Instance> make) {
        if (CACHE.size() > 200) CACHE.clear();
        Instance in = CACHE.get(key);
        if (in == null && !CACHE.containsKey(key)) {
            in = make.get();
            if (in != null) CACHE.put(key, in);
        }
        return in;
    }

    private static Instance grid(long seed, int cell, int gx, int gz) {
        Random r = new Random(mix(seed, cell * 7919L + gx, gz, 1));
        int x = originX(cell) + gx * GRID + 30 + r.nextInt(GRID - 60), z = gz * GRID + 30 + r.nextInt(GRID - 60);
        if (Math.hypot(x - originX(cell), z) < 130) return null;
        for (int k = 0; k < 4; k++) { int[] p = storyPos(seed, cell, k); if (Math.hypot(p[0] - x, p[1] - z) < STORY_RADIUS[k] + 60) return null; }
        int[] st = starterPos(seed, cell);
        if (Math.hypot(st[0] - x, st[1] - z) < 70) return null;
        int roll = r.nextInt(100);
        if (roll < 24) return smallIsland(r, x, z, 7 + r.nextInt(8), false);
        if (roll < 34) return jungleIsland(r, x, z, 24 + r.nextInt(13));
        if (roll < 46) return wreck(r, x, z);
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private static void set(Instance in, int x, int y, int z, String b) { in.canvas.set(x, y, z, b.startsWith("minecraft:") ? b : "minecraft:" + b); }
    private static void late(Instance in, int x, int y, int z, String b) { in.canvas.setLate(x, y, z, b.startsWith("minecraft:") ? b : "minecraft:" + b); }
    private static void chest(Instance in, int x, int y, int z, String facing, String kind) {
        set(in, x, y, z, "chest[facing=" + facing + "]");
        in.chests.add(new Chest(x, y, z, kind));
    }

    private static void palm(Instance in, Random r, int x, int y, int z) {
        int h = 4 + r.nextInt(4);
        for (int i = 0; i < h; i++) set(in, x + (i > h - 3 ? 1 : 0) * (i == h - 1 ? 1 : 0), y + i, z, "jungle_log");
        int top = y + h - 1, tx = x + 1;
        for (int dx = -2; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) {
            double d = Math.hypot(dx - 0.5, dz);
            if (d > 2.7) continue;
            set(in, x + dx, top, z + dz, "jungle_leaves[persistent=true]");
            if (d < 1.6) set(in, x + dx, top + 1, z + dz, "jungle_leaves[persistent=true]");
        }
        set(in, tx, top, z, "jungle_log");
        if (r.nextInt(3) == 0) set(in, x, top - 1, z + 1, "cocoa[age=2,facing=north]");
    }

    // ------------------------------------------------------------------ islands

    private static Instance smallIsland(Random r, int cx, int cz, int rad, boolean starter) {
        Instance in = new Instance("island", cx, cz, rad + 4);
        for (int x = -rad; x <= rad; x++) for (int z = -rad; z <= rad; z++) {
            double d = Math.hypot(x, z) + (SkyHubBuilder.fbm((cx + x) * 0.2, (cz + z) * 0.2, 211) - 0.5) * 3;
            if (d > rad) continue;
            int top = SEA + (d < rad * 0.5 ? 2 : d < rad * 0.78 ? 1 : 0);
            boolean grass = d < rad * 0.52;
            for (int y = floor(cx + x, cz + z) + 1; y <= top; y++) set(in, cx + x, y, cz + z, y == top && grass ? "grass_block" : y > top - 2 && grass ? "dirt" : "sand");
        }
        int trees = 2 + rad / 4;
        for (int i = 0; i < trees; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * rad * 0.55;
            palm(in, r, cx + (int) Math.round(Math.cos(a) * d), SEA + 3, cz + (int) Math.round(Math.sin(a) * d));
        }
        for (int i = 0; i < 6; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = rad * (0.6 + r.nextDouble() * 0.2);
            int x = cx + (int) Math.round(Math.cos(a) * d), z = cz + (int) Math.round(Math.sin(a) * d);
            set(in, x, SEA + 1, z, "sugar_cane[age=" + r.nextInt(8) + "]");
        }
        chest(in, cx, SEA + 3, cz + 1, "north", starter ? "starter" : "island");
        return in;
    }

    private static Instance jungleIsland(Random r, int cx, int cz, int rad) {
        Instance in = new Instance("jungle", cx, cz, rad + 8);
        for (int x = -rad; x <= rad; x++) for (int z = -rad; z <= rad; z++) {
            double d = Math.hypot(x, z) + (SkyHubBuilder.fbm((cx + x) * 0.1, (cz + z) * 0.1, 212) - 0.5) * 6;
            if (d > rad) continue;
            int top = SEA + (int) Math.round(Math.max(0, 1 - d / rad) * 7 * (0.7 + 0.6 * SkyHubBuilder.fbm((cx + x) * 0.07, (cz + z) * 0.07, 213)));
            top = Math.max(top, SEA);
            boolean sandy = d > rad * 0.82;
            for (int y = floor(cx + x, cz + z) + 1; y <= top; y++) {
                String m = sandy ? (y > top - 2 ? "sand" : "sandstone") : y == top ? "grass_block" : y > top - 3 ? "dirt" : "stone";
                if (!sandy && y < top - 3 && r.nextInt(40) == 0) m = "iron_ore";
                if (sandy && d > rad * 0.93 && y == top && top <= SEA && r.nextInt(5) == 0) m = "clay";
                set(in, cx + x, y, cz + z, m);
            }
        }
        for (int i = 0; i < rad; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = r.nextDouble() * rad * 0.75;
            int x = cx + (int) Math.round(Math.cos(a) * d), z = cz + (int) Math.round(Math.sin(a) * d);
            int top = SEA + (int) Math.round(Math.max(0, 1 - Math.hypot(x - cx, z - cz) / rad) * 4);
            palm(in, r, x, Math.max(top, SEA) + 1, z);
        }
        String[] crops = {"wheat[age=7]", "carrots[age=7]", "potatoes[age=7]", "beetroots[age=3]"};
        for (int p = 0; p < 3; p++) {
            double a = r.nextDouble() * Math.PI * 2, d = rad * 0.3;
            int px = cx + (int) Math.round(Math.cos(a) * d), pz = cz + (int) Math.round(Math.sin(a) * d);
            int top = SEA + (int) Math.round(Math.max(0, 1 - Math.hypot(px - cx, pz - cz) / rad) * 4) + 1;
            String crop = crops[r.nextInt(crops.length)];
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) { set(in, px, top, pz, "water"); continue; }
                set(in, px + dx, top, pz + dz, "farmland[moisture=7]");
                set(in, px + dx, top + 1, pz + dz, crop);
            }
        }
        for (int i = 0; i < 8; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = rad * (0.3 + r.nextDouble() * 0.4);
            int x = cx + (int) Math.round(Math.cos(a) * d), z = cz + (int) Math.round(Math.sin(a) * d);
            int top = SEA + (int) Math.round(Math.max(0, 1 - Math.hypot(x - cx, z - cz) / rad) * 4) + 1;
            set(in, x, top, z, i % 2 == 0 ? "melon" : "pumpkin");
        }
        for (int i = 0; i < 2; i++) {
            int x = cx + r.nextInt(9) - 4, z = cz + r.nextInt(9) - 4;
            int top = SEA + (int) Math.round(Math.max(0, 1 - Math.hypot(x - cx, z - cz) / rad) * 4) + 1;
            chest(in, x, top, z, "south", "island");
        }
        return in;
    }

    // ------------------------------------------------------------------ wreck

    private static Instance wreck(Random r, int cx, int cz) {
        Instance in = new Instance("wreck", cx, cz, 22);
        double a = r.nextDouble() * Math.PI * 2, cs = Math.cos(a), sn = Math.sin(a);
        int base = SEA - 4;
        String plank = r.nextBoolean() ? "dark_oak_planks" : "spruce_planks", log = plank.startsWith("dark") ? "stripped_dark_oak_log" : "stripped_spruce_log";
        int L = 13, depth = 6;
        for (int u = -L; u <= L; u++) {
            double hw = 5.2 * Math.sqrt(Math.max(0, 1 - Math.pow(u / (double) (L + 1), 2))) + 0.8;
            for (int v = (int) -Math.ceil(hw); v <= Math.ceil(hw); v++) {
                if (Math.abs(v) > hw) continue;
                int x = cx + (int) Math.round(u * cs - v * sn), z = cz + (int) Math.round(u * sn + v * cs);
                int rise = u > 4 ? (u - 4) / 3 : 0;
                for (int dy = 0; dy <= depth + rise; dy++) {
                    boolean hull = dy == 0 || Math.abs(v) >= hw - 1;
                    if (!hull) continue;
                    if (r.nextInt(100) < (dy > 3 ? 20 : 6)) continue;
                    set(in, x, base + dy, z, dy == 0 ? log : (u % 4 == 0 ? log : plank));
                }
                if (r.nextInt(100) < 75) set(in, x, base + 2, z, plank);
            }
        }
        for (int[] m : new int[][]{{0, 12}, {7, 9}}) {
            int h = m[1] - r.nextInt(4);
            for (int y = 1; y <= h; y++) { int x = cx + (int) Math.round(m[0] * cs), z = cz + (int) Math.round(m[0] * sn); set(in, x, base + y, z, y % 4 == 0 ? "dark_oak_log" : "oak_log"); }
        }
        int sx = cx + (int) Math.round(-9 * cs), sz = cz + (int) Math.round(-9 * sn);
        chest(in, sx, base + 3, sz, "north", "wreck");
        chest(in, sx + 1, base + 3, sz, "north", "wreck");
        set(in, sx, base + 3, sz + 1, "barrel[facing=up]");
        late(in, sx - 1, base + 4, sz, "lantern");
        return in;
    }

    // ------------------------------------------------------------------ radio towers

    private static Instance tower(long seed, int cx, int cz, int k) {
        Instance in = new Instance("tower", cx, cz, 20);
        Random r = new Random(mix(seed, cx, cz, 41));
        int rad = 12;
        for (int x = -rad; x <= rad; x++) for (int z = -rad; z <= rad; z++) {
            double d = Math.hypot(x, z) + (SkyHubBuilder.fbm((cx + x) * 0.15, (cz + z) * 0.15, 214) - 0.5) * 4;
            if (d > rad) continue;
            int top = SEA + (d < rad * 0.6 ? 3 : d < rad * 0.85 ? 2 : 1);
            for (int y = floor(cx + x, cz + z) + 1; y <= top; y++) set(in, cx + x, y, cz + z, y == top && d < rad * 0.9 ? "stone_bricks" : r.nextInt(6) == 0 ? "mossy_cobblestone" : "cobblestone");
        }
        int floorY = SEA + 3;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            boolean edge = Math.abs(x) == 4 || Math.abs(z) == 4;
            for (int y = floorY + 1; y <= floorY + 6; y++) set(in, cx + x, y, cz + z, edge ? (Math.abs(x) == 4 && Math.abs(z) == 4 ? "iron_block" : y == floorY + 6 ? "iron_block" : "stone_bricks") : "air");
            set(in, cx + x, floorY, cz + z, "smooth_stone");
            set(in, cx + x, floorY + 7, cz + z, "iron_block");
        }
        for (int y = floorY + 1; y <= floorY + 3; y++) set(in, cx, y, cz + 4, "air");        // the door
        for (int y = floorY + 1; y <= floorY + 5; y++) for (int z = -1; z <= 1; z++) if (z != 0) { set(in, cx - 4, y, cz + z, "iron_bars"); set(in, cx + 4, y, cz + z, "iron_bars"); }
        chest(in, cx - 3, floorY + 1, cz - 3, "south", "tower");
        chest(in, cx + 3, floorY + 1, cz - 3, "south", "tower");
        set(in, cx - 3, floorY + 1, cz + 3, "barrel[facing=up]");
        late(in, cx, floorY + 6, cz - 3, "lantern[hanging=true]");
        late(in, cx, floorY + 6, cz + 3, "lantern[hanging=true]");
        // the mast: a pillar with a ladder on its north side, a lattice around it
        for (int y = floorY + 1; y <= TOWER_TOP - 1; y++) {
            set(in, cx, y, cz, "iron_block");
            set(in, cx, y, cz - 1, "ladder[facing=north]");
        }
        for (int y = floorY + 8; y <= TOWER_TOP - 1; y++) {
            for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) set(in, cx + c[0], y, cz + c[1], "iron_block");
            if (y % 5 == 0) for (int d = -2; d <= 2; d++) {
                set(in, cx + d, y, cz - 2, "iron_bars"); set(in, cx + d, y, cz + 2, "iron_bars");
                set(in, cx - 2, y, cz + d, "iron_bars"); set(in, cx + 2, y, cz + d, "iron_bars");
            }
        }
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) if (Math.hypot(x, z) < 3.6 && !(x == 0 && z == -1)) set(in, cx + x, TOWER_TOP - 1, cz + z, "iron_block");
        set(in, cx, TOWER_TOP, cz - 1, "ladder[facing=north]");
        for (int d = -3; d <= 3; d++) for (int s : new int[]{-3, 3}) {
            if (Math.hypot(d, s) < 3.7 && !(d == 0 && s == -3)) { set(in, cx + d, TOWER_TOP, cz + s, "iron_bars"); set(in, cx + s, TOWER_TOP, cz + d, "iron_bars"); }
        }
        set(in, cx + 2, TOWER_TOP, cz, "jukebox");                                            // the radio
        late(in, cx + 2, TOWER_TOP + 1, cz, "light[level=15]");
        for (int y = 0; y < 4; y++) set(in, cx, TOWER_TOP + y, cz, y == 3 ? "lightning_rod[facing=up]" : "iron_block");
        late(in, cx, floorY + 6, cz, "redstone_lamp[lit=true]");
        // a warning: "TOWER n" above the door
        String text = "TOWER " + (k + 1);
        int w = BlockFont.width(text, 1);
        BlockFont.draw(text, 1, (col, row, gr) -> set(in, cx - w / 2 + col, floorY + 12 + row, cz + 5, "gold_block"));
        for (int y = floorY + 8; y <= floorY + 11; y++) { set(in, cx - w / 2, y, cz + 5, "iron_block"); set(in, cx - w / 2 + w - 1, y, cz + 5, "iron_block"); }
        return in;
    }

    // ------------------------------------------------------------------ Utopia

    private static Instance utopia(long seed, int cx, int cz) {
        Instance in = new Instance("utopia", cx, cz, 78);
        Random r = new Random(mix(seed, cx, cz, 51));
        int R = 70;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            double d = Math.hypot(x, z);
            if (d > R) continue;
            if (d > 44 && d < 54) continue;                                                    // the moat stays water
            int top = d < 44 ? SEA + 2 + (d < 30 ? 1 : 0) : SEA;
            for (int y = floor(cx + x, cz + z) + 1; y <= top; y++) set(in, cx + x, y, cz + z, d < 44 && y == top ? ((x + z) % 4 == 0 ? "smooth_quartz" : "quartz_block") : d < 44 ? "stone" : "sand");
        }
        for (int x = 44; x <= 54; x++) for (int z = -2; z <= 2; z++) set(in, cx + x, SEA + 2, cz + z, "smooth_quartz");        // the bridge
        for (int x = 44; x <= 54; x += 2) for (int z : new int[]{-3, 3}) set(in, cx + x, SEA + 3, cz + z, "quartz_pillar");
        int ay = SEA + 3;
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            int px = cx + (int) Math.round(Math.cos(a) * 20), pz = cz + (int) Math.round(Math.sin(a) * 20);
            for (int y = 0; y < 14; y++) set(in, px, ay + y, pz, y == 0 || y == 13 ? "chiseled_quartz_block" : "quartz_pillar");
            late(in, px, ay + 14, pz, "sea_lantern");
        }
        for (int x = -22; x <= 22; x++) for (int z = -22; z <= 22; z++) if (Math.hypot(x, z) <= 22.5) {
            set(in, cx + x, ay + 14, cz + z, Math.hypot(x, z) > 19 ? "quartz_slab[type=bottom]" : "smooth_quartz");
            if (Math.hypot(x, z) < 17) for (int y = 0; y < 14; y++) set(in, cx + x, ay + y, cz + z, "air");
        }
        for (int y = ay; y <= ay + 3; y++) set(in, cx + 20, y, cz, "air");
        for (int y = ay + 1; y <= ay + 3; y++) for (int z = -1; z <= 1; z++) set(in, cx + 20, y, cz + z, "air");
        set(in, cx, ay, cz, "gold_block");
        set(in, cx, ay + 1, cz, "jukebox");                                                        // Utopia's beacon
        late(in, cx, ay + 2, cz, "light[level=15]");
        for (int[] c : new int[][]{{8, 8}, {-8, 8}, {8, -8}, {-8, -8}}) {
            chest(in, cx + c[0], ay, cz + c[1], "south", "utopia");
            late(in, cx + c[0], ay + 1, cz + c[1], "lantern");
        }
        String text = "UTOPIA";
        int w = BlockFont.width(text, 1);
        BlockFont.draw(text, 1, (col, row, gr) -> set(in, cx - w / 2 + col, ay + 18 + row, cz - 24, "gold_block"));
        for (int i = 0; i < 20; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 56 + r.nextDouble() * 12;
            palm(in, r, cx + (int) Math.round(Math.cos(a) * d), SEA + 1, cz + (int) Math.round(Math.sin(a) * d));
        }
        return in;
    }
}
