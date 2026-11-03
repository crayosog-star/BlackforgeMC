package net.alloymc.core.survival;

import net.alloymc.core.build.Canvas;

import java.util.Random;

/**
 * The OneBlock spawn, v2: a dark gothic keep on a floating rock. A paved plaza round the giant OneBlock (on a
 * tiered dais with a ring of phase blocks), a spired keep behind the two gates, a colonnade hall for the four
 * crate vaults on the west side, a gatehouse behind the arrival point, and amethyst islets drifting round it.
 * Fixed points the rest of the plugin relies on: arrival (0.5, Y+1, 14.5), gates at z = -18 (x = +-7), the giant
 * block at (0, 0), crate vaults at {@link SkyHub#OB_CRATES}.
 */
public final class OneBlockSpawnBuilder {
    private OneBlockSpawnBuilder() {}

    private static final int Y = SkyHub.Y;
    /** The OneBlock spawn's own crates, on the west side of its island. */
    static final java.util.List<SurvivalLayout.CrateSpot> CRATES = java.util.List.of(
            new SurvivalLayout.CrateSpot("iron", -16, -6), new SurvivalLayout.CrateSpot("vote", -16, -2),
            new SurvivalLayout.CrateSpot("gold", -16, 2), new SurvivalLayout.CrateSpot("netherite", -16, 6));
    private static final String BR = "minecraft:polished_blackstone_bricks", CR = "minecraft:cracked_polished_blackstone_bricks",
            DT = "minecraft:deepslate_tiles", DB = "minecraft:deepslate_bricks", BS = "minecraft:blackstone",
            PB = "minecraft:polished_blackstone", GILD = "minecraft:gilded_blackstone", OBS = "minecraft:crying_obsidian",
            WALL = "minecraft:polished_blackstone_brick_wall", CHAIN = "minecraft:iron_chain[axis=y]";

    public static Canvas build() {
        Canvas c = new Canvas();
        rock(c);
        avenue(c);
        dais(c);
        keep(c);
        gates(c);
        crateHall(c);
        gatehouse(c);
        lampPosts(c);
        islets(c);
        return c;
    }

    // ------------------------------------------------------------------ the island

    private static void rock(Canvas c) {
        Random rnd = new Random(23);
        int r = 27;
        for (int x = -r - 4; x <= r + 4; x++)
            for (int z = -r - 4; z <= r + 4; z++) {
                double d = Math.hypot(x, z), a = Math.atan2(z, x);
                double R = r + 2 * Math.sin(a * 5 + 3) + 1.5 * Math.sin(a * 3);
                if (d > R) continue;
                String top;
                if (R - d < 1.5) top = BS;
                else if (d < 19) top = (((x >> 1) + (z >> 1)) & 1) == 0 ? BR : DT;
                else top = rnd.nextInt(3) == 0 ? "minecraft:moss_block" : "minecraft:grass_block";
                c.set(x, Y, z, top);
                int depth = (int) Math.round(Math.pow(1 - d / R, 0.8) * r * 0.95 + 3 + rnd.nextInt(3));
                for (int y = 1; y <= depth; y++) {
                    String b = y <= 1 ? BS : y < depth - 1 ? "minecraft:deepslate" : "minecraft:basalt";
                    if (y > 3 && y < depth - 2 && rnd.nextInt(70) == 0) b = "minecraft:magma_block";
                    c.set(x, Y - y, z, b);
                }
                if (d > 19 && R - d > 2 && rnd.nextInt(9) == 0) c.setLate(x, Y + 1, z, rnd.nextBoolean() ? "minecraft:flowering_azalea" : "minecraft:azalea");
            }
        // a glowing seam round the plaza edge
        c.ring(0, Y, 0, 18.4, 19.4, "minecraft:magma_block");
    }

    private static void avenue(Canvas c) {
        for (int z = -17; z <= 18; z++) {
            for (int x = -1; x <= 1; x++) c.set(x, Y, z, PB);
            c.set(0, Y, z, z % 4 == 0 ? GILD : DB);
        }
    }

    // ------------------------------------------------------------------ the giant OneBlock

