package net.blackforge.core.hub;

import net.blackforge.core.build.BlockFont;
import net.blackforge.core.build.Canvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static net.blackforge.core.hub.HubLayout.Y;

/**
 * The Black Forge hub as one place: a wide dark plaza with a molten forge fountain, four gatehouses, great halls down both
 * sides and the fortress at the far end under its giant BLACK FORGE sign (see {@link KeepBuilder} for the fortress itself).
 * Everything is on one level (Y), so a player walks from spawn to any gate without a step.
 *
 * North is -z. Spawn stands at the south end looking up the avenue at the castle.
 */
final class ForgeHub {
    /** Plaza and forecourt: a rounded rectangle, and the castle's rock behind it overlapping a little. */
    static final double AX = 0, AZ = -9, AHX = 78, AHZ = 73, AR = 28;
    static final double BX = 0, BZ = -104, BHX = 84, BHZ = 34, BR = 24;
    static final int HALL_IN = 74, HALL_OUT = 78, HALL_TOP = Y + 24, HALL_N = -82, HALL_S = 56;
    static final int FOUNTAIN_Z = -28;
    /** Sign: "BLACK FORGE" over "MC", hung in front of the castle on two pylons. */
    static final int SIGN_Z = -76, SIGN_SCALE = 2, SIGN_L1_Y = Y + 38, SIGN_L2_Y = Y + 22;
    static final String LINE1 = "BLACK FORGE", LINE2 = "MC";

    private static final String[] STONE = {"minecraft:polished_blackstone_bricks", "minecraft:polished_blackstone_bricks", "minecraft:cracked_polished_blackstone_bricks",
            "minecraft:deepslate_bricks", "minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks", "minecraft:blackstone"};
    private static final String TRIM = "minecraft:polished_deepslate", GLOW = "minecraft:shroomlight", ROOF = "minecraft:deepslate_tiles",
            GOLD = "minecraft:gilded_blackstone", BRICK = "minecraft:polished_blackstone_bricks";

    private final Canvas c;
    private final Random rnd = new Random(0xF0A6E5);

    ForgeHub(Canvas c) { this.c = c; }

