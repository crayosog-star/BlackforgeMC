package net.alloymc.core.hub;

import net.alloymc.core.build.Canvas;

import java.util.Random;

/**
 * The Arena: a small floating forest glade in its own world on the hub server, used as a testing ground for items.
 * A clearing with a test dummy in the middle, a mossy outcrop to jump off (mace testing), a pond, archery targets to
 * the north and an overgrown showcase wall to the west whose 5x5 grid of glow item frames holds the newest items.
 * Server-independent (a Canvas) so gradle walkcheck and gradle preview can check it offline.
 */
public final class TestArenaBuilder {
    public static final int Y = 100;                                   // clearing surface; players stand at Y+1
    public static final double SPAWN_X = 0.5, SPAWN_Y = Y + 1, SPAWN_Z = 13.5;
    public static final float SPAWN_YAW = 180f;                        // facing north, at the dummy
    public static final double DUMMY_X = 0.5, DUMMY_Y = Y + 1, DUMMY_Z = 0.5;
    public static final int VOID_Y = Y - 40;
    /** Showcase wall: frames hang on the wall's east face at x = WALL_X, in front of it at x = WALL_X + 1. */
    public static final int WALL_X = -13, GRID = 5, GRID_Y0 = Y + 2, GRID_Z0 = -2;

    private static final long SEED = 7719;
    private static final int CLEARING = 15;
    private final Canvas c = new Canvas();
    private final Random rnd = new Random(SEED);
    private final int[][] h = new int[80][80];          // terrain height above Y, offset by 40; -1 = no island

    private TestArenaBuilder() {}

    public static Canvas build() { return new TestArenaBuilder().draw(); }

    private Canvas draw() {
        heights();
        ground();
        pond();
        outcrop();
        dummyRing();
        archery();
        showcaseWall();
        fallenLog();
        trees();
        plants();
        return c;
    }

    // ------------------------------------------------------------------ terrain

    private int hAt(int x, int z) { return x < -40 || x >= 40 || z < -40 || z >= 40 ? -1 : h[x + 40][z + 40]; }

    private static double edge(int x, int z) {
        double a = Math.atan2(z, x);
        return 25 + (noise(Math.cos(a) * 3 + 5, Math.sin(a) * 3 + 5, SEED) - 0.5) * 7;
    }

