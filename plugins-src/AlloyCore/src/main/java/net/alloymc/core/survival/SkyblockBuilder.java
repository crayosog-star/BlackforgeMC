package net.alloymc.core.survival;

import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.List;
import java.util.Random;

/**
 * Sky Harbor: the Skyblock server's spawn. A lush floating island with cherry trees and
 * waterfalls, the Island Gate (walk in to go to your island), crates, a market, the Hall of Fame.
 */
public final class SkyblockBuilder {
    // ---- layout (shared with SkyblockModule)
    public static final int Y = 150, R = 42;
    public static final double SPAWN_X = 0.5, SPAWN_Y = Y + 1, SPAWN_Z = 24.5;
    public static final int GATE_Z = -30;   // gates face south, opening along x
    public static final int SKY_GATE_X = -10, OB_GATE_X = 10;
    public static final List<SurvivalLayout.CrateSpot> CRATES = List.of(
        new SurvivalLayout.CrateSpot("iron", -26, -6), new SurvivalLayout.CrateSpot("vote", -26, -2),
        new SurvivalLayout.CrateSpot("gold", -26, 2), new SurvivalLayout.CrateSpot("netherite", -26, 6));
    public static final List<SurvivalLayout.HallSpot> HALL = List.of(
        new SurvivalLayout.HallSpot(24, -10), new SurvivalLayout.HallSpot(27, -5), new SurvivalLayout.HallSpot(28, 0),
        new SurvivalLayout.HallSpot(27, 5), new SurvivalLayout.HallSpot(24, 10));
    /** Market stalls: bell = /ah, smithing table = Scrap Shop, ender chest = /daily. */
    public static final int MARKET_X = -14, MARKET_Z = 30;
    public static final int[] BELL = {MARKET_X - 5, Y + 2, MARKET_Z + 3}, SHOP = {MARKET_X, Y + 1, MARKET_Z + 3}, DAILY = {MARKET_X + 5, Y + 1, MARKET_Z + 3};

    public static boolean inGate(double x, double y, double z) { return inGateAt(x, y, z, SKY_GATE_X); }
    public static boolean inOneBlockGate(double x, double y, double z) { return inGateAt(x, y, z, OB_GATE_X); }
    private static boolean inGateAt(double x, double y, double z, int gx) {
        return Math.abs(x - (gx + 0.5)) <= 3.5 && Math.abs(z - (GATE_Z + 0.5)) <= 1.2 && y >= Y + 1 && y <= Y + 10;
    }
    public static boolean inSpawn(double x, double y, double z) { return x * x + z * z <= 70 * 70 && y > Y - 50 && y < Y + 60; }

    private final Canvas c = new Canvas();
    private final Random rnd = new Random(0x5CB10C);

    public static Canvas build() {
        SkyblockBuilder b = new SkyblockBuilder();
        b.island();
        b.plaza();
        b.fountain();
        b.paths();
        b.gate();
        b.crates();
        b.market();
        b.hall();
        b.trees();
        b.flowers();
        b.waterfalls();
        b.satellites();
        b.railing();
        b.lettering();
        return b.c;
    }

    // ------------------------------------------------------------------ island

    private double edge(double a) { return R + 2.5 * Math.sin(3 * a + 1) + 1.6 * Math.sin(5 * a + 2) + Math.sin(9 * a); }
    private boolean on(int x, int z) { return Math.hypot(x, z) <= edge(Math.atan2(z, x)); }

    private void island() {
        for (int x = -R - 6; x <= R + 6; x++)
            for (int z = -R - 6; z <= R + 6; z++) {
                double r = Math.hypot(x, z), E = edge(Math.atan2(z, x));
                if (r > E) continue;
                int depth = Math.max(2, (int) Math.round(Math.pow(1 - r / E, 0.7) * 36 + 2 + rnd.nextInt(2)));
                for (int d = 0; d < depth; d++) {
                    String b;
                    if (d == 0) b = E - r < 1.5 && rnd.nextBoolean() ? "minecraft:moss_block" : "minecraft:grass_block";
                    else if (d <= 2) b = "minecraft:dirt";
                    else {
                        int n = rnd.nextInt(100);
                        b = n < 3 ? "minecraft:copper_ore" : n < 5 ? "minecraft:iron_ore" : n < 6 ? "minecraft:emerald_ore"
                                : d > 12 ? (n < 60 ? "minecraft:deepslate" : "minecraft:tuff") : (n < 55 ? "minecraft:stone" : n < 75 ? "minecraft:andesite" : n < 88 ? "minecraft:moss_block" : "minecraft:tuff");
                    }
                    c.set(x, Y - d, z, b);
                }
                // hanging roots and vines under the rim
                if (E - r < 3 && rnd.nextInt(5) == 0) {
                    int len = 2 + rnd.nextInt(5);
                    for (int k = 1; k <= len; k++) c.setLate(x, Y - depth - k + 1, z, k == len ? "minecraft:glow_lichen[down=true]" : "minecraft:hanging_roots");
                }
            }
    }

