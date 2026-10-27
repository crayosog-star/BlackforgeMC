package net.alloymc.core.arcade;

import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.*;

/**
 * The BlackForge Arcade lobby, in the hub's blackstone-and-gold style: a floating forge island with
 * gold veins, a molten forge centrepiece, glowing channels out to a ring of themed game arches (step on
 * the gold plate in front of an arch to queue), a hub arch behind spawn, a giant ARCADE sign and
 * little floating islets. Coordinates are relative to the lobby centre at y = {@link #Y}.
 */
public final class LobbyBuilder {
    public static final int Y = 100, R = 31, RING = 19;
    /** Arch order around the ring (clockwise from north-west); "hub" sits behind spawn. */
    public static final String[] GAMES = {"bedwars", "skywars", "duels", "spleef", "tntrun", "murder", "buildbattle", "parkour"};

    private final Canvas c = new Canvas();
    private final Random rnd = new Random(77);

    /** Where each game's join plate is (x, z). */
    public static Map<String, int[]> plates() {
        Map<String, int[]> m = new LinkedHashMap<>();
        for (int i = 0; i < GAMES.length; i++) { double a = angle(i); m.put(GAMES[i], new int[]{(int) Math.round(Math.sin(a) * RING), (int) Math.round(-Math.cos(a) * RING)}); }
        m.put("hub", new int[]{0, RING + 3});
        return m;
    }

    /** Spread the eight arches over the ring, leaving the south (spawn/hub) side open. */
    private static double angle(int i) { return Math.toRadians(-140 + i * 40); }

    public static double[] spawn() { return new double[]{0.5, Y + 1, 15.5}; }
    public static double[] board() { return new double[]{-12.5, Y + 4.2, 11.5}; }

    private void set(int x, int y, int z, String b) { c.set(x, Y + y, z, "minecraft:" + b); }
    private void late(int x, int y, int z, String b) { c.setLate(x, Y + y, z, "minecraft:" + b); }
    private boolean has(int x, int y, int z) { return c.isSet(x, Y + y, z); }

    private static double noise(double x, double z, long s) { return net.alloymc.core.arcade.core.MapKit.noise(x, z, s); }

    public static Canvas build() {
        LobbyBuilder b = new LobbyBuilder();
        b.island();
        b.forge();
        b.channels();
        Map<String, int[]> plates = plates();
        for (int i = 0; i < GAMES.length; i++) b.arch(GAMES[i], plates.get(GAMES[i]), angle(i));
        b.hubArch(plates.get("hub"));
        b.rim();
        b.podium();
        b.sign();
        b.islets();
        return b.c;
    }

    // ------------------------------------------------------------------ island

    private void island() {
        for (int x = -R - 3; x <= R + 3; x++) for (int z = -R - 3; z <= R + 3; z++) {
            double d = Math.hypot(x, z), edge = R + (noise(x / 6.0, z / 6.0, 3) - 0.5) * 6;
            if (d > edge) continue;
            // top: blackstone bricks with gold veins where the noise crosses a band (like cracks of molten gold)
            double v = noise(x / 9.0, z / 9.0, 11);
            boolean vein = Math.abs(v - 0.5) < 0.025 && d > 8;
            String top = vein ? "gold_block" : d < 9 ? "polished_blackstone" : noise(x / 3.0, z / 3.0, 5) > 0.72 ? "cracked_polished_blackstone_bricks" : (((x >> 1) + (z >> 1)) & 1) == 0 ? "polished_blackstone_bricks" : "blackstone";
            set(x, 0, z, top);
            int depth = (int) ((edge - d) * 0.9) + 2 + rnd.nextInt(2);
            for (int y = 1; y <= depth; y++) set(x, -y, z, y == 1 ? "blackstone" : rnd.nextInt(14) == 0 ? "magma_block" : rnd.nextInt(5) == 0 ? "basalt" : "blackstone");
        }
    }

