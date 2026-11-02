package net.alloymc.core.arcade;

import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;
import net.alloymc.core.survival.SkyHubBuilder;

import java.util.*;

/**
 * The BlackForge Arcade hub: a neon entertainment district floating in the sky. A central plaza with a giant arcade
 * cabinet, an upper ring walkway and benches; five colour-coded bridges out to themed district islands (PvP,
 * Competitive, Parkour, Casual, Party) plus a "More Games" expansion island. Every district has {@link #PODS} generic
 * game pods (a pad, a frame and a join plate); {@code ArcadeHubLife} puts each game on the next free pod of its
 * district at runtime, so new games appear without rebuilding. Coordinates are absolute; the plaza floor is at Y.
 */
public final class ArcadeHubBuilder {
    private ArcadeHubBuilder() {}

    public static final int Y = 100, PODS = 8;
    public static final double SPAWN_X = 0.5, SPAWN_Z = 33.5;

    /** A district: id, label, colour (MiniMessage), centre, floor height offset, island radius, palette. */
    public record District(String id, String label, String color, int x, int z, int dy, int r, String floor, String accent, String light, String rock) {}

    public static final List<District> DISTRICTS = List.of(
            new District("pvp", "PvP District", "<#ff5a3c>", 78, -52, 4, 26, "red_nether_bricks", "orange_concrete", "shroomlight", "blackstone"),
            new District("competitive", "Competitive", "<#ffc53d>", 0, -100, 8, 26, "polished_blackstone_bricks", "gold_block", "glowstone", "gilded_blackstone"),
            new District("parkour", "Parkour", "<#9dff6b>", 96, 30, 12, 24, "white_concrete", "lime_concrete", "sea_lantern", "calcite"),
            new District("casual", "Casual", "<#5fd3ff>", -96, 30, 2, 26, "grass_block", "light_blue_concrete", "sea_lantern", "stone"),
            new District("party", "Party", "<#ff8ad8>", -78, -52, 3, 26, "white_terracotta", "magenta_concrete", "ochre_froglight", "pink_terracotta"),
            new District("more", "More Games", "<#b9a7ff>", 0, 100, -4, 22, "smooth_stone", "purple_concrete", "end_rod", "andesite"));

    /** Which district each game goes to; new games without an entry go by category (see ArcadeHubLife). */
    public static final Map<String, String> GAME_DISTRICT = Map.ofEntries(
            Map.entry("kitpvp", "pvp"), Map.entry("duels", "pvp"), Map.entry("skywars", "pvp"), Map.entry("bridge", "pvp"), Map.entry("uhc", "pvp"),
            Map.entry("bedwars", "competitive"), Map.entry("forgewars", "competitive"),
            Map.entry("parkour", "parkour"), Map.entry("dropper", "parkour"),
            Map.entry("buildbattle", "casual"), Map.entry("speedbuilders", "casual"), Map.entry("hideseek", "casual"),
            Map.entry("blockparty", "party"), Map.entry("infected", "party"), Map.entry("murder", "party"), Map.entry("spleef", "party"),
            Map.entry("tntrun", "party"), Map.entry("tnttag", "party"));

    /** A game pod: its district, slot number and the join plate (x, y, z). */
    public record Pod(String district, int slot, int x, int y, int z) {}

    /** Hidden secrets (id, x, y, z): right-click the block there once for a reward. */
    public record Secret(String id, int x, int y, int z) {}

    public static District district(String id) { return DISTRICTS.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow(); }

    /** The pods of every district, in slot order: an arc on the district's far side, facing its centre. */
    public static List<Pod> pods() {
        List<Pod> out = new ArrayList<>();
        for (District d : DISTRICTS) {
            double away = Math.atan2(d.z(), d.x());   // pointing away from the plaza
            for (int i = 0; i < PODS; i++) {
                double a = away + Math.toRadians(-105 + i * 30);
                int px = d.x() + (int) Math.round(Math.cos(a) * (d.r() - 8)), pz = d.z() + (int) Math.round(Math.sin(a) * (d.r() - 8));
                out.add(new Pod(d.id(), i, px, Y + d.dy() + 1, pz));
            }
        }
        return out;
    }