    private String stone(int x, int y, int z) {
        long h = (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
        return STONE[(int) Math.floorMod(h, (long) STONE.length)];
    }

    private void put(int x, int y, int z, String b) { if (!c.isSet(x, y, z)) c.set(x, y, z, b); }

    /** Signed distance to a rounded rectangle: negative inside. */
    static double rr(double x, double z, double cx, double cz, double hx, double hz, double r) {
        double qx = Math.abs(x - cx) - hx + r, qz = Math.abs(z - cz) - hz + r;
        return Math.hypot(Math.max(qx, 0), Math.max(qz, 0)) + Math.min(Math.max(qx, qz), 0) - r;
    }

    /** Distance to the edge of the whole rock (negative inside). */
    static double edge(double x, double z) {
        return Math.min(rr(x, z, AX, AZ, AHX, AHZ, AR), rr(x, z, BX, BZ, BHX, BHZ, BR));
    }

    // ------------------------------------------------------------------ rock and floor

    /** One rock under everything: flat on top, hollow, tapering to a jagged point below. */
    void rock() {
        for (int x = -90; x <= 90; x++)
            for (int z = -142; z <= 66; z++) {
                double d = edge(x + 0.5, z + 0.5);
                if (d > 0) continue;
                double e = Math.min(1, -d / 44);
                int depth = (int) Math.round(Math.pow(e, 0.85) * 46 + 3 + 3 * Math.sin(x * 0.37) * Math.cos(z * 0.31) + rnd.nextInt(2));
                for (int y = Y - 1; y >= Y - depth; y--) {
                    boolean skin = y == Y - depth || y > Y - 3 || d > -7;
                    if (skin) c.set(x, y, z, y > Y - 3 ? "minecraft:stone" : rnd.nextInt(16) == 0 ? "minecraft:magma_block" : rnd.nextInt(3) == 0 ? "minecraft:basalt" : "minecraft:blackstone");
                }
                c.set(x, Y, z, floor(x, z, d));
            }
        // a low wall with lamps round the open edge
        for (int x = -90; x <= 90; x++)
            for (int z = -142; z <= 66; z++) {
                double d = edge(x + 0.5, z + 0.5);
                if (d > -0.1 || d < -1.6) continue;
                if (z < -80 || Math.abs(x) >= HALL_IN - 1) continue;           // the castle and the halls take that edge
                c.set(x, Y + 1, z, "minecraft:polished_blackstone_wall");
                if (Math.floorMod(x + z, 11) == 0) { c.set(x, Y + 2, z, "minecraft:polished_blackstone_wall"); c.setLate(x, Y + 3, z, "minecraft:lantern"); }
            }
    }

    private String floor(int x, int z, double d) {
        int ax = Math.abs(x);
        if (d > -3) return Math.floorMod(x + z, 6) == 0 ? "minecraft:chiseled_polished_blackstone" : BRICK;
        // the avenue: red carpet in gold and black, glowing studs
        if (z >= -82 && z <= 62) {
            if (ax <= 3) return ((x + z) & 1) == 0 && ax == 3 ? "minecraft:polished_blackstone" : "minecraft:red_nether_bricks";
            if (ax == 4) return GOLD;
            if (ax == 5 && Math.floorMod(z, 8) == 0) return GLOW;
        }
        // ring of gold and red round the spawn
        double rs = Math.hypot(x, z - 40);
        if (rs > 7 && rs <= 8.2) return "minecraft:red_nether_bricks";
        if (rs > 8.2 && rs <= 9.2) return GOLD;
        // a cross path in front of the gates
        if (z >= -50 && z <= -44 && ax > 4 && ax < 72) return z == -47 ? "minecraft:red_nether_bricks" : (z == -44 || z == -50) ? GOLD : "minecraft:polished_blackstone";
        return ((Math.floorDiv(x, 3) + Math.floorDiv(z, 3)) & 1) == 0 ? "minecraft:polished_deepslate" : "minecraft:deepslate_tiles";
    }

    // ------------------------------------------------------------------ fountain

    /** The molten forge: a round basin of lava under glass with a netherite anvil on a stepped plinth, ringed by fire. */
    void fountain() {
        int cx = 0, cz = FOUNTAIN_Z;
        for (int x = -12; x <= 12; x++)
            for (int z = -12; z <= 12; z++) {
                double r = Math.hypot(x, z);
                int X = cx + x, Z = cz + z;
                if (r > 11.5) continue;
                if (r > 9.6) { c.set(X, Y, Z, r > 10.8 ? BRICK : GOLD); c.set(X, Y + 1, Z, BRICK); if (r > 10.8) c.set(X, Y + 2, Z, "minecraft:polished_blackstone_brick_wall"); continue; }
                if (r > 8.3) { c.set(X, Y, Z, "minecraft:red_nether_bricks"); c.set(X, Y + 1, Z, BRICK); continue; }
                if (r > 3.2) {
                    c.set(X, Y, Z, "minecraft:orange_stained_glass");
                    c.setLate(X, Y - 1, Z, "minecraft:lava");
                    c.set(X, Y - 2, Z, "minecraft:magma_block");
                    c.set(X, Y - 3, Z, "minecraft:blackstone");
                    continue;
                }
                // stepped plinth
                c.set(X, Y, Z, "minecraft:polished_blackstone");
                for (int y = Y - 3; y < Y; y++) c.set(X, y, Z, "minecraft:blackstone");
                for (int y = Y + 1; y <= Y + (r <= 1.6 ? 4 : r <= 2.6 ? 3 : 2); y++) c.set(X, y, Z, y == Y + 1 ? GOLD : "minecraft:polished_blackstone");
            }
        // sealing ring under the rim so the lava has walls
        for (int x = -10; x <= 10; x++)
            for (int z = -10; z <= 10; z++) {
                double r = Math.hypot(x, z);
                if (r > 8.0 && r <= 9.6) for (int y = Y - 3; y < Y; y++) c.set(cx + x, y, cz + z, "minecraft:polished_blackstone");
            }
        int top = Y + 5;
        c.set(cx, top, cz, "minecraft:netherite_block");
        c.set(cx, top + 1, cz, "minecraft:anvil[facing=east]");
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) c.setLate(cx + o[0], top, cz + o[1], "minecraft:campfire[lit=true]");
        c.set(cx, top + 2, cz, "minecraft:shroomlight");
        // the avenue's glowing stripes run out of the basin
        for (int side : new int[]{-1, 1})
            for (int z = FOUNTAIN_Z + 12; z <= 33; z++) {
                int x = side * 5;
                c.set(x, Y, z, "minecraft:orange_stained_glass");
                c.setLate(x, Y - 1, z, "minecraft:lava");
                c.set(x, Y - 2, z, "minecraft:magma_block");
                c.set(x - side, Y - 1, z, "minecraft:polished_blackstone");
                c.set(x + side, Y - 1, z, "minecraft:polished_blackstone");
            }
        for (int side : new int[]{-1, 1}) { c.set(side * 5, Y - 1, FOUNTAIN_Z + 11, "minecraft:polished_blackstone"); c.set(side * 5, Y - 1, 34, "minecraft:polished_blackstone"); }
    }

