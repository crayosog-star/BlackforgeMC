package net.alloymc.core.survival;

import net.alloymc.core.build.Canvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Skyblock Hub (world "skyhub"): a lush floating spawn isle with the Heartwood Arch over a waterfall pool, a wooden
 * plaza with leaderboard pedestals, campfires and kiosks; a rope bridge west to the Vanilla Skyblock isle (hills,
 * trees, farm plots, a mossy portal) and a crystal bridge east to the OneBlock isle (end stone, purpur, amethyst, rune
 * circles, crystal spires and a purple portal under the giant floating block that {@link SkyHub} animates). Smaller
 * islands float around it for depth. Everything is generated here; SkyHub pastes it and runs the live parts.
 *
 * Coordinates: plaza surface at Y; north is -Z. Players arrive at the south edge facing the arch.
 */
public final class SkyHubBuilder {
    private SkyHubBuilder() {}

    static final int Y = SkyHub.Y;
    /** Spawn, facing north (yaw 180) at the arch. */
    static final double SPAWN_X = 0.5, SPAWN_Z = 27.5;
    /** Isle centres and the portals at their far ends (portal plane is x = GATE_X, opening z -2..2, y Y+1..Y+6). */
    static final int VANILLA_X = -96, ONEBLOCK_X = 96, VANILLA_GATE_X = -119, ONEBLOCK_GATE_X = 119;
    /** Where the giant OneBlock floats (centre) and its size. */
    static final double BIG_BLOCK_X = ONEBLOCK_X + 0.5, BIG_BLOCK_Y = Y + 15, BIG_BLOCK_Z = 0.5;
    /** Pedestals for the three leaderboards (x), on the plaza's north edge. */
    static final int[] PEDESTAL_X = {-9, 0, 9};
    static final int PEDESTAL_Z = 1;
    /** Kiosks: id, x, z (an NPC stands on each). */
    record Kiosk(String id, int x, int z) {}
    static final List<Kiosk> KIOSKS = List.of(
            new Kiosk("guide", -15, 9), new Kiosk("events", 15, 9),
            new Kiosk("cosmetics", -13, 19), new Kiosk("store", 13, 19),
            new Kiosk("achievements", -6, 25), new Kiosk("rewards", 6, 25));

    // ================================================================== noise

