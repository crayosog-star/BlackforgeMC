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

    // ================================================================== phase 2: the four districts

    /** One district: a floating platform beyond the rampart, with a bridge and a gate cut through the wall. d: 0 east, 1 south, 2 west, 3 north. */
    private static final class Loc {
        final Canvas c; final int dx, dz, sx, sz, cx, cz;
        Loc(Canvas c, int d) {
            this.c = c;
            dx = new int[]{1, 0, -1, 0}[d]; dz = new int[]{0, 1, 0, -1}[d];
            sx = -dz; sz = dx;   // "right" of someone looking outward, so text reads from the island side
            cx = dx * DIST; cz = dz * DIST;
        }
        int wx(int u, int v) { return cx + u * dx + v * sx; }
        int wz(int u, int v) { return cz + u * dz + v * sz; }
        void set(int u, int y, int v, String b) { c.set(wx(u, v), Y + y, wz(u, v), mc(b)); }
        void late(int u, int y, int v, String b) { c.setLate(wx(u, v), Y + y, wz(u, v), mc(b)); }
        void fill(int u1, int y1, int v1, int u2, int y2, int v2, String b) {
            for (int u = Math.min(u1, u2); u <= Math.max(u1, u2); u++) for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) for (int v = Math.min(v1, v2); v <= Math.max(v1, v2); v++) set(u, y, v, b);
        }
        /** The compass facing of a local direction (du along u, dv along v). */
        String face(int du, int dv) {
            int x = du * dx + dv * sx, z = du * dz + dv * sz;
            return x > 0 ? "east" : x < 0 ? "west" : z > 0 ? "south" : "north";
        }
        /** Walls, floor and a flat roof of a room; the door is a 3-wide gap in the wall nearest the island. */
        void room(int u1, int u2, int v1, int v2, int h, String wall, String floor, String roof) {
            for (int u = u1; u <= u2; u++) for (int v = v1; v <= v2; v++) {
                set(u, 0, v, floor);
                boolean edge = u == u1 || u == u2 || v == v1 || v == v2;
                for (int y = 1; y <= h; y++) set(u, y, v, edge ? (y % 4 == 0 ? "polished_deepslate" : wall) : "air");
                set(u, h + 1, v, roof);
            }
            for (int v = -1; v <= 1; v++) for (int y = 1; y <= 3; y++) set(u1, y, v, "air");
        }
        /** Burning letters on the outer face of a wall, facing the island. */
        void sign(String text, int u, int y0, int scale) {
            int w = BlockFont.width(text, 1) * scale;
            BlockFont.draw(text, 1, (col, row, gr) -> {
                for (int a = 0; a < scale; a++) for (int b = 0; b < scale; b++) {
                    int v = -w / 2 + col * scale + a, y = y0 + row * scale + b;
                    set(u, y, v, "shroomlight");
                    set(u + 1, y, v, "blackstone");
                }
            });
        }
    }

    static final int DIST = 111, PLAT_R = 9;

    private static void platform(Loc l, long seed) {
        Random rnd = new Random(seed);
        for (int u = -PLAT_R - 1; u <= PLAT_R + 1; u++) for (int v = -PLAT_R - 1; v <= PLAT_R + 1; v++) {
            double d = Math.hypot(u, v), R = PLAT_R + 0.8 * Math.sin(Math.atan2(v, u) * 4 + seed);
            if (d > R) continue;
            l.set(u, 0, v, d > R - 1.2 ? "polished_blackstone_bricks" : (((u >> 1) + (v >> 1)) & 1) == 0 ? "deepslate_tiles" : "polished_blackstone");
            int depth = (int) Math.round(Math.pow(1 - d / R, 0.7) * 13 + 3 + rnd.nextInt(2));
            for (int y = 1; y <= depth; y++) l.set(u, -y, v, y < depth - 1 ? (rnd.nextInt(45) == 0 ? "magma_block" : "blackstone") : "basalt[axis=y]");
        }
        // the bridge from the wall: a deck, balustrades with lamps, and piers
        for (int u = -DIST + 94; u <= -PLAT_R + 1; u++) {
            for (int v = -2; v <= 2; v++) l.set(u, 0, v, Math.abs(v) == 2 ? "polished_blackstone_bricks" : "polished_deepslate");
            for (int v : new int[]{-2, 2}) {
                l.set(u, 1, v, "polished_blackstone_brick_wall");
                if ((u + DIST) % 4 == 0) { l.set(u, 2, v, "polished_blackstone_brick_wall"); l.late(u, 3, v, "soul_lantern"); }
            }
            if ((u + DIST) % 6 == 0) for (int y = -1; y >= -5; y--) l.set(u, y, 0, "blackstone");
        }
        // a pair of braziers flanking the bridge head
        for (int v : new int[]{-3, 3}) { l.set(-PLAT_R + 1, 1, v, "chiseled_polished_blackstone"); l.late(-PLAT_R + 1, 2, v, "campfire[lit=true]"); }
    }

    /** Dressing for the gate cut through the wall: two buttresses with braziers and a gilded lintel, outside the wall face. */
    private static void gateDressing(Loc l) {
        for (int v : new int[]{-4, 4}) {
            l.fill(-DIST + 91, 1, v, -DIST + 92, 12, v, "polished_blackstone_bricks");
            l.set(-DIST + 91, 13, v, "gilded_blackstone");
            l.late(-DIST + 91, 14, v, "campfire[lit=true]");
        }
        for (int v = -4; v <= 4; v++) l.set(-DIST + 91, 8, v, "gilded_blackstone");
    }

    private static void carve(Canvas out, int d) {
        Loc l = new Loc(out, d);
        for (int u = -DIST + 86; u <= -DIST + 91; u++) for (int v = -2; v <= 2; v++) for (int y = 1; y <= 6; y++) l.set(u, y, v, "air");
    }

    private static void smithy(Canvas c) {
        Loc l = new Loc(c, 0);
        platform(l, 5);
        gateDressing(l);
        l.room(-5, 6, -6, 6, 7, "polished_blackstone_bricks", "deepslate_tiles", "polished_blackstone_slab");
        l.sign("SMITHY", -5, 8, 1);
        String toIn = l.face(-1, 0), toOut = l.face(1, 0);
        // the great furnace wall: blast furnaces and magma behind iron bars
        for (int v = -5; v <= 5; v++) {
            boolean f = v % 2 == 0;
            l.set(5, 1, v, f ? "blast_furnace[facing=" + toIn + ",lit=true]" : "magma_block");
            l.set(5, 2, v, f ? "furnace[facing=" + toIn + ",lit=true]" : "iron_bars");
            l.set(5, 3, v, "iron_bars"); l.set(5, 4, v, "magma_block");
        }
        // the legendary forge: a lava basin ringed with blackstone, anvils round it
        l.fill(-1, 1, -1, 1, 1, 1, "polished_blackstone_bricks");
        l.set(0, 1, 0, "lava_cauldron");
        for (int[] a : new int[][]{{-3, -3}, {-3, 3}, {2, -4}, {2, 4}}) l.set(a[0], 1, a[1], "anvil[facing=" + l.face(0, 1) + "]");
        for (int v : new int[]{-5, 5}) { l.set(-3, 1, v, "smithing_table"); l.set(0, 1, v, "grindstone[face=floor,facing=" + l.face(0, 1) + "]"); l.set(3, 1, v, "blast_furnace[facing=" + toOut + "]"); }
        // weapon and armour pedestals under hanging lanterns
        for (int v : new int[]{-3, 0, 3}) { l.set(3, 1, v, "polished_blackstone"); l.set(3, 2, v, v == 0 ? "netherite_block" : "iron_block"); }
        for (int v = -5; v <= 5; v += 5) { for (int y = 6; y >= 4; y--) l.set(-1, y, v, "iron_chain[axis=y]"); l.late(-1, 3, v, "lantern[hanging=true]"); }
        // chimney
        l.fill(4, 8, -1, 5, 14, 0, "blackstone"); l.late(4, 15, 0, "campfire[lit=true]");
    }

    private static void market(Canvas c) {
        Loc l = new Loc(c, 1);
        platform(l, 6);
        gateDressing(l);
        l.sign("MARKET", -4, 9, 1);
        String[] wool = {"orange_wool", "black_wool"};
        int n = 0;
        for (int[] st : new int[][]{{-3, -5}, {-3, 5}, {3, -5}, {3, 5}}) {
            int u = st[0], v = st[1];
            for (int du : new int[]{-2, 2}) for (int dv : new int[]{-2, 2}) l.fill(u + du, 1, v + dv, u + du, 3, v + dv, "dark_oak_fence");
            for (int du = -2; du <= 2; du++) for (int dv = -2; dv <= 2; dv++) l.set(u + du, 4, v + dv, wool[(du + dv + n + 8) % 2]);
            l.set(u, 1, v - 1, "barrel[facing=up]"); l.set(u, 1, v + 1, "smoker[facing=" + l.face(1, 0) + "]");
            l.set(u + 1, 1, v, "barrel[facing=up]"); l.late(u, 3, v, "lantern[hanging=true]");
            n++;
        }
        // the central well: a ring of blackstone round a basin of lava, capped with a bell
        l.fill(-1, 1, -1, 1, 1, 1, "polished_blackstone_bricks"); l.set(0, 1, 0, "lava_cauldron");
        for (int y = 2; y <= 5; y++) for (int[] p : new int[][]{{-1, -1}, {-1, 1}, {1, -1}, {1, 1}}) l.set(p[0], y, p[1], "polished_blackstone_brick_wall");
        l.fill(-1, 6, -1, 1, 6, 1, "polished_blackstone_slab");
    }

    private static void gate(Canvas c) {
        Loc l = new Loc(c, 2);
        platform(l, 7);
        gateDressing(l);
        l.sign("WILDS", -5, 16, 1);
        // two great towers and an arch between them, at the far end of the platform
        for (int v : new int[]{-6, 6}) {
            for (int u = 2; u <= 6; u++) for (int w = -2; w <= 2; w++) for (int y = 1; y <= 20; y++) {
                boolean edge = u == 2 || u == 6 || w == -2 || w == 2;
                l.set(u, y, v + w, edge ? (y % 5 == 0 ? "polished_deepslate" : "polished_blackstone_bricks") : "air");
            }
            for (int y = 1; y <= 20; y += 6) l.set(2, y + 2, v, "orange_stained_glass");
            for (int i = 0; i < 6; i++) l.fill(2 + (i < 3 ? 0 : 1), 21 + i, v - 2 + i / 2, 6 - (i < 3 ? 0 : 1), 21 + i, v + 2 - i / 2, i % 3 == 2 ? "deepslate_tiles" : "blackstone");
            l.set(4, 27, v, "lightning_rod");
        }
        for (int v = -4; v <= 4; v++) for (int y = 11; y <= 15; y++) for (int u = 3; u <= 5; u++) l.set(u, y, v, y == 15 ? "gilded_blackstone" : "polished_blackstone_bricks");
        // the portcullis, half raised, and a curtain of glowing glass
        for (int v = -4; v <= 4; v++) for (int y = 8; y <= 10; y++) l.set(4, y, v, v % 2 == 0 ? "iron_bars" : "air");
        for (int v = -3; v <= 3; v++) for (int y = 1; y <= 10; y++) l.set(5, y, v, y > 8 ? "iron_bars" : "orange_stained_glass_pane");
        for (int v : new int[]{-3, 3}) { l.set(1, 1, v, "chiseled_polished_blackstone"); l.late(1, 2, v, "campfire[lit=true]"); }
    }

    private static void grandHall(Canvas c) {
        Loc l = new Loc(c, 3);
        platform(l, 8);
        gateDressing(l);
        l.room(-6, 8, -6, 6, 10, "polished_blackstone_bricks", "polished_deepslate", "deepslate_tiles");
        l.sign("GRAND HALL", -6, 12, 1);
        // a stepped roof and two flanking spires
        for (int i = 0; i < 6; i++) l.fill(-6 + i, 12 + i, -6 + i, 8 - i, 12 + i, 6 - i, i % 3 == 2 ? "blackstone" : "deepslate_tiles");
        for (int v : new int[]{-7, 7}) {
            l.fill(-6, 1, v - 1, -4, 16, v + 1, "polished_blackstone_bricks");
            for (int i = 0; i < 4; i++) l.fill(-6 + i / 2, 17 + i, v - 1 + i / 2, -4 - i / 2, 17 + i, v + 1 - i / 2, "deepslate_tiles");
            l.set(-5, 21, v, "lightning_rod");
        }
        // the aisle, pillars, chandeliers and windows
        for (int u = -5; u <= 7; u++) { l.set(u, 0, 0, "polished_blackstone_bricks"); l.set(u, 1, 0, u % 2 == 0 ? "red_carpet" : "black_carpet"); }
        for (int u = -4; u <= 6; u += 3) for (int v : new int[]{-3, 3}) {
            l.fill(u, 1, v, u, 9, v, "polished_blackstone_bricks"); l.set(u, 10, v, "gilded_blackstone");
        }
        for (int u = -4; u <= 6; u += 5) {
            for (int y = 10; y >= 7; y--) l.set(u, y, 0, "iron_chain[axis=y]");
            for (int[] o : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) l.late(u + o[0], 6, o[1], "lantern");
            l.set(u, 6, 0, "gilded_blackstone");
        }
        for (int u = -3; u <= 5; u += 4) for (int v : new int[]{-6, 6}) for (int y = 3; y <= 8; y++) l.set(u, y, v, "orange_stained_glass");
        // the throne dais at the far end: an anvil on a stepped plinth, flanked by braziers and banners
        l.fill(6, 1, -3, 7, 1, 3, "polished_blackstone_bricks"); l.fill(7, 2, -2, 7, 2, 2, "polished_blackstone_bricks");
        l.set(7, 3, 0, "anvil[facing=" + l.face(0, 1) + "]");
        for (int v : new int[]{-5, 5}) { l.set(7, 5, v, "gold_block"); l.late(7, 6, v, "campfire[lit=true]"); }
        for (int v : new int[]{-2, 2}) l.late(8, 7, v, "black_wall_banner[facing=" + l.face(-1, 0) + "]");
    }

    /** The four districts (blocks only ever go where the world has air). */
    public static Canvas phase2() {
        Canvas c = new Canvas();
        smithy(c);
        market(c);
        gate(c);
        grandHall(c);
        Canvas out = new Canvas();
        c.blocks().forEach((k, v) -> { if (allowedAt(Canvas.kx(k), Canvas.ky(k), Canvas.kz(k)) && !v.equals("minecraft:air")) out.set(Canvas.kx(k), Canvas.ky(k), Canvas.kz(k), v); });
        c.lateBlocks().forEach((k, v) -> { if (allowedAt(Canvas.kx(k), Canvas.ky(k), Canvas.kz(k))) out.setLate(Canvas.kx(k), Canvas.ky(k), Canvas.kz(k), v); });
        return out;
    }

    /** The doorways cut through the rampart (positions to turn to air; only wall blocks are ever replaced). */
    public static Canvas phase2Carve() {
        Canvas c = new Canvas();
        for (int d = 0; d < 4; d++) carve(c, d);
        return c;
    }
}
