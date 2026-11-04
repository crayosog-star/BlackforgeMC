package net.blackforge.core.survival;

import static net.blackforge.core.survival.InfernalLayout.*;

/**
 * What rewards exploring in the Infernal Kingdom: the keep's throne hall, spiral stairs up two corner towers to rooftop
 * viewpoints, a hidden cache behind the Treasury, a dragon's skull watching over the back of the terrace, and a few lore
 * details (a forge hearth with a ruined sword, bone piles round the skull).
 */
final class KingdomExtras {
    private KingdomExtras() {}

    static void build(KBuild k) {
        throneHall(k);
        viewpoints(k);
        foundersCache(k);
        dragonSkull(k);
    }

    // ------------------------------------------------------------------ the keep's throne hall

    private static void throneHall(KBuild k) {
        int T = TERRACE_Y, z1 = -86, z2 = -138;
        for (int x = -28; x <= 28; x++)
            for (int z = z1; z >= z2; z--) {
                int ax = Math.abs(x);
                String b = ax <= 3 ? ((x + z) % 2 == 0 ? "red_nether_bricks" : "polished_blackstone") : ax == 4 ? "gilded_blackstone" : ((Math.floorDiv(x, 4) + Math.floorDiv(z, 4)) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles";
                k.set(x, T, z, b);
                if (ax == 7 && z > z2 + 10) { k.lavaStrip(x, T, z); k.set(x, T - 1, z, "polished_blackstone"); k.late(x, T - 1, z, "lava"); k.set(x - 1, T, z, "gilded_blackstone"); k.set(x + 1, T, z, "gilded_blackstone"); }
            }
        for (int s : new int[]{-1, 1})
            for (int z = z1 - 8; z >= z2 + 8; z -= 11) {
                int px = s * 16;
                for (int y = T + 1; y <= T + 42; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) k.set(px + dx, y, z + dz, y % 8 == 0 ? "gold_block" : (Math.abs(dx) + Math.abs(dz) == 2 ? "polished_blackstone" : k.brick(px + dx, y, z + dz)));
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) { k.set(px + dx, T + 1, z + dz, "gilded_blackstone"); k.set(px + dx, T + 41, z + dz, "gilded_blackstone"); }
                k.brazier(px - s * 4, T + 1, z);
            }
        // chandeliers down the nave
        for (int z = z1 - 6; z >= z2 + 10; z -= 11) {
            for (int y = T + 42; y >= T + 33; y--) k.late(0, y, z, "iron_chain[axis=y]");
            for (int dx = -3; dx <= 3; dx += 3) k.late(dx, T + 32, z, "lantern[hanging=true]");
            k.set(0, T + 32, z - 1, "gold_block"); k.set(0, T + 32, z + 1, "gold_block");
        }
        // the dais and the throne
        for (int step = 0; step < 4; step++) for (int x = -12 + step; x <= 12 - step; x++) for (int z = z2 + 9 - step * 2; z >= z2 + 6 - step * 2 - 1; z--) for (int y = T + 1; y <= T + 1 + step; y++) k.set(x, y, z, y == T + 1 + step ? (step % 2 == 0 ? "gilded_blackstone" : "polished_blackstone_bricks") : "polished_blackstone");
        int ty = T + 5, tz = z2 + 2;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) k.set(dx, ty, tz + dz, "netherite_block");
        for (int dx : new int[]{-3, 3}) for (int y = ty; y <= ty + 4; y++) for (int dz = -1; dz <= 1; dz++) k.set(dx, y, tz + dz, "netherite_block");
        for (int y = ty; y <= ty + 16; y++) for (int dx = -4; dx <= 4; dx++) { double hw = 4 - Math.max(0, y - ty - 10) * 0.6; if (Math.abs(dx) <= hw) k.set(dx, y, tz - 2, (y == ty + 15 || Math.abs(dx) == 4) ? "gold_block" : "netherite_block"); }
        for (int dx : new int[]{-2, 2}) k.set(dx, ty + 9, tz - 1, KBuild.GLOW);
        for (int dx : new int[]{-10, 10}) k.banner(dx, ty + 18, tz - 2, 's', 2, 16);
        k.late(-6, ty, tz, "campfire[lit=true]"); k.late(6, ty, tz, "campfire[lit=true]");
        k.set(-6, ty - 1, tz, "chiseled_polished_blackstone"); k.set(6, ty - 1, tz, "chiseled_polished_blackstone");
    }

    // ------------------------------------------------------------------ spiral stairs and rooftop viewpoints

    private static void viewpoints(KBuild k) {
        int T = TERRACE_Y;
        int[][] towers = {{-34, -88, 9, 66}, {34, -88, 9, 66}};
        for (int[] t : towers) {
            int cx = t[0], cz = t[1], r = t[2], h = t[3], land = T + h - 8;
            double rad = r - 2.5, a = Math.PI / 2;
            // doorway at the foot, on the courtyard side
            for (int y = T + 1; y <= T + 3; y++) for (int dz = r - 2; dz <= r; dz++) k.set(cx, y, cz + dz, "air");
            for (int y = T + 1; y <= land; y++) {
                a += 1.25 / rad;
                for (double d = -1.2; d <= 1.2; d += 0.6) {
                    int bx = cx + (int) Math.round(Math.cos(a) * (rad + d)), bz = cz + (int) Math.round(Math.sin(a) * (rad + d));
                    k.set(bx, y - 1, bz, y == land ? "gilded_blackstone" : "polished_blackstone_bricks");
                    for (int hy = 0; hy < 3; hy++) if (y + hy != y - 1) k.set(bx, y + hy, bz, "air");
                }
            }
            // the viewpoint: a doorway out through the wall at the top and a railed balcony round a quarter of the tower
            double fa = a;
            for (double d = rad - 1.5; d <= r + 3.5; d += 0.5) {
                int bx = cx + (int) Math.round(Math.cos(fa) * d), bz = cz + (int) Math.round(Math.sin(fa) * d);
                k.set(bx, land - 1, bz, "gilded_blackstone");
                for (int hy = 0; hy < 4; hy++) k.set(bx, land + hy, bz, d > r + 3 ? "polished_blackstone_brick_wall" : "air");
            }
            for (double span = -0.9; span <= 0.9; span += 0.04)
                for (double d = r + 0.5; d <= r + 3.5; d += 0.5) {
                    int bx = cx + (int) Math.round(Math.cos(fa + span) * d), bz = cz + (int) Math.round(Math.sin(fa + span) * d);
                    k.set(bx, land - 1, bz, d > r + 2.5 ? "polished_blackstone_bricks" : "polished_deepslate");
                    if (d > r + 3.1) { k.set(bx, land, bz, "polished_blackstone_brick_wall"); k.set(bx, land + 1, bz, "polished_blackstone_brick_wall"); if (Math.abs(span) % 0.32 < 0.04) k.late(bx, land + 2, bz, "lantern"); }
                    else for (int hy = 0; hy < 4; hy++) k.set(bx, land + hy, bz, "air");
                }
            // doorway through the shell at the landing
            for (int hy = 0; hy < 3; hy++) for (double d = r - 2; d <= r + 0.6; d += 0.5) k.set(cx + (int) Math.round(Math.cos(fa) * d), land + hy, cz + (int) Math.round(Math.sin(fa) * d), "air");
        }
    }

    // ------------------------------------------------------------------ the Founders' Cache, behind the Treasury

    private static void foundersCache(KBuild k) {
        int x1 = -60, x2 = -47, z1 = -86, z2 = -98, h = 7;
        k.hall(x1, 0, z1, x2, h, z2, 4, "polished_blackstone", "deepslate_tiles");
        // the way in: a narrow arch off the Treasury's back-left corner, hung with chains and a single soul lantern
        for (int x = TREASURY_X1 - 3; x <= TREASURY_X1 + 1; x++) for (int y = 1; y <= 3; y++) for (int z = -92; z >= -93; z--) k.set(x, y, z, "air");
        for (int y = 1; y <= 3; y++) k.late(TREASURY_X1 - 1, y, -91, y == 3 ? "soul_lantern[hanging=true]" : "iron_chain[axis=y]");
        k.set(TREASURY_X1 - 2, 4, -92, "chiseled_polished_blackstone"); k.set(TREASURY_X1 - 2, 4, -93, "chiseled_polished_blackstone");
        // inside: a hearth with a ruined sword, a coffer, a lectern with the founders' words, soul fire
        k.set(-58, 1, -92, "blackstone"); k.late(-58, 2, -92, "soul_campfire[lit=true]");
        k.set(-52, 1, -88, "ender_chest[facing=south]");
        k.set(-52, 1, -96, "lectern[facing=north]");
        for (int i = 0; i < 5; i++) k.set(-50 + (i % 2), 1 + i / 2, -92 - 2 + i % 3, "bone_block");
        k.set(-56, 1, -88, "gold_block"); k.set(-56, 2, -88, "gold_block"); k.set(-56, 3, -88, "iron_block");
        k.late(-55, 6, -92, "soul_lantern[hanging=true]"); k.late(-55, 7, -92, "iron_chain[axis=y]");
        for (int x = -59; x <= -48; x += 3) k.set(x, 6, -86, "orange_stained_glass");   // glow from behind the stone
    }

    // ------------------------------------------------------------------ a dragon's skull

    private static void dragonSkull(KBuild k) {
        int T = TERRACE_Y, cz = TERRACE_BACK + 8, base = T + 1;
        for (int dx = -11; dx <= 11; dx++)
            for (int dy = 0; dy <= 13; dy++) {
                double cranium = (dx * dx) / 121.0 + Math.pow(dy - 7, 2) / 49.0;
                boolean snout = dy <= 5 && Math.abs(dx) <= 8;
                if (cranium > 1 && !snout) continue;
                for (int dz = 0; dz < 7; dz++) {
                    boolean shellFace = dz <= 1 || dz >= 5 || cranium > 0.55 || dy == 0;
                    if (!shellFace) continue;
                    String b = (dx + dy + dz) % 5 == 0 ? "calcite" : "bone_block";
                    k.set(dx, base + dy, cz - dz, b);
                }
            }
        for (int s : new int[]{-1, 1}) for (int dx = 4; dx <= 6; dx++) for (int dy = 7; dy <= 9; dy++) { k.set(s * dx, base + dy, cz, "air"); k.set(s * dx, base + dy, cz - 1, KBuild.GLOW); }
        for (int dx = -8; dx <= 8; dx += 2) { k.set(dx, base, cz, "air"); k.set(dx, base + 1, cz, "calcite"); k.set(dx, base + 2, cz, "calcite"); }
        for (int s : new int[]{-1, 1}) for (int i = 0; i < 11; i++) { int hx = s * (8 + i / 2), hy = 11 + i; for (int dz = 2; dz <= 4; dz++) k.set(hx, base + hy, cz - dz, i < 8 ? "bone_block" : "calcite"); }
        for (int i = 0; i < 18; i++) { int bx = -16 + k.rnd.nextInt(33), bz = cz - 3 - k.rnd.nextInt(4); k.set(bx, T + 1, bz, "bone_block"); if (k.rnd.nextBoolean()) k.set(bx, T + 2, bz, "skeleton_skull[rotation=" + k.rnd.nextInt(16) + "]"); }
        k.late(0, base + 14, cz - 3, "soul_campfire[lit=true]");
    }
}