    // ------------------------------------------------------------------ braziers and trees

    void braziers() {
        int[][] at = {{-14, 16}, {14, 16}, {-40, -4}, {40, -4}, {-13, -62}, {13, -62}, {-30, 44}, {30, 44}};
        for (int[] p : at) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int y = Y; y <= Y + 2; y++) {
                        boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
                        c.set(p[0] + dx, y, p[1] + dz, y == Y + 2 ? TRIM : corner ? "minecraft:chiseled_polished_blackstone" : BRICK);
                    }
            for (int[] o : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) c.set(p[0] + o[0], Y, p[1] + o[1], GLOW);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) c.set(p[0] + dx, Y + 3, p[1] + dz, "minecraft:polished_blackstone_brick_wall");
            c.setLate(p[0], Y + 3, p[1], "minecraft:campfire[lit=true]");
        }
    }

    void trees() {
        int[][] at = {{-66, 30}, {66, 30}, {-66, 6}, {66, 6}, {-66, -22}, {66, -22}, {-20, 56}, {20, 56}, {-46, 52}, {46, 52}};
        for (int[] p : at) tree(p[0], p[1]);
    }

    private void tree(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { c.set(x + dx, Y, z + dz, BRICK); c.set(x + dx, Y + 1, z + dz, Math.abs(dx) + Math.abs(dz) == 2 ? "minecraft:polished_blackstone_wall" : "minecraft:moss_block"); }
        int h = 8 + rnd.nextInt(3);
        for (int y = 1; y <= h; y++) c.set(x, Y + y, z, "minecraft:dark_oak_log");
        for (int dx = -4; dx <= 4; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -4; dz <= 4; dz++)
            if (dx * dx + dy * dy * 1.6 + dz * dz <= 14 && !(dx == 0 && dz == 0 && dy < 0)) put(x + dx, Y + h + dy, z + dz, rnd.nextInt(9) == 0 ? "minecraft:moss_block" : "minecraft:spruce_leaves[persistent=true]");
    }

    // ------------------------------------------------------------------ gatehouses

    /** Walls and towers either side of an HubBuilder gate, so each one reads as a gatehouse in the line. */
    void gatehouse(HubLayout.Gate g) {
        int base = Y + 1;
        for (int side : new int[]{-1, 1})
            for (int u = 7; u <= 12; u++)
                for (int y = base; y <= base + 9; y++)
                    for (int w = -2; w <= 2; w++) {
                        boolean face = Math.abs(w) == 2 || y == base + 9;
                        if (!face) continue;
                        String b = stone(g.cx() + side * u, y, g.cz() + w);
                        if (y == base + 5) b = TRIM;
                        if (Math.abs(w) == 2 && w > 0 && u == 9 && y >= base + 3 && y <= base + 6) b = y == base + 4 ? GLOW : "minecraft:black_stained_glass";
                        put(g.cx() + side * u, y, g.cz() + w, b);
                        if (y == base + 9 && (Math.abs(w) == 2) && (u & 1) == 0) put(g.cx() + side * u, y + 1, g.cz() + w, stone(u, y, w));
                    }
        for (int side : new int[]{-1, 1}) tower(g.cx() + side * 13, g.cz(), 3, base + 15);
        // a banner on each wing, black with the red anvil
        for (int side : new int[]{-1, 1}) banner(g.cx() + side * 9, base + 8, g.cz() + 3, 3, 8);
    }

    private void tower(int cx, int cz, int r, int top) {
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                double d = Math.hypot(x, z);
                if (d > r + 0.4) continue;
                boolean shell = d > r - 1.2;
                for (int y = Y + 1; y <= top; y++) {
                    if (!shell && y != top) continue;
                    String b = stone(cx + x, y, cz + z);
                    if ((y - Y) % 8 == 0) b = TRIM;
                    if (shell && z > 0 && x == 0 && (y - Y) % 6 >= 3 && (y - Y) % 6 <= 4 && y > Y + 6) b = GLOW;
                    put(cx + x, y, cz + z, b);
                }
            }
        int roof = r * 3;
        for (int k = 0; k <= roof; k++) {
            double rr = (r + 0.6) * (1 - (double) k / roof);
            for (int x = -r - 1; x <= r + 1; x++) for (int z = -r - 1; z <= r + 1; z++) { double d = Math.hypot(x, z); if (d <= rr && d > rr - 1.5) put(cx + x, top + 1 + k, cz + z, ROOF); }
        }
        c.set(cx, top + roof + 2, cz, "minecraft:lightning_rod");
    }

    /** Black wool banner with a red anvil, hung on the face that looks south. */
    void banner(int cx, int topY, int z, int halfW, int h) {
        for (int dy = 0; dy < h; dy++)
            for (int dx = -halfW; dx <= halfW; dx++) {
                if (dy == h - 1 && dx == 0) continue;                  // swallow-tail
                int row = h - 1 - dy;
                boolean anvil = (row == 2 && Math.abs(dx) <= halfW - 1) || (row == 3 && Math.abs(dx) <= 1) || (row == 4 && Math.abs(dx) <= 1) || (row == 5 && Math.abs(dx) <= halfW - 1 && halfW >= 2);
                boolean edge = Math.abs(dx) == halfW || dy == 0;
                c.set(cx + dx, topY - dy, z, anvil ? "minecraft:red_wool" : edge ? "minecraft:red_concrete" : "minecraft:black_wool");
            }
        c.set(cx - halfW, topY + 1, z, "minecraft:iron_chain[axis=y]");
        c.set(cx + halfW, topY + 1, z, "minecraft:iron_chain[axis=y]");
    }

    // ------------------------------------------------------------------ great halls

    /** Two long dark halls closing the plaza on the east and west, lit by molten slits and hung with banners. */
    void halls() {
        for (int side : new int[]{-1, 1})
            for (int z = HALL_N; z <= HALL_S; z++)
                for (int a = HALL_IN; a <= HALL_OUT; a++)
                    for (int y = Y + 1; y <= HALL_TOP + 2; y++) {
                        int x = side * a;
                        boolean inner = a == HALL_IN || a == HALL_IN + 1, top = y >= HALL_TOP;
                        boolean buttress = Math.floorMod(z, 12) <= 1 && a <= HALL_IN + 3 && y <= HALL_TOP;
                        if (y == HALL_TOP + 1) { if ((a == HALL_IN || a == HALL_OUT) && Math.floorMod(z, 2) == 0) put(x, y, z, stone(x, y, z)); continue; }
                        if (y == HALL_TOP + 2) continue;
                        if (!(inner || a == HALL_OUT || top || buttress)) continue;
                        String b = stone(x, y, z);
                        if ((y - Y) % 12 == 0) b = TRIM;
                        // molten arrow slits between the buttresses
                        if (inner && a == HALL_IN && Math.floorMod(z, 12) == 6 && y >= Y + 9 && y <= Y + 20) b = (y - Y) % 4 == 0 ? GLOW : "minecraft:orange_stained_glass";
                        put(x, y, z, b);
                    }
        for (int side : new int[]{-1, 1}) {
            tower(side * 76, HALL_S + 2, 6, Y + 40);
            tower(side * 76, HALL_N - 1, 7, Y + 46);
        }
        // banners on the inside faces between the buttresses (they face the avenue, so they run along z)
        for (int side : new int[]{-1, 1})
            for (int z = HALL_N + 8; z <= HALL_S - 8; z += 12)
                for (int dy = 0; dy < 12; dy++)
                    for (int dz = -2; dz <= 2; dz++) {
                        boolean anvil = (dy == 5 && Math.abs(dz) <= 2) || ((dy == 6 || dy == 7) && Math.abs(dz) <= 0) || (dy == 8 && Math.abs(dz) <= 2);
                        boolean edge = Math.abs(dz) == 2 || dy == 0;
                        c.set(side * (HALL_IN - 1), Y + 18 - dy, z + dz, anvil ? "minecraft:red_wool" : edge ? "minecraft:red_concrete" : "minecraft:black_wool");
                    }
    }

    // ------------------------------------------------------------------ the sign

    private static String[] lines() { return new String[]{LINE1, LINE2}; }

    private static int[] origin(int line) {
        String t = lines()[line];
        int w = BlockFont.width(t, 1) * SIGN_SCALE;
        return new int[]{-w / 2, line == 0 ? SIGN_L1_Y : SIGN_L2_Y};
    }

    /** Front-layer {x, y, z} of every lit letter block (what players see from spawn); the welcome show draws on these. */
    static List<int[]> signFront() {
        List<int[]> out = new ArrayList<>();
        for (int ln = 0; ln < 2; ln++) {
            final int line = ln;
            int[] o = origin(line);
            BlockFont.draw(lines()[line], 1, (col, row, glyphRow) -> {
                for (int sx = 0; sx < SIGN_SCALE; sx++) for (int sy = 0; sy < SIGN_SCALE; sy++) out.add(new int[]{o[0] + col * SIGN_SCALE + sx, o[1] + row * SIGN_SCALE + sy, SIGN_Z});
            });
        }
        return out;
    }

    void sign() {
        for (int ln = 0; ln < 2; ln++) {
            final int line = ln;
            int[] o = origin(line);
            String text = lines()[line];
            BlockFont.draw(text, 1, (col, row, glyphRow) -> {
                // cast-iron letters with a molten edge, like the reference
                String b = glyphRow <= 1 ? "minecraft:iron_block" : glyphRow <= 4 ? "minecraft:polished_andesite" : "minecraft:stone";
                for (int sx = 0; sx < SIGN_SCALE; sx++)
                    for (int sy = 0; sy < SIGN_SCALE; sy++) {
                        int x = o[0] + col * SIGN_SCALE + sx, y = o[1] + row * SIGN_SCALE + sy;
                        c.set(x, y, SIGN_Z, b);
                        c.set(x, y, SIGN_Z - 1, b);
                        for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) if (!c.isSet(x + ox, y + oy, SIGN_Z - 2)) c.set(x + ox, y + oy, SIGN_Z - 2, "minecraft:shroomlight");
                    }
            });
        }
        // two great pylons carry it, tied back to the wall with chains
        for (int side : new int[]{-1, 1}) {
            int px = side * 68;
            for (int y = Y + 1; y <= SIGN_L1_Y + 16; y++)
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                        String b = stone(px + dx, y, SIGN_Z + dz);
                        if ((y - Y) % 10 == 0) b = TRIM;
                        if (dz == 2 && dx == 0 && (y - Y) % 7 >= 3 && (y - Y) % 7 <= 4) b = GLOW;
                        c.set(px + dx, y, SIGN_Z + dz, b);
                    }
            for (int y = SIGN_L1_Y + 17; y <= SIGN_L1_Y + 20; y++) c.set(px, y, SIGN_Z, "minecraft:iron_chain[axis=y]");
            c.setLate(px, SIGN_L1_Y + 21, SIGN_Z, "minecraft:soul_lantern");
            for (int z = SIGN_Z - 3; z >= KeepBuilder.FRONT; z--) c.set(px, SIGN_L1_Y + 10, z, "minecraft:iron_chain[axis=z]");
        }
    }

    // ------------------------------------------------------------------ the sky around it

    /** Floating islands around the hub, trees on the larger ones, and waterfalls pouring off a few. */
    void islands(HubBuilder hb) {
        int[][] spots = {{-104, 38, -14, 16}, {106, 30, 6, 18}, {-112, 60, 46, 14}, {104, 56, -50, 15}, {-98, 24, -88, 14}, {100, 22, -96, 15}, {0, 52, 104, 20}, {-70, 36, 96, 13}, {72, 40, 94, 13}};
        for (int[] s : spots) {
            int ox = s[0], oy = s[1], oz = s[2];
            double scale = s[3] / 52.0;
            hb.island(ox, oy, oz, scale, false);
            int ty = Y + oy;
            // a tree or two and a lantern
            int trees = 1 + rnd.nextInt(2);
            for (int i = 0; i < trees; i++) {
                int tx = ox + rnd.nextInt(7) - 3, tz = oz + rnd.nextInt(7) - 3;
                int h = 5 + rnd.nextInt(3);
                for (int y = 1; y <= h; y++) c.set(tx, ty + y, tz, "minecraft:dark_oak_log");
                for (int dx = -3; dx <= 3; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -3; dz <= 3; dz++)
                    if (dx * dx + dy * dy * 1.7 + dz * dz <= 9 && !(dx == 0 && dz == 0 && dy < 0)) put(tx + dx, ty + h + dy, tz + dz, "minecraft:spruce_leaves[persistent=true]");
            }
            c.set(ox, ty + 1, oz + 4, "minecraft:polished_blackstone_wall");
            c.setLate(ox, ty + 2, oz + 4, "minecraft:lantern");
            // glowing crystals
            for (int k = 0; k < 3; k++) { int cx = ox + rnd.nextInt(9) - 4, cz = oz + rnd.nextInt(9) - 4; c.set(cx, ty + 1, cz, "minecraft:amethyst_block"); c.set(cx, ty + 2, cz, "minecraft:amethyst_cluster[facing=up]"); }
        }
        // waterfalls off two of them, falling away into the dark
        for (int[] s : new int[][]{{-104, 38, -14, 16}, {104, 56, -50, 15}}) {
            int wx = s[0] + (s[0] < 0 ? 7 : -7), wz = s[2], ty = Y + s[1];
            for (int y = ty; y >= ty - 34; y--) c.setLate(wx, y, wz, y == ty ? "minecraft:water[level=0]" : "minecraft:water[level=8]");
        }
    }
}
