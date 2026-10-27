package net.alloymc.core.survival;

import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.Random;

import static net.alloymc.core.survival.SurvivalLayout.*;

/** Floating survival spawn town (crates pavilion, wild + koth gates) and the KOTH arena. */
public final class SpawnBuilder {
    private final Canvas c = new Canvas();
    private final Random rnd = new Random(0x5AFE);

    public static Canvas spawn() {
        SpawnBuilder b = new SpawnBuilder();
        b.island(0, Y, 0, ISLAND_R, 40);
        b.plaza();
        b.fountain();
        b.cratePavilion();
        b.gate(GATE_X, "oxidized");
        b.dropZone();
        b.paths();
        b.courtyard();
        b.welcomeArch();
        b.board(-BOARD_X, true);
        b.board(BOARD_X, false);
        b.market();
        b.hallOfFame();
        for (int[] q : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) b.spire(q[0] * 31, q[1] * 30);
        b.grounds();
        b.farmRing();
        b.northRoad();
        b.tradingHall();
        b.satellites();
        b.railing(0, 0, ISLAND_R);
        return b.c;
    }

    public static Canvas arena() {
        SpawnBuilder b = new SpawnBuilder();
        b.island(KOTH_X, KOTH_Y, KOTH_Z, KOTH_R + 2, 24);
        b.arenaFloor();
        b.hill();
        b.cover();
        b.arenaWall();
        return b.c;
    }

    // ------------------------------------------------------------------ shared

    private double edge(double theta, double base) {
        return base + 2.2 * Math.sin(3 * theta + 1) + 1.5 * Math.sin(5 * theta + 2) + Math.sin(9 * theta);
    }

    private boolean onIsland(int x, int z, int cx, int cz, double base) {
        return Math.hypot(x - cx, z - cz) <= edge(Math.atan2(z - cz, x - cx), base);
    }

    private void island(int cx, int top, int cz, double base, int maxDepth) {
        int m = (int) base + 6;
        for (int x = -m; x <= m; x++)
            for (int z = -m; z <= m; z++) {
                double r = Math.hypot(x, z), R = edge(Math.atan2(z, x), base);
                if (r > R) continue;
                int depth = Math.max(1, (int) Math.round(Math.pow(1 - r / R, 0.75) * maxDepth + 1 + rnd.nextInt(2)));
                for (int d = 0; d < depth; d++) {
                    String b;
                    if (d == 0) b = R - r < 1.5 ? (rnd.nextBoolean() ? "minecraft:moss_block" : "minecraft:grass_block") : "minecraft:grass_block";
                    else if (d <= 2) b = "minecraft:dirt";
                    else {
                        int n = rnd.nextInt(100);
                        b = n < 3 ? "minecraft:copper_ore" : n < 5 ? "minecraft:iron_ore" : n < 6 ? "minecraft:gold_ore"
                                : d > 10 ? (n < 60 ? "minecraft:deepslate" : "minecraft:tuff") : (n < 60 ? "minecraft:stone" : n < 80 ? "minecraft:andesite" : "minecraft:tuff");
                    }
                    c.set(cx + x, top - d, cz + z, b);
                }
            }
    }

    private void railing(int cx, int cz, double base) {
        for (int i = 0; i < 720; i++) {
            double a = i * Math.PI * 2 / 720;
            double R = edge(a, base) - 0.8;
            int x = cx + (int) Math.round(R * Math.cos(a)), z = cz + (int) Math.round(R * Math.sin(a));
            if (Math.abs(z - cz) <= 4 && Math.abs(Math.abs(x - cx) - GATE_X) <= 6) continue;   // gate approaches
            if (c.isSet(x, Y + 1, z)) continue;
            c.set(x, Y + 1, z, "minecraft:polished_blackstone_wall");
            if (i % 36 == 0) { c.set(x, Y + 2, z, "minecraft:waxed_chiseled_copper"); c.setLate(x, Y + 3, z, "minecraft:copper_lantern"); }
        }
    }

    // ------------------------------------------------------------------ spawn