    private static double hash(int x, int y, long seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2147483647L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    /** Smooth value noise in 0..1. */
    public static double noise(double x, double y, long seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        double fx = x - xi, fy = y - yi;
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
        double a = hash(xi, yi, seed), b = hash(xi + 1, yi, seed), c = hash(xi, yi + 1, seed), d = hash(xi + 1, yi + 1, seed);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    public static double fbm(double x, double y, long seed) {
        return 0.6 * noise(x, y, seed) + 0.3 * noise(x * 2.1, y * 2.1, seed + 7) + 0.1 * noise(x * 4.3, y * 4.3, seed + 13);
    }

    private static String mc(String s) { return s.startsWith("minecraft:") ? s : "minecraft:" + s; }

    // ================================================================== islands

    /**
     * An organic floating island: wobbly outline, a grassy top that rises in gentle hills, and a deep rocky underside
     * that tapers into hanging spurs. Returns the surface height for each column (or Integer.MIN_VALUE off-island).
     */
    public static int[][] island(Canvas c, int cx, int cz, int r, int hills, int depth, long seed, String top, String[] rock) {
        int size = r * 2 + 12;
        int[][] surface = new int[size][size];
        Random rnd = new Random(seed);
        for (int dx = -r - 6; dx < r + 6; dx++)
            for (int dz = -r - 6; dz < r + 6; dz++) {
                surface[dx + r + 6][dz + r + 6] = Integer.MIN_VALUE;
                double ang = Math.atan2(dz, dx), d = Math.hypot(dx, dz);
                double R = r * (0.82 + 0.3 * noise(Math.cos(ang) * 2.2 + 5, Math.sin(ang) * 2.2 + 5, seed));
                if (d > R) continue;
                double edge = d / R;
                int h = (int) Math.round(hills * Math.pow(fbm((cx + dx) * 0.06, (cz + dz) * 0.06, seed + 1), 1.4) * (1 - edge * edge));
                int y = Y + h;
                surface[dx + r + 6][dz + r + 6] = y;
                c.set(cx + dx, y, cz + dz, mc(edge > 0.93 && rnd.nextInt(3) == 0 ? "moss_block" : top));
                for (int k = 1; k <= 2; k++) c.set(cx + dx, y - k, cz + dz, mc(top.equals("grass_block") ? "dirt" : top));
                // underside: deepest under the middle, ragged spurs hanging below
                double spur = fbm((cx + dx) * 0.18, (cz + dz) * 0.18, seed + 3);
                int down = (int) Math.round(depth * Math.pow(Math.max(0, 1 - edge), 0.9) * (0.55 + 0.9 * spur)) + 2;
                for (int k = 3; k <= down + (y - Y); k++) {
                    int yy = y - k;
                    String m = rock[Math.floorMod((int) (yy * 0.5 + 3 * noise((cx + dx) * 0.3, yy * 0.3, seed + 5)), rock.length)];
                    c.set(cx + dx, yy, cz + dz, mc(m));
                }
                if (edge > 0.7 && rnd.nextInt(14) == 0)   // roots and vines hanging off the rim
                    for (int k = 1; k <= 2 + rnd.nextInt(5); k++) c.setLate(cx + dx, y - down - (y - Y) - k + 1 - 3, cz + dz, mc(rnd.nextBoolean() ? "hanging_roots" : "cave_vines_plant"));
            }
        return surface;
    }

    public static int surf(int[][] s, int r, int cx, int cz, int x, int z) {
        int i = x - cx + r + 6, j = z - cz + r + 6;
        if (i < 0 || j < 0 || i >= s.length || j >= s.length) return Integer.MIN_VALUE;
        return s[i][j];
    }

    /** Grass, flowers and bushes sprinkled over a grassy island, avoiding a protected radius around points. */
    static void meadow(Canvas c, int[][] s, int r, int cx, int cz, long seed, String[] flowers, double density) {
        Random rnd = new Random(seed);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                int y = surf(s, r, cx, cz, cx + dx, cz + dz);
                if (y == Integer.MIN_VALUE || c.isSet(cx + dx, y + 1, cz + dz)) continue;
                String below = c.get(cx + dx, y, cz + dz);
                if (below == null || !(below.contains("grass_block") || below.contains("moss_block"))) continue;
                double v = rnd.nextDouble();
                if (v < density * 0.55) c.setLate(cx + dx, y + 1, cz + dz, "minecraft:short_grass");
                else if (v < density * 0.75) c.setLate(cx + dx, y + 1, cz + dz, mc(flowers[rnd.nextInt(flowers.length)]));
                else if (v < density * 0.8) c.set(cx + dx, y + 1, cz + dz, "minecraft:azalea_leaves[persistent=true]");
            }
    }

    // ================================================================== trees

    /** A big, characterful tree: thick trunk, a few climbing branches, lumpy leaf clusters, vines underneath. */
    public static void tree(Canvas c, int x, int y, int z, int height, String log, String leaves, long seed) {
        Random rnd = new Random(seed);
        boolean thick = height >= 10;
        for (int k = 0; k < height; k++) {
            c.set(x, y + k, z, mc(log));
            if (thick && k < height * 0.6) { c.set(x + 1, y + k, z, mc(log)); c.set(x, y + k, z + 1, mc(log)); c.set(x + 1, y + k, z + 1, mc(log)); }
        }
        if (thick) for (int[] d : new int[][]{{-1, 0}, {2, 0}, {0, -1}, {1, 2}}) {   // flared roots
            c.set(x + d[0], y, z + d[1], mc(log));
            if (rnd.nextBoolean()) c.set(x + d[0], y + 1, z + d[1], mc(log));
        }
        List<int[]> crowns = new ArrayList<>();
        crowns.add(new int[]{x, y + height, z});
        int branches = 2 + rnd.nextInt(3);
        for (int b = 0; b < branches; b++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            int len = 3 + rnd.nextInt(Math.max(2, height / 2));
            int by = y + (int) (height * (0.45 + rnd.nextDouble() * 0.35));
            double bx = x, bz = z, yy = by;
            for (int k = 0; k < len; k++) {
                bx += Math.cos(a); bz += Math.sin(a); yy += 0.55;
                String axis = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? "x" : "z";
                c.set((int) Math.round(bx), (int) Math.round(yy), (int) Math.round(bz), mc(log.replace("_wood", "_log") + "[axis=" + axis + "]"));
            }
            crowns.add(new int[]{(int) Math.round(bx), (int) Math.round(yy), (int) Math.round(bz)});
        }
        for (int[] cr : crowns) {
            int rr = 3 + rnd.nextInt(2);
            for (int dx = -rr; dx <= rr; dx++)
                for (int dy = -2; dy <= rr - 1; dy++)
                    for (int dz = -rr; dz <= rr; dz++) {
                        double d = Math.sqrt(dx * dx + dy * dy * 1.6 + dz * dz);
                        if (d > rr + 0.4 - noise(dx * 0.7 + seed, dz * 0.7 + dy, seed) * 1.2) continue;
                        int X = cr[0] + dx, Y2 = cr[1] + dy, Z = cr[2] + dz;
                        if (!c.isSet(X, Y2, Z)) c.set(X, Y2, Z, mc(leaves + "[persistent=true]"));
                        if (dy == -2 && rnd.nextInt(7) == 0)
                            for (int v = 1; v <= 1 + rnd.nextInt(4); v++) if (!c.isSet(X, Y2 - v, Z)) c.setLate(X, Y2 - v, Z, "minecraft:vine[north=true]");
                    }
        }
    }

    // ================================================================== the spawn isle

    private static void spawnIsle(Canvas c) {
        String[] rock = {"stone", "andesite", "stone", "tuff", "cobblestone", "mossy_cobblestone", "dirt", "stone"};
        int R = 46;
        int[][] s = island(c, 0, -4, R, 3, 34, 101, "grass_block", rock);
        // flatten the heart of the isle: plaza, pool and walkway sit at Y
        for (int x = -30; x <= 30; x++)
            for (int z = -34; z <= 34; z++) {
                int y = surf(s, R, 0, -4, x, z);
                if (y == Integer.MIN_VALUE || y == Y) continue;
                if (Math.hypot(x, z - 8) < 24 || (Math.abs(x) < 20 && z < -2 && z > -34)) {
                    for (int yy = Y + 1; yy <= y; yy++) c.set(x, yy, z, "minecraft:air");
                    c.set(x, Y, z, "minecraft:grass_block");
                    s[x + R + 6][z + 4 + R + 6] = Y;
                }
            }

        plaza(c);
        pool(c);
        cliffs(c);
        arch(c, 0, -32);
        // trees framing the plaza and along the cliffs
        int[][] trees = {{-27, 10, 13}, {28, 14, 11}, {-22, 30, 9}, {24, 31, 10}, {-34, -12, 12}, {35, -8, 11}, {-30, 24, 8}, {31, 2, 9}};
        for (int i = 0; i < trees.length; i++) {
            int[] t = trees[i];
            int y = surf(s, R, 0, -4, t[0], t[1]);
            if (y != Integer.MIN_VALUE) tree(c, t[0], y + 1, t[1], t[2], i % 3 == 2 ? "birch_log" : "oak_log", i % 4 == 1 ? "azalea_leaves" : "oak_leaves", 200 + i);
        }
        meadow(c, s, R, 0, -4, 5, new String[]{"poppy", "dandelion", "cornflower", "oxeye_daisy", "azure_bluet", "allium", "lily_of_the_valley"}, 0.32);
        // lantern posts lighting the paths
        for (int[] l : new int[][]{{-6, 32}, {6, 32}, {-20, 2}, {20, 2}, {-30, 4}, {30, 4}}) lampPost(c, l[0], Y + 1, l[1], "spruce_fence");
    }

    private static void lampPost(Canvas c, int x, int y, int z, String fence) {
        for (int k = 0; k < 3; k++) c.set(x, y + k, z, mc(fence));
        c.setLate(x, y + 3, z, "minecraft:lantern[hanging=false]");
    }

    /** The wooden plaza (after the reference): a radial plank floor with stone trim, campfire circles and the spawn pad. */
    private static void plaza(Canvas c) {
        int cx = 0, cz = 12, R = 17;
        for (int x = -R; x <= R; x++)
            for (int z = -R; z <= R; z++) {
                double d = Math.hypot(x, z);
                if (d > R + 0.3) continue;
                int ring = (int) d;
                double ang = Math.atan2(z, x);
                int spoke = (int) Math.floor((ang + Math.PI) / (Math.PI / 8));
                String m = ring >= R - 1 ? "polished_andesite"
                        : ring == R - 2 ? "spruce_planks"
                        : ring % 5 == 0 ? "stripped_spruce_log[axis=y]"
                        : spoke % 2 == 0 ? "stripped_oak_log[axis=y]" : "oak_planks";
                c.set(cx + x, Y, cz + z, mc(m));
                if (ring == R - 1) c.set(cx + x, Y + 1, cz + z, "minecraft:stone_brick_slab[type=bottom]");
            }
        // compass rose in the middle (the info centre stands on it)
        for (int k = -3; k <= 3; k++) { c.set(cx + k, Y, cz, "minecraft:spruce_planks"); c.set(cx, Y, cz + k, "minecraft:spruce_planks"); }
        c.set(cx, Y, cz, "minecraft:red_terracotta");
        for (int[] d : new int[][]{{1, 1}, {-1, 1}, {1, -1}, {-1, -1}}) c.set(cx + d[0], Y, cz + d[1], "minecraft:chiseled_stone_bricks");
        // openings in the rim: south (spawn), north (pool), west and east (bridges)
        for (int k = -2; k <= 2; k++) {
            c.set(cx + k, Y + 1, cz + R - 1, "minecraft:air"); c.set(cx + k, Y + 1, cz - R + 1, "minecraft:air");
            c.set(cx - R + 1, Y + 1, cz + k, "minecraft:air"); c.set(cx + R - 1, Y + 1, cz + k, "minecraft:air");
        }
        // campfire circles with benches
        for (int sx : new int[]{-8, 8}) {
            int fz = cz + 6;
            c.set(sx, Y, fz, "minecraft:stone_bricks");
            c.setLate(sx, Y + 1, fz, "minecraft:campfire[lit=true]");
            for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) c.set(sx + d[0], Y, fz + d[1], "minecraft:cobblestone");
            for (int k = -1; k <= 1; k++) {
                c.set(sx + k, Y + 1, fz - 3, "minecraft:spruce_stairs[facing=north]");
                c.set(sx + k, Y + 1, fz + 3, "minecraft:spruce_stairs[facing=south]");
                c.set(sx - 3, Y + 1, fz + k, "minecraft:spruce_stairs[facing=west]");
                c.set(sx + 3, Y + 1, fz + k, "minecraft:spruce_stairs[facing=east]");
            }
        }
        // leaderboard pedestals on the north edge: stepped stone with a gold, diamond or iron crown
        String[] crowns = {"gold_block", "diamond_block", "iron_block"};
        for (int i = 0; i < PEDESTAL_X.length; i++) {
            int px = PEDESTAL_X[i];
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) c.set(px + dx, Y, PEDESTAL_Z + dz, "minecraft:polished_andesite");
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(px + dx, Y + 1, PEDESTAL_Z + dz, "minecraft:stone_brick_slab[type=bottom]");
            c.set(px, Y + 1, PEDESTAL_Z, "minecraft:chiseled_stone_bricks");
            c.set(px, Y + 2, PEDESTAL_Z, mc(crowns[i]));
            c.setLate(px - 1, Y + 2, PEDESTAL_Z, "minecraft:red_candle[candles=3,lit=true]");
            c.setLate(px + 1, Y + 2, PEDESTAL_Z, "minecraft:red_candle[candles=3,lit=true]");
        }
        // kiosk pads
        for (Kiosk k : KIOSKS) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(k.x() + dx, Y, k.z() + dz, "minecraft:polished_deepslate");
            c.set(k.x(), Y, k.z(), "minecraft:chiseled_polished_blackstone");
        }
        // the path from spawn and the spawn pad
        for (int z = cz + R; z <= 32; z++) for (int x = -2; x <= 2; x++) c.set(x, Y, z, mc(Math.abs(x) == 2 ? "polished_andesite" : "spruce_planks"));
        c.set(0, Y, 27, "minecraft:red_terracotta");
    }

    /** The waterfall pool in front of the arch, with a stone walkway across the middle. */
    private static void pool(Canvas c) {
        int cx = 0, cz = -14;
        for (int x = -18; x <= 18; x++)
            for (int z = -26; z <= -3; z++) {
                double d = Math.hypot(x / 1.25, z - cz);
                if (d > 11.5) continue;
                boolean walk = Math.abs(x) <= 2;
                boolean rim = d > 10.5;
                for (int y = Y - 3; y <= Y; y++) c.set(cx + x, y, z, "minecraft:air");
                if (walk) {
                    c.set(x, Y, z, mc(Math.abs(x) == 2 ? "stone_bricks" : "polished_andesite"));
                    for (int y = Y - 3; y < Y; y++) c.set(x, y, z, "minecraft:stone_bricks");
                } else if (rim) {
                    c.set(x, Y, z, "minecraft:mossy_stone_bricks");
                    c.set(x, Y - 1, z, "minecraft:stone_bricks");
                } else if (d > 9.3) {   // a step ledge just under the surface, so nobody is stuck in the pool
                    c.set(x, Y - 1, z, "minecraft:mossy_stone_bricks");
                    c.set(x, Y - 2, z, "minecraft:stone_bricks");
                } else {
                    c.set(x, Y - 3, z, "minecraft:gravel");
                    for (int y = Y - 2; y <= Y - 1; y++) c.set(x, y, z, "minecraft:water");
                    if ((x * 7 + z * 13) % 11 == 0) c.setLate(x, Y - 2, z, "minecraft:seagrass");
                    if ((x * 5 + z * 3) % 17 == 0) c.setLate(x, Y, z, "minecraft:lily_pad");
                }
            }
    }

    /** Crescent cliffs behind the arch, with terraces, trees on top and two waterfalls into the pool. */
    private static void cliffs(Canvas c) {
        for (int x = -42; x <= 42; x++)
            for (int z = -60; z <= -22; z++) {
                double d = Math.hypot(x, (z + 14) * 1.15);
                if (d < 22 || d > 44) continue;
                // the gap behind the arch: cliffs fall away so the arch frames open sky
                double gap = Math.max(0, 1 - Math.max(0, Math.abs(x) - 15) / 2.5);
                double top = (18 + 12 * fbm(x * 0.09, z * 0.09, 33) - Math.max(0, d - 38) * 2.2) * (1 - gap);
                int h = (int) Math.round(top * Math.min(1, (d - 22) / 1.5));
                if (h < 2) continue;
                for (int y = Y + 1; y <= Y + h; y++) {
                    double n = noise(x * 0.25, y * 0.35 + z * 0.2, 41);
                    String m = y == Y + h ? "grass_block" : y == Y + h - 1 ? "dirt"
                            : n < 0.28 ? "andesite" : n < 0.55 ? "stone" : n < 0.7 ? "tuff" : n < 0.85 ? "mossy_cobblestone" : "moss_block";
                    c.set(x, y, z, mc(m));
                    if (y < Y + h - 1 && noise(x * 0.5, y * 0.5 + 9, 43) > 0.78 && !c.isSet(x, y, z + 1)) c.setLate(x, y, z + 1, "minecraft:vine[north=true]");
                }
                if ((x * 31 + z * 17) % 9 == 0 && h > 10) c.setLate(x, Y + h + 1, z, "minecraft:short_grass");
            }
        // trees along the crest
        int[][] t = {{-30, -40}, {-12, -48}, {14, -47}, {31, -38}, {-38, -26}, {39, -24}};
        for (int i = 0; i < t.length; i++) {
            int top = Y;
            while (top < Y + 40 && c.isSet(t[i][0], top + 1, t[i][1])) top++;
            if (top > Y) tree(c, t[i][0], top + 1, t[i][1], 9 + (i % 3) * 2, "oak_log", i % 2 == 0 ? "oak_leaves" : "azalea_leaves", 300 + i);
        }
        // two waterfalls: a notch in the cliff face, falling water, and a splash where they meet the pool
        for (int wx : new int[]{-19, 19}) {
            int z0 = -24;
            int top = Y;
            while (top < Y + 40 && c.isSet(wx, top + 1, z0 - 3)) top++;
            for (int dx = -1; dx <= 1; dx++) {
                for (int y = Y; y <= top; y++) {
                    c.set(wx + dx, y, z0 - 1, "minecraft:water[level=8]");   // the falling sheet
                    c.set(wx + dx, y, z0 - 2, mc(y % 3 == 0 ? "mossy_cobblestone" : "stone"));
                }
                c.set(wx + dx, top, z0 - 2, "minecraft:water");               // its source on the crest
                c.set(wx + dx, top, z0 - 3, "minecraft:water");
            }
        }
    }

    /** The Heartwood Arch: a ring of gnarled living wood with roots on a raised dais, draped in leaves and vines. */
    private static void arch(Canvas c, int cx, int cz) {
        int base = Y + 3, R = 13;
        // the dais: three steps up from the walkway
        for (int x = -12; x <= 12; x++)
            for (int z = cz - 5; z <= cz + 6; z++) {
                double d = Math.hypot(x, (z - cz) * 1.4);
                if (d > 13) continue;
                int step = d > 11 ? 1 : d > 9 ? 2 : 3;
                for (int y = Y + 1; y < Y + step; y++) c.set(x, y, z, "minecraft:stone_bricks");
                c.set(x, Y + step, z, mc(step == 3 ? ((x + z) % 2 == 0 ? "polished_andesite" : "stone_bricks") : "stone_brick_slab[type=bottom]"));
                if (step < 3) c.set(x, Y + step, z, "minecraft:stone_brick_stairs[facing=" + (z > cz ? "north" : x < 0 ? "east" : "west") + "]");
            }
        String[] wood = {"oak_wood", "stripped_oak_wood", "spruce_wood", "stripped_birch_wood", "dark_oak_wood"};
        // the ring itself, 3 thick, with a twisting mix of woods
        for (double a = 0; a < Math.PI; a += 0.012)
            for (double t = -1.6; t <= 1.6; t += 0.5)
                for (int dz = -1; dz <= 1; dz++) {
                    double r = R + t + 0.8 * Math.sin(a * 9);
                    int x = cx + (int) Math.round(Math.cos(a) * r), y = base + (int) Math.round(Math.sin(a) * r * 1.15);
                    double n = noise(a * 8, t + dz, 51);
                    c.set(x, y, cz + dz, mc(wood[(int) (n * wood.length) % wood.length]));
                }
        // legs down to the dais, with roots sprawling out
        for (int side : new int[]{-1, 1}) {
            for (int y = Y + 3; y <= base + 1; y++) for (int k = 0; k < 3; k++) for (int dz = -1; dz <= 1; dz++) c.set(cx + side * (R - 1 + k), y, cz + dz, "minecraft:oak_wood");
            Random rnd = new Random(side * 77);
            for (int root = 0; root < 5; root++) {
                double ra = rnd.nextDouble() * Math.PI - Math.PI / 2;
                double x = cx + side * (R + 1), z = cz, y = Y + 3;
                for (int k = 0; k < 7; k++) {
                    x += side * Math.cos(ra) * 0.9; z += Math.sin(ra) * 0.9; y -= 0.35;
                    c.set((int) Math.round(x), (int) Math.round(Math.max(Y + 1, y)), (int) Math.round(z), "minecraft:oak_wood");
                }
            }
        }
        // leaves draped over the crown and vines hanging into the opening
        Random rnd = new Random(52);
        for (double a = 0.15; a < Math.PI - 0.15; a += 0.05) {
            int x = cx + (int) Math.round(Math.cos(a) * (R + 1.5)), y = base + (int) Math.round(Math.sin(a) * (R + 1.5) * 1.15);
            for (int dx = -2; dx <= 2; dx++)
                for (int dy = -1; dy <= 2; dy++)
                    for (int dz = -2; dz <= 2; dz++)
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 3 && rnd.nextInt(3) > 0 && !c.isSet(x + dx, y + dy, cz + dz))
                            c.set(x + dx, y + dy, cz + dz, mc(rnd.nextInt(4) == 0 ? "flowering_azalea_leaves[persistent=true]" : "azalea_leaves[persistent=true]"));
            if (rnd.nextInt(3) == 0) {
                int ix = cx + (int) Math.round(Math.cos(a) * (R - 2.2)), iy = base + (int) Math.round(Math.sin(a) * (R - 2.2) * 1.15);
                for (int v = 0; v < 2 + rnd.nextInt(5); v++) if (!c.isSet(ix, iy - v, cz + 2)) c.setLate(ix, iy - v, cz + 2, "minecraft:vine[north=true]");
            }
        }
        // glow berries and lanterns hanging inside the arch
        for (int x : new int[]{-6, 0, 6}) {
            int top = base + (int) Math.round(Math.sqrt(Math.max(0, R * R - x * x)) * 1.15) - 2;
            for (int k = 0; k < 3; k++) c.setLate(cx + x, top - k, cz, "minecraft:chain[axis=y]");
            c.setLate(cx + x, top - 3, cz, "minecraft:lantern[hanging=true]");
        }
    }

    // ================================================================== the bridges

    /** A rope bridge (west) or a crystal bridge (east), arching gently between two points at Y. */
    private static void bridge(Canvas c, int x1, int x2, boolean crystal) {
        int len = Math.abs(x2 - x1), dir = Integer.signum(x2 - x1);
        for (int i = 0; i <= len; i++) {
            int x = x1 + dir * i;
            double t = i / (double) len;
            int y = Y + (int) Math.round(Math.sin(t * Math.PI) * 2.5);
            for (int z = -2; z <= 2; z++) {
                boolean edge = Math.abs(z) == 2;
                if (crystal) {
                    c.set(x, y, z, mc(edge ? "purpur_block" : i % 6 == 0 ? "amethyst_block" : "end_stone_bricks"));
                    if (edge) { c.set(x, y + 1, z, mc(i % 6 == 0 ? "purpur_pillar" : "purple_stained_glass_pane")); if (i % 6 == 0) c.setLate(x, y + 2, z, "minecraft:end_rod"); }
                } else {
                    c.set(x, y, z, mc(edge ? "spruce_log[axis=x]" : (i % 2 == 0 ? "spruce_planks" : "spruce_slab[type=top]")));
                    if (edge) {
                        c.set(x, y + 1, z, mc(i % 6 == 0 ? "spruce_log[axis=y]" : "spruce_fence"));
                        if (i % 6 == 0) { c.set(x, y + 2, z, "minecraft:spruce_fence"); c.setLate(x, y + 3, z, "minecraft:lantern"); }
                    }
                }
            }
            // supports hanging under the deck every few blocks
            if (i % 6 == 3) for (int k = 1; k <= 3; k++) c.setLate(x, y - k, 0, mc(crystal ? "end_rod[facing=down]" : "chain[axis=y]"));
        }
    }

    // ================================================================== Vanilla Skyblock isle

    private static void vanillaIsle(Canvas c) {
        int cx = VANILLA_X, cz = 0, R = 30;
        String[] rock = {"stone", "andesite", "dirt", "stone", "coarse_dirt", "gravel", "stone", "cobblestone"};
        int[][] s = island(c, cx, cz, R, 7, 30, 211, "grass_block", rock);
        // a level path from the bridge to the portal
        for (int x = cx + R; x >= VANILLA_GATE_X; x--)
            for (int z = -2; z <= 2; z++) {
                int y = surf(s, R, cx, cz, x, z);
                if (y == Integer.MIN_VALUE) continue;
                for (int yy = Y + 1; yy <= y; yy++) c.set(x, yy, z, "minecraft:air");
                c.set(x, Y, z, mc(Math.abs(z) == 2 ? "coarse_dirt" : (x + z) % 4 == 0 ? "gravel" : "dirt_path"));
                s[x - cx + R + 6][z - cz + R + 6] = Y;
            }
        // stone outcrops
        for (int[] o : new int[][]{{cx - 10, -16}, {cx + 12, 15}, {cx - 18, 14}}) {
            int base = surf(s, R, cx, cz, o[0], o[1]);
            if (base == Integer.MIN_VALUE) continue;
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    int h = (int) Math.round(5 * (1 - Math.hypot(dx, dz) / 4.2) + 2 * noise(dx, dz, o[0]));
                    for (int y = 1; y <= h; y++) c.set(o[0] + dx, base + y, o[1] + dz, mc(y == h && h > 2 ? "mossy_cobblestone" : (dx + y) % 3 == 0 ? "andesite" : "stone"));
                }
        }
        // trees: oaks and birches, like a classic overworld forest edge
        int[][] t = {{cx - 6, -20}, {cx + 8, -18}, {cx - 22, -6}, {cx + 18, 8}, {cx - 4, 20}, {cx + 4, -10}, {cx - 16, 18}, {cx + 20, -6}};
        for (int i = 0; i < t.length; i++) {
            int y = surf(s, R, cx, cz, t[i][0], t[i][1]);
            if (y != Integer.MIN_VALUE && Math.abs(t[i][1]) > 3) tree(c, t[i][0], y + 1, t[i][1], 6 + (i % 4) * 2, i % 2 == 0 ? "oak_log" : "birch_log", i % 2 == 0 ? "oak_leaves" : "birch_leaves", 400 + i);
        }
        // farm plots with water channels
        for (int fx = cx + 6; fx <= cx + 14; fx++)
            for (int fz = 6; fz <= 12; fz++) {
                c.set(fx, Y, fz, mc(fz == 9 ? "water" : "farmland[moisture=7]"));
                for (int yy = Y + 1; yy <= Y + 8; yy++) c.set(fx, yy, fz, "minecraft:air");
                if (fz != 9) c.setLate(fx, Y + 1, fz, mc(fx % 3 == 0 ? "carrots[age=7]" : fx % 3 == 1 ? "wheat[age=7]" : "potatoes[age=7]"));
            }
        for (int fx = cx + 5; fx <= cx + 15; fx++) { c.set(fx, Y + 1, 5, "minecraft:oak_fence"); c.set(fx, Y + 1, 13, "minecraft:oak_fence"); }
        c.set(cx + 5, Y + 1, 9, "minecraft:oak_fence"); c.set(cx + 15, Y + 1, 9, "minecraft:oak_fence");
        c.setLate(cx + 5, Y + 2, 5, "minecraft:lantern"); c.setLate(cx + 15, Y + 2, 13, "minecraft:lantern");
        c.set(cx + 10, Y + 1, 14, "minecraft:pumpkin"); c.set(cx + 12, Y + 1, 14, "minecraft:melon");
        // a waterfall pouring off the north rim into the void
        for (int dx = -1; dx <= 1; dx++) {
            int wz = -R + 4;
            int y = surf(s, R, cx, cz, cx + dx, wz);
            if (y == Integer.MIN_VALUE) continue;
            c.set(cx + dx, y, wz, "minecraft:water");
            for (int z = wz - 1; z >= wz - 7; z--) { int yy = surf(s, R, cx, cz, cx + dx, z); if (yy != Integer.MIN_VALUE) c.set(cx + dx, yy, z, "minecraft:water"); }
        }
        meadow(c, s, R, cx, cz, 17, new String[]{"poppy", "dandelion", "oxeye_daisy", "short_grass", "fern"}, 0.35);
        // the portal: mossy stone pillars, log lintel and a leafy crown (opening x = GATE_X, z -2..2)
        portalFrame(c, VANILLA_GATE_X, new String[]{"mossy_stone_bricks", "stone_bricks", "cracked_stone_bricks"}, "oak_log[axis=z]", "oak_leaves[persistent=true]", false);
        // the classic starter island floating off to the side: an L of dirt, one tree and a chest
        int sx = cx - 26, sy = Y + 16, sz = -34;
        for (int dx = 0; dx < 6; dx++) for (int dz = 0; dz < 3; dz++) for (int dy = -2; dy <= 0; dy++) c.set(sx + dx, sy + dy, sz + dz, mc(dy == 0 ? "grass_block" : "dirt"));
        for (int dx = 0; dx < 3; dx++) for (int dz = 3; dz < 6; dz++) for (int dy = -2; dy <= 0; dy++) c.set(sx + dx, sy + dy, sz + dz, mc(dy == 0 ? "grass_block" : "dirt"));
        tree(c, sx + 1, sy + 1, sz + 4, 5, "oak_log", "oak_leaves", 77);
        c.set(sx + 5, sy + 1, sz + 1, "minecraft:chest[facing=south]");
    }

    /** A gate in the x = gx plane: pillars, a lintel, a crown, steps up; the opening is left empty (SkyHub fills it with effects). */
    private static void portalFrame(Canvas c, int gx, String[] stone, String lintel, String crown, boolean mystic) {
        for (int z = -4; z <= 4; z++)
            for (int y = Y + 1; y <= Y + 8; y++) {
                boolean pillar = Math.abs(z) >= 3;
                boolean top = y >= Y + 7;
                if (!pillar && !top) { c.set(gx, y, z, "minecraft:air"); continue; }
                c.set(gx, y, z, mc(top && !pillar ? lintel : stone[Math.floorMod(y * 3 + z * 7, stone.length)]));
                c.set(gx - 1, y, z, mc(pillar ? stone[Math.floorMod(y + z, stone.length)] : "air"));
                c.set(gx + 1, y, z, mc(pillar ? stone[Math.floorMod(y + z + 1, stone.length)] : "air"));
            }
        for (int z = -5; z <= 5; z++) for (int dx = -2; dx <= 2; dx++) c.set(gx + dx, Y, z, mc(mystic ? "purpur_block" : "stone_bricks"));
        Random rnd = new Random(gx);
        for (int z = -5; z <= 5; z++)
            for (int y = Y + 9; y <= Y + 11; y++)
                for (int dx = -1; dx <= 1; dx++)
                    if (Math.abs(z) + (y - Y - 9) <= 5 && rnd.nextInt(4) > 0) c.set(gx + dx, y, z, mc(crown));
        if (!mystic) for (int z : new int[]{-3, 3}) c.setLate(gx + 2, Y + 6, z, "minecraft:lantern");
    }

    // ================================================================== OneBlock isle

    private static void oneblockIsle(Canvas c) {
        int cx = ONEBLOCK_X, cz = 0, R = 30;
        String[] rock = {"end_stone", "end_stone", "calcite", "amethyst_block", "purpur_block", "end_stone", "deepslate"};
        int[][] s = island(c, cx, cz, R, 3, 30, 311, "end_stone", rock);
        // flatten: a broad rune court
        for (int x = cx - R; x <= cx + R; x++)
            for (int z = -R; z <= R; z++) {
                int y = surf(s, R, cx, cz, x, z);
                if (y == Integer.MIN_VALUE) continue;
                for (int yy = Y + 1; yy <= y; yy++) c.set(x, yy, z, "minecraft:air");
                double d = Math.hypot(x - cx, z);
                String m = d < 13 ? (Math.abs(d - 9) < 0.6 || Math.abs(d - 12) < 0.5 ? "verdant_froglight" : (int) d % 3 == 0 ? "purpur_block" : "end_stone_bricks")
                        : d < 14 ? "purpur_pillar[axis=y]" : (x + z) % 5 == 0 ? "calcite" : "end_stone";
                c.set(x, Y, z, mc(m));
                s[x - cx + R + 6][z - cz + R + 6] = Y;
            }
        // glowing runes: spokes and glyphs in the court
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            for (double r = 3; r < 9; r += 0.5) c.set(cx + (int) Math.round(Math.cos(a) * r), Y, (int) Math.round(Math.sin(a) * r), mc(k % 2 == 0 ? "pearlescent_froglight" : "amethyst_block"));
        }
        c.set(cx, Y, 0, "minecraft:crying_obsidian");
        // crystal spires around the court
        Random rnd = new Random(9);
        for (int k = 0; k < 9; k++) {
            double a = k * Math.PI * 2 / 9 + 0.3;
            int x = cx + (int) Math.round(Math.cos(a) * 20), z = (int) Math.round(Math.sin(a) * 20);
            if (Math.abs(z) <= 4 && x > cx) continue;   // keep the way to the portal open
            int h = 6 + rnd.nextInt(9);
            for (int y = 1; y <= h; y++) {
                int w = y < h / 3 ? 1 : 0;
                for (int dx = -w; dx <= w; dx++) for (int dz = -w; dz <= w; dz++) c.set(x + dx, Y + y, z + dz, mc(y == h ? "amethyst_cluster" : (y + k) % 4 == 0 ? "purpur_pillar" : "amethyst_block"));
            }
            c.setLate(x, Y + h + 1, z, "minecraft:end_rod");
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) c.setLate(x + d[0] * 2, Y + 1, z + d[1] * 2, "minecraft:amethyst_cluster");
        }
        // little floating blocks drifting over the court (the big one is a live display, see SkyHub)
        String[] floaters = {"grass_block", "oak_log", "stone", "iron_ore", "gold_ore", "diamond_ore", "amethyst_block", "dirt"};
        for (int k = 0; k < 14; k++) {
            double a = k * 2.399, r = 8 + (k % 5) * 3;
            c.set(cx + (int) Math.round(Math.cos(a) * r), Y + 6 + (k * 7) % 12, (int) Math.round(Math.sin(a) * r), mc(floaters[k % floaters.length]));
        }
        // trees with a twist: cherry and flowering azalea between the spires
        for (int[] t : new int[][]{{cx - 18, -18}, {cx + 16, 18}, {cx - 20, 16}, {cx + 18, -17}}) tree(c, t[0], Y + 1, t[1], 7, "cherry_log", "cherry_leaves", t[0] * 31L + t[1]);
        portalFrame(c, ONEBLOCK_GATE_X, new String[]{"purpur_block", "purpur_pillar[axis=y]", "amethyst_block"}, "crying_obsidian", "amethyst_block", true);
        for (int z : new int[]{-3, 3}) c.setLate(ONEBLOCK_GATE_X - 2, Y + 6, z, "minecraft:end_rod");
        // the portal surface: real portal blocks between the pillars (portal travel is cancelled by SkyHub)
        for (int z = -2; z <= 2; z++) for (int y = Y + 1; y <= Y + 6; y++) c.set(ONEBLOCK_GATE_X, y, z, "minecraft:nether_portal[axis=z]");
    }

    // ================================================================== background isles

    private static void backgroundIsles(Canvas c) {
        int[][] isles = {{-40, 105, -95, 12}, {55, 92, -110, 9}, {150, 118, -60, 10}, {-150, 85, 70, 11}, {20, 75, 120, 13}, {-110, 128, -110, 8},
                {140, 96, 95, 9}, {-60, 112, 115, 7}};
        for (int i = 0; i < isles.length; i++) {
            int[] b = isles[i];
            Canvas tmp = new Canvas();
            int[][] s = island(tmp, 0, 0, b[3], 3, b[3] + 8, 600 + i, i % 3 == 2 ? "moss_block" : "grass_block", new String[]{"stone", "andesite", "dirt", "tuff", "mossy_cobblestone"});
            int dy = b[1] - Y;
            tmp.blocks().forEach((k, v) -> c.set(Canvas.kx(k) + b[0], Canvas.ky(k) + dy, Canvas.kz(k) + b[2], v));
            tmp.lateBlocks().forEach((k, v) -> c.setLate(Canvas.kx(k) + b[0], Canvas.ky(k) + dy, Canvas.kz(k) + b[2], v));
            int top = surf(s, b[3], 0, 0, 0, 0);
            if (top != Integer.MIN_VALUE) tree(c, b[0], top + dy + 1, b[2], 6 + i % 4, i % 2 == 0 ? "oak_log" : "cherry_log", i % 2 == 0 ? "oak_leaves" : "cherry_leaves", 700 + i);
            if (i % 2 == 0) {   // a waterfall off its edge
                int ex = b[0] + b[3] - 3;
                int ey = surf(s, b[3], 0, 0, b[3] - 3, 0);
                if (ey != Integer.MIN_VALUE) c.set(ex, ey + dy, b[2], "minecraft:water");
            }
        }
    }

    // ================================================================== all of it

    public static Canvas build() {
        Canvas c = new Canvas();
        spawnIsle(c);
        vanillaIsle(c);
        oneblockIsle(c);
        bridge(c, -44, VANILLA_X + 28, false);
        bridge(c, 44, ONEBLOCK_X - 28, true);
        backgroundIsles(c);
        return c;
    }
}
