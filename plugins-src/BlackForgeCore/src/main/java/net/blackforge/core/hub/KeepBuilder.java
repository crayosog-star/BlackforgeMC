package net.blackforge.core.hub;

import net.blackforge.core.build.Canvas;

import java.util.Random;

import static net.blackforge.core.hub.HubLayout.Y;

/**
 * The Black Forge itself: a gothic fortress on its own floating rock behind the BLACKFORGE sign, with a great purple-lit
 * gate, molten windows, flanking towers with spires, banners, a giant glowing anvil above the sign, and two airships
 * hanging in the sky either side. Scenery only (nobody can reach it, the dragons keep out of the north).
 *
 * Faces point south (+z), towards spawn. Built hollow, like the curtain wall, to keep the block count down.
 */
public final class KeepBuilder {
    /** Front face of the curtain wall, and how far back the fortress runs. */
    static final int FRONT = -84, BACK = -134;

    private static final String[] STONE = {"minecraft:polished_blackstone_bricks", "minecraft:polished_blackstone_bricks", "minecraft:cracked_polished_blackstone_bricks",
            "minecraft:deepslate_bricks", "minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks", "minecraft:blackstone"};
    private static final String TRIM = "minecraft:polished_deepslate", ROOF = "minecraft:deepslate_tiles", ROCK = "minecraft:blackstone",
            GLOW = "minecraft:shroomlight", MAGMA = "minecraft:magma_block", WIN = "minecraft:orange_stained_glass", GOLD = "minecraft:gilded_blackstone";

    private final Canvas c;
    private final Random rnd = new Random(0xF0A6E);

    private KeepBuilder(Canvas c) { this.c = c; }

    /** Just the fortress and airships, for pasting into a hub built before they existed. */
    public static Canvas build() {
        Canvas c = new Canvas();
        addTo(c);
        return c;
    }

    static void addTo(Canvas c) {
        KeepBuilder b = new KeepBuilder(c);
        b.foundation();
        b.frontWall();
        b.keep();
        b.towers();
        b.anvil();
        b.banners();
        b.airship(-82, Y + 22, -52, 0.0, "minecraft:dark_oak_planks");
        b.airship(84, Y + 40, -60, Math.PI, "minecraft:spruce_planks");
    }

