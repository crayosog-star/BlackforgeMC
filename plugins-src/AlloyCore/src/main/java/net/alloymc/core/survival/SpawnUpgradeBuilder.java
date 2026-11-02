package net.alloymc.core.survival;

import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.Random;

/**
 * The BlackForge volcanic kingdom around the Survival spawn, phase 1 (see docs/SURVIVAL_SPAWN_UPGRADE.md). Purely
 * additive: {@link SpawnUpgrade} only places these blocks where the world has air, and never inside the spawn island's
 * volume except the sky above it ({@link #allowedAt}). So the existing spawn is never touched.
 *
 * The island becomes the courtyard of a fortress inside a volcanic caldera: a ring of volcanic cliffs (tallest in the
 * north, where an active cone glows) with molten falls into the abyss, a rampart with eight towers on a rock collar
 * just past the island's edge, BLACKFORGE in burning letters over the north cliffs, and the Legendary Forge (a
 * giant anvil and hammer) floating above the fountain, chained to the towers.
 */
public final class SpawnUpgradeBuilder {
    private SpawnUpgradeBuilder() {}

    static final int Y = SurvivalLayout.Y;
    static final int CALDERA_IN = 125, CALDERA_OUT = 180, WALL_IN = 87, WALL_OUT = 90, TOWER_R = 92;
    static final int FORGE_Y = Y + 24;

    /** Where phase 1 may place blocks: outside the island's volume, or high in the sky above it. */
    public static boolean allowedAt(int x, int y, int z) {
        double r = Math.hypot(x, z);
        if (r > 85.5) return true;
        return y >= Y + 20;   // the Legendary Forge and the chains, well above every build and hologram
    }

    private static String mc(String s) { return "minecraft:" + s; }

    // ================================================================== the caldera and the cone

    private static void caldera(Canvas c) {
        Random rnd = new Random(31);
        String[] rock = {"basalt[axis=y]", "blackstone", "smooth_basalt", "tuff", "blackstone", "deepslate", "basalt[axis=y]"};
        for (int x = -CALDERA_OUT; x <= CALDERA_OUT; x++)
            for (int z = -CALDERA_OUT; z <= CALDERA_OUT; z++) {
                double r = Math.hypot(x, z);
                double wob = 8 * (SkyHubBuilder.noise(x * 0.03 + 3, z * 0.03 + 3, 71) - 0.5);
                if (r < CALDERA_IN + wob || r > CALDERA_OUT + wob) continue;
                double north = Math.max(0, (1 - z / r) / 2);            // 1 due north, 0 due south
                double t = (r - CALDERA_IN - wob) / (CALDERA_OUT - CALDERA_IN);
                double prof = Math.pow(Math.max(0, 1 - Math.abs(t - 0.3) / 0.7), 0.85);
                double ridge = SkyHubBuilder.fbm(x * 0.045, z * 0.045, 72);
                int top = Y + 6 + (int) Math.round((10 + 60 * Math.pow(north, 1.6)) * prof * (0.75 + 0.5 * ridge));
                // the active cone, north
                double dc = Math.hypot(x, z + 152);
                if (dc < 52) {
                    int cone = Y + 98 - (int) Math.round(Math.pow(dc / 52, 1.15) * 95 + 4 * SkyHubBuilder.noise(x * 0.2, z * 0.2, 73));
                    top = Math.max(top, cone);
                }
                int bottom = Y - 40 - (int) Math.round(45 * prof * (0.6 + 0.8 * SkyHubBuilder.fbm(x * 0.07, z * 0.07, 74)));
                for (int y = bottom; y <= top; y++) {
                    String m;
                    if (y == top) m = rnd.nextInt(40) == 0 ? "magma_block" : (north > 0.6 && y > Y + 50 ? "basalt[axis=y]" : (rnd.nextInt(4) == 0 ? "tuff" : "blackstone"));
                    else if (y > top - 3) m = rnd.nextInt(60) == 0 ? "magma_block" : "smooth_basalt";
                    else m = rock[Math.floorMod((int) (y * 0.4 + 6 * SkyHubBuilder.noise(x * 0.1, y * 0.15 + z * 0.1, 75)), rock.length)];
                    c.set(x, y, z, mc(m));
                }
            }
        // the crater: a lava lake in a magma bowl
        for (int x = -16; x <= 16; x++)
            for (int z = -168; z <= -136; z++) {
                double d = Math.hypot(x, z + 152);
                if (d > 15) continue;
                int floor = Y + 82 + (int) Math.round(d * 0.5);
                for (int y = floor + 1; y <= Y + 99; y++) c.set(x, y, z, mc("air"));
                c.set(x, floor, z, mc(d < 12 ? "lava" : "magma_block"));
                c.set(x, floor - 1, z, mc("magma_block"));
            }
        // lava rivers down the cone's south face, spilling over the cliff into the abyss
        for (int side : new int[]{-7, 7}) {
            for (int z = -137; z <= -122; z++) {
                int top = Y;
                while (top < Y + 100 && c.isSet(side, top + 1, z) && !c.get(side, top + 1, z).equals(mc("air"))) top++;
                if (top <= Y) continue;
                for (int dx = 0; dx <= 1; dx++) { c.set(side + dx, top, z, mc("lava")); c.set(side + dx, top - 1, z, mc("magma_block")); }
            }
        }
        // molten falls on the inner face of the caldera, all round the north half and a few east and west
        for (int deg = -170; deg <= 10; deg += 18) {
            double a = Math.toRadians(deg);
            int x = (int) Math.round(Math.cos(a) * (CALDERA_IN - 2)), z = (int) Math.round(Math.sin(a) * (CALDERA_IN - 2));
            int top = Y;
            int sx = (int) Math.round(Math.cos(a) * (CALDERA_IN + 6)), sz = (int) Math.round(Math.sin(a) * (CALDERA_IN + 6));
            while (top < Y + 100 && c.isSet(sx, top + 1, sz)) top++;
            if (top <= Y + 8) continue;
            for (int y = Y - 70; y <= top - 2; y++) c.set(x, y, z, mc("lava[level=8]"));
            c.set(x, top - 1, z, mc("lava"));
        }
    }