    private void plaza() {
        for (int x = -15; x <= 15; x++)
            for (int z = -15; z <= 15; z++) {
                double r = Math.hypot(x, z);
                if (r > 14.4) continue;
                String b = ((Math.floorDiv(x, 2) + Math.floorDiv(z, 2)) & 1) == 0 ? "minecraft:polished_deepslate" : "minecraft:deepslate_tiles";
                if (r > 8.4 && r <= 9.5) b = "minecraft:waxed_oxidized_cut_copper";
                if (r > 13.3) b = "minecraft:mossy_stone_bricks";
                c.set(x, Y, z, b);
            }
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI / 5;
            c.set((int) Math.round(11.5 * Math.cos(a)), Y, (int) Math.round(11.5 * Math.sin(a)), "minecraft:waxed_oxidized_copper_bulb[lit=true]");
        }
    }

    private void fountain() {
        for (int x = -5; x <= 5; x++)
            for (int z = -5; z <= 5; z++) {
                double r = Math.hypot(x, z);
                if (r <= 3.6) { c.set(x, Y - 1, z, "minecraft:prismarine_bricks"); c.setLate(x, Y, z, "minecraft:water"); }
                else if (r <= 4.7) {
                    c.set(x, Y, z, "minecraft:mossy_stone_bricks");
                    // four gaps in the rim so nobody who hops into the basin gets stuck in it
                    if (Math.abs(x) > 1 && Math.abs(z) > 1) c.set(x, Y + 1, z, ((x + z) & 1) == 0 ? "minecraft:gold_block" : "minecraft:waxed_oxidized_cut_copper");
                    else c.set(x, Y, z, "minecraft:gold_block");
                }
            }
        // a little floating grass islet hovering over the fountain, dripping water
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            int d2 = x * x + z * z;
            if (d2 > 5) continue;
            c.set(x, Y + 7, z, "minecraft:grass_block");
            if (d2 <= 2) c.set(x, Y + 6, z, "minecraft:dirt");
        }
        c.set(0, Y + 5, 0, "minecraft:waxed_oxidized_copper");
        c.setLate(0, Y + 4, 0, "minecraft:water");
        for (int[] k : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) c.setLate(k[0], Y + 8, k[1], "minecraft:pink_petals[flower_amount=4]");
    }

    private void path(int x, int z, int p) {
        c.set(x, Y, z, Math.abs(p) == 2 ? "minecraft:mossy_stone_bricks" : Math.abs(p) == 1 ? "minecraft:waxed_oxidized_cut_copper" : "minecraft:polished_deepslate");
    }

    private void paths() {
        for (int z = -15; z >= GATE_Z + 7; z--) for (int p = -2; p <= 2; p++) path(p, z, p);
        for (int x = -12; x <= 12; x++) for (int p = -2; p <= 2; p++) path(x, GATE_Z + 6 + p, p);
        for (int gx : new int[]{SKY_GATE_X, OB_GATE_X}) for (int z = GATE_Z + 4; z >= GATE_Z + 2; z--) for (int p = -2; p <= 2; p++) path(gx + p, z, p);
        for (int z = 15; z <= 30; z++) for (int p = -2; p <= 2; p++) path(p, z, p);
        for (int x = -15; x >= -22; x--) for (int p = -2; p <= 2; p++) path(x, p, p);
        for (int x = 15; x <= 20; x++) for (int p = -2; p <= 2; p++) path(x, p, p);
        for (int d = 0; d <= 10; d++) for (int p = -1; p <= 1; p++) path(-6 - d, 18 + d + p, 0);   // diagonal to the market
    }

    private void gate() {
        portalGate(SKY_GATE_X, false);
        portalGate(OB_GATE_X, true);
    }

    /** Island Gate (oxidized copper and moss) or OneBlock Gate (deepslate, amethyst and gold). */
    private void portalGate(int gx, boolean oneblock) {
        int gz = GATE_Z;
        String main = oneblock ? "minecraft:polished_deepslate" : "minecraft:waxed_oxidized_cut_copper";
        String accent = oneblock ? "minecraft:gold_block" : "minecraft:waxed_oxidized_copper";
        String trim = oneblock ? "minecraft:amethyst_block" : "minecraft:moss_block";
        String light = oneblock ? "minecraft:pearlescent_froglight" : "minecraft:waxed_oxidized_copper_bulb[lit=true]";
        for (int u = -7; u <= 7; u++) for (int w = -2; w <= 2; w++) c.set(gx + u, Y, gz + w, accent);
        for (int u = -6; u <= 6; u++)
            for (int ry = 0; ry <= 11; ry++)
                for (int w = -1; w <= 1; w++) {
                    int au = Math.abs(u), y = Y + 1 + ry;
                    boolean open = (ry <= 7 && au <= 3) || (ry == 8 && au <= 2) || (ry == 9 && au <= 1);
                    if (open) { if (w == 0) c.set(gx + u, y, gz, "minecraft:nether_portal[axis=x]"); continue; }
                    if (au >= 5 && ry >= 11) continue;
                    boolean edgeOfOpening = (ry <= 8 && au == 4) || (ry == 8 && au == 3) || (ry == 9 && au == 2) || (ry == 10 && au <= 1);
                    String b = au == 6 || ry == 11 ? accent : edgeOfOpening ? trim : main;
                    if (au == 5 && (ry == 3 || ry == 6) && w != 0) b = light;
                    c.set(gx + u, y, gz + w, b);
                }
        if (oneblock) {
            // the One Block itself, floating above the gate
            for (int u = -2; u <= 2; u++) for (int w = -1; w <= 1; w++) c.set(gx + u, Y + 12, gz + w, "minecraft:polished_deepslate");
            c.set(gx, Y + 13, gz, "minecraft:gold_block");
            c.set(gx, Y + 15, gz, "minecraft:grass_block");
            for (int[] k : new int[][]{{-2, 0}, {2, 0}}) c.setLate(gx + k[0], Y + 13, gz + k[1], "minecraft:end_rod[facing=up]");
            return;
        }
        for (int u = -5; u <= 5; u++) for (int w = -1; w <= 1; w++) c.set(gx + u, Y + 12, gz + w, "minecraft:moss_block");
        for (int side : new int[]{-1}) {
            int cu = gx + side * 11, fy = Y + 8;
            for (int u = -2; u <= 2; u++) for (int w = -2; w <= 2; w++) {
                int d2 = u * u + w * w;
                if (d2 > 5) continue;
                c.set(cu + u, fy, gz + w, "minecraft:grass_block");
                if (d2 <= 2) c.set(cu + u, fy - 1, gz + w, "minecraft:dirt");
            }
            c.set(cu, fy - 2, gz, "minecraft:waxed_oxidized_copper");
            for (int k = 1; k <= 3; k++) c.set(cu, fy + k, gz, "minecraft:cherry_log");
            blossom(cu, fy + 4, gz, 2);
        }
    }

    private void crates() {
        int x0 = -26;
        for (int x = x0 - 3; x <= x0 + 3; x++)
            for (int z = -10; z <= 10; z++) {
                c.set(x, Y, z, "minecraft:polished_deepslate");
                if (Math.abs(x - x0) == 3 || Math.abs(z) == 10) c.set(x, Y, z, "minecraft:waxed_oxidized_cut_copper");
            }
        for (SurvivalLayout.CrateSpot s : CRATES) {
            String plinth = switch (s.crate()) {
                case "iron" -> "minecraft:gilded_blackstone"; case "gold" -> "minecraft:magma_block";   // Cinder, Molten
                case "netherite" -> "minecraft:netherite_block"; default -> "minecraft:shroomlight";     // Forgeheart, Spark
            };
            c.set(s.x(), Y + 1, s.z(), plinth);
            c.set(s.x(), Y + 2, s.z(), "minecraft:vault[facing=east,ominous=" + s.crate().equals("netherite") + "]");
            c.setLate(s.x() - 1, Y + 1, s.z(), "minecraft:copper_lantern");
        }
        // back wall + canopy
        for (int z = -10; z <= 10; z++) for (int y = Y + 1; y <= Y + 7; y++) c.set(x0 - 3, y, z, y == Y + 4 && z % 4 == 0 ? "minecraft:waxed_oxidized_copper_bulb[lit=true]" : "minecraft:mossy_stone_bricks");
        for (int x = x0 - 3; x <= x0 + 2; x++) for (int z = -10; z <= 10; z++) c.set(x, Y + 8, z, ((x + z) & 1) == 0 ? "minecraft:waxed_oxidized_cut_copper_slab[type=top]" : "minecraft:waxed_oxidized_copper_grate");
        for (int z : new int[]{-10, 10}) for (int y = Y + 1; y <= Y + 7; y++) c.set(x0 + 2, y, z, "minecraft:waxed_oxidized_copper");
    }

    private void market() {
        int mx = MARKET_X, mz = MARKET_Z;
        for (int x = mx - 9; x <= mx + 9; x++)
            for (int z = mz - 2; z <= mz + 6; z++) {
                for (int y = Y - 3; y < Y; y++) if (!c.isSet(x, y, z)) c.set(x, y, z, "minecraft:stone");
                c.set(x, Y, z, ((x + z) & 1) == 0 ? "minecraft:cherry_planks" : "minecraft:spruce_planks");
            }
        for (int x = mx - 9; x <= mx + 9; x += 6) for (int z : new int[]{mz - 2, mz + 6}) for (int y = Y + 1; y <= Y + 5; y++) c.set(x, y, z, "minecraft:stripped_cherry_log");
        for (int x = mx - 10; x <= mx + 10; x++) for (int z = mz - 3; z <= mz + 7; z++) {
            int dz = Math.min(z - (mz - 3), (mz + 7) - z);
            c.set(x, Y + 6 + Math.min(dz / 2, 2), z, Math.floorMod(x, 4) < 2 ? "minecraft:pink_wool" : "minecraft:white_wool");
        }
        // counters
        for (int x = mx - 7; x <= mx + 7; x++) { c.set(x, Y + 1, mz + 4, "minecraft:stripped_cherry_wood"); }
        c.set(BELL[0], Y + 1, BELL[2], "minecraft:gold_block");
        c.setLate(BELL[0], BELL[1], BELL[2], "minecraft:bell[attachment=floor,facing=north]");
        c.set(SHOP[0], SHOP[1], SHOP[2], "minecraft:smithing_table");
        c.set(DAILY[0], DAILY[1], DAILY[2], "minecraft:ender_chest[facing=north]");
        for (int x = mx - 7; x <= mx + 7; x += 7) c.setLate(x, Y + 2, mz + 5, "minecraft:lantern");
    }

    private void hall() {
        for (int x = 18; x <= 33; x++) for (int z = -14; z <= 14; z++) {
            double d = Math.hypot(x - 20, z * 0.9);
            if (d > 11.5 || x < 19) continue;
            c.set(x, Y, z, d > 10.5 ? "minecraft:waxed_cut_copper" : (((x + z) & 1) == 0 ? "minecraft:polished_tuff" : "minecraft:tuff_bricks"));
        }
        String[] trophies = {"minecraft:emerald_block", "minecraft:gold_block", "minecraft:deepslate_diamond_ore", "minecraft:waxed_copper_bulb[lit=true]", "minecraft:bone_block"};
        for (int i = 0; i < HALL.size(); i++) {
            var s = HALL.get(i);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                c.set(s.x() + dx, Y, s.z() + dz, "minecraft:polished_blackstone_bricks");
                c.set(s.x() + dx, Y + 1, s.z() + dz, Math.abs(dx) + Math.abs(dz) == 2 ? "minecraft:gold_block" : "minecraft:polished_blackstone_bricks");
            }
            c.set(s.x(), Y + 2, s.z(), "minecraft:waxed_chiseled_copper");
            c.set(s.x(), Y + 3, s.z(), trophies[i]);
            if (i == 4) c.setLate(s.x(), Y + 4, s.z(), "minecraft:skeleton_skull");
        }
    }

    private void blossom(int cx, int cy, int cz, int r) {
        for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) for (int dy = -1; dy <= r; dy++) {
            double d = Math.sqrt(dx * dx + dz * dz + dy * dy * 1.8);
            if (d > r + 0.8 || (d > r && rnd.nextInt(2) == 0)) continue;
            if (!c.isSet(cx + dx, cy + dy, cz + dz)) c.set(cx + dx, cy + dy, cz + dz, "minecraft:cherry_leaves[persistent=true]");
        }
    }

    private void trees() {
        int[][] spots = {{-34, -18}, {-32, 20}, {33, -24}, {35, 22}, {-12, -38}, {14, -37}, {-38, 2}, {12, 38}, {-24, -30}, {26, 32}, {38, -6}, {-6, 40}};
        for (int[] t : spots) {
            if (!on(t[0], t[1]) || Math.hypot(t[0], t[1]) > R - 4 || c.isSet(t[0], Y + 1, t[1])) continue;
            int h = 5 + rnd.nextInt(3);
            for (int y = 1; y <= h; y++) c.set(t[0], Y + y, t[1], "minecraft:cherry_log");
            c.set(t[0] + 1, Y + h - 1, t[1], "minecraft:cherry_log[axis=x]");
            blossom(t[0], Y + h, t[1], 3);
            for (int k = 0; k < 8; k++) c.setLate(t[0] + rnd.nextInt(7) - 3, Y + 1, t[1] + rnd.nextInt(7) - 3, "minecraft:pink_petals[flower_amount=" + (1 + rnd.nextInt(4)) + "]");
        }
    }

    private void flowers() {
        for (int i = 0; i < 520; i++) {
            int x = rnd.nextInt(2 * R) - R, z = rnd.nextInt(2 * R) - R;
            if (!on(x, z) || Math.hypot(x, z) < 15 || c.isSet(x, Y + 1, z)) continue;
            String top = c.get(x, Y, z);
            if (top == null || !top.contains("grass")) continue;
            int n = rnd.nextInt(12);
            c.setLate(x, Y + 1, z, n < 4 ? "minecraft:short_grass" : n < 6 ? "minecraft:pink_petals[flower_amount=3]" : n < 7 ? "minecraft:allium" : n < 8 ? "minecraft:cornflower"
                    : n < 9 ? "minecraft:azure_bluet" : n < 10 ? "minecraft:oxeye_daisy" : n < 11 ? "minecraft:fern" : "minecraft:lily_of_the_valley");
        }
    }

    /** Water pouring off four points of the rim. */
    private void waterfalls() {
        for (double a : new double[]{0.9, 2.5, 3.9, 5.4}) {
            double E = edge(a);
            for (double d = E - 6; d <= E + 0.5; d += 0.5) {
                int x = (int) Math.round(d * Math.cos(a)), z = (int) Math.round(d * Math.sin(a));
                if (!on(x, z)) continue;
                c.set(x, Y, z, "minecraft:mossy_cobblestone");
                c.setLate(x, Y + 1, z, "minecraft:water");
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + o[0], nz = z + o[1];
                    if (on(nx, nz) && !"minecraft:water".equals(c.get(nx, Y + 1, nz)) && !c.isSet(nx, Y + 1, nz)) c.set(nx, Y + 1, nz, "minecraft:mossy_cobblestone_wall");
                }
            }
        }
    }

    private void satellites() {
        double[][] spots = {{0.3, 60, -6}, {1.1, 64, 8}, {1.9, 58, -12}, {2.7, 66, 4}, {3.6, 61, -8}, {4.4, 65, 10}, {5.3, 59, -4}};
        for (double[] sp : spots) {
            int ox = (int) (sp[1] * Math.cos(sp[0])), oz = (int) (sp[1] * Math.sin(sp[0])), ty = Y + (int) sp[2];
            int r = 4 + rnd.nextInt(3);
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                double d = Math.hypot(x, z);
                if (d > r + 0.3) continue;
                int depth = Math.max(1, (int) ((1 - d / (r + 1)) * 6) + rnd.nextInt(2));
                for (int k = 0; k < depth; k++) c.set(ox + x, ty - k, oz + z, k == 0 ? "minecraft:grass_block" : k < 2 ? "minecraft:dirt" : "minecraft:stone");
            }
            for (int y = 1; y <= 4; y++) c.set(ox, ty + y, oz, "minecraft:cherry_log");
            blossom(ox, ty + 5, oz, 2);
        }
    }

    private void railing() {
        for (int i = 0; i < 720; i++) {
            double a = i * Math.PI * 2 / 720, E = edge(a) - 0.8;
            int x = (int) Math.round(E * Math.cos(a)), z = (int) Math.round(E * Math.sin(a));
            if (c.isSet(x, Y + 1, z)) continue;
            c.set(x, Y + 1, z, "minecraft:mossy_stone_brick_wall");
            if (i % 40 == 0) c.setLate(x, Y + 2, z, "minecraft:lantern");
        }
    }

    /** "SKYBLOCK" floating behind the island gate. */
    private void lettering() {
        String text = "SKYBLOCK";
        int w = BlockFont.width(text, 1) * 2;
        BlockFont.draw(text, 1, (col, row, gr) -> {
            for (int sx = 0; sx < 2; sx++) for (int sy = 0; sy < 2; sy++) {
                int x = -w / 2 + col * 2 + sx, y = Y + 18 + row * 2 + sy;
                c.set(x, y, GATE_Z - 14, gr <= 1 ? "minecraft:waxed_oxidized_copper" : gr <= 4 ? "minecraft:waxed_oxidized_cut_copper" : "minecraft:moss_block");
                c.set(x, y, GATE_Z - 15, "minecraft:polished_deepslate");
            }
        });
    }
}