    private String stone(int x, int y, int z) {
        long h = (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
        return STONE[(int) Math.floorMod(h, (long) STONE.length)];
    }

    private void put(int x, int y, int z, String b) { if (!c.isSet(x, y, z)) c.set(x, y, z, b); }

    // ------------------------------------------------------------------ rock

    /** A torn-out lump of mountain: flat on top, a jagged taper underneath. */
    private void foundation() {
        int cz = (FRONT + BACK) / 2, hz = (FRONT - BACK) / 2 + 4, hx = 62;
        for (int x = -hx; x <= hx; x++)
            for (int z = BACK - 4; z <= FRONT + 2; z++) {
                double u = (double) x / hx, v = (double) (z - cz) / hz, d = u * u + v * v;
                if (d > 1) continue;
                double t = 1 - Math.sqrt(d);
                int depth = (int) Math.round(Math.pow(t, 0.8) * 52 + 3 + 3 * Math.sin(x * 0.4) * Math.cos(z * 0.35) + rnd.nextInt(2));
                for (int y = Y; y >= Y - depth; y--) {
                    boolean skin = y == Y || y == Y - depth || d > 0.7 || y > Y - 3;
                    if (skin) put(x, y, z, y == Y ? stone(x, y, z) : rnd.nextInt(14) == 0 ? MAGMA : ROCK);
                }
            }
    }

    // ------------------------------------------------------------------ curtain wall with the great gate

    private void frontWall() {
        int top = Y + 20;
        for (int x = -58; x <= 58; x++)
            for (int y = Y + 1; y <= top + 2; y++)
                for (int z = FRONT; z >= FRONT - 2; z--) {
                    int dy = y - Y - 1;
                    double half = dy <= 12 ? 7.5 : dy > 22 ? -1 : 7.5 * (22 - dy) / 10.0;
                    boolean gate = Math.abs(x) <= half;
                    boolean face = z == FRONT || y >= top;
                    if (gate) {
                        if (z == FRONT - 1 && Math.abs(x) <= half - 1) c.set(x, y, z, "minecraft:purple_stained_glass");
                        if (z == FRONT - 2 && Math.abs(x) <= half - 1) c.set(x, y, z, y % 4 == 0 ? GLOW : "minecraft:amethyst_block");
                        continue;
                    }
                    if (!face) continue;
                    if (y == top + 1) { if (Math.floorMod(x, 2) == 0) put(x, y, z, stone(x, y, z)); continue; }
                    if (y == top + 2) { if (Math.floorMod(x, 2) == 0) put(x, y, z, TRIM); continue; }
                    String b = stone(x, y, z);
                    if (dy == 11) b = TRIM;
                    // golden frame hugging the arch
                    if (Math.abs(x) > half && Math.abs(x) <= half + 1.5 && dy < 14) b = (dy % 3 == 1) ? GLOW : "minecraft:chiseled_polished_blackstone";
                    // arrow slits glowing from inside, every few blocks
                    if (z == FRONT && Math.abs(x) > 12 && Math.floorMod(x, 9) == 4 && dy >= 6 && dy <= 9) b = dy == 7 ? GLOW : "minecraft:black_stained_glass";
                    c.set(x, y, z, b);
                }
        // a few braziers on top of the wall
        for (int x = -50; x <= 50; x += 12) {
            if (Math.abs(x) < 10) continue;
            c.set(x, top + 1, FRONT - 1, "minecraft:polished_blackstone_bricks");
            c.setLate(x, top + 2, FRONT - 1, "minecraft:campfire[lit=true]");
        }
    }

    // ------------------------------------------------------------------ the keep

    private void keep() {
        // great hall block
        shell(-26, Y + 1, FRONT - 6, 26, Y + 52, FRONT - 34, true);
        // second tier
        shell(-18, Y + 53, FRONT - 10, 18, Y + 70, FRONT - 28, false);
        // central spire
        cone(0, Y + 71, (FRONT - 10 + FRONT - 28) / 2, 11, 34, ROOF);
        for (int y = Y + 105; y <= Y + 112; y++) c.set(0, y, (FRONT - 10 + FRONT - 28) / 2, "minecraft:iron_chain[axis=y]");
        c.set(0, Y + 113, (FRONT - 10 + FRONT - 28) / 2, "minecraft:lightning_rod");
        // the great door on the keep face, lit violet
        int fz = FRONT - 6;
        for (int x = -6; x <= 6; x++)
            for (int y = Y + 1; y <= Y + 22; y++) {
                int dy = y - Y - 1;
                double half = dy <= 14 ? 6.5 : 6.5 * (22 - dy) / 8.0;
                if (Math.abs(x) <= half) {
                    c.set(x, y, fz, "minecraft:purple_stained_glass");
                    c.set(x, y, fz - 1, dy % 5 == 2 ? GLOW : "minecraft:crying_obsidian");
                } else if (Math.abs(x) <= half + 1.5 && dy < 24) c.set(x, y, fz, dy % 4 == 0 ? GLOW : GOLD);
            }
        // tall molten windows, three columns each side, three rows
        for (int side : new int[]{-1, 1})
            for (int col : new int[]{10, 16, 22})
                for (int row = 0; row < 3; row++) {
                    int y0 = Y + 24 + row * 9;
                    for (int dy = 0; dy < 6; dy++) {
                        int x = side * col;
                        c.set(x, y0 + dy, fz, dy == 5 ? TRIM : WIN);
                        c.set(x, y0 + dy, fz - 1, dy % 2 == 0 ? GLOW : MAGMA);
                    }
                }
        // buttress pillars up the face
        for (int x = -24; x <= 24; x += 8) {
            if (Math.abs(x) < 8) continue;
            for (int y = Y + 1; y <= Y + 52; y++) { c.set(x, y, fz + 1, stone(x, y, fz + 1)); c.set(x, y, fz + 2, y % 8 == 0 ? TRIM : stone(x, y, fz + 2)); }
            for (int y = Y + 53; y <= Y + 58; y++) c.set(x, y, fz + 1, "minecraft:polished_blackstone_brick_wall");
        }
        // the upper tier gets windows too
        for (int col : new int[]{-12, -6, 6, 12})
            for (int dy = 0; dy < 7; dy++) { c.set(col, Y + 57 + dy, FRONT - 10, WIN); c.set(col, Y + 57 + dy, FRONT - 11, dy % 2 == 0 ? GLOW : MAGMA); }
    }

    /** Hollow stone box with trim bands, battlements on top when {@code battle}. */
    private void shell(int x1, int y1, int z1, int x2, int y2, int z2, boolean battle) {
        int xa = Math.min(x1, x2), xb = Math.max(x1, x2), za = Math.min(z1, z2), zb = Math.max(z1, z2);
        for (int x = xa; x <= xb; x++)
            for (int z = za; z <= zb; z++)
                for (int y = y1; y <= y2; y++) {
                    boolean edge = x <= xa + 1 || x >= xb - 1 || z <= za + 1 || z >= zb - 1;
                    if (!edge && y != y2) continue;
                    String b = stone(x, y, z);
                    if ((y - y1) % 12 == 11) b = TRIM;
                    put(x, y, z, b);
                }
        if (battle)
            for (int x = xa; x <= xb; x++)
                for (int z = za; z <= zb; z++) {
                    boolean rim = x == xa || x == xb || z == za || z == zb;
                    if (rim && Math.floorMod(x + z, 2) == 0) put(x, y2 + 1, z, stone(x, y2 + 1, z));
                }
    }

    /** Hollow cone: a steep slate roof of base radius r. */
    private void cone(int cx, int y0, int cz, int r, int h, String block) {
        for (int k = 0; k <= h; k++) {
            double rr = (r + 0.5) * (1 - (double) k / h);
            for (int x = -r - 1; x <= r + 1; x++)
                for (int z = -r - 1; z <= r + 1; z++) {
                    double d = Math.hypot(x, z);
                    if (d <= rr && d > rr - 1.5) put(cx + x, y0 + k, cz + z, block);
                }
        }
    }

    // ------------------------------------------------------------------ towers

    private void towers() {
        // corner turrets on the great hall, mid towers at the sides, big gate towers on the wall
        int[][] t = {
                {-26, FRONT - 6, 5, Y + 62}, {26, FRONT - 6, 5, Y + 62}, {-26, FRONT - 34, 5, Y + 62}, {26, FRONT - 34, 5, Y + 62},
                {-42, FRONT - 20, 7, Y + 48}, {42, FRONT - 20, 7, Y + 48},
                {-58, FRONT - 1, 6, Y + 34}, {58, FRONT - 1, 6, Y + 34}, {-30, FRONT - 1, 5, Y + 30}, {30, FRONT - 1, 5, Y + 30}
        };
        for (int[] a : t) tower(a[0], a[1], a[2], a[3]);
    }

    private void tower(int cx, int cz, int r, int top) {
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                double d = Math.hypot(x, z);
                if (d > r + 0.5) continue;
                boolean shell = d > r - 1.2;
                for (int y = Y + 1; y <= top; y++) {
                    if (!shell && y != top) continue;
                    String b = stone(cx + x, y, cz + z);
                    if ((y - Y) % 11 == 0) b = TRIM;
                    // glowing slit facing south
                    if (shell && z > 0 && Math.abs(x) == 0 && (y - Y) % 9 >= 5 && (y - Y) % 9 <= 7 && y > Y + 10) b = (y - Y) % 9 == 6 ? GLOW : WIN;
                    put(cx + x, y, cz + z, b);
                }
                if (d > r - 0.5 && Math.floorMod((int) Math.round(Math.atan2(z, x) * r), 2) == 0) put(cx + x, top + 1, cz + z, stone(cx + x, top + 1, cz + z));
            }
        int roof = r * 3;
        cone(cx, top + 2, cz, r, roof, ROOF);
        for (int y = top + 2 + roof; y <= top + roof + 5; y++) c.set(cx, y, cz, "minecraft:iron_chain[axis=y]");
        c.set(cx, top + roof + 6, cz, "minecraft:lightning_rod");
    }