    public static final List<Secret> SECRETS = List.of(
            new Secret("cabinet", 0, Y - 5, -2),            // in the crawlspace under the giant cabinet
            new Secret("summit", 96, Y + 12 + 31, 30),       // top of the parkour tower
            new Secret("gondola", -78, Y + 3 + 15, -40));    // in the top gondola of the ferris wheel

    /** Where the NPC kiosks stand on the plaza: id, x, z. */
    public record Kiosk(String id, int x, int z) {}
    public static final List<Kiosk> KIOSKS = List.of(
            new Kiosk("calendar", -16, 20), new Kiosk("cosmetics", 16, 20), new Kiosk("store", -22, 6),
            new Kiosk("stats", 22, 6), new Kiosk("guide", 0, 24));

    public static double[] board() { return new double[]{0.5, Y + 8 + 9, -100 - 14.5}; }

    // ================================================================== helpers

    private static String mc(String s) { return s.startsWith("minecraft:") ? s : "minecraft:" + s; }

    private static final String[] NEON = {"magenta_concrete", "cyan_concrete", "yellow_concrete", "lime_concrete", "orange_concrete", "light_blue_concrete"};

    // ================================================================== the plaza

    private static void plaza(Canvas c) {
        int R = 36;
        SkyHubBuilder.island(c, 0, 0, R + 4, 0, 30, 901, "polished_blackstone", new String[]{"blackstone", "basalt", "polished_basalt", "blackstone", "gilded_blackstone"});
        for (int x = -R - 4; x <= R + 4; x++)
            for (int z = -R - 4; z <= R + 4; z++) {
                double d = Math.hypot(x, z);
                if (d > R + 4 || !c.isSet(x, Y, z)) continue;
                double a = Math.atan2(z, x);
                int ring = (int) d;
                String m;
                if (Math.abs(d - 14) < 0.55 || Math.abs(d - 27) < 0.55) m = NEON[Math.floorMod((int) Math.floor((a + Math.PI) / (Math.PI / 12)), NEON.length)];   // neon rings
                else if (Math.abs(d - 14) < 1.3 || Math.abs(d - 27) < 1.3) m = "black_concrete";
                else if (ring % 6 == 3 && Math.floorMod((int) Math.floor((a + Math.PI) / (Math.PI / 24)), 2) == 0) m = "sea_lantern";   // light dots
                else if (d > R) m = "polished_blackstone_bricks";
                else m = ((x + z) & 3) == 0 ? "polished_andesite" : ((x * 3 + z) % 5 == 0 ? "smooth_stone" : "polished_deepslate");
                c.set(x, Y, z, mc(m));
            }
        // the upper ring walkway (level 2) between r 18 and 23, on pillars, with glass rails and stairs up
        for (int x = -24; x <= 24; x++)
            for (int z = -24; z <= 24; z++) {
                double d = Math.hypot(x, z);
                if (d < 19.5 || d > 22.5) continue;
                boolean gap = Math.abs(x) <= 3 && z > 0;   // open over the spawn path
                if (gap) continue;
                boolean rim = d > 21.9 || d < 20.1;
                c.set(x, Y + 6, z, mc(rim ? "smooth_quartz_slab[type=top]" : "smooth_quartz_slab[type=top]"));
                if (rim) c.set(x, Y + 7, z, mc(d > 21.9 ? "cyan_stained_glass_pane" : "magenta_stained_glass_pane"));
                double a = Math.atan2(z, x);
                if (Math.floorMod((int) Math.round(Math.toDegrees(a)), 45) == 0 && Math.abs(d - 21) < 0.6)
                    for (int y = Y + 1; y < Y + 6; y++) c.set(x, y, z, mc(y == Y + 5 ? "sea_lantern" : "quartz_pillar"));
            }
        for (int side : new int[]{-1, 1})   // stairs up on both sides of the spawn path
            for (int k = 0; k < 6; k++)
                for (int w = 0; w < 2; w++) c.set(side * (9 + w), Y + 1 + k, 26 - k, "minecraft:quartz_stairs[facing=north]");
        // benches and planters round the inner ring: places to hang out
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4 + Math.PI / 8;
            int bx = (int) Math.round(Math.cos(a) * 11), bz = (int) Math.round(Math.sin(a) * 11);
            if (bz > 8 && Math.abs(bx) < 4) continue;
            c.set(bx, Y + 1, bz, "minecraft:dark_oak_stairs[facing=" + (Math.abs(bx) > Math.abs(bz) ? (bx > 0 ? "east" : "west") : (bz > 0 ? "south" : "north")) + "]");
            c.set(bx + (Math.abs(bx) > Math.abs(bz) ? 0 : 1), Y + 1, bz + (Math.abs(bx) > Math.abs(bz) ? 1 : 0), "minecraft:potted_azalea_bush");
        }
        // spawn path and pad, the hub exit plate
        for (int z = 28; z <= R + 4; z++) for (int x = -3; x <= 3; x++) c.set(x, Y, z, mc(Math.abs(x) == 3 ? "magenta_concrete" : "polished_blackstone_bricks"));
        c.set(0, Y, 33, "minecraft:sea_lantern");
        c.set(0, Y, 39, "minecraft:red_concrete");
        c.setLate(0, Y + 1, 39, "minecraft:light_weighted_pressure_plate");   // back to the hub
        smallCabinet(c, -8, 30, "cyan"); smallCabinet(c, 8, 30, "magenta"); smallCabinet(c, -30, -8, "lime"); smallCabinet(c, 30, -8, "yellow");
        cabinet(c);
    }

    /** The giant arcade cabinet in the middle, its screen facing spawn (south). */
    private static void cabinet(Canvas c) {
        int x0 = -6, x1 = 6, zb = -8, zf = 2, top = Y + 24;
        for (int x = x0; x <= x1; x++)
            for (int z = zb; z <= zf; z++)
                for (int y = Y + 1; y <= top; y++) {
                    boolean side = x == x0 || x == x1, back = z == zb;
                    // the classic profile: deep base, slanted control deck, upright screen, overhanging marquee
                    int depthAt = y < Y + 8 ? zf : y < Y + 10 ? zf - (y - (Y + 7)) : y < Y + 20 ? zf - 3 : zf - 1;
                    if (z > depthAt) continue;
                    String m = side ? ((y + z) % 5 == 0 ? "magenta_concrete" : (y + z) % 5 == 2 ? "cyan_concrete" : "black_concrete") : "black_concrete";
                    c.set(x, y, z, mc(back ? "black_concrete" : m));
                }
        // hollow base with a crawlspace (a secret lives under here)
        for (int x = -2; x <= 2; x++) for (int z = -6; z <= 0; z++) for (int y = Y - 6; y <= Y - 4; y++) c.set(x, y, z, "minecraft:air");
        for (int x = -2; x <= 2; x++) for (int z = -6; z <= 0; z++) c.set(x, Y - 7, z, "minecraft:polished_blackstone");
        c.set(0, Y - 5, -2, "minecraft:jukebox");
        for (int y = Y - 3; y <= Y; y++) c.set(-5, y, 3, "minecraft:ladder[facing=north]");
        c.set(-5, Y, 3, "minecraft:polished_blackstone_brick_slab[type=top]");   // looks like floor, it's the way down
        // the screen: dark glass with a pixel border; the live text floats in front of it
        for (int x = x0 + 1; x <= x1 - 1; x++)
            for (int y = Y + 11; y <= Y + 19; y++) {
                boolean border = x == x0 + 1 || x == x1 - 1 || y == Y + 11 || y == Y + 19;
                c.set(x, y, zf - 3, mc(border ? NEON[Math.floorMod(x + y, 3)] : "black_stained_glass"));
                c.set(x, y, zf - 4, "minecraft:sea_lantern");
            }
        // control deck: buttons and a joystick
        for (int x = x0 + 2; x <= x1 - 2; x++) c.set(x, Y + 8, zf, "minecraft:polished_blackstone_slab[type=bottom]");
        c.setLate(-3, Y + 9, zf - 1, "minecraft:end_rod"); c.set(-3, Y + 10, zf - 1, "minecraft:red_wool");
        String[] btn = {"red_candle[candles=1,lit=true]", "lime_candle[candles=1,lit=true]", "light_blue_candle[candles=1,lit=true]", "yellow_candle[candles=1,lit=true]"};
        for (int k = 0; k < 4; k++) c.setLate(k, Y + 9, zf - 1, mc(btn[k]));
        // marquee with the ARCADE letters, lit
        for (int x = x0 - 1; x <= x1 + 1; x++) for (int y = top - 3; y <= top; y++) c.set(x, y, zf, mc(y == top || y == top - 3 || x == x0 - 1 || x == x1 + 1 ? "magenta_concrete" : "black_concrete"));
        int w = BlockFont.width("ARCADE", 1);
        BlockFont.draw("ARCADE", 1, (col, row, gr) -> {
            int x = -w / 2 + col, y = top - 1 - row / 2;
            if (row % 2 == 0 && x >= x0 && x <= x1) c.set(x, y, zf + 1, "minecraft:yellow_stained_glass");
        });
        for (int x = x0; x <= x1; x += 2) c.setLate(x, top + 1, zf, "minecraft:end_rod");
    }

    private static void smallCabinet(Canvas c, int x, int z, String color) {
        for (int y = Y + 1; y <= Y + 3; y++) { c.set(x, y, z, mc(y == Y + 3 ? color + "_stained_glass" : "black_concrete")); c.set(x + 1, y, z, mc(y == Y + 3 ? color + "_stained_glass" : "black_concrete")); }
        c.set(x, Y + 4, z, mc(color + "_concrete")); c.set(x + 1, Y + 4, z, mc(color + "_concrete"));
        c.setLate(x, Y + 2, z + (z > 0 ? -1 : 1), "minecraft:stone_button[face=floor]");
    }

    // ================================================================== bridges

    private static void bridge(Canvas c, District d) {
        double a = Math.atan2(d.z(), d.x());
        double startR = 38, endR = Math.hypot(d.x(), d.z()) - d.r() + 2;
        int steps = (int) Math.round(endR - startR);
        for (int i = 0; i <= steps; i++) {
            double r = startR + i, t = i / (double) Math.max(1, steps);
            int y = Y + (int) Math.round(d.dy() * t);
            int cx = (int) Math.round(Math.cos(a) * r), cz = (int) Math.round(Math.sin(a) * r);
            for (int k = -2; k <= 2; k++) {
                int x = cx + (int) Math.round(-Math.sin(a) * k), z = cz + (int) Math.round(Math.cos(a) * k);
                boolean edge = Math.abs(k) == 2;
                c.set(x, y, z, mc(edge ? d.accent() : i % 4 == 0 ? d.light() : "polished_blackstone_bricks"));
                if (edge) c.set(x, y + 1, z, mc(i % 4 == 0 ? d.light() : "black_stained_glass_pane"));
                if (Math.abs(k) == 1 && i % 8 == 4) c.setLate(x, y - 1, z, "minecraft:end_rod[facing=down]");
            }
        }
    }

    // ================================================================== districts

    private static void district(Canvas c, District d) {
        Canvas isle = new Canvas();
        SkyHubBuilder.island(isle, d.x(), d.z(), d.r(), 0, 26, d.id().hashCode(), d.floor(), new String[]{d.rock(), "blackstone", d.rock(), "basalt", d.rock()});
        int dy = d.dy();
        isle.blocks().forEach((k, v) -> c.set(Canvas.kx(k), Canvas.ky(k) + dy, Canvas.kz(k), v));
        isle.lateBlocks().forEach((k, v) -> c.setLate(Canvas.kx(k), Canvas.ky(k) + dy, Canvas.kz(k), v));
        int fy = Y + dy;
        // floor pattern: accent rings and spokes over the district's floor
        for (int x = -d.r(); x <= d.r(); x++)
            for (int z = -d.r(); z <= d.r(); z++) {
                if (!c.isSet(d.x() + x, fy, d.z() + z)) continue;
                double r = Math.hypot(x, z);
                if (Math.abs(r - 7) < 0.5 || Math.abs(r - (d.r() - 3)) < 0.5) c.set(d.x() + x, fy, d.z() + z, mc(d.accent()));
                else if (Math.abs(r - 7) < 1.2 && (x + z) % 3 == 0) c.set(d.x() + x, fy, d.z() + z, mc(d.light()));
            }
        // the pods
        for (Pod p : pods()) if (p.district().equals(d.id())) pod(c, d, p);
        // the district's landmark
        switch (d.id()) {
            case "pvp" -> colosseum(c, d, fy);
            case "competitive" -> stage(c, d, fy);
            case "parkour" -> towers(c, d, fy);
            case "casual" -> garden(c, d, fy);
            case "party" -> carnival(c, d, fy);
            default -> construction(c, d, fy);
        }
    }

    /** A pod: a pad in the district's colours, a frame arch with lights, and the join plate. */
    private static void pod(Canvas c, District d, Pod p) {
        int fy = p.y() - 1;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) c.set(p.x() + dx, fy, p.z() + dz, mc(Math.abs(dx) == 2 || Math.abs(dz) == 2 ? d.accent() : "polished_blackstone"));
        c.set(p.x(), fy, p.z(), mc(d.light()));
        c.setLate(p.x(), fy + 1, p.z(), "minecraft:light_weighted_pressure_plate");
        // frame: two posts and a lintel behind the pad (away from the district centre)
        double a = Math.atan2(p.z() - d.z(), p.x() - d.x());
        int bx = p.x() + (int) Math.round(Math.cos(a) * 3), bz = p.z() + (int) Math.round(Math.sin(a) * 3);
        int ox = (int) Math.round(-Math.sin(a) * 2), oz = (int) Math.round(Math.cos(a) * 2);
        for (int y = fy + 1; y <= fy + 7; y++) {
            String m = y % 3 == 0 ? d.light() : y == fy + 7 ? d.accent() : "polished_blackstone_bricks";
            c.set(bx + ox, y, bz + oz, mc(m)); c.set(bx - ox, y, bz - oz, mc(m));
        }
        for (int k = -2; k <= 2; k++) c.set(bx + (int) Math.round(-Math.sin(a) * k), fy + 8, bz + (int) Math.round(Math.cos(a) * k), mc(Math.abs(k) == 2 ? d.light() : d.accent()));
        c.setLate(bx, fy + 9, bz, "minecraft:end_rod");
    }

    private static void colosseum(Canvas c, District d, int fy) {
        // a ring wall with arches, braziers on top and banners
        for (double a = 0; a < Math.PI * 2; a += 0.02) {
            int x = d.x() + (int) Math.round(Math.cos(a) * 11), z = d.z() + (int) Math.round(Math.sin(a) * 11);
            boolean arch = Math.floorMod((int) Math.round(Math.toDegrees(a)), 45) < 12;
            for (int y = fy + 1; y <= fy + 5; y++) if (!(arch && y <= fy + 3)) c.set(x, y, z, mc(y == fy + 5 ? "chiseled_polished_blackstone" : (y % 2 == 0 ? "red_nether_bricks" : "nether_bricks")));
            if (Math.floorMod((int) Math.round(Math.toDegrees(a)), 45) == 22) c.setLate(x, fy + 6, z, "minecraft:campfire[lit=true]");
        }
        // crossed swords statue in the middle: iron and gold
        for (int k = -4; k <= 4; k++) {
            c.set(d.x() + k, fy + 6 + k / 2 + 2, d.z(), "minecraft:iron_block");
            c.set(d.x() - k, fy + 6 + k / 2 + 2, d.z(), "minecraft:iron_block");
        }
        for (int y = fy + 1; y <= fy + 5; y++) c.set(d.x(), y, d.z(), mc(y == fy + 5 ? "gold_block" : "polished_blackstone_bricks"));
        for (int[] o : new int[][]{{-5, -5}, {5, 5}, {-5, 5}, {5, -5}}) { c.set(d.x() + o[0], fy + 1, d.z() + o[1], "minecraft:anvil"); c.set(d.x() + o[0], fy + 1, d.z() + o[1] + 1, "minecraft:grindstone[face=floor]"); }
    }

    private static void stage(Canvas c, District d, int fy) {
        // tiered podium 2-1-3 with a giant trophy behind it, black and gold
        int[][] pod = {{-4, 3, 2}, {0, 4, 3}, {4, 2, 1}};
        String[] tops = {"iron_block", "gold_block", "copper_block"};
        for (int i = 0; i < 3; i++)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 1; y <= pod[i][1]; y++)
                c.set(d.x() + pod[i][0] + dx, fy + y, d.z() + 4 + dz, mc(y == pod[i][1] ? tops[i] : "polished_blackstone_bricks"));
        int tx = d.x(), tz = d.z() - 6;
        for (int y = 1; y <= 3; y++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (Math.abs(dx) + Math.abs(dz) <= 3) c.set(tx + dx, fy + y, tz + dz, "minecraft:polished_blackstone_bricks");
        for (int y = 4; y <= 6; y++) c.set(tx, fy + y, tz, "minecraft:gold_block");
        for (int y = 7; y <= 11; y++) {
            int r = y < 9 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) if (Math.abs(dx) == r || Math.abs(dz) == r || y == 7) c.set(tx + dx, fy + y, tz + dz, "minecraft:gold_block");
        }
        for (int y = 8; y <= 10; y++) { c.set(tx - 3, fy + y, tz, "minecraft:gold_block"); c.set(tx + 3, fy + y, tz, "minecraft:gold_block"); }
        c.setLate(tx, fy + 12, tz, "minecraft:end_rod");
        // the leaderboard wall behind the trophy
        for (int x = -9; x <= 9; x++) for (int y = 1; y <= 12; y++) c.set(d.x() + x, fy + y, d.z() - 14, mc(Math.abs(x) == 9 || y == 12 || y == 1 ? "gold_block" : "black_concrete"));
    }

    private static void towers(Canvas c, District d, int fy) {
        // the playable jump course: a spiral of floating pads round a tower to the summit (a secret waits there)
        String[] col = {"lime_concrete", "yellow_concrete", "cyan_concrete", "orange_concrete", "magenta_concrete"};
        for (int y = 1; y <= 30; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(d.x() + dx, fy + y, d.z() + dz, mc(y % 6 == 0 ? "sea_lantern" : "white_concrete"));
        for (int i = 0; i < 28; i++) {
            double a = i * 0.62;
            int x = d.x() + (int) Math.round(Math.cos(a) * 4.5), z = d.z() + (int) Math.round(Math.sin(a) * 4.5);
            c.set(x, fy + 1 + i, z, mc(i % 7 == 6 ? "slime_block" : col[i % col.length]));
        }
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) c.set(d.x() + dx, fy + 30, d.z() + dz, "minecraft:gold_block");
        c.set(d.x(), fy + 31, d.z(), "minecraft:jukebox");   // the summit secret
        // decorative colourful pillars around
        Random rnd = new Random(5);
        for (int k = 0; k < 9; k++) {
            double a = k * 0.7 + 0.4;
            int x = d.x() + (int) Math.round(Math.cos(a) * 11), z = d.z() + (int) Math.round(Math.sin(a) * 11);
            int h = 4 + rnd.nextInt(10);
            for (int y = 1; y <= h; y++) c.set(x, fy + y, z, mc(col[(k + y / 3) % col.length]));
            c.set(x, fy + h + 1, z, "minecraft:sea_lantern");
        }
    }

    private static void garden(Canvas c, District d, int fy) {
        // a pond, a glass-roofed pavilion with café seats, trees
        for (int x = -6; x <= 6; x++) for (int z = -4; z <= 4; z++) if (Math.hypot(x / 1.4, z) < 4) { c.set(d.x() + x - 6, fy, d.z() + z + 6, "minecraft:water"); c.set(d.x() + x - 6, fy - 1, d.z() + z + 6, "minecraft:clay"); }
        int px = d.x() + 5, pz = d.z() - 4;
        for (int[] o : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) for (int y = 1; y <= 5; y++) c.set(px + o[0], fy + y, pz + o[1], "minecraft:stripped_birch_log[axis=y]");
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) c.set(px + x, fy + 6, pz + z, mc(Math.abs(x) == 5 || Math.abs(z) == 5 ? "birch_planks" : "light_blue_stained_glass"));
        for (int[] t : new int[][]{{-2, -2}, {2, 2}}) {
            c.set(px + t[0], fy + 1, pz + t[1], "minecraft:birch_fence");
            c.set(px + t[0], fy + 2, pz + t[1], "minecraft:birch_pressure_plate");
            for (int[] s : new int[][]{{1, 0}, {-1, 0}}) c.set(px + t[0] + s[0], fy + 1, pz + t[1] + s[1], "minecraft:birch_stairs[facing=" + (s[0] > 0 ? "west" : "east") + "]");
        }
        SkyHubBuilder.tree(c, d.x() - 12, fy + 1, d.z() - 8, 8, "oak_log", "azalea_leaves", 81);
        SkyHubBuilder.tree(c, d.x() + 14, fy + 1, d.z() + 10, 7, "birch_log", "birch_leaves", 82);
    }

    private static void carnival(Canvas c, District d, int fy) {
        // striped tents
        String[][] stripes = {{"magenta_wool", "yellow_wool"}, {"red_wool", "white_wool"}, {"cyan_wool", "white_wool"}};
        int[][] tents = {{-9, 6}, {8, 8}, {-2, -10}};
        for (int i = 0; i < tents.length; i++) {
            int tx = d.x() + tents[i][0], tz = d.z() + tents[i][1];
            for (int y = 0; y <= 5; y++) {   // the canopy: a cone over 4 posts, stripes running down it
                double r = 4.5 - y * 0.85;
                for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
                    double dd = Math.hypot(dx, dz);
                    if (dd > r || dd < r - 1.2) continue;
                    int stripe = Math.floorMod((int) Math.floor((Math.atan2(dz, dx) + Math.PI) / (Math.PI / 6)), 2);
                    c.set(tx + dx, fy + 4 + y, tz + dz, mc(stripes[i][stripe]));
                }
            }
            for (int[] o : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) for (int y = 1; y <= 3; y++) c.set(tx + o[0], fy + y, tz + o[1], "minecraft:dark_oak_fence");
            c.setLate(tx, fy + 10, tz, "minecraft:end_rod");
        }
        // balloons on strings
        String[] bal = {"red_wool", "yellow_wool", "lime_wool", "light_blue_wool", "magenta_wool"};
        for (int k = 0; k < 7; k++) {
            int bx = d.x() + (int) Math.round(Math.cos(k * 0.9) * 15), bz = d.z() + (int) Math.round(Math.sin(k * 0.9) * 15);
            for (int y = 1; y <= 5; y++) c.set(bx, fy + y, bz, "minecraft:white_stained_glass_pane");
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 1) <= 2) c.set(bx + dx, fy + 6 + dy, bz + dz, mc(bal[k % bal.length]));
        }
        // the ferris wheel: a ring in the x-y plane with gondolas
        int wx = d.x(), wz = d.z() + 12, wy = fy + 14, wr = 11;
        for (double a = 0; a < Math.PI * 2; a += 0.02) c.set(wx + (int) Math.round(Math.cos(a) * wr), wy + (int) Math.round(Math.sin(a) * wr), wz, "minecraft:white_concrete");
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            for (double r = 0; r < wr; r += 0.5) c.set(wx + (int) Math.round(Math.cos(a) * r), wy + (int) Math.round(Math.sin(a) * r), wz, "minecraft:iron_bars");
            int gx = wx + (int) Math.round(Math.cos(a) * wr), gy = wy + (int) Math.round(Math.sin(a) * wr) - 2;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { c.set(gx + dx, gy, wz + dz, mc(bal[k % bal.length])); c.set(gx + dx, gy + 1, wz + dz, Math.abs(dx) + Math.abs(dz) == 2 ? mc(bal[k % bal.length]) : "minecraft:air"); }
        }
        c.set(wx, wy, wz, "minecraft:ochre_froglight");
        for (int y = fy + 1; y < wy; y++) { c.set(wx - 3, y, wz, "minecraft:white_concrete"); c.set(wx + 3, y, wz, "minecraft:white_concrete"); }
        c.set(wx, wy + wr - 1, wz, "minecraft:decorated_pot");   // the gondola secret (top gondola)
    }

    private static void construction(Canvas c, District d, int fy) {
        // scaffolding towers and a crane: under construction, more games coming
        for (int[] t : new int[][]{{-8, -6}, {7, -8}, {0, 9}})
            for (int y = 1; y <= 10; y++) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) c.set(d.x() + t[0] + dx, fy + y, d.z() + t[1] + dz, "minecraft:scaffolding[bottom=false,distance=0]");
        for (int y = 1; y <= 16; y++) c.set(d.x() - 12, fy + y, d.z(), "minecraft:yellow_concrete");
        for (int x = -12; x <= 4; x++) c.set(d.x() + x, fy + 16, d.z(), "minecraft:yellow_concrete");
        for (int y = 10; y <= 15; y++) c.set(d.x() + 3, fy + y, d.z(), "minecraft:iron_chain[axis=y]");
        c.set(d.x() + 3, fy + 9, d.z(), "minecraft:purple_concrete");
    }

    // ================================================================== all of it

    public static Canvas build() {
        Canvas c = new Canvas();
        plaza(c);
        for (District d : DISTRICTS) { bridge(c, d); district(c, d); }
        // background floating islets for depth
        int[][] isles = {{140, 90, -120, 9}, {-150, 115, -10, 8}, {60, 80, 150, 10}, {-120, 95, 130, 7}, {170, 120, 60, 8}, {-40, 130, -170, 9}};
        for (int i = 0; i < isles.length; i++) {
            int[] b = isles[i];
            Canvas t = new Canvas();
            SkyHubBuilder.island(t, 0, 0, b[3], 2, b[3] + 6, 950 + i, "polished_blackstone", new String[]{"blackstone", "basalt", "gilded_blackstone"});
            int dy = b[1] - Y;
            t.blocks().forEach((k, v) -> c.set(Canvas.kx(k) + b[0], Canvas.ky(k) + dy, Canvas.kz(k) + b[2], v));
            c.set(b[0], b[1] + 1, b[2], mc(NEON[i % NEON.length]));
            c.setLate(b[0], b[1] + 2, b[2], "minecraft:end_rod");
        }
        return c;
    }
}