    /** BLACKFORGE in burning letters standing before the north cliffs, facing the spawn. */
    private static void letters(Canvas c) {
        String text = "BLACKFORGE";
        int scale = 2, w = BlockFont.width(text, 1) * scale, z = -112, y0 = Y + 36;
        BlockFont.draw(text, 1, (col, row, gr) -> {
            for (int a = 0; a < scale; a++)
                for (int b = 0; b < scale; b++) {
                    int x = -w / 2 + col * scale + a, y = y0 + row * scale + b;
                    c.set(x, y, z, mc(text.indexOf("FORGE") * 6 * scale <= col * scale ? "shroomlight" : "magma_block"));
                    c.set(x, y, z - 1, mc("blackstone"));
                }
        });
    }

    // ================================================================== the fortress rampart

    private static void rampart(Canvas c) {
        Random rnd = new Random(41);
        for (int x = -TOWER_R - 8; x <= TOWER_R + 8; x++)
            for (int z = -TOWER_R - 8; z <= TOWER_R + 8; z++) {
                double r = Math.hypot(x, z);
                // the rock collar the wall stands on, tucked under the island's rim
                if (r >= 85.5 && r <= 94) {
                    int depth = 6 + (int) Math.round(5 * SkyHubBuilder.noise(x * 0.15, z * 0.15, 42));
                    for (int y = Y - depth; y <= Y - 1; y++) c.set(x, y, z, mc(y < Y - 3 ? "blackstone" : "polished_blackstone"));
                    c.set(x, Y, z, mc("polished_blackstone_bricks"));
                }
                if (r < WALL_IN || r > WALL_OUT + 0.5) continue;
                boolean face = r > WALL_OUT - 0.5 || r < WALL_IN + 0.5;
                for (int y = Y + 1; y <= Y + 9; y++) {
                    String m = face ? (y % 4 == 0 ? "polished_deepslate" : rnd.nextInt(6) == 0 ? "cracked_polished_blackstone_bricks" : "polished_blackstone_bricks") : "blackstone";
                    c.set(x, y, z, mc(m));
                }
                // battlements: merlons on the outer face, a walkway on top
                c.set(x, Y + 10, z, mc(r > WALL_OUT - 0.5 && Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(z, x)) * 1.5), 3) == 0 ? "polished_blackstone_brick_wall" : "air"));
                c.set(x, Y + 9, z, mc("polished_blackstone_bricks"));
                // glowing arrow slits on the inner face
                if (r < WALL_IN + 0.5 && Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(z, x)) * 2), 9) == 0)
                    for (int y = Y + 4; y <= Y + 6; y++) c.set(x, y, z, mc("orange_stained_glass"));
            }
        // braziers along the wall top
        for (int deg = 0; deg < 360; deg += 15) {
            double a = Math.toRadians(deg);
            int x = (int) Math.round(Math.cos(a) * 88.5), z = (int) Math.round(Math.sin(a) * 88.5);
            c.set(x, Y + 10, z, mc("polished_blackstone_wall"));
            c.set(x, Y + 11, z, mc("campfire[lit=true]"));
        }
        for (int k = 0; k < 8; k++) tower(c, Math.toRadians(22.5 + k * 45), k);
    }

    private static void tower(Canvas c, double a, int k) {
        int cx = (int) Math.round(Math.cos(a) * TOWER_R), cz = (int) Math.round(Math.sin(a) * TOWER_R);
        int R = 6, top = Y + 26;
        for (int x = -R - 1; x <= R + 1; x++)
            for (int z = -R - 1; z <= R + 1; z++) {
                double d = Math.hypot(x, z);
                if (d > R + 0.5) continue;
                boolean wall = d > R - 1.2;
                // footing: a buttress down into the collar
                for (int y = Y - 12 + (int) (d * 1.2); y <= Y; y++) c.set(cx + x, y, cz + z, mc("blackstone"));
                for (int y = Y + 1; y <= top; y++) {
                    if (!wall) { c.set(cx + x, y, cz + z, mc(y % 6 == 0 ? "polished_blackstone" : d < 1 ? "shroomlight" : "air")); continue; }
                    boolean window = y % 6 >= 2 && y % 6 <= 4 && Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(z, x))), 60) < 14;
                    c.set(cx + x, y, cz + z, mc(window ? "orange_stained_glass" : (y % 6 == 0 ? "polished_deepslate" : "polished_blackstone_bricks")));
                }
                // corbelled crown and a spired roof
                if (d > R - 0.5) c.set(cx + x, top + 1, cz + z, mc("polished_blackstone_brick_wall"));
            }
        for (int y = 0; y < 14; y++) {
            double rr = R + 0.5 - y * 0.5;
            for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) if (Math.hypot(x, z) <= rr && Math.hypot(x, z) > rr - 1.3) c.set(cx + x, top + 1 + y, cz + z, mc(y % 3 == 2 ? "deepslate_tiles" : "blackstone"));
        }
        c.set(cx, top + 15, cz, mc("gold_block"));
        c.set(cx, top + 16, cz, mc("lightning_rod"));
        // orange banners hanging on the courtyard side
        double in = Math.atan2(-cz, -cx);
        int bx = cx + (int) Math.round(Math.cos(in) * (R + 1)), bz = cz + (int) Math.round(Math.sin(in) * (R + 1));
        String facing = Math.abs(Math.cos(in)) > Math.abs(Math.sin(in)) ? (Math.cos(in) > 0 ? "east" : "west") : (Math.sin(in) > 0 ? "south" : "north");
        c.set(bx, Y + 18, bz, mc((k % 2 == 0 ? "orange" : "black") + "_wall_banner[facing=" + facing + "]"));
        // every other tower spills lava from a spout on its outer side into the abyss
        if (k % 2 == 0) {
            int ox = cx + (int) Math.round(Math.cos(a) * (R + 1)), oz = cz + (int) Math.round(Math.sin(a) * (R + 1));
            c.set(ox, Y + 12, oz, mc("chiseled_polished_blackstone"));
            c.set(ox, Y + 13, oz, mc("lava"));
            for (int y = Y - 60; y <= Y + 12; y++) {
                int fx = cx + (int) Math.round(Math.cos(a) * (R + 2)), fz = cz + (int) Math.round(Math.sin(a) * (R + 2));
                c.set(fx, y, fz, mc("lava[level=8]"));
            }
        }
    }

    // ================================================================== the Legendary Forge

    private static void forge(Canvas c) {
        int y0 = FORGE_Y;
        // the anvil: foot, waist, face and horn (long along x)
        for (int x = -5; x <= 5; x++) for (int z = -3; z <= 3; z++) c.set(x, y0, z, mc("polished_blackstone"));
        for (int x = -4; x <= 4; x++) for (int z = -2; z <= 2; z++) c.set(x, y0 + 1, z, mc("polished_blackstone_bricks"));
        for (int y = y0 + 2; y <= y0 + 3; y++) for (int x = -2; x <= 2; x++) for (int z = -1; z <= 1; z++) c.set(x, y, z, mc("blackstone"));
        for (int y = y0 + 4; y <= y0 + 6; y++)
            for (int x = -7; x <= 7; x++) for (int z = -3; z <= 3; z++) c.set(x, y, z, mc(y == y0 + 6 ? "netherite_block" : "polished_blackstone_bricks"));
        for (int x = 8; x <= 11; x++) for (int z = -(11 - x) / 2; z <= (11 - x) / 2; z++) for (int y = y0 + 5; y <= y0 + 6; y++) c.set(x, y, z, mc(y == y0 + 6 ? "netherite_block" : "polished_blackstone"));
        // a glowing bar being forged on it
        for (int x = -3; x <= 2; x++) c.set(x, y0 + 7, 0, mc(x == 2 ? "magma_block" : "shroomlight"));
        // the hammer, resting at an angle
        for (int k = 0; k <= 9; k++) c.set(-4 + k / 2, y0 + 7 + k, 2, mc("dark_oak_log[axis=y]"));
        for (int x = -6; x <= 1; x++) for (int y = 0; y <= 2; y++) for (int z = 1; z <= 3; z++) c.set(x, y0 + 16 + y, z, mc(y == 1 && (x == -6 || x == 1) ? "iron_block" : "netherite_block"));
        // underside: magma glow and chains with lanterns hanging toward the fountain
        for (int x = -4; x <= 4; x += 4) {
            c.set(x, y0 - 1, 0, mc("magma_block"));
            for (int y = y0 - 5; y <= y0 - 2; y++) c.set(x, y, 0, mc("iron_chain[axis=y]"));
            c.set(x, y0 - 6, 0, mc("lantern[hanging=true]"));
        }
        // four great chains from the forge out to the corner towers
        for (int k : new int[]{0, 2, 4, 6}) {
            double a = Math.toRadians(22.5 + k * 45);
            double tx = Math.cos(a) * (TOWER_R - 6), tz = Math.sin(a) * (TOWER_R - 6), ty = Y + 22;
            int n = 160;
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n;
                double x = tx * t, z = tz * t, y = y0 + 3 + (ty - (y0 + 3)) * t - 6 * Math.sin(Math.PI * t);   // a gentle sag
                int bx = (int) Math.round(x), by = (int) Math.round(y), bz = (int) Math.round(z);
                if (Math.hypot(bx, bz) < 8) continue;
                c.set(bx, by, bz, mc(i % 6 < 3 ? "iron_chain[axis=" + (Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? "x" : "z") + "]" : "iron_bars"));
            }
        }
    }

    // ================================================================== phase 1

    public static Canvas phase1() {
        Canvas c = new Canvas();
        caldera(c);
        letters(c);
        rampart(c);
        forge(c);
        // drop anything that strays into the island's volume (the rules SpawnUpgrade applies again live)
        Canvas out = new Canvas();
        c.blocks().forEach((k, v) -> { if (allowedAt(Canvas.kx(k), Canvas.ky(k), Canvas.kz(k)) && !v.equals("minecraft:air")) out.set(Canvas.kx(k), Canvas.ky(k), Canvas.kz(k), v); });
        return out;
    }
}