    // ------------------------------------------------------------------ giant glowing anvil

    private void anvil() {
        int z = FRONT - 6 + 3, y0 = Y + 40;
        // halo
        for (int x = -15; x <= 15; x++)
            for (int y = -15; y <= 15; y++) {
                double d = Math.hypot(x, y);
                if (d <= 15.5 && d > 13.2) c.set(x, y0 + 7 + y, z + 1, d > 14.6 ? GLOW : GOLD);
                else if (d > 10.5 && d <= 13.2 && ((Math.abs(x) + Math.abs(y)) % 6 == 0)) c.set(x, y0 + 7 + y, z + 1, "minecraft:chiseled_polished_blackstone");
            }
        // the anvil: base, waist, face with a horn out to the left
        for (int dz = 0; dz < 2; dz++) {
            int zz = z - dz;
            for (int x = -7; x <= 7; x++) for (int y = 0; y <= 2; y++) c.set(x, y0 + y, zz, edge(x, 7) ? GLOW : "minecraft:polished_blackstone");
            for (int x = -4; x <= 4; x++) for (int y = 3; y <= 5; y++) c.set(x, y0 + y, zz, "minecraft:blackstone");
            for (int x = -9; x <= 9; x++) for (int y = 6; y <= 9; y++) c.set(x, y0 + y, zz, y == 9 ? "minecraft:iron_block" : edge(x, 9) ? GLOW : "minecraft:netherite_block");
            for (int h = 0; h < 6; h++) for (int y = 7 + h / 2; y <= 9; y++) c.set(-10 - h, y0 + y, zz, y == 9 ? "minecraft:iron_block" : "minecraft:netherite_block");
        }
        // sparks above it
        for (int i = 0; i < 14; i++) c.setLate(-5 + rnd.nextInt(11), y0 + 11 + rnd.nextInt(6), z, "minecraft:shroomlight");
    }