    /** The forge: a sunken lava crucible ringed in gold with an anvil and chains hanging from a gold crown above. */
    private void forge() {
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) {
            double d = Math.hypot(x, z);
            if (d <= 8.5 && d > 7.5) set(x, 0, z, "gold_block");
            else if (d <= 7.5 && d > 4.5) set(x, 0, z, ((x + z) & 1) == 0 ? "chiseled_polished_blackstone" : "polished_blackstone");
            else if (d <= 4.5 && d > 3.5) { set(x, 0, z, "gilded_blackstone"); set(x, 1, z, "polished_blackstone_brick_wall"); }
            else if (d <= 3.5) { set(x, 0, z, "magma_block"); set(x, -1, z, "magma_block"); late(x, 0, z, d <= 2.5 ? "lava" : "magma_block"); }
        }
        // four gold-capped pillars around the crucible holding a crown
        for (int[] p : new int[][]{{-5, -5}, {5, -5}, {-5, 5}, {5, 5}}) {
            for (int y = 1; y <= 7; y++) set(p[0], y, p[1], y == 7 ? "gold_block" : y == 4 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
            late(p[0], 8, p[1], "lantern");
        }
        for (int x = -5; x <= 5; x++) for (int z : new int[]{-5, 5}) { set(x, 8, z, "polished_blackstone_brick_slab[type=bottom]"); set(z, 8, x, "polished_blackstone_brick_slab[type=bottom]"); }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(x, 9, z, x == 0 && z == 0 ? "gold_block" : "polished_blackstone_bricks");
        for (int y = 5; y <= 8; y++) set(0, y, 0, "iron_chain[axis=y]");
        set(0, 4, 0, "gold_block");
        late(0, 3, 0, "lantern[hanging=true]");
        for (int[] q : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) late(q[0], 8, q[1], "lantern[hanging=true]");
    }