    private static void dais(Canvas c) {
        c.disc(0, Y, 0, 9, DT);
        c.ring(0, Y, 0, 7.5, 9, BR);
        c.ring(0, Y, 0, 3.6, 4.4, OBS);
        c.disc(0, Y, 0, 3.4, PB);
        // the giant one block
        for (int x = -1; x <= 1; x++) for (int y = 1; y <= 3; y++) for (int z = -1; z <= 1; z++)
            c.set(x, Y + y, z, "minecraft:" + (y == 3 ? "grass_block" : "dirt"));
        // a ring of phase blocks, one per stage of the OneBlock
        String[] phases = {"grass_block", "sandstone", "snow_block", "netherrack", "prismarine", "end_stone", "purpur_block", "crying_obsidian"};
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            int px = (int) Math.round(Math.cos(a) * 6.2), pz = (int) Math.round(Math.sin(a) * 6.2);
            c.set(px, Y, pz, "minecraft:" + phases[i]);
            // a pillar between each pair, topped with a soul lantern
            double b = a + Math.PI / 8;
            int lx = (int) Math.round(Math.cos(b) * 8), lz = (int) Math.round(Math.sin(b) * 8);
            c.set(lx, Y + 1, lz, BR);
            c.set(lx, Y + 2, lz, WALL);
            c.set(lx, Y + 3, lz, WALL);
            c.set(lx, Y + 4, lz, OBS);
            c.setLate(lx, Y + 5, lz, "minecraft:soul_lantern");
        }
        for (int[] f : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) {   // braziers
            c.set(f[0], Y + 1, f[1], "minecraft:chiseled_polished_blackstone");
            c.setLate(f[0], Y + 2, f[1], "minecraft:soul_campfire");
        }
    }

    // ------------------------------------------------------------------ the keep behind the gates

    private static void keep(Canvas c) {
        c.fill(-17, Y + 1, -23, 17, Y + 8, -21, BR);
        for (int x = -17; x <= 17; x += 2) c.set(x, Y + 9, -23, BR);   // crenellations
        for (int x = -17; x <= 17; x += 2) c.set(x, Y + 9, -21, BR);
        for (int x : new int[]{-12, -4, 4, 12}) {   // lit windows
            for (int y = 3; y <= 6; y++) { c.set(x, Y + y, -21, "minecraft:orange_stained_glass"); c.set(x, Y + y, -22, "minecraft:shroomlight"); }
            c.set(x, Y + 7, -21, "minecraft:red_stained_glass");
            c.set(x, Y + 2, -21, GILD);
        }
        for (int x = -16; x <= 16; x += 8) c.fill(x, Y + 1, -20, x, Y + 8, -20, DB);   // buttresses
        // great central keep with a spire
        c.fill(-3, Y + 1, -27, 3, Y + 15, -21, DB);
        c.fill(-2, Y + 3, -21, 2, Y + 11, -21, "minecraft:orange_stained_glass");
        c.fill(-2, Y + 3, -22, 2, Y + 11, -22, "minecraft:shroomlight");
        c.fill(0, Y + 3, -21, 0, Y + 11, -21, BR);   // mullion
        c.fill(-3, Y + 12, -21, 3, Y + 12, -21, GILD);
        spire(c, 0, Y + 16, -24, 4, 10);
        // corner towers
        for (int sx : new int[]{-1, 1}) tower(c, sx * 17, -22, 2, 17);
    }

    private static void tower(Canvas c, int cx, int cz, int half, int height) {
        c.fill(cx - half, Y + 1, cz - half, cx + half, Y + height, cz + half, DB);
        for (int y = 4; y <= height - 3; y += 6) {
            c.set(cx, Y + y, cz + half, "minecraft:orange_stained_glass");
            c.set(cx, Y + y + 1, cz + half, "minecraft:orange_stained_glass");
            c.set(cx + half, Y + y, cz, "minecraft:orange_stained_glass");
            c.set(cx - half, Y + y, cz, "minecraft:orange_stained_glass");
        }
        c.fill(cx - half - 1, Y + height + 1, cz - half - 1, cx + half + 1, Y + height + 1, cz + half + 1, BR);
        spire(c, cx, Y + height + 2, cz, half + 1, 6);
    }

    /** A stepped pyramid roof topped with a lightning rod. */
    private static void spire(Canvas c, int cx, int baseY, int cz, int half, int height) {
        for (int i = 0; i < height && half - i * half / height >= 0; i++) {
            int h = half - i * half / height;
            c.fill(cx - h, baseY + i, cz - h, cx + h, baseY + i, cz + h, i % 3 == 2 ? BS : DT);
        }
        c.setLate(cx, baseY + height, cz, "minecraft:lightning_rod");
    }

    // ------------------------------------------------------------------ the two gates

    private static void gates(Canvas c) {
        for (int gx : new int[]{-7, 7}) {
            gate(c, gx, -18, gx < 0 ? "minecraft:purpur_block" : "minecraft:prismarine_bricks", gx < 0 ? "minecraft:purpur_pillar" : "minecraft:dark_prismarine");
            // a tall buttress either side with a hanging lantern
            for (int s : new int[]{-1, 1}) {
                int px = gx + s * 4;
                c.fill(px, Y + 1, -19, px, Y + 7, -19, DB);
                c.set(px, Y + 8, -19, GILD);
                c.setLate(px, Y + 9, -19, "minecraft:soul_lantern");
            }
        }
    }

    private static void gate(Canvas c, int gx, int gz, String frame, String trim) {
        for (int x = gx - 2; x <= gx + 2; x++)
            for (int y = Y + 1; y <= Y + 6; y++) {
                boolean edge = x == gx - 2 || x == gx + 2 || y == Y + 6;
                c.set(x, y, gz, edge ? (y == Y + 6 || y == Y + 1 ? trim : frame) : "minecraft:nether_portal[axis=x]");
                if (y == Y + 5 && !edge) c.set(x, y, gz, frame);
            }
        for (int x = gx - 3; x <= gx + 3; x++) c.set(x, Y, gz + 1, "minecraft:polished_andesite");
    }

    // ------------------------------------------------------------------ the crate colonnade (west)

    private static void crateHall(Canvas c) {
        c.fill(-23, Y, -10, -13, Y, 10, DT);
        for (SurvivalLayout.CrateSpot s : CRATES) {
            c.fill(s.x() - 1, Y, s.z() - 1, s.x() + 1, Y, s.z() + 1, PB);
            c.set(s.x(), Y, s.z(), GILD);
            c.set(s.x(), Y + 1, s.z(), "minecraft:vault[facing=east]");
        }
        c.fill(-22, Y + 1, -10, -21, Y + 9, 10, BR);   // the back wall
        for (int z = -10; z <= 10; z += 2) c.set(-22, Y + 10, z, BR);
        for (SurvivalLayout.CrateSpot s : CRATES) {   // a glowing niche behind each vault
            for (int y = 2; y <= 5; y++) c.set(-20, Y + y, s.z(), y == 5 ? GILD : "minecraft:shroomlight");
            c.set(-20, Y + 1, s.z(), GILD);
        }
        for (int z = -8; z <= 8; z += 4) {   // colonnade
            c.fill(-18, Y + 1, z, -18, Y + 7, z, DB);
            c.set(-18, Y + 8, z, GILD);
            c.setLate(-18, Y + 9, z, "minecraft:soul_lantern");
        }
        c.fill(-18, Y + 8, -9, -18, Y + 8, 9, BR);   // lintel beam
        for (int z = -6; z <= 6; z += 4) {
            c.set(-18, Y + 7, z, CHAIN);
            c.set(-18, Y + 6, z, CHAIN);
            c.setLate(-18, Y + 5, z, "minecraft:soul_lantern[hanging=true]");
        }
        for (int sz : new int[]{-1, 1}) tower(c, -22, sz * 12, 1, 12);
    }

    // ------------------------------------------------------------------ the arrival gatehouse

    private static void gatehouse(Canvas c) {
        for (int sx : new int[]{-1, 1}) {
            c.fill(sx * 5 - 1, Y + 1, 18, sx * 5 + 1, Y + 11, 19, DB);
            c.set(sx * 5, Y + 12, 18, GILD);
            c.setLate(sx * 5, Y + 13, 18, "minecraft:soul_lantern");
        }
        c.fill(-5, Y + 9, 18, 5, Y + 11, 19, BR);   // lintel
        for (int x : new int[]{-2, 0, 2}) {
            c.setLate(x, Y + 8, 18, CHAIN);
            c.setLate(x, Y + 7, 18, "minecraft:soul_lantern[hanging=true]");
        }
        c.fill(-3, Y + 10, 18, 3, Y + 10, 18, GILD);
        // two guardian pillars flanking the way in
        for (int sx : new int[]{-1, 1}) {
            c.fill(sx * 4, Y + 1, 9, sx * 4, Y + 3, 9, BR);
            c.set(sx * 4, Y + 4, 9, OBS);
            c.setLate(sx * 4, Y + 5, 9, "minecraft:soul_lantern");
        }
    }

    private static void lampPosts(Canvas c) {
        for (int z : new int[]{-12, -6, 6, 12}) for (int sx : new int[]{-1, 1}) {
            c.set(sx * 3, Y + 1, z, WALL);
            c.set(sx * 3, Y + 2, z, WALL);
            c.set(sx * 3, Y + 3, z, WALL);
            c.setLate(sx * 3, Y + 4, z, "minecraft:lantern");
        }
    }

    // ------------------------------------------------------------------ amethyst islets

    private static void islets(Canvas c) {
        Random rnd = new Random(99);
        for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2 * i / 6 + 0.4;
            int ix = (int) Math.round(Math.cos(a) * 40), iz = (int) Math.round(Math.sin(a) * 40), iy = Y + rnd.nextInt(9) - 4;
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
                double d = Math.hypot(x, z);
                if (d > 3.2) continue;
                c.set(ix + x, iy, iz + z, d > 2.2 ? BS : "minecraft:moss_block");
                int depth = (int) Math.round((3.2 - d) * 1.6) + 1;
                for (int y = 1; y <= depth; y++) c.set(ix + x, iy - y, iz + z, y == depth ? "minecraft:basalt" : "minecraft:deepslate");
            }
            c.set(ix, iy + 1, iz, "minecraft:amethyst_block");
            c.set(ix, iy + 2, iz, "minecraft:budding_amethyst");
            c.set(ix, iy + 3, iz, "minecraft:amethyst_cluster[facing=up]");
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) c.set(ix + d[0], iy + 1, iz + d[1], "minecraft:small_amethyst_bud[facing=up]");
        }
    }
}
