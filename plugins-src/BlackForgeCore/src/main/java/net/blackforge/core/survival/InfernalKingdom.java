package net.blackforge.core.survival;

import net.blackforge.core.build.Canvas;

import static net.blackforge.core.survival.InfernalLayout.*;

/**
 * The Infernal Kingdom of BlackForge: a dark gothic fortress kingdom in a mountain caldera, built around the legendary forge.
 * Terrain with real elevation (valley, district terraces, the citadel terrace, mountains behind), the Molten Plaza with its
 * giant anvil monument, the double grand staircase, the Infernal Citadel with its spires, and the Dragon Gate. The Royal
 * Treasury, Hall of Ascension and Hall of Champions are in {@link KingdomHalls}; the Merchant District and Blacksmith Quarter
 * in {@link KingdomDistricts}. Everything is generated into a Canvas (nothing touches a world until a staging paste).
 *
 * Coordinates: see {@link InfernalLayout}. +z is south; the citadel is north, the Dragon Gate south.
 */
public final class InfernalKingdom {
    private InfernalKingdom() {}

    public static Canvas build() {
        Canvas c = new Canvas();
        KBuild k = new KBuild(c);
        KingdomTerrain.build(k);
        plaza(k);
        avenueAndStairs(k);
        terraceWall(k);
        citadel(k);
        KingdomHalls.build(k);
        KingdomDistricts.build(k);
        dragonGate(k);
        KingdomTerrain.trees(k);
        return c;
    }

    // ================================================================== Molten Plaza