    /** Glowing channels from the forge out to each arch (shroomlight flush with the floor, gold edging). */
    private void channels() {
        for (Map.Entry<String, int[]> e : plates().entrySet()) {
            int[] p = e.getValue();
            double len = Math.hypot(p[0], p[1]);
            for (double t = 9; t < len - 2; t += 0.5) {
                int x = (int) Math.round(p[0] * t / len), z = (int) Math.round(p[1] * t / len);
                set(x, 0, z, "shroomlight");
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) { String g = c.get(x + o[0], Y, z + o[1]); if (g == null || !g.contains("shroomlight")) set(x + o[0], 0, z + o[1], "gold_block"); }
            }
        }
    }

    record Theme(String fill, String accent, String light) {}

    private static Theme theme(String game) {
        return switch (game) {
            case "bedwars" -> new Theme("red_wool", "red_concrete", "redstone_lamp[lit=true]");
            case "skywars" -> new Theme("purpur_block", "end_stone_bricks", "end_rod[facing=up]");
            case "duels" -> new Theme("iron_block", "polished_andesite", "sea_lantern");
            case "spleef" -> new Theme("snow_block", "packed_ice", "sea_lantern");
            case "tntrun" -> new Theme("tnt", "sand", "redstone_lamp[lit=true]");
            case "murder" -> new Theme("redstone_block", "dark_oak_planks", "lantern");
            case "buildbattle" -> new Theme("pink_concrete", "light_blue_concrete", "pearlescent_froglight");
            default -> new Theme("emerald_block", "moss_block", "verdant_froglight");   // parkour
        };
    }

    /** A gold-framed blackstone arch facing the forge, with the game's blocks inside and a join plate in front. */
    private void arch(String game, int[] plate, double a) {
        Theme t = theme(game);
        double ox = Math.sin(a), oz = -Math.cos(a);            // outward
        double sx = Math.cos(a), sz = Math.sin(a);             // sideways
        int bx = (int) Math.round(plate[0] + ox * 3), bz = (int) Math.round(plate[1] + oz * 3);   // arch centre, behind the plate
        // pad
        for (int u = -3; u <= 3; u++) for (int w = -1; w <= 4; w++) {
            int x = (int) Math.round(plate[0] + sx * u + ox * w), z = (int) Math.round(plate[1] + sz * u + oz * w);
            set(x, 0, z, Math.abs(u) == 3 || w == -1 ? "gold_block" : "polished_blackstone");
        }
        set(plate[0], 0, plate[1], "gold_block");
        late(plate[0], 1, plate[1], "light_weighted_pressure_plate");
        // arch: pillars, a pointed top, and the game's blocks filling the opening
        for (int u = -3; u <= 3; u++) for (int y = 1; y <= 8; y++) {
            int au = Math.abs(u);
            boolean open = au <= 1 && y <= 5 || au == 0 && y == 6;
            boolean frame = au == 3 && y <= 6 || au == 2 && y >= 6 && y <= 7 || au <= 1 && y == 7 || au == 0 && y == 8;
            if (!open && !frame) continue;
            for (int w = 0; w <= 1; w++) {
                int x = (int) Math.round(bx + sx * u + ox * w), z = (int) Math.round(bz + sz * u + oz * w);
                String blk = open ? (w == 1 ? t.fill() : "air") : (y == 6 && au == 3 || y >= 7) ? "gold_block" : (y == 3 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
                set(x, y, z, blk);
            }
        }
        // themed accent blocks at the arch feet and lights on top of the pillars
        for (int s : new int[]{-3, 3}) {
            int x = (int) Math.round(bx + sx * s - ox), z = (int) Math.round(bz + sz * s - oz);
            set(x, 1, z, t.accent());
            late(x, 2, z, t.light().startsWith("end_rod") || t.light().contains("froglight") || t.light().startsWith("sea") ? t.light() : "lantern");
        }
    }

    /** The way back to the hub: a wide gold arch with an orange glow, like the hub's own gates. */
    private void hubArch(int[] p) {
        for (int x = -4; x <= 4; x++) for (int z = -1; z <= 3; z++) set(p[0] + x, 0, p[1] + z, Math.abs(x) == 4 ? "gold_block" : "polished_blackstone");
        set(p[0], 0, p[1], "gold_block");
        late(p[0], 1, p[1], "light_weighted_pressure_plate");
        int bz = p[1] + 3;
        for (int x = -4; x <= 4; x++) for (int y = 1; y <= 8; y++) {
            int ax = Math.abs(x);
            boolean open = ax <= 2 && y <= 6 || ax <= 1 && y == 7;
            boolean frame = ax == 4 && y <= 7 || ax == 3 && y >= 7 || ax <= 2 && y == 8;
            if (open) set(p[0] + x, y, bz, "orange_stained_glass");
            else if (frame) set(p[0] + x, y, bz, y == 8 || ax == 4 && y == 7 ? "gold_block" : y % 3 == 0 ? "gilded_blackstone" : "polished_blackstone_bricks");
        }
        late(p[0] - 4, 8, bz, "lantern"); late(p[0] + 4, 8, bz, "lantern");
    }

    /** Gold-capped blackstone posts and walls around the edge. */
    private void rim() {
        for (int i = 0; i < 64; i++) {
            double a = i * Math.PI * 2 / 64;
            int x = (int) Math.round(Math.sin(a) * (R - 3)), z = (int) Math.round(-Math.cos(a) * (R - 3));
            if (!has(x, 0, z) || c.get(x, Y + 1, z) != null) continue;
            if (i % 4 == 0) { for (int y = 1; y <= 3; y++) set(x, y, z, y == 3 ? "gold_block" : "polished_blackstone_bricks"); late(x, 4, z, "lantern"); }
            else set(x, 1, z, "polished_blackstone_brick_wall");
        }
    }

    /** Raised podium for the top-winners leaderboard hologram. */
    private void podium() {
        double[] b = board();
        int bx = (int) Math.floor(b[0]), bz = (int) Math.floor(b[2]);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) { set(bx + x, 0, bz + z, Math.abs(x) == 2 || Math.abs(z) == 2 ? "gold_block" : "chiseled_polished_blackstone"); }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(bx + x, 1, bz + z, "polished_blackstone_bricks");
        set(bx, 2, bz, "gold_block");
    }

    /** Giant ARCADE letters floating north, gold on a blackstone backing - the hub's sign in miniature. */
    private void sign() {
        String text = "ARCADE";
        int scale = 2, spacing = 1;
        int width = BlockFont.width(text, spacing) * scale;
        int x0 = -width / 2, y0 = 22, z = -R - 6;
        BlockFont.draw(text, spacing, (col, row, glyphRow) -> {
            String b = glyphRow <= 1 ? "shroomlight" : glyphRow <= 4 ? "gold_block" : "raw_gold_block";
            for (int sx = 0; sx < scale; sx++) for (int sy = 0; sy < scale; sy++) {
                int x = x0 + col * scale + sx, y = y0 + row * scale + sy;
                set(x, y, z, b); set(x, y, z + 1, b);
                for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) if (!has(x + ox, y + oy, z - 1)) set(x + ox, y + oy, z - 1, "polished_blackstone");
            }
        });
        for (int s : new int[]{-1, 1}) {
            int ax = s * (width / 2 - 4);
            for (int y = 1; y < y0 - 1; y++) set(ax, y, z - 1, "iron_chain[axis=y]");
        }
    }

    /** Little floating blackstone islets around the main island, each with a gold-topped post. */
    private void islets() {
        for (int i = 0; i < 9; i++) {
            double a = i * Math.PI * 2 / 9 + 0.3;
            int cx = (int) Math.round(Math.sin(a) * (R + 12)), cz = (int) Math.round(-Math.cos(a) * (R + 12)), cy = -6 + rnd.nextInt(14);
            double r = 2.5 + rnd.nextDouble() * 2;
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
                double d = Math.hypot(x, z);
                if (d > r) continue;
                set(cx + x, cy, cz + z, noise(x, z, i) > 0.8 ? "gold_block" : "polished_blackstone");
                for (int y = 1; y <= (int) ((r - d) * 1.4) + 1; y++) set(cx + x, cy - y, cz + z, rnd.nextInt(8) == 0 ? "magma_block" : "blackstone");
            }
            for (int y = 1; y <= 2; y++) set(cx, cy + y, cz, "polished_blackstone_brick_wall");
            set(cx, cy + 3, cz, "gold_block");
            late(cx, cy + 4, cz, "lantern");
        }
    }
}