    /** Flat clearing in the middle, gentle hills (at most one block per step) towards the rim. */
    private void heights() {
        for (int x = -40; x < 40; x++)
            for (int z = -40; z < 40; z++) {
                double r = Math.hypot(x, z);
                if (r > edge(x, z)) { h[x + 40][z + 40] = -1; continue; }
                double t = Math.max(0, Math.min(1, (r - CLEARING) / 7));
                int v = (int) Math.round(fbm(x, z, SEED + 3) * 5 * t * t * (3 - 2 * t));
                if (flatZone(x, z)) v = 0;
                h[x + 40][z + 40] = Math.max(0, Math.min(4, v));
            }
        // relax so neighbouring columns never differ by more than one block (no hidden two-block walls)
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int x = -39; x < 39; x++)
                for (int z = -39; z < 39; z++) {
                    int v = hAt(x, z);
                    if (v <= 0) continue;
                    int lo = Integer.MAX_VALUE;
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) { int n = hAt(x + d[0], z + d[1]); if (n >= 0) lo = Math.min(lo, n); }
                    if (lo != Integer.MAX_VALUE && v > lo + 1) { h[x + 40][z + 40] = lo + 1; changed = true; }
                }
        }
    }

    /** Kept level: the showcase forecourt and the archery lane. */
    private static boolean flatZone(int x, int z) {
        return (x >= -17 && x <= -6 && Math.abs(z) <= 7) || (Math.abs(x) <= 7 && z <= -10 && z >= -23);
    }

    private void ground() {
        for (int x = -40; x < 40; x++)
            for (int z = -40; z < 40; z++) {
                int v = hAt(x, z);
                if (v < 0) continue;
                double r = Math.hypot(x, z), e = edge(x, z);
                int top = Y + v;
                double n = fbm(x * 1.7, z * 1.7, SEED + 11);
                c.set(x, top, z, n > 0.66 ? "minecraft:moss_block" : n < 0.25 && r > CLEARING ? "minecraft:podzol" : "minecraft:grass_block");
                for (int y = top - 1; y >= Y - 3; y--) c.set(x, y, z, "minecraft:dirt");
                // tapered rocky underside, like a lifted chunk of forest floor
                int depth = (int) Math.round((e - r) * 0.9 + 2 + rnd.nextInt(2));
                for (int d = 0; d < depth; d++) {
                    int k = rnd.nextInt(10);
                    c.set(x, Y - 4 - d, z, k < 5 ? "minecraft:stone" : k < 7 ? "minecraft:andesite" : k < 9 ? "minecraft:tuff" : "minecraft:cobblestone");
                }
                if (r > e - 1.2 && rnd.nextInt(3) == 0) c.set(x, Y - 4, z, "minecraft:rooted_dirt");
            }
    }

    // ------------------------------------------------------------------ features

    private void pond() {
        int px = 11, pz = 5;
        for (int x = px - 5; x <= px + 5; x++)
            for (int z = pz - 5; z <= pz + 5; z++) {
                double d = Math.hypot((x - px) * 0.9, z - pz) + (noise(x * 0.6, z * 0.6, SEED + 21) - 0.5) * 1.2;
                if (d <= 3.4) {
                    c.set(x, Y, z, "minecraft:water");
                    c.set(x, Y - 1, z, d < 2 ? "minecraft:water" : "minecraft:clay");
                    c.set(x, Y - 2, z, "minecraft:clay");
                    if (d > 2.4 && rnd.nextInt(4) == 0) c.setLate(x, Y + 1, z, "minecraft:lily_pad");
                } else if (d <= 4.4) {
                    c.set(x, Y, z, rnd.nextBoolean() ? "minecraft:sand" : "minecraft:mud");
                }
            }
        // reeds on the shore (placed late so the water is already there)
        for (int[] s : new int[][]{{px + 4, pz - 2}, {px + 4, pz - 1}, {px + 2, pz + 4}})
            if (c.get(s[0], Y, s[1]) != null && !c.get(s[0], Y, s[1]).contains("water"))
                for (int y = 1; y <= 2; y++) c.setLate(s[0], Y + y, s[1], "minecraft:sugar_cane");
    }

    /** A mossy rock mound with one-block steps up to Y+4: jump off it to test the mace. */
    private void outcrop() {
        int ox = 9, oz = -7;
        double[] radii = {3.6, 2.7, 1.8, 0.9};
        for (int lvl = 0; lvl < radii.length; lvl++)
            for (int x = ox - 4; x <= ox + 4; x++)
                for (int z = oz - 4; z <= oz + 4; z++) {
                    double d = Math.hypot(x - ox, z - oz) + (noise(x * 0.8, z * 0.8, SEED + 31 + lvl) - 0.5) * 0.8;
                    if (d > radii[lvl]) continue;
                    int k = rnd.nextInt(6);
                    c.set(x, Y + 1 + lvl, z, k < 2 ? "minecraft:mossy_cobblestone" : k < 4 ? "minecraft:stone" : k < 5 ? "minecraft:andesite" : "minecraft:moss_block");
                }
        c.setLate(ox, Y + 5, oz, "minecraft:moss_carpet");
    }

    /** Worn dirt ring around the dummy with four lantern posts. */
    private void dummyRing() {
        for (int x = -6; x <= 6; x++)
            for (int z = -6; z <= 6; z++) {
                double d = Math.hypot(x, z);
                if (d <= 1.6) c.set(x, Y, z, "minecraft:packed_mud");
                else if (d <= 4.6 && d >= 3.0) c.set(x, Y, z, rnd.nextInt(3) == 0 ? "minecraft:coarse_dirt" : "minecraft:dirt_path");
                else if (d < 3.0) c.set(x, Y, z, rnd.nextInt(4) == 0 ? "minecraft:coarse_dirt" : "minecraft:grass_block");
            }
        // path from spawn
        for (int z = 5; z <= 14; z++)
            for (int x = -1; x <= 1; x++) c.set(x, Y, z, (x == 0 || rnd.nextInt(3) > 0) ? "minecraft:dirt_path" : "minecraft:coarse_dirt");
        c.disc(0, Y, 14, 1.6, "minecraft:mossy_stone_bricks");
        for (int[] q : new int[][]{{4, 4}, {-4, 4}, {4, -4}, {-4, -4}}) {
            c.set(q[0], Y + 1, q[1], "minecraft:spruce_fence");
            c.set(q[0], Y + 2, q[1], "minecraft:spruce_fence");
            c.setLate(q[0], Y + 3, q[1], "minecraft:lantern");
        }
    }

    /** Three targets on hay bales up the north lane, 20 blocks from the dummy. */
    private void archery() {
        for (int x : new int[]{-4, 0, 4}) {
            c.set(x, Y + 1, -20, "minecraft:hay_block");
            c.set(x, Y + 2, -20, "minecraft:target");
        }
        for (int x = -6; x <= 6; x++) c.set(x, Y, -18, rnd.nextInt(3) == 0 ? "minecraft:coarse_dirt" : "minecraft:dirt_path");   // shooting line
    }

    /** Overgrown stone wall facing east; the plugin hangs 5x5 glow item frames on its face. */
    private void showcaseWall() {
        int x0 = WALL_X;
        for (int z = -4; z <= 4; z++)
            for (int y = Y + 1; y <= Y + 8; y++)
                for (int x = x0 - 1; x <= x0; x++) {
                    boolean border = Math.abs(z) >= 3 || y == Y + 1 || y >= Y + 7;
                    String b;
                    if (Math.abs(z) == 4) b = "minecraft:stripped_dark_oak_log";               // timber posts
                    else if (!border && x == x0) b = "minecraft:polished_deepslate";           // dark panel behind the frames
                    else { int k = rnd.nextInt(5); b = k < 2 ? "minecraft:mossy_stone_bricks" : k < 4 ? "minecraft:stone_bricks" : "minecraft:cracked_stone_bricks"; }
                    c.set(x, y, z, b);
                }
        for (int z = -4; z <= 4; z++) {
            c.set(x0 + 1, Y + 8, z, "minecraft:mossy_stone_brick_stairs[facing=west,half=top]");   // overhang cornice
            for (int x = x0 - 1; x <= x0 + 1; x++)
                if (rnd.nextInt(3) > 0) c.set(x, Y + 9, z, "minecraft:azalea_leaves[persistent=true]");
        }
        c.set(x0, Y + 9, 0, "minecraft:flowering_azalea_leaves[persistent=true]");
        // vines spilling down the posts
        for (int z : new int[]{-4, 4})
            for (int y = Y + 4; y <= Y + 7; y++) if (rnd.nextInt(3) > 0) c.setLate(x0 + 1, y, z, "minecraft:vine[west=true]");
        // mossy forecourt with two soul lanterns
        for (int x = x0 + 1; x <= x0 + 5; x++)
            for (int z = -4; z <= 4; z++) c.set(x, Y, z, rnd.nextInt(3) == 0 ? "minecraft:moss_block" : "minecraft:mossy_stone_bricks");
        for (int z : new int[]{-5, 5}) {
            c.set(x0 + 2, Y + 1, z, "minecraft:mossy_cobblestone_wall");
            c.setLate(x0 + 2, Y + 2, z, "minecraft:soul_lantern");
        }
    }

    private void fallenLog() {
        for (int x = -8; x <= -5; x++) {
            c.set(x, Y + 1, 9, "minecraft:oak_log[axis=x]");
            if (rnd.nextBoolean()) c.setLate(x, Y + 2, 9, "minecraft:moss_carpet");
        }
        c.setLate(-4, Y + 1, 9, "minecraft:brown_mushroom");
    }

    private boolean keepClear(int x, int z) {
        return Math.hypot(x, z) < 13.5 || (x <= -8 && Math.abs(z) <= 9) || (Math.abs(x) <= 8 && z <= -9) || (Math.abs(x) <= 4 && z >= 8);
    }

    private void trees() {
        int placed = 0;
        for (int i = 0; i < 400 && placed < 22; i++) {
            int x = rnd.nextInt(56) - 28, z = rnd.nextInt(56) - 28;
            int v = hAt(x, z);
            if (v < 0 || keepClear(x, z) || Math.hypot(x, z) > edge(x, z) - 2.5) continue;
            String top = c.get(x, Y + v, z);
            if (top == null || !(top.contains("grass") || top.contains("podzol") || top.contains("moss"))) continue;
            boolean near = false;
            for (int dx = -3; dx <= 3 && !near; dx++) for (int dz = -3; dz <= 3 && !near; dz++) near = c.isSet(x + dx, Y + v + 4, z + dz);
            if (near) continue;
            if (rnd.nextInt(3) == 0) birch(x, Y + v, z); else oak(x, Y + v, z);
            placed++;
        }
    }

    private void oak(int x, int y, int z) {
        int hgt = 5 + rnd.nextInt(2);
        c.set(x, y, z, "minecraft:dirt");
        for (int i = 1; i <= hgt; i++) c.set(x, y + i, z, "minecraft:oak_log");
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = hgt - 2; dy <= hgt + 2; dy++) {
            double d = Math.sqrt(dx * dx + dz * dz + (dy - hgt) * (dy - hgt) * 1.6);
            if (d > 3.0 + rnd.nextDouble() * 0.3 || (dx == 0 && dz == 0 && dy <= hgt)) continue;
            if (!c.isSet(x + dx, y + dy, z + dz)) c.set(x + dx, y + dy, z + dz, "minecraft:oak_leaves[persistent=true]");
        }
    }

    private void birch(int x, int y, int z) {
        int hgt = 6 + rnd.nextInt(2);
        c.set(x, y, z, "minecraft:dirt");
        for (int i = 1; i <= hgt; i++) c.set(x, y + i, z, "minecraft:birch_log");
        for (int dy = hgt - 3; dy <= hgt + 1; dy++) {
            int r = dy >= hgt ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) == r && Math.abs(dz) == r && rnd.nextBoolean()) continue;
                if (!c.isSet(x + dx, y + dy, z + dz)) c.set(x + dx, y + dy, z + dz, "minecraft:birch_leaves[persistent=true]");
            }
        }
    }

    /** Grass, ferns, flowers and a few leafy bushes on whatever grass is left uncovered. */
    private void plants() {
        String[] flora = {"short_grass", "short_grass", "short_grass", "fern", "poppy", "dandelion", "oxeye_daisy", "cornflower", "azure_bluet", "allium"};
        for (int i = 0; i < 900; i++) {
            int x = rnd.nextInt(56) - 28, z = rnd.nextInt(56) - 28;
            int v = hAt(x, z);
            if (v < 0 || Math.hypot(x, z) < 5 || c.isSet(x, Y + v + 1, z)) continue;
            String top = c.get(x, Y + v, z);
            if (top == null || !(top.contains("grass_block") || top.contains("moss_block") || top.contains("podzol"))) continue;
            if (Math.abs(x) <= 1 && z >= 5) continue;                    // keep the spawn path clean
            if (rnd.nextInt(40) == 0 && !keepClear(x, z)) { c.set(x, Y + v + 1, z, "minecraft:oak_leaves[persistent=true]"); continue; }   // bush
            if (top.contains("podzol")) c.setLate(x, Y + v + 1, z, rnd.nextBoolean() ? "minecraft:fern" : "minecraft:brown_mushroom");
            else c.setLate(x, Y + v + 1, z, "minecraft:" + flora[rnd.nextInt(flora.length)]);
        }
        // pink petals under the birches' side of the glade
        for (int i = 0; i < 60; i++) {
            int x = rnd.nextInt(20) + 2, z = rnd.nextInt(14) + 9;
            int v = hAt(x, z);
            if (v >= 0 && !c.isSet(x, Y + v + 1, z) && "minecraft:grass_block".equals(c.get(x, Y + v, z)))
                c.setLate(x, Y + v + 1, z, "minecraft:pink_petals[flower_amount=" + (1 + rnd.nextInt(4)) + "]");
        }
    }

    // ------------------------------------------------------------------ noise (same scheme as MapKit, kept local so this stays server-free)

    private static double noise(double x, double z, long seed) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double fx = x - xi, fz = z - zi;
        double a = hash(xi, zi, seed), b = hash(xi + 1, zi, seed), cc = hash(xi, zi + 1, seed), d = hash(xi + 1, zi + 1, seed);
        double u = fx * fx * (3 - 2 * fx), v = fz * fz * (3 - 2 * fz);
        return a * (1 - u) * (1 - v) + b * u * (1 - v) + cc * (1 - u) * v + d * u * v;
    }

    private static double fbm(double x, double z, long seed) {
        return noise(x / 12, z / 12, seed) * 0.6 + noise(x / 6, z / 6, seed + 7) * 0.3 + noise(x / 3, z / 3, seed + 13) * 0.1;
    }

    private static double hash(int x, int z, long seed) {
        long q = seed * 6364136223846793005L + x * 374761393L + z * 668265263L;
        q = (q ^ (q >>> 13)) * 1274126177L;
        return ((q ^ (q >>> 16)) & 0xFFFFFF) / (double) 0xFFFFFF;
    }
}