    private boolean edge(int x, int half) { return Math.abs(x) == half; }

    // ------------------------------------------------------------------ banners

    private void banners() {
        for (int x : new int[]{-44, -17, 17, 44}) {
            int z = FRONT + 1;
            for (int y = Y + 4; y <= Y + 19; y++)
                for (int dx = -2; dx <= 2; dx++) {
                    boolean tail = (y == Y + 4 && dx == 0);
                    if (tail) continue;
                    boolean glyph = (y == Y + 14 && Math.abs(dx) <= 2) || (y == Y + 13 && Math.abs(dx) <= 1) || (y == Y + 12 && dx == 0) || (y == Y + 11 && Math.abs(dx) <= 1) || (y == Y + 15 && dx <= 0 && dx >= -2);
                    c.set(x + dx, y, z, glyph ? "minecraft:red_wool" : (y == Y + 19 || Math.abs(dx) == 2) ? "minecraft:red_concrete" : "minecraft:black_wool");
                }
            for (int dx = -2; dx <= 2; dx += 4) c.set(x + dx, Y + 20, z, "minecraft:iron_chain[axis=y]");
        }
    }

    // ------------------------------------------------------------------ airships

    /** A hanging airship: hull, deck, mast, striped balloon, banner, lanterns. {@code yaw} 0 faces +x, PI faces -x. */
    private void airship(int cx, int cy, int cz, double yaw, String planks) {
        double cs = Math.cos(yaw), sn = Math.sin(yaw);
        int len = 17;
        for (int u = -len; u <= len; u++) {
            double f = Math.abs(u) / (double) len;
            double half = 5.2 * Math.sqrt(Math.max(0, 1 - f * f)) + 0.6;
            int keel = (int) Math.round(6 * (1 - f * f * 0.6));
            int rise = u > 0 ? (int) Math.round(f * f * 3) : (int) Math.round(f * f * 2);
            for (int w = (int) -Math.ceil(half); w <= Math.ceil(half); w++) {
                double aw = Math.abs(w);
                if (aw > half) continue;
                double depthHere = keel * (1 - Math.pow(aw / (half + 0.01), 2) * 0.8);
                for (int dy = 0; dy <= depthHere; dy++) {
                    boolean side = aw > half - 1.2 || dy >= depthHere - 1;
                    int[] p = at(cx, cz, u, w, cs, sn);
                    int y = cy - dy + rise * (dy == 0 ? 1 : 0);
                    if (!side && dy != 0) continue;
                    c.set(p[0], y, p[1], dy == 0 ? "minecraft:stripped_dark_oak_log" : (dy + u) % 4 == 0 ? "minecraft:dark_oak_log" : planks);
                }
                // rail
                if (aw > half - 1.0 && aw <= half) { int[] p = at(cx, cz, u, w, cs, sn); c.set(p[0], cy + rise + 1, p[1], "minecraft:dark_oak_fence"); }
            }
        }
        // bow figurehead and stern cabin
        int[] bow = at(cx, cz, len + 1, 0, cs, sn);
        c.set(bow[0], cy + 3, bow[1], GOLD);
        c.set(bow[0], cy + 4, bow[1], "minecraft:gold_block");
        for (int u = -len + 1; u <= -len + 7; u++)
            for (int w = -3; w <= 3; w++)
                for (int h = 1; h <= 5; h++) {
                    boolean wall = Math.abs(w) == 3 || u == -len + 1 || u == -len + 7 || h == 5;
                    if (!wall) continue;
                    int[] p = at(cx, cz, u, w, cs, sn);
                    c.set(p[0], cy + 1 + h, p[1], h == 5 ? "minecraft:dark_oak_planks" : (Math.abs(w) == 3 && h == 3 ? "minecraft:orange_stained_glass" : "minecraft:spruce_planks"));
                }
        // propeller at the stern
        int[] st = at(cx, cz, -len - 1, 0, cs, sn);
        for (int k = -3; k <= 3; k++) { c.set(st[0], cy + 3 + k, st[1], "minecraft:iron_bars"); int[] q = at(cx, cz, -len - 1, k, cs, sn); c.set(q[0], cy + 3, q[1], "minecraft:iron_bars"); }
        // balloon: striped ellipsoid, hollow, held on chains
        int by = cy + 17;
        for (int u = -15; u <= 15; u++)
            for (int w = -9; w <= 9; w++)
                for (int h = -8; h <= 8; h++) {
                    double d = (u * u) / 225.0 + (w * w) / 81.0 + (h * h) / 64.0;
                    if (d > 1 || d < 0.72) continue;
                    int[] p = at(cx, cz, u, w, cs, sn);
                    boolean stripe = Math.floorMod(u / 3, 2) == 0;
                    c.set(p[0], by + h, p[1], stripe ? "minecraft:red_wool" : "minecraft:black_wool");
                }
        // black forge anvil on the balloon's flank (red on black)
        for (int side : new int[]{-1, 1})
            for (int dx = -3; dx <= 3; dx++)
                for (int dy = 0; dy <= 3; dy++) {
                    boolean on = dy == 3 || (dy == 2 && Math.abs(dx) <= 2) || (dy < 2 && Math.abs(dx) <= 1);
                    if (!on) continue;
                    int[] p = at(cx, cz, dx, side * 9, cs, sn);
                    c.set(p[0], by - 2 + dy, p[1], "minecraft:orange_concrete");
                }
        // rigging from the balloon to the deck, and lanterns
        for (int u : new int[]{-9, 0, 9})
            for (int side : new int[]{-1, 1}) {
                int[] p = at(cx, cz, u, side * 4, cs, sn);
                for (int y = cy + 2; y < by - 5; y++) c.set(p[0], y, p[1], "minecraft:iron_chain[axis=y]");
            }
        for (int u = -12; u <= 12; u += 6) {
            int[] p = at(cx, cz, u, 0, cs, sn);
            c.set(p[0], cy + 1, p[1], "minecraft:polished_blackstone");
            c.setLate(p[0], cy + 2, p[1], "minecraft:lantern");
        }
        // hull lanterns, hanging
        for (int u = -12; u <= 12; u += 8)
            for (int side : new int[]{-1, 1}) {
                int[] p = at(cx, cz, u, side * 6, cs, sn);
                c.set(p[0], cy + 3, p[1], "minecraft:iron_chain[axis=y]");
                c.setLate(p[0], cy + 2, p[1], "minecraft:lantern[hanging=true]");
            }
    }

    private static int[] at(int cx, int cz, int u, int w, double cs, double sn) {
        return new int[]{cx + (int) Math.round(u * cs - w * sn), cz + (int) Math.round(u * sn + w * cs)};
    }
}