    private void plaza() {
        for (int x = -19; x <= 19; x++)
            for (int z = -19; z <= 19; z++) {
                double r = Math.hypot(x, z);
                if (r > 18.4) continue;
                String b = ((Math.floorDiv(x, 2) + Math.floorDiv(z, 2)) & 1) == 0 ? "minecraft:polished_deepslate" : "minecraft:deepslate_tiles";
                if (r > 10.4 && r <= 11.6) b = "minecraft:waxed_cut_copper";
                if (r > 17.2) b = "minecraft:chiseled_tuff_bricks";
                c.set(x, Y, z, b);
            }
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6 + Math.PI / 12;
            c.set((int) Math.round(14.3 * Math.cos(a)), Y, (int) Math.round(14.3 * Math.sin(a)), "minecraft:waxed_copper_bulb[lit=true]");
        }
    }

    /** Two-tier fountain: the upper bowl spills through four notches into the lower basin. */
    private void fountain() {
        for (int x = -7; x <= 7; x++)
            for (int z = -7; z <= 7; z++) {
                double r = Math.hypot(x, z);
                if (r <= 5.6) { c.set(x, Y - 1, z, "minecraft:prismarine_bricks"); c.setLate(x, Y, z, "minecraft:water"); }
                else if (r <= 6.7) {
                    c.set(x, Y, z, "minecraft:polished_blackstone_bricks");
                    // four gaps in the rim so nobody who hops into the basin gets stuck in it
                    if (Math.abs(x) > 1 && Math.abs(z) > 1) c.set(x, Y + 1, z, ((x + z) & 1) == 0 ? "minecraft:gold_block" : "minecraft:waxed_cut_copper");
                    else c.set(x, Y, z, "minecraft:gold_block");
                }
            }
        for (int y = Y; y <= Y + 3; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            c.set(x, y, z, x == 0 && z == 0 ? "minecraft:waxed_copper_block" : (y == Y + 2 ? "minecraft:waxed_copper_bulb[lit=true]" : "minecraft:polished_blackstone_bricks"));
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) {
                double r = Math.hypot(x, z);
                if (r > 2.7) continue;
                c.set(x, Y + 4, z, "minecraft:gold_block");
                if (r > 1.7) c.set(x, Y + 5, z, "minecraft:waxed_cut_copper");
                else c.setLate(x, Y + 5, z, "minecraft:water");
            }
        for (int[] g : new int[][]{{2, 0}, {-2, 0}, {0, 2}, {0, -2}}) c.set(g[0], Y + 5, g[1], "minecraft:air");
        for (int y = Y + 5; y <= Y + 8; y++) c.set(0, y, 0, y == Y + 7 ? "minecraft:waxed_copper_bulb[lit=true]" : "minecraft:waxed_copper_block");
        c.set(0, Y + 9, 0, "minecraft:gold_block");
        c.setLate(0, Y + 10, 0, "minecraft:lightning_rod");
    }

    private void cratePavilion() {
        int z0 = CRATE_Z + 4, z1 = CRATE_Z - 10;
        for (int x = -15; x <= 15; x++)
            for (int z = z1; z <= z0; z++) {
                c.set(x, Y, z, "minecraft:polished_blackstone_bricks");
                c.set(x, Y + 1, z, ((x + z) & 3) == 0 ? "minecraft:waxed_cut_copper" : "minecraft:polished_deepslate");
            }
        for (int x = -3; x <= 3; x++) c.set(x, Y + 1, z0, "minecraft:polished_deepslate_stairs[facing=north]");
        for (CrateSpot s : CRATES) {
            String plinth = switch (s.crate()) {
                case "iron" -> "minecraft:gilded_blackstone";      // Cinder
                case "gold" -> "minecraft:magma_block";            // Molten
                case "netherite" -> "minecraft:netherite_block";   // Forgeheart
                case "vote" -> "minecraft:shroomlight";            // Spark
                default -> "minecraft:gold_block";                 // Conqueror (KOTH)
            };
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                c.set(s.x() + dx, Y + 1, s.z() + dz, dx == 0 && dz == 0 ? plinth : "minecraft:polished_blackstone_bricks");
            boolean ominous = s.crate().equals("netherite") || s.crate().equals("koth");
            c.set(s.x(), Y + 2, s.z(), "minecraft:vault[facing=south,ominous=" + ominous + "]");
            for (int[] k : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) c.setLate(s.x() + k[0], Y + 2, s.z() + k[1], "minecraft:copper_lantern");
        }
        for (int x = -15; x <= 15; x += 6)
            for (int z : new int[]{z0, z1})
                for (int y = Y + 2; y <= Y + 8; y++) c.set(x, y, z, y == Y + 5 ? "minecraft:waxed_copper_bulb[lit=true]" : "minecraft:polished_blackstone_bricks");
        for (int x = -16; x <= 16; x++)
            for (int z = z1 - 1; z <= z0 + 1; z++) {
                boolean rim = x == -16 || x == 16 || z == z1 - 1 || z == z0 + 1;
                c.set(x, Y + 9, z, rim ? "minecraft:waxed_cut_copper" : (((x + z) & 1) == 0 ? "minecraft:waxed_copper_grate" : "minecraft:waxed_cut_copper_slab[type=top]"));
            }
        for (int x = -15; x <= 15; x++)
            for (int y = Y + 2; y <= Y + 8; y++) c.set(x, y, z1 - 1, "minecraft:polished_deepslate");
        int w = BlockFont.width("CRATES", 1);
        BlockFont.draw("CRATES", 1, (col, row, gr) -> {
            int x = -w / 2 + col;
            c.set(x, Y + 13 + row, z1, gr <= 2 ? "minecraft:gold_block" : "minecraft:waxed_copper_block");
            c.set(x, Y + 13 + row, z1 - 1, "minecraft:polished_blackstone");
        });
        for (int x = -w / 2 - 1; x <= w / 2 + 1; x++) c.set(x, Y + 10, z1, "minecraft:polished_blackstone_bricks");
    }

    private void gate(int gx, String theme) {
        boolean wild = theme.equals("oxidized");
        String main = wild ? "minecraft:waxed_oxidized_cut_copper" : "minecraft:polished_blackstone_bricks";
        String accent = wild ? "minecraft:waxed_oxidized_copper" : "minecraft:netherite_block";
        String trim = wild ? "minecraft:moss_block" : "minecraft:red_nether_bricks";
        String light = wild ? "minecraft:waxed_oxidized_copper_bulb[lit=true]" : "minecraft:shroomlight";
        for (int u = -7; u <= 7; u++) for (int w = -2; w <= 2; w++) c.set(gx + w, Y, u, accent);
        for (int u = -6; u <= 6; u++)
            for (int ry = 0; ry <= 11; ry++)
                for (int w = -1; w <= 1; w++) {
                    int au = Math.abs(u), y = Y + 1 + ry;
                    boolean open = (ry <= 7 && au <= 3) || (ry == 8 && au <= 2) || (ry == 9 && au <= 1);
                    if (open) { if (w == 0) c.set(gx, y, u, "minecraft:nether_portal[axis=z]"); continue; }
                    if (au >= 5 && ry >= 11) continue;
                    boolean edgeOfOpening = (ry <= 8 && au == 4) || (ry == 8 && au == 3) || (ry == 9 && au == 2) || (ry == 10 && au <= 1);
                    String b = au == 6 || ry == 11 ? accent : edgeOfOpening ? trim : main;
                    if (au == 5 && (ry == 3 || ry == 6) && w != 0) b = light;
                    c.set(gx + w, y, u, b);
                }
        int cy = Y + 13;
        if (wild) {
            for (int u = -2; u <= 2; u++) for (int w = -1; w <= 1; w++) c.set(gx + w, cy, u, "minecraft:moss_block");
            for (int k = 1; k <= 3; k++) c.set(gx, cy + k, 0, "minecraft:oak_log");
            for (int u = -2; u <= 2; u++) for (int w = -2; w <= 2; w++) for (int k = 3; k <= 5; k++)
                if (Math.abs(u) + Math.abs(w) + (k - 3) <= 3 && !(u == 0 && w == 0 && k < 4)) c.set(gx + w, cy + k, u, "minecraft:azalea_leaves[persistent=true]");
        } else {
            for (int u = -4; u <= 4; u += 2) { c.set(gx, cy, u, "minecraft:polished_blackstone_bricks"); c.set(gx, cy + 1, u, "minecraft:crying_obsidian"); }
            c.set(gx, cy + 2, 0, "minecraft:shroomlight");
        }
    }

    /** Ender-themed platform with two portal-framed shafts that drop players into the fight zones. */
    private void dropZone() {
        int cx = DROP_X;
        // platform
        for (int x = cx - 8; x <= cx + 8; x++)
            for (int z = -12; z <= 12; z++) {
                double d = Math.max(Math.abs(x - cx) / 8.0, Math.abs(z) / 12.0);
                if (d > 1) continue;
                for (int y = Y - 3; y < Y; y++) if (!c.isSet(x, y, z)) c.set(x, y, z, "minecraft:obsidian");
                boolean edge = Math.abs(x - cx) == 8 || Math.abs(z) == 12;
                c.set(x, Y, z, edge ? "minecraft:waxed_cut_copper" : (((x + z) & 1) == 0 ? "minecraft:polished_blackstone_bricks" : "minecraft:obsidian"));
            }
        for (int sz : new int[]{PVP_SHAFT_Z, KOTH_SHAFT_Z}) shaft(cx, sz, sz == PVP_SHAFT_Z);
        // crying obsidian pillars with end rods, ender chests and purple glass
        for (int[] k : new int[][]{{-7, -11}, {7, -11}, {-7, 11}, {7, 11}}) {
            for (int y = Y + 1; y <= Y + 5; y++) c.set(cx + k[0], y, k[1], y == Y + 3 ? "minecraft:purple_stained_glass" : "minecraft:crying_obsidian");
            c.setLate(cx + k[0], Y + 6, k[1], "minecraft:end_rod[facing=up]");
        }
        for (int[] k : new int[][]{{-6, -2}, {-6, 2}, {6, -2}, {6, 2}}) c.set(cx + k[0], Y + 1, k[1], "minecraft:ender_chest[facing=" + (k[0] < 0 ? "east" : "west") + "]");
        // entrance arch across the walkway on the spawn side
        int ax = cx + 8;
        for (int y = Y + 1; y <= Y + 8; y++) for (int z : new int[]{-3, 3}) c.set(ax, y, z, y == Y + 4 ? "minecraft:purple_stained_glass" : "minecraft:obsidian");
        for (int z = -4; z <= 4; z++) c.set(ax, Y + 9, z, Math.abs(z) % 2 == 0 ? "minecraft:crying_obsidian" : "minecraft:obsidian");
        for (int z = -2; z <= 2; z++) c.set(ax, Y + 10, z, "minecraft:obsidian");
        c.setLate(ax, Y + 11, 0, "minecraft:end_rod[facing=up]");
        for (int z : new int[]{-2, 2}) c.setLate(ax, Y + 8, z, "minecraft:end_rod[facing=down]");
    }

    private void shaft(int cx, int sz, boolean pvp) {
        // open 3x3 hole straight through the island
        for (int x = cx - 1; x <= cx + 1; x++)
            for (int z = sz - 1; z <= sz + 1; z++)
                for (int y = Y - 60; y <= Y + 4; y++) c.set(x, y, z, "minecraft:air");
        // end-portal frame ring (eyes in) around the hole
        for (int i = -1; i <= 1; i++) {
            c.set(cx + i, Y, sz - 2, "minecraft:end_portal_frame[eye=true,facing=south]");
            c.set(cx + i, Y, sz + 2, "minecraft:end_portal_frame[eye=true,facing=north]");
            c.set(cx - 2, Y, sz + i, "minecraft:end_portal_frame[eye=true,facing=east]");
            c.set(cx + 2, Y, sz + i, "minecraft:end_portal_frame[eye=true,facing=west]");
        }
        for (int[] k : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) c.set(cx + k[0], Y, sz + k[1], pvp ? "minecraft:red_nether_bricks" : "minecraft:crying_obsidian");
        // glowing lining a few blocks down so the shaft looks bottomless and lit
        for (int y = Y - 6; y < Y; y++)
            for (int i = -1; i <= 1; i++) {
                String b = y % 2 == 0 ? (pvp ? "minecraft:red_stained_glass" : "minecraft:purple_stained_glass") : "minecraft:obsidian";
                c.set(cx + i, y, sz - 2, b); c.set(cx + i, y, sz + 2, b); c.set(cx - 2, y, sz + i, b); c.set(cx + 2, y, sz + i, b);
            }
    }

    private void pathStrip(int x, int z, int p) {
        c.set(x, Y, z, Math.abs(p) == 2 ? "minecraft:polished_tuff" : Math.abs(p) == 1 ? "minecraft:waxed_cut_copper" : "minecraft:polished_deepslate");
    }

    private void paths() {
        for (int x = 18; x <= GATE_X - 2; x++)
            for (int z = -2; z <= 2; z++) for (int s : new int[]{-1, 1}) pathStrip(s * x, z, z);
        for (int z = 18; z <= MARKET_Z - 3; z++) for (int x = -2; x <= 2; x++) pathStrip(x, z, x);
        for (int z = -18; z >= CRATE_Z + 5; z--) for (int x = -2; x <= 2; x++) pathStrip(x, z, x);
        for (int d = 22; d <= GATE_X - 6; d += 6)
            for (int s : new int[]{-1, 1}) for (int side : new int[]{-3, 3}) lamp(s * d, side);
    }

    private void lamp(int x, int z) {
        c.set(x, Y, z, "minecraft:polished_blackstone_bricks");
        c.set(x, Y + 1, z, "minecraft:polished_blackstone_wall");
        c.set(x, Y + 2, z, "minecraft:polished_blackstone_wall");
        c.set(x, Y + 3, z, "minecraft:waxed_chiseled_copper");
        c.setLate(x, Y + 4, z, "minecraft:copper_lantern");
    }

    /** Arrival courtyard around the spawn point. */
    private void courtyard() {
        int sz = (int) SPAWN_Z;
        for (int x = -11; x <= 11; x++)
            for (int z = sz - 8; z <= sz + 8; z++) {
                boolean border = Math.abs(x) == 11 || Math.abs(z - sz) == 8;
                String b = border ? "minecraft:chiseled_tuff_bricks" : ((x + z) & 1) == 0 ? "minecraft:polished_deepslate" : "minecraft:deepslate_tiles";
                if (!border && (Math.abs(x) == 9 || Math.abs(z - sz) == 6)) b = "minecraft:waxed_cut_copper";
                c.set(x, Y, z, b);
            }
        for (int x = -1; x <= 1; x++) for (int z = sz - 1; z <= sz + 1; z++) c.set(x, Y, z, "minecraft:gold_block");
        c.set(0, Y, sz, "minecraft:lodestone");
        for (int[] k : new int[][]{{-10, sz - 7}, {10, sz - 7}, {-10, sz + 7}, {10, sz + 7}}) lamp(k[0], k[1]);
        for (int s : new int[]{-1, 1})
            for (int z = sz - 3; z <= sz + 3; z++)
                if (z != sz) c.set(s * 8, Y + 1, z, "minecraft:dark_oak_stairs[facing=" + (s < 0 ? "west" : "east") + "]");
    }

    private void welcomeArch() {
        int z = (int) SPAWN_Z - 9;
        for (int s : new int[]{-1, 1})
            for (int y = Y + 1; y <= Y + 7; y++)
                for (int dx = 0; dx <= 1; dx++) c.set(s * (4 + dx), y, z, y == Y + 4 ? "minecraft:waxed_copper_bulb[lit=true]" : "minecraft:polished_blackstone_bricks");
        for (int x = -6; x <= 6; x++) {
            c.set(x, Y + 8, z, "minecraft:waxed_cut_copper");
            if (Math.abs(x) <= 4) c.set(x, Y + 9, z, Math.abs(x) % 2 == 0 ? "minecraft:gold_block" : "minecraft:waxed_copper_block");
        }
        for (int x = -3; x <= 3; x++) c.set(x, Y + 7, z, "minecraft:waxed_cut_copper_slab[type=top]");
        c.setLate(-2, Y + 6, z, "minecraft:copper_lantern[hanging=true]");
        c.setLate(2, Y + 6, z, "minecraft:copper_lantern[hanging=true]");
    }

    /** Framed board for the rules (west) or leaderboard (east); the text is a hologram the plugin adds. */
    private void board(int bx, boolean rules) {
        int s = bx < 0 ? -1 : 1, back = bx + s;
        for (int z = BOARD_Z - 7; z <= BOARD_Z + 7; z++)
            for (int y = Y; y <= Y + 11; y++) {
                boolean frame = Math.abs(z - BOARD_Z) == 7 || y == Y + 1 || y == Y + 11;
                c.set(back, y, z, "minecraft:polished_blackstone_bricks");
                if (y >= Y + 1) c.set(bx, y, z, frame ? (rules ? "minecraft:waxed_cut_copper" : "minecraft:gold_block") : "minecraft:black_concrete");
            }
        for (int z = BOARD_Z - 8; z <= BOARD_Z + 8; z++) {
            c.set(bx, Y + 12, z, "minecraft:polished_blackstone_bricks");
            c.set(back, Y + 12, z, "minecraft:polished_blackstone_bricks");
        }
        for (int z = BOARD_Z - 3; z <= BOARD_Z + 3; z++) c.set(bx, Y + 13, z, rules ? "minecraft:waxed_copper_block" : "minecraft:gold_block");
        c.set(bx, Y + 14, BOARD_Z, rules ? "minecraft:waxed_copper_bulb[lit=true]" : "minecraft:gold_block");
        if (!rules) for (int dz = -1; dz <= 1; dz++) c.set(bx, Y + 15, BOARD_Z + dz, "minecraft:gold_block");
        for (int z : new int[]{BOARD_Z - 8, BOARD_Z + 8}) c.setLate(bx - s, Y + 1, z, "minecraft:copper_lantern");
    }

    private void market() {
        int z0 = MARKET_Z - 3, z1 = MARKET_Z + 6;
        for (int x = -13; x <= 13; x++)
            for (int z = z0; z <= z1; z++) {
                for (int y = Y - 3; y < Y; y++) if (!c.isSet(x, y, z)) c.set(x, y, z, "minecraft:stone");
                boolean border = Math.abs(x) == 13 || z == z0 || z == z1;
                c.set(x, Y, z, border ? "minecraft:waxed_cut_copper" : ((x + z) & 1) == 0 ? "minecraft:dark_oak_planks" : "minecraft:spruce_planks");
            }
        for (int x = -13; x <= 13; x += 13) for (int z : new int[]{z0, z1}) for (int y = Y + 1; y <= Y + 6; y++) c.set(x, y, z, "minecraft:stripped_dark_oak_log");
        for (int x = -6; x <= 6; x += 12) for (int y = Y + 1; y <= Y + 6; y++) c.set(x, y, z0, "minecraft:stripped_dark_oak_log");
        for (int x = -14; x <= 14; x++)
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                int dz = Math.min(z - (z0 - 1), (z1 + 1) - z);
                c.set(x, Y + 7 + Math.min(dz / 2, 2), z, Math.floorMod(x, 4) < 2 ? "minecraft:orange_wool" : "minecraft:white_wool");
            }
        for (int x = -12; x <= 12; x++) for (int y = Y + 1; y <= Y + 6; y++) c.set(x, y, z1, y == Y + 4 && x % 4 == 0 ? "minecraft:waxed_copper_bulb[lit=true]" : "minecraft:spruce_planks");
        String[][] goods = {{"minecraft:barrel[facing=up]", "minecraft:hay_block"}, {"minecraft:chest[facing=north]", "minecraft:melon"},
                {"minecraft:barrel[facing=up]", "minecraft:pumpkin"}, {"minecraft:chest[facing=north]", "minecraft:honey_block"}};
        int[] stalls = {-10, -5, 5, 10};
        for (int i = 0; i < stalls.length; i++) {
            int x = stalls[i];
            for (int dx = -1; dx <= 1; dx++) {
                c.set(x + dx, Y + 1, z1 - 2, goods[i][0]);
                c.set(x + dx, Y + 2, z1 - 2, "minecraft:spruce_slab");
            }
            c.set(x, Y + 3, z1 - 2, goods[i][1]);
            c.setLate(x - 1, Y + 3, z1 - 2, "minecraft:lantern");
        }
        // the auction bell: ring it to open /ah
        c.set(BELL_X - 1, Y + 1, BELL_Z, "minecraft:gold_block");
        c.set(BELL_X + 1, Y + 1, BELL_Z, "minecraft:gold_block");
        c.set(BELL_X, Y + 1, BELL_Z, "minecraft:polished_blackstone_bricks");
        c.setLate(BELL_X, Y + 2, BELL_Z, "minecraft:bell[attachment=floor,facing=north]");
        for (int x = -1; x <= 1; x++) c.set(x, Y, BELL_Z - 1, "minecraft:gold_block");
    }

    /** Five trophy pedestals; the plugin floats a top-10 hologram above each. */
    private void hallOfFame() {
        // a paved half-moon terrace
        for (int x = HALL_CX - 13; x <= HALL_CX + 13; x++)
            for (int z = HALL_CZ - 13; z <= HALL_CZ + 2; z++) {
                double d = Math.hypot(x - HALL_CX, (z - HALL_CZ) * 1.15);
                if (d > 12.5) continue;
                c.set(x, Y, z, d > 11.5 ? "minecraft:waxed_cut_copper" : (((x + z) & 1) == 0 ? "minecraft:polished_tuff" : "minecraft:tuff_bricks"));
                for (int y = Y + 1; y <= Y + 6; y++) {
                    String b = c.get(x, y, z);
                    if (b != null && (b.contains("leaves") || b.contains("grass") || b.contains("poppy") || b.contains("daisy") || b.contains("fern") || b.contains("allium") || b.contains("cornflower"))) c.set(x, y, z, "minecraft:air");
                }
            }
        String[] trophies = {"minecraft:gold_block", "minecraft:deepslate_diamond_ore", "minecraft:redstone_block", "minecraft:waxed_copper_bulb[lit=true]", "minecraft:bone_block"};
        for (int i = 0; i < HALL.size(); i++) {
            HallSpot s = HALL.get(i);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                c.set(s.x() + dx, Y, s.z() + dz, "minecraft:polished_blackstone_bricks");
                c.set(s.x() + dx, Y + 1, s.z() + dz, Math.abs(dx) + Math.abs(dz) == 2 ? "minecraft:gold_block" : "minecraft:polished_blackstone_bricks");
            }
            c.set(s.x(), Y + 2, s.z(), "minecraft:waxed_chiseled_copper");
            c.set(s.x(), Y + 3, s.z(), trophies[i]);
            if (i == 4) c.setLate(s.x(), Y + 4, s.z(), "minecraft:skeleton_skull");
            if (i == 2) c.setLate(s.x(), Y + 4, s.z(), "minecraft:wither_skeleton_skull");
            for (int[] k : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) c.setLate(s.x() + k[0], Y + 2, s.z() + k[1], "minecraft:copper_lantern");
        }
    }

    /** A copper-roofed spire for the skyline. */
    private void spire(int cx, int cz) {
        int h = 20;
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) {
                if (Math.abs(x) == 3 && Math.abs(z) == 3) continue;
                for (int y = Y - 2; y <= Y + h; y++) {
                    boolean wall = Math.abs(x) == 3 || Math.abs(z) == 3;
                    if (!wall && y > Y) continue;
                    String b = (y - Y) % 6 == 0 ? "minecraft:waxed_cut_copper" : "minecraft:deepslate_bricks";
                    if (wall && (y - Y) % 6 == 3 && (x == 0 || z == 0)) b = "minecraft:orange_stained_glass";
                    if (wall && (y - Y) % 6 == 3 && (Math.abs(x) == 2 || Math.abs(z) == 2) && Math.abs(x) != Math.abs(z)) b = "minecraft:waxed_copper_bulb[lit=true]";
                    c.set(cx + x, y, cz + z, b);
                }
            }
        String[] roof = {"minecraft:waxed_copper_block", "minecraft:waxed_exposed_copper", "minecraft:waxed_weathered_copper", "minecraft:waxed_oxidized_copper"};
        for (int k = 0; k <= 4; k++) {
            int r = 4 - k;
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++)
                if (Math.abs(x) == r || Math.abs(z) == r || k == 4) c.set(cx + x, Y + h + 1 + k, cz + z, roof[Math.min(3, k)]);
        }
        c.set(cx, Y + h + 6, cz, "minecraft:gold_block");
        c.setLate(cx, Y + h + 7, cz, "minecraft:lightning_rod");
    }

    private void grounds() {
        int[][] trees = {{-24, 14}, {24, 16}, {-40, -14}, {46, -24}, {-22, -44}, {22, -45}, {-44, 18}, {44, 20}, {-30, 44}, {31, 43}, {-48, -2}, {12, -50}};
        for (int[] t : trees) if (onIsland(t[0], t[1], 0, 0, ISLAND_R - 4) && !c.isSet(t[0], Y + 1, t[1])) tree(t[0], t[1]);
        for (int i = 0; i < 500; i++) {
            int x = rnd.nextInt(110) - 55, z = rnd.nextInt(110) - 55;
            if (!onIsland(x, z, 0, 0, ISLAND_R - 2) || Math.hypot(x, z) < 19 || c.isSet(x, Y + 1, z)) continue;
            String top = c.get(x, Y, z);
            if (top == null || !top.contains("grass")) continue;
            int n = rnd.nextInt(9);
            c.setLate(x, Y + 1, z, n < 4 ? "minecraft:short_grass" : n < 5 ? "minecraft:poppy" : n < 6 ? "minecraft:cornflower" : n < 7 ? "minecraft:oxeye_daisy" : n < 8 ? "minecraft:fern" : "minecraft:allium");
        }
    }

    private void tree(int x, int z) {
        int h = 5 + rnd.nextInt(2);
        for (int y = 1; y <= h; y++) c.set(x, Y + y, z, "minecraft:dark_oak_log");
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = h - 2; dy <= h + 2; dy++) {
            double d = Math.sqrt(dx * dx + dz * dz + (dy - h) * (dy - h) * 1.6);
            if (d > 3.1 || (dx == 0 && dz == 0 && dy <= h)) continue;
            if (!c.isSet(x + dx, Y + dy, z + dz))
                c.set(x + dx, Y + dy, z + dz, rnd.nextInt(4) == 0 ? "minecraft:flowering_azalea_leaves[persistent=true]" : "minecraft:azalea_leaves[persistent=true]");
        }
    }

    // ------------------------------------------------------------------ farm ring + trading hall

    private static final java.util.Map<String, String> MATURE = java.util.Map.of(
            "wheat", "minecraft:wheat[age=7]", "carrots", "minecraft:carrots[age=7]", "potatoes", "minecraft:potatoes[age=7]", "beetroots", "minecraft:beetroots[age=3]");

    /** Decorative crop fields in a ring around the town: eight sectors split by roads, a road on each side. */
    private void farmRing() {
        for (int x = -FARM_OUT - 2; x <= FARM_OUT + 2; x++)
            for (int z = -FARM_OUT - 2; z <= FARM_OUT + 2; z++) {
                double r = Math.hypot(x, z);
                if (r < FARM_IN - 3.5 || r > FARM_OUT + 1.5) continue;
                String crop = FARM_SECTORS[farmSector(x + 0.5, z + 0.5)];
                for (int y = Y + 1; y <= Y + 4; y++) c.set(x, y, z, "minecraft:air");
                c.set(x, Y - 1, z, "minecraft:dirt");
                if (r < FARM_IN - 0.5 || r > FARM_OUT - 0.5) {   // inner and outer ring roads
                    c.set(x, Y, z, r < FARM_IN - 2.5 || r > FARM_OUT + 0.5 ? "minecraft:polished_blackstone_bricks" : "minecraft:packed_mud");
                    continue;
                }
                if (crop.equals("hall")) { c.set(x, Y, z, "minecraft:grass_block"); continue; }
                double rd = farmRoadDist(x + 0.5, z + 0.5);
                if (rd <= 1.6) { c.set(x, Y, z, rd <= 0.6 ? "minecraft:packed_mud" : "minecraft:mud_bricks"); continue; }
                int mx = Math.floorMod(x, 9), m3 = Math.floorMod(x, 3), m4 = Math.floorMod(x, 4);
                switch (crop) {
                    case "melons" -> {
                        boolean melon = Math.floorMod(z, 10) < 5;
                        if (m3 == 0) {
                            c.set(x, Y, z, "minecraft:farmland[moisture=7]");
                            c.setLate(x, Y + 1, z, "minecraft:attached_" + (melon ? "melon" : "pumpkin") + "_stem[facing=east]");
                        } else if (m3 == 1) {
                            c.set(x, Y, z, "minecraft:dirt");
                            c.set(x, Y + 1, z, melon ? "minecraft:melon" : "minecraft:pumpkin");
                        } else c.set(x, Y, z, "minecraft:rooted_dirt");
                    }
                    case "sugar_cane" -> {
                        if (m4 == 0) c.set(x, Y, z, "minecraft:water");
                        else if (m4 == 2) c.set(x, Y, z, "minecraft:coarse_dirt");
                        else { c.set(x, Y, z, "minecraft:sand"); for (int h = 1; h <= 3; h++) c.setLate(x, Y + h, z, "minecraft:sugar_cane"); }
                    }
                    default -> {
                        if (mx == 4) c.set(x, Y, z, "minecraft:water");
                        else { c.set(x, Y, z, "minecraft:farmland[moisture=7]"); c.setLate(x, Y + 1, z, MATURE.get(crop)); }
                    }
                }
            }
        // lamps where each farm road meets the ring roads, a scarecrow in every field
        for (int i = 0; i < 8; i++) {
            double ra = Math.PI / 8 + i * Math.PI / 4;
            for (double rr : new double[]{FARM_IN - 2, FARM_OUT + 1}) {
                int lx = (int) Math.round(Math.cos(ra) * rr + Math.sin(ra) * 2.5), lz = (int) Math.round(Math.sin(ra) * rr - Math.cos(ra) * 2.5);
                if (!FARM_SECTORS[farmSector(lx + 0.5, lz + 0.5)].equals("hall")) lamp(lx, lz);
            }
            if (FARM_SECTORS[i].equals("hall")) continue;
            double sa = i * Math.PI / 4;
            int sx = (int) Math.round(Math.cos(sa) * 69), sz = (int) Math.round(Math.sin(sa) * 69);
            String face = Math.abs(sx) > Math.abs(sz) ? (sx > 0 ? "west" : "east") : (sz > 0 ? "north" : "south");
            c.set(sx, Y, sz, "minecraft:coarse_dirt");
            c.set(sx, Y + 1, sz, "minecraft:oak_fence");
            c.set(sx, Y + 2, sz, "minecraft:hay_block");
            c.set(sx, Y + 3, sz, "minecraft:carved_pumpkin[facing=" + face + "]");
        }
    }

    /** The road from the crates pavilion up to the Trading Hall doors. */
    private void northRoad() {
        for (int z = CRATE_Z - 11; z >= TH_Z2; z--) for (int x = -2; x <= 2; x++) {
            pathStrip(x, z, x);
            for (int y = Y + 1; y <= Y + 4; y++) c.set(x, y, z, "minecraft:air");
        }
        for (int z = CRATE_Z - 14; z >= TH_Z2 + 3; z -= 6) for (int s : new int[]{-1, 1}) lamp(s * 4, z);
    }

    /** A forge-style market hall with a gabled roof; the plugin spawns a villager in each stall. */
    private void tradingHall() {
        int x1 = TH_X1, x2 = TH_X2, z1 = TH_Z1, z2 = TH_Z2;
        for (int x = x1 - 1; x <= x2 + 1; x++)
            for (int z = z1 - 1; z <= z2 + 1; z++) {
                for (int y = Y - 5; y < Y; y++) if (!c.isSet(x, y, z) || y == Y - 1) c.set(x, y, z, y == Y - 1 ? "minecraft:stone" : "minecraft:deepslate");
                for (int y = Y + 1; y <= Y + 18; y++) c.set(x, y, z, "minecraft:air");
                boolean wall = x == x1 || x == x2 || z == z1 || z == z2;
                boolean rim = x < x1 || x > x2 || z < z1 || z > z2;
                c.set(x, Y, z, rim || wall ? "minecraft:polished_blackstone_bricks"
                        : Math.abs(x) <= 2 ? ((z & 1) == 0 ? "minecraft:polished_blackstone" : "minecraft:gilded_blackstone")
                        : (((x + z) & 1) == 0 ? "minecraft:dark_oak_planks" : "minecraft:spruce_planks"));
            }
        // walls: red-brick pillars and tall amber windows
        for (int y = Y + 1; y <= Y + 7; y++) {
            for (int x = x1; x <= x2; x++) for (int z : new int[]{z1, z2}) {
                boolean pillar = Math.floorMod(x - x1, 5) == 0;
                boolean window = !pillar && y >= Y + 3 && y <= Y + 5 && Math.floorMod(x - x1, 5) == 2;
                c.set(x, y, z, pillar ? "minecraft:red_nether_bricks" : window ? "minecraft:orange_stained_glass" : "minecraft:polished_blackstone_bricks");
            }
            for (int z = z1; z <= z2; z++) for (int x : new int[]{x1, x2}) {
                boolean pillar = Math.floorMod(z - z1, 4) == 0;
                boolean window = !pillar && y >= Y + 3 && y <= Y + 5 && Math.floorMod(z - z1, 4) == 2;
                c.set(x, y, z, pillar ? "minecraft:red_nether_bricks" : window ? "minecraft:orange_stained_glass" : "minecraft:polished_blackstone_bricks");
            }
        }
        // gabled roof running north-south, gable walls, a shroomlight-studded ridge
        for (int x = x1 - 1; x <= x2 + 1; x++) {
            int h = Y + 8 + (x2 + 1 - Math.abs(x)) / 2;
            for (int z = z1 - 1; z <= z2 + 1; z++) c.set(x, h, z, x == 0 ? (Math.floorMod(z, 4) == 0 ? "minecraft:shroomlight" : "minecraft:red_nether_bricks") : "minecraft:nether_bricks");
            for (int y = Y + 8; y < h; y++) for (int z : new int[]{z1, z2}) c.set(x, y, z, Math.abs(x) <= 1 && y >= h - 2 ? "minecraft:orange_stained_glass" : "minecraft:polished_blackstone_bricks");
        }
        for (int z = z1 + 2; z <= z2 - 2; z += 4) for (int x : new int[]{-9, 0, 9}) {
            int h = Y + 8 + (x2 + 1 - Math.abs(x)) / 2;
            c.setLate(x, h - 1, z, "minecraft:chain");
            c.setLate(x, h - 2, z, "minecraft:lantern[hanging=true]");
        }
        // the doors: a 5-wide opening in the south wall under a gold arch
        for (int x = -2; x <= 2; x++) for (int y = Y + 1; y <= Y + 4; y++) c.set(x, y, z2, "minecraft:air");
        for (int x = -3; x <= 3; x++) c.set(x, Y + 5, z2, "minecraft:gold_block");
        for (int y = Y + 1; y <= Y + 4; y++) for (int x : new int[]{-3, 3}) c.set(x, y, z2, "minecraft:gold_block");
        c.set(0, Y + 6, z2, "minecraft:raw_gold_block");
        for (int x : new int[]{-5, 5}) { c.set(x, Y + 1, z2 + 1, "minecraft:polished_blackstone_bricks"); c.setLate(x, Y + 2, z2 + 1, "minecraft:lantern"); }
        // stalls: counter between aisle and villager, workstation behind, barrels, fence dividers
        for (TradeStall st : STALLS) {
            if (st.facing().equals("south")) {
                c.set(st.x(), Y + 1, st.z() - 1, "minecraft:" + st.station());
                c.set(st.x(), Y + 1, st.z() + 2, "minecraft:dark_oak_slab[type=top]");
                continue;
            }
            int sx = Integer.signum(st.x());
            for (int dz = -1; dz <= 1; dz++) c.set(st.x() - sx, Y + 1, st.z() + dz, dz == 0 ? "minecraft:dark_oak_slab[type=top]" : "minecraft:polished_blackstone_wall");
            c.set(st.x() + 2 * sx, Y + 1, st.z(), "minecraft:" + st.station());
            c.set(st.x() + 3 * sx, Y + 1, st.z() - 1, "minecraft:barrel[facing=up]");
            c.set(st.x() + 3 * sx, Y + 1, st.z() + 1, "minecraft:barrel[facing=up]");
            for (int x = st.x() - sx; Math.abs(x) < x2; x += sx) c.set(x, Y + 1, st.z() - 2, "minecraft:spruce_fence");
        }
    }

    /** Small floating islands around the spawn. */
    private void satellites() {
        double[][] spots = {{0.4, 108, -8}, {1.2, 112, 6}, {2.0, 106, -12}, {2.7, 114, 3}, {3.6, 109, -6}, {4.5, 113, 9}, {5.4, 107, -10}};
        for (double[] sp : spots) {
            int ox = (int) (sp[1] * Math.cos(sp[0])), oz = (int) (sp[1] * Math.sin(sp[0])), ty = Y + (int) sp[2];
            int r = 5 + rnd.nextInt(3);
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
                double d = Math.hypot(x, z);
                if (d > r + 0.3) continue;
                int depth = Math.max(1, (int) ((1 - d / (r + 1)) * 7) + rnd.nextInt(2));
                for (int k = 0; k < depth; k++) c.set(ox + x, ty - k, oz + z, k == 0 ? "minecraft:grass_block" : k < 2 ? "minecraft:dirt" : rnd.nextInt(8) == 0 ? "minecraft:copper_ore" : "minecraft:stone");
            }
            if (rnd.nextBoolean()) {
                for (int y = 1; y <= 4; y++) c.set(ox, ty + y, oz, "minecraft:dark_oak_log");
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 3; dy <= 6; dy++)
                    if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 4) <= 3 && !(dx == 0 && dz == 0 && dy <= 4)) c.set(ox + dx, ty + dy, oz + dz, "minecraft:azalea_leaves[persistent=true]");
            } else {
                c.set(ox, ty + 1, oz, "minecraft:raw_gold_block");
                c.set(ox + 1, ty + 1, oz, "minecraft:raw_copper_block");
                c.set(ox, ty + 2, oz, "minecraft:amethyst_block");
            }
        }
    }

    // ------------------------------------------------------------------ koth arena

    private void arenaFloor() {
        for (int x = -KOTH_R; x <= KOTH_R; x++)
            for (int z = -KOTH_R; z <= KOTH_R; z++) {
                double r = Math.hypot(x, z);
                if (r > KOTH_R + 0.5) continue;
                double a = Math.atan2(z, x);
                boolean spoke = Math.abs(Math.sin(a * 4)) < 0.08 && r > 13;
                int n = rnd.nextInt(10);
                String b = spoke ? "minecraft:red_nether_bricks" : n < 5 ? "minecraft:polished_blackstone" : n < 8 ? "minecraft:deepslate_tiles" : "minecraft:cracked_deepslate_tiles";
                if (r > 24.5 && r <= 25.5) b = "minecraft:waxed_cut_copper";
                c.set(KOTH_X + x, KOTH_Y, KOTH_Z + z, b);
            }
        // spawn pads at the four compass points
        for (int[] d : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}})
            for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++)
                c.set(KOTH_X + d[0] * 28 + u, KOTH_Y, KOTH_Z + d[1] * 28 + v, u == 0 && v == 0 ? "minecraft:shroomlight" : "minecraft:crimson_nylium");
    }

    private void hill() {
        double[] radii = {12.5, 10.5, 8.5, 6.5, 4.5, 3.2};
        for (int t = 0; t < radii.length; t++)
            for (int x = -13; x <= 13; x++)
                for (int z = -13; z <= 13; z++) {
                    if (Math.hypot(x, z) > radii[t]) continue;
                    String b = t % 2 == 0 ? "minecraft:polished_blackstone_bricks" : "minecraft:deepslate_bricks";
                    if (Math.hypot(x, z) > radii[t] - 1) b = t % 2 == 0 ? "minecraft:waxed_cut_copper" : "minecraft:polished_deepslate";
                    c.set(KOTH_X + x, KOTH_Y + 1 + t, KOTH_Z + z, b);
                }
        // capture pad: gold with a beacon in the middle (iron base under it lights the beam)
        int top = HILL_TOP;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            c.set(KOTH_X + x, top, KOTH_Z + z, "minecraft:gold_block");
            c.set(KOTH_X + x, top - 1, KOTH_Z + z, "minecraft:iron_block");
        }
        c.set(KOTH_X, top, KOTH_Z, "minecraft:beacon");
        for (int[] k : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            c.set(KOTH_X + k[0], top, KOTH_Z + k[1], "minecraft:netherite_block");
            c.set(KOTH_X + k[0], top + 1, KOTH_Z + k[1], "minecraft:polished_blackstone_wall");
            c.setLate(KOTH_X + k[0], top + 2, KOTH_Z + k[1], "minecraft:soul_lantern");
        }
    }

    private void cover() {
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI * 2 / 12 + 0.26;
            double r = 17 + (i % 3) * 2.5;
            int x = (int) Math.round(r * Math.cos(a)), z = (int) Math.round(r * Math.sin(a));
            boolean alongX = Math.abs(Math.cos(a)) < 0.7;
            int len = 2 + rnd.nextInt(2);
            for (int k = -len; k <= len; k++) {
                int px = KOTH_X + x + (alongX ? k : 0), pz = KOTH_Z + z + (alongX ? 0 : k);
                int h = 2 + (Math.abs(k) < len ? 1 : 0);
                for (int y = 1; y <= h; y++) c.set(px, KOTH_Y + y, pz, y == h ? "minecraft:polished_blackstone_brick_slab" : (rnd.nextInt(4) == 0 ? "minecraft:waxed_copper_grate" : "minecraft:polished_blackstone_bricks"));
            }
        }
    }

    private void arenaWall() {
        for (int x = -KOTH_R - 2; x <= KOTH_R + 2; x++)
            for (int z = -KOTH_R - 2; z <= KOTH_R + 2; z++) {
                double r = Math.hypot(x, z);
                if (r <= KOTH_R + 0.5 || r > KOTH_R + 1.8) continue;
                for (int y = 0; y <= 3; y++) {
                    String b = y == 3 ? (((x + z) & 1) == 0 ? "minecraft:polished_blackstone_brick_wall" : "minecraft:air") : "minecraft:polished_blackstone_bricks";
                    if (y == 2 && ((x * 7 + z * 3) % 11 == 0)) b = "minecraft:shroomlight";
                    c.set(KOTH_X + x, KOTH_Y + y, KOTH_Z + z, b);
                }
                for (int y = 4; y <= 14; y++) c.set(KOTH_X + x, KOTH_Y + y, KOTH_Z + z, "minecraft:barrier");
            }
    }
}