    private static String plazaFloor(int x, int z) {
        double dx = x - PLAZA_X, dz = z - PLAZA_Z, r = Math.hypot(dx, dz);
        if (r > 44) return Math.floorMod(x + z, 5) == 0 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks";
        if (r > 42) return ((int) r & 1) == 0 ? "gilded_blackstone" : "polished_deepslate";
        if (Math.abs(r - 30) < 1.1) return "gilded_blackstone";
        if (Math.abs(r - 22) < 0.8) return "red_nether_bricks";
        double ang = Math.atan2(dz, dx);
        double spoke = Math.abs(Math.sin(ang * 4)) * r;           // distance to the nearest of 8 spokes, roughly
        if (r > 17 && spoke < 1.6) return spoke < 0.9 ? "red_nether_bricks" : "polished_blackstone";
        return ((Math.floorDiv(x, 3) + Math.floorDiv(z, 3)) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles";
    }

    private static void plaza(KBuild k) {
        for (int x = PLAZA_X - PLAZA_R; x <= PLAZA_X + PLAZA_R; x++)
            for (int z = PLAZA_Z - PLAZA_R; z <= PLAZA_Z + PLAZA_R; z++) {
                if (Math.hypot(x - PLAZA_X, z - PLAZA_Z) > PLAZA_R) continue;
                k.set(x, 0, z, plazaFloor(x, z));
                k.set(x, -1, z, "stone");
            }
        monument(k, PLAZA_X, PLAZA_Z);
        // eight braziers on the gold ring, four knight statues between them, banner poles at the edge
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + Math.PI / 8;
            int bx = PLAZA_X + (int) Math.round(Math.cos(a) * 30), bz = PLAZA_Z + (int) Math.round(Math.sin(a) * 30);
            k.brazier(bx, 0, bz);
        }
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2 + Math.PI / 4;
            int sx = PLAZA_X + (int) Math.round(Math.cos(a) * 24), sz = PLAZA_Z + (int) Math.round(Math.sin(a) * 24);
            char face = i == 0 ? 'w' : i == 1 ? 'n' : i == 2 ? 'e' : 's';
            k.statue(sx, 0, sz, face, "iron_block", i % 2 == 0 ? "red_wool" : "black_wool");
        }
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6 + 0.26;
            int px = PLAZA_X + (int) Math.round(Math.cos(a) * 40), pz = PLAZA_Z + (int) Math.round(Math.sin(a) * 40);
            for (int y = 1; y <= 12; y++) k.set(px, y, pz, "polished_blackstone_brick_wall");
            k.set(px, 13, pz, "chiseled_polished_blackstone");
            k.late(px, 14, pz, "soul_lantern");
            char face = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? (Math.cos(a) > 0 ? 'w' : 'e') : (Math.sin(a) > 0 ? 'n' : 's');
            k.banner(px + (face == 'w' ? -1 : face == 'e' ? 1 : 0), 12, pz + (face == 'n' ? -1 : face == 's' ? 1 : 0), face == 'n' || face == 's' ? 'n' : 'w', 1, 7);
        }
        // landscaping: planters with dark pines on the outer ring
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI / 5 + 0.5;
            int px = PLAZA_X + (int) Math.round(Math.cos(a) * 36), pz = PLAZA_Z + (int) Math.round(Math.sin(a) * 36);
            planter(k, px, pz);
        }
    }

    static void planter(KBuild k, int x, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
            boolean rim = Math.abs(dx) == 2 || Math.abs(dz) == 2;
            k.set(x + dx, 0, z + dz, "polished_blackstone_bricks");
            k.set(x + dx, 1, z + dz, rim ? "polished_blackstone_brick_slab" : "moss_block");
        }
        int h = 8 + k.rnd.nextInt(4);
        for (int y = 2; y <= h; y++) k.set(x, y, z, "dark_oak_log");
        for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 4; dy++) for (int dz = -3; dz <= 3; dz++) {
            double w = 3.4 - Math.max(0, dy) * 0.55;
            if (dx * dx + dz * dz > w * w || (dx == 0 && dz == 0 && dy < 0)) continue;
            k.put(x + dx, h + dy, z + dz, "spruce_leaves[persistent=true]");
        }
    }

    /** The BlackForge anvil: a giant forged anvil on a stepped plinth in a molten basin, lava pillars at its corners. */
    private static void monument(KBuild k, int cx, int cz) {
        // basin: lava under glass, ringed by a raised gilded rim
        for (int x = -18; x <= 18; x++)
            for (int z = -18; z <= 18; z++) {
                double r = Math.hypot(x, z);
                int X = cx + x, Z = cz + z;
                if (r > 17.6) continue;
                if (r > 15.4) { k.set(X, 0, Z, r > 16.6 ? "polished_blackstone_bricks" : "gilded_blackstone"); k.set(X, 1, Z, "polished_blackstone_bricks"); if (r > 16.6) k.set(X, 2, Z, "polished_blackstone_brick_wall"); continue; }
                if (r > 6.8) { k.set(X, 0, Z, "orange_stained_glass"); k.late(X, -1, Z, "lava"); k.set(X, -2, Z, "magma_block"); k.set(X, -3, Z, "blackstone"); continue; }
                // plinth
                int top = r <= 3.2 ? 4 : r <= 5 ? 3 : 2;
                for (int y = -3; y <= top; y++) k.set(X, y, Z, y == top ? (r <= 3.2 ? "gilded_blackstone" : "polished_blackstone") : "blackstone");
            }
        for (int x = -17; x <= 17; x++) for (int z = -17; z <= 17; z++) { double r = Math.hypot(x, z); if (r > 15.4 && r <= 16.0) for (int y = -3; y <= -1; y++) k.set(cx + x, y, cz + z, "polished_blackstone"); }
        // the anvil, long axis along x: base, waist, face with a horn to the west and a tail to the east
        int y0 = 5;
        for (int x = -7; x <= 7; x++) for (int z = -3; z <= 3; z++) for (int y = y0; y <= y0 + 2; y++) k.set(cx + x, y, cz + z, edgeAnvil(x, 7, z, 3) ? "shroomlight" : "polished_blackstone");
        for (int x = -4; x <= 4; x++) for (int z = -2; z <= 2; z++) for (int y = y0 + 3; y <= y0 + 6; y++) k.set(cx + x, y, cz + z, "blackstone");
        int f0 = y0 + 7;
        for (int x = -13; x <= 13; x++) for (int z = -5; z <= 5; z++) for (int y = f0; y <= f0 + 3; y++) {
            String b = y == f0 + 3 ? "iron_block" : (y == f0 + 1 && (Math.abs(x) == 13 || Math.abs(z) == 5)) ? "shroomlight" : "netherite_block";
            k.set(cx + x, y, cz + z, b);
        }
        for (int i = 1; i <= 13; i++) {                    // horn: tapers to a point
            int half = Math.max(0, 5 - (int) Math.round(i * 5.0 / 13));
            for (int z = -half; z <= half; z++) for (int y = f0 + Math.min(i / 4, 2); y <= f0 + 3; y++) k.set(cx - 13 - i, y, cz + z, y == f0 + 3 ? "iron_block" : "netherite_block");
        }
        for (int i = 1; i <= 5; i++) for (int z = -(5 - i) / 2; z <= (5 - i) / 2; z++) for (int y = f0 + 1; y <= f0 + 3; y++) k.set(cx + 13 + i, y, cz + z, y == f0 + 3 ? "iron_block" : "netherite_block");
        // the hammer: a netherite head on a gilded haft laid across the face, with embers above
        for (int z = -4; z <= 4; z++) k.set(cx + 2, f0 + 4, cz + z, "gilded_blackstone");
        for (int dx = 0; dx <= 3; dx++) for (int dz = 0; dz <= 2; dz++) for (int dy = 0; dy <= 2; dy++) k.set(cx + 1 + dx, f0 + 5 + dy, cz + 4 + dz, "netherite_block");
        for (int i = 0; i < 16; i++) k.late(cx - 8 + k.rnd.nextInt(17), f0 + 5 + k.rnd.nextInt(8), cz - 3 + k.rnd.nextInt(7), "shroomlight");
        // lava pillars at the four corners of the basin, in glass tubes, topped with fire
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            int px = cx + sx * 11, pz = cz + sz * 11;
            for (int y = -3; y <= 12; y++) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    boolean core = dx == 0 && dz == 0;
                    if (y == -3 || y == 12) k.set(px + dx, y, pz + dz, "polished_blackstone");
                    else if (core) k.late(px, y, pz, "lava");
                    else k.set(px + dx, y, pz + dz, "orange_stained_glass");
                }
            }
            k.brazier(px, 13, pz);
        }
    }

    private static boolean edgeAnvil(int x, int hx, int z, int hz) { return (Math.abs(x) == hx && Math.abs(z) == hz) || (Math.abs(z) == hz && Math.floorMod(x, 4) == 0); }

    // ================================================================== avenue, double grand staircase, treasury front

    private static void avenueAndStairs(KBuild k) {
        // the avenue north of the plaza, with a red carpet and glowing channels
        for (int z = PLAZA_Z - PLAZA_R + 1; z >= TERRACE_FRONT + 20; z--)
            for (int x = -10; x <= 10; x++) {
                int ax = Math.abs(x);
                String b = ax <= 3 ? "red_nether_bricks" : ax == 4 ? "gilded_blackstone" : ax <= 8 ? ((Math.floorDiv(z, 2) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles") : "polished_blackstone_bricks";
                k.set(x, 0, z, b);
                k.set(x, -1, z, "stone");
                if (ax == 6) { k.lavaStrip(x, 0, z); k.set(x - 1, -1, z, "polished_blackstone"); k.set(x + 1, -1, z, "polished_blackstone"); }
            }
        for (int z = PLAZA_Z - PLAZA_R; z >= TERRACE_FRONT + 22; z -= 8)
            for (int s : new int[]{-1, 1}) { for (int y = 1; y <= 5; y++) k.set(s * 10, y, z, "polished_blackstone_brick_wall"); k.late(s * 10, 6, z, "lantern"); }
        // the forecourt in front of the treasury doors, and the two flights going up either side of it
        for (int i = 0; i < 20; i++) {
            int z = TERRACE_FRONT + 20 - i, top = i + 1;
            for (int s : new int[]{-1, 1})
                for (int x = 11; x <= 25; x++) {
                    int X = s * x;
                    for (int y = 0; y <= top; y++) k.set(X, y, z, y == top ? (x == 11 || x == 25 ? "gilded_blackstone" : "polished_blackstone_bricks") : "blackstone");
                    if (x == 18) { k.set(X, top, z, "orange_stained_glass"); k.late(X, top - 1, z, "lava"); k.set(X, top - 2 < 0 ? 0 : top - 2, z, top - 2 < 0 ? "magma_block" : "magma_block"); }
                }
        }
        for (int s : new int[]{-1, 1}) {      // balustrades with lamps on the outer edge
            for (int i = 0; i < 20; i++) {
                int z = TERRACE_FRONT + 20 - i, top = i + 1;
                k.set(s * 26, top, z, "polished_blackstone_bricks");
                k.set(s * 26, top + 1, z, "polished_blackstone_brick_wall");
                if (i % 5 == 2) { k.set(s * 26, top + 2, z, "polished_blackstone_brick_wall"); k.late(s * 26, top + 3, z, "soul_lantern"); }
            }
        }
    }

    private static void terraceWall(KBuild k) {
        // retaining wall of the citadel terrace: masonry over the cliff, buttresses with banners, parapet
        for (int x = -TERRACE_HALF; x <= TERRACE_HALF; x++) {
            boolean flight = Math.abs(x) >= 10 && Math.abs(x) <= 26;
            for (int y = 0; y <= TERRACE_Y; y++)
                if (!(flight && y > 0)) for (int z = TERRACE_FRONT; z >= TERRACE_FRONT - 1; z--) k.set(x, y, z, (y % 10 == 9) ? KBuild.TRIM : k.brick(x, y, z));
            if (!flight) {
                k.set(x, TERRACE_Y + 1, TERRACE_FRONT, KBuild.TRIM);
                k.set(x, TERRACE_Y + 1, TERRACE_FRONT - 1, KBuild.TRIM);
                if (Math.floorMod(x, 2) == 0) k.set(x, TERRACE_Y + 2, TERRACE_FRONT, k.brick(x, TERRACE_Y + 2, 0));
            }
        }
        for (int x = -TERRACE_HALF + 8; x <= TERRACE_HALF - 8; x += 14) {
            if (Math.abs(x) <= 28) continue;
            for (int y = 0; y <= TERRACE_Y + 3; y++) for (int dx = -1; dx <= 1; dx++) k.set(x + dx, y, TERRACE_FRONT + 1, y % 10 == 9 ? KBuild.TRIM : k.brick(x + dx, y, 7));
            k.set(x, TERRACE_Y + 4, TERRACE_FRONT + 1, "chiseled_polished_blackstone");
            k.late(x, TERRACE_Y + 5, TERRACE_FRONT + 1, "soul_lantern");
            k.banner(x, TERRACE_Y - 3, TERRACE_FRONT + 2, 'n', 2, 12);
        }
        // the sides of the terrace: a masonry wall with towers every so often
        for (int s : new int[]{-1, 1})
            for (int z = TERRACE_FRONT; z >= TERRACE_BACK; z--) {
                int x = s * TERRACE_HALF;
                for (int y = TERRACE_Y - 6; y <= TERRACE_Y + 9; y++) { k.set(x, y, z, y % 10 == 3 ? KBuild.TRIM : k.brick(x, y, z)); k.set(x - s, y, z, k.brick(x - s, y, z)); }
                k.set(x, TERRACE_Y + 10, z, KBuild.TRIM);
                if (Math.floorMod(z, 2) == 0) k.set(x, TERRACE_Y + 11, z, k.brick(x, 0, z));
            }
        for (int x = -TERRACE_HALF; x <= TERRACE_HALF; x++)
            for (int y = TERRACE_Y - 6; y <= TERRACE_Y + 9; y++) { k.set(x, y, TERRACE_BACK, y % 10 == 3 ? KBuild.TRIM : k.brick(x, y, TERRACE_BACK)); k.set(x, y, TERRACE_BACK + 1, k.brick(x, y, 5)); }
        for (int s : new int[]{-1, 1}) {
            k.spireTower(s * TERRACE_HALF, TERRACE_FRONT, TERRACE_Y, 8, 34, 24, false);
            k.spireTower(s * TERRACE_HALF, TERRACE_BACK, TERRACE_Y, 9, 44, 30, true);
            k.spireTower(s * TERRACE_HALF, (TERRACE_FRONT + TERRACE_BACK) / 2, TERRACE_Y, 6, 26, 18, false);
        }
    }

    // ================================================================== the Infernal Citadel

    private static void citadel(KBuild k) {
        int T = TERRACE_Y;
        // forecourt paving: the whole terrace in front of and between the buildings
        for (int x = -TERRACE_HALF + 1; x < TERRACE_HALF; x++)
            for (int z = TERRACE_FRONT - 2; z >= TERRACE_BACK + 2; z--) {
                int ax = Math.abs(x);
                String b = ax <= 3 && z > -84 ? "red_nether_bricks" : ax == 4 && z > -84 ? "gilded_blackstone" : ((Math.floorDiv(x, 3) + Math.floorDiv(z, 3)) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles";
                k.set(x, T, z, b);
            }
        // curtain wall with the Great Gate, and BLACKFORGE across it
        int wz = -62, wh = 46;
        for (int x = -62; x <= 62; x++)
            for (int z = wz; z >= wz - 3; z--)
                for (int y = T + 1; y <= T + wh; y++) {
                    boolean face = z == wz || z == wz - 3 || y >= T + wh - 1;
                    if (face) k.set(x, y, z, (y - T) % 12 == 11 ? KBuild.TRIM : k.brick(x, y, z));
                    else k.set(x, y, z, "air");
                }
        k.archX(0, T + 1, wz - 3, 4, 23, 24, "air");
        for (int z = wz; z >= wz - 3; z--) { k.archX(0, T + 1, z, 1, 23, 24, "air"); }
        // arch moulding and a half-lowered portcullis
        for (int dy = 0; dy <= 25; dy++) {
            double half = dy <= 13 ? 12 : Math.max(0, 12 - (dy - 13));
            for (int s : new int[]{-1, 1}) {
                int xx = (int) Math.round(s * (half + 1));
                for (int z = wz; z >= wz - 3; z--) k.set(xx, T + 1 + dy, z, dy % 3 == 1 ? KBuild.GLOW : KBuild.GOLD);
            }
        }
        for (int x = -11; x <= 11; x += 2) for (int y = T + 14; y <= T + 24; y++) {
            double half = y - T - 1 <= 13 ? 11 : Math.max(0, 11 - (y - T - 1 - 13));
            if (Math.abs(x) <= half) k.set(x, y, wz - 1, "iron_bars");
        }
        for (int y = T + 14; y <= T + 24; y += 3) for (int x = -11; x <= 11; x++) { double half = y - T - 1 <= 13 ? 11 : Math.max(0, 11 - (y - T - 1 - 13)); if (Math.abs(x) <= half) k.set(x, y, wz - 1, "iron_bars"); }
        k.lettersX("BLACKFORGE", 0, T + wh - 33, wz + 1, 2, 1, "iron_block", "shroomlight");
        // crenellated top, gatehouse towers and wall towers
        k.battlements(-62, wz - 3, 62, wz, T + wh);
        for (int s : new int[]{-1, 1}) {
            k.spireTower(s * 34, wz - 9, T, 9, wh + 22, 34, true);       // the gatehouse towers stand behind the wall, clear of the sign
            k.spireTower(s * 67, wz - 1, T, 8, wh + 4, 26, false);
        }
        // the keep: tiered, buttressed, with a ring of spires
        int kz1 = -84, kz2 = -140;
        k.shell(-30, T + 1, kz1, 30, T + 44, kz2, 11);
        k.shell(-23, T + 45, kz1 - 7, 23, T + 66, kz2 + 7, 11);
        k.battlements(-30, kz1, 30, kz2, T + 44);
        for (int x = -24; x <= 24; x += 8) {
            if (Math.abs(x) < 8) { continue; }
            for (int y = T + 1; y <= T + 44; y++) for (int dz = 0; dz <= 2; dz++) k.set(x, y, kz1 + 1 + dz, y % 11 == 10 ? KBuild.TRIM : k.brick(x, y, kz1 + dz));
            for (int y = T + 45; y <= T + 52; y++) k.set(x, y, kz1 + 3, "polished_blackstone_brick_wall");
            k.cone(x, T + 53, kz1 + 3, 1, 6, KBuild.ROOF);
        }
        for (int x = -24; x <= 24; x += 8) {
            if (Math.abs(x) < 8) continue;
            for (int row = 0; row < 3; row++) k.window(x + 4, T + 8 + row * 11, kz1, 's', 3, 8);
        }
        for (int x : new int[]{-14, -6, 6, 14}) k.window(x, T + 49, kz1 - 7, 's', 3, 10);
        // the keep's great door
        k.archX(0, T + 1, kz1, 3, 15, 21, "air");
        for (int dy = 0; dy <= 22; dy++) {
            double half = dy <= 14 ? 8 : Math.max(0, 8 - (dy - 14));
            for (int s : new int[]{-1, 1}) k.set((int) Math.round(s * (half + 1)), T + 1 + dy, kz1, dy % 3 == 1 ? KBuild.GLOW : KBuild.GOLD);
        }
        // spires: central tower, corner towers, mid towers
        k.spireTower(0, (kz1 + kz2) / 2, T + 67, 11, 36, 56, true);
        int[][] corners = {{-34, kz1 - 4, 9, 66, 34}, {34, kz1 - 4, 9, 66, 34}, {-34, kz2 + 4, 9, 70, 36}, {34, kz2 + 4, 9, 70, 36}, {-34, (kz1 + kz2) / 2, 7, 54, 28}, {34, (kz1 + kz2) / 2, 7, 54, 28},
                {-14, kz2 - 3, 7, 62, 32}, {14, kz2 - 3, 7, 62, 32}};
        for (int[] t : corners) k.spireTower(t[0], t[1], T, t[2], t[3], t[4], true);
        // banners down the keep face
        for (int s : new int[]{-1, 1}) k.banner(s * 12, T + 40, kz1 + 4 - 4, 's', 3, 26);
        // chains between the great towers
        for (int y = T + 56; y <= T + 58; y++) for (int x = -34; x <= 34; x++) if (Math.abs(x) > 10 && (x & 1) == 0) k.late(x, y, kz1 - 4 - 9, "iron_chain[axis=x]");
        // outer towers on the terrace for the skyline
        int[][] outer = {{-100, -150, 6, 46, 40}, {100, -150, 6, 46, 40}, {-122, -118, 7, 58, 44}, {122, -118, 7, 58, 44}, {-80, -164, 5, 38, 34}, {80, -164, 5, 38, 34}, {-64, -112, 6, 40, 30}, {64, -112, 6, 40, 30}};
        for (int[] t : outer) k.spireTower(t[0], t[1], T, t[2], t[3], t[4], false);
    }

    // ================================================================== the Dragon Gate

    private static void dragonGate(KBuild k) {
        int z = GATE_Z;
        // the road down from the plaza, between low walls and lamp posts
        for (int zz = PLAZA_Z + PLAZA_R; zz <= z + 12; zz++)
            for (int x = -7; x <= 7; x++) {
                int ax = Math.abs(x);
                k.set(x, 0, zz, ax <= 1 ? "red_nether_bricks" : ax == 2 ? "gilded_blackstone" : ((Math.floorDiv(zz, 2) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles"));
                k.set(x, -1, zz, "stone");
                if (ax == 5 && zz < z - 8) k.lavaStrip(x, 0, zz);
                if (ax == 7) { k.set(x, 1, zz, "polished_blackstone_brick_wall"); if (Math.floorMod(zz, 9) == 0) { k.set(x, 2, zz, "polished_blackstone_brick_wall"); k.set(x, 3, zz, "polished_blackstone_brick_wall"); k.late(x, 4, zz, "lantern"); } }
            }
        // the walls closing the pass, and two enormous gothic towers
        for (int x = -62; x <= 62; x++)
            for (int zz = z - 3; zz <= z + 3; zz++)
                for (int y = 0; y <= 34; y++) {
                    boolean face = zz == z - 3 || zz == z + 3 || y >= 33;
                    if (face) k.set(x, y, zz, y % 11 == 10 ? KBuild.TRIM : k.brick(x, y, zz));
                    else k.set(x, y, zz, "air");
                }
        for (int dy = 0; dy <= 30; dy++) {
            double half = dy <= 16 ? 13 : Math.max(0, 13 - (dy - 16) * 1.0);
            for (int xx = -14; xx <= 14; xx++) if (Math.abs(xx) <= half) for (int zz = z - 3; zz <= z + 3; zz++) k.set(xx, dy, zz, "air");
            for (int s : new int[]{-1, 1}) for (int zz = z - 3; zz <= z + 3; zz++) k.set((int) Math.round(s * (half + 1)), dy, zz, dy % 3 == 1 ? KBuild.GLOW : KBuild.GOLD);
        }
        for (int x = -13; x <= 13; x++) for (int y = 16; y <= 29; y++) {      // the portcullis, lowered to head height
            double half = y <= 16 ? 13 : Math.max(0, 13 - (y - 16) * 1.0);
            if (Math.abs(x) <= half && (x % 2 == 0 || y % 3 == 0)) k.set(x, y, z - 1, "iron_bars");
        }
        k.battlements(-62, z - 3, 62, z + 3, 34);
        for (int s : new int[]{-1, 1}) {
            k.spireTower(s * 24, z, 0, 10, 62, 44, true);
            k.spireTower(s * 50, z, 0, 7, 40, 28, false);
            k.banner(s * 38, 30, z - 4, 'n', 3, 20);
            for (int zz = z - 26; zz < z - 4; zz++) { k.set(s * 16, 1, zz, "gilded_blackstone"); }
            k.statue(s * 10, 0, z - 9, 's', "netherite_block", "red_wool");
        }
        k.lettersX("DRAGON GATE", 0, 40, z - 4, 1, -1, "iron_block", "shroomlight");
        // the bridge beyond: over the chasm to the wilderness landing
        int zb = z + 4;
        for (int zz = zb; zz <= zb + 60; zz++)
            for (int x = -5; x <= 5; x++) {
                int ax = Math.abs(x);
                k.set(x, 0, zz, ax == 5 ? "polished_blackstone_bricks" : ax == 0 ? "red_nether_bricks" : "polished_deepslate");
                k.set(x, -1, zz, "polished_blackstone_bricks");
                if (ax == 5) { k.set(x, 1, zz, "polished_blackstone_brick_wall"); if (Math.floorMod(zz, 8) == 0) { k.set(x, 2, zz, "polished_blackstone_brick_wall"); k.late(x, 3, zz, "soul_lantern"); } }
            }
        for (int zz = zb + 6; zz <= zb + 60; zz += 12) for (int y = -2; y >= -18; y--) for (int x = -1; x <= 1; x++) k.set(x, y, zz, y == -18 ? "basalt[axis=y]" : "polished_blackstone_bricks");
        for (int x = -14; x <= 14; x++) for (int zz = zb + 61; zz <= zb + 80; zz++) {     // the landing platform
            if (Math.hypot(x, zz - zb - 70) > 14.5) continue;
            k.set(x, 0, zz, Math.floorMod(x + zz, 4) == 0 ? "gilded_blackstone" : "polished_deepslate");
            for (int y = -1; y >= -8 + (int) Math.hypot(x, zz - zb - 70) / 2; y--) k.set(x, y, zz, "blackstone");
        }
    }
}
