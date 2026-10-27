package net.alloymc.core.factions;

import net.alloymc.core.arcade.core.MapKit;
import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.Random;

/**
 * Factions spawn: a floating war citadel (blackstone, deepslate and gold, with battlements and four gate
 * towers that drop you into the wild) built around two giant statues in netherite armour locked in
 * combat - a sword-bearer with a crimson cape against an axe-and-shield warrior with an azure cape.
 * Absolute coordinates, centred on (0, Y, 0).
 */
public final class FactionsBuilder {
    public static final int Y = 190, R = 44;
    public static final int GATE = R - 2;   // gate towers at (0,-GATE) (GATE,0) (0,GATE) (-GATE,0)

    private final Canvas c = new Canvas();
    private final Random rnd = new Random(1312);

    public static double[] spawn() { return new double[]{0.5, Y + 1, 30.5}; }
    public static double[] rules() { return new double[]{-9.5, Y + 3.6, 27.5}; }
    public static double[] season() { return new double[]{10.5, Y + 3.6, 27.5}; }

    private void set(int x, int y, int z, String b) { c.set(x, Y + y, z, "minecraft:" + b); }
    private void late(int x, int y, int z, String b) { c.setLate(x, Y + y, z, "minecraft:" + b); }
    private String get(int x, int y, int z) { return c.get(x, Y + y, z); }

    public static Canvas build() {
        FactionsBuilder b = new FactionsBuilder();
        b.island();
        b.plaza();
        b.walls();
        for (int[] g : new int[][]{{0, -GATE, 0}, {GATE, 0, 1}, {0, GATE, 2}, {-GATE, 0, 3}}) b.gate(g[0], g[1], g[2]);
        b.statues();
        b.braziers();
        b.sign();
        return b.c;
    }

    // ------------------------------------------------------------------ ground

    private void island() {
        for (int x = -R - 4; x <= R + 4; x++) for (int z = -R - 4; z <= R + 4; z++) {
            double d = Math.hypot(x, z), edge = R + 1 + (MapKit.noise(x / 7.0, z / 7.0, 9) - 0.5) * 5;
            if (d > edge) continue;
            int depth = (int) ((edge - d) * 0.8) + 3 + rnd.nextInt(3);
            for (int y = 1; y <= depth; y++) set(x, -y, z, y == 1 ? "deepslate_bricks" : rnd.nextInt(16) == 0 ? "magma_block" : rnd.nextInt(4) == 0 ? "basalt" : "blackstone");
            set(x, 0, z, "polished_deepslate");
        }
    }

    /** War-plaza floor: dark tiles, a gold compass rose, red-lit cracks running to the gates. */
    private void plaza() {
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            if (get(x, 0, z) == null) continue;
            double d = Math.hypot(x, z), a = Math.atan2(z, x);
            String b = (((x >> 1) + (z >> 1)) & 1) == 0 ? "deepslate_tiles" : "polished_blackstone_bricks";
            if (MapKit.noise(x / 4.0, z / 4.0, 21) > 0.78) b = "cracked_deepslate_tiles";
            if (d > 15.5 && d <= 16.5 || d > 30.5 && d <= 31.5) b = "gold_block";
            if (d <= 15.5 && Math.abs(Math.sin(4 * a)) < 0.08 && d > 2) b = "gold_block";                // compass rays
            if (Math.abs(x) <= 1 && d > 16.5 || Math.abs(z) <= 1 && d > 16.5) b = Math.abs(x) + Math.abs(z) <= 1 || (x == 0 || z == 0) ? "red_nether_bricks" : "nether_bricks";   // roads to the gates
            if ((x == 0 || z == 0) && d > 16.5 && ((int) d) % 4 == 0) b = "shroomlight";
            set(x, 0, z, b);
        }
        // plinths for the two warriors
        for (int s : new int[]{-1, 1}) for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) {
            int cx = s * 10;
            boolean rim = Math.abs(x) == 7 || Math.abs(z) == 7;
            set(cx + x, 1, z, rim ? "gold_block" : "polished_blackstone_bricks");
            set(cx + x, 2, z, rim ? "polished_blackstone_brick_slab[type=bottom]" : "chiseled_polished_blackstone");
        }
    }

    private void walls() {
        for (int x = -R - 1; x <= R + 1; x++) for (int z = -R - 1; z <= R + 1; z++) {
            double d = Math.hypot(x, z);
            if (d <= R - 1.5 || d > R - 0.5 || get(x, 0, z) == null) continue;
            boolean gateGap = Math.abs(x) <= 4 || Math.abs(z) <= 4;
            if (gateGap) continue;
            for (int y = 1; y <= 5; y++) set(x, y, z, y == 3 && ((x + z) & 7) == 0 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
            set(x, 6, z, ((int) (Math.atan2(z, x) * 40) & 1) == 0 ? "polished_blackstone_brick_wall" : "air");
            if (((int) (Math.atan2(z, x) * 20) % 5) == 0) late(x, 6, z, "lantern");
        }
        // corner watchtowers
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            int tx = (int) Math.round(Math.cos(a) * (R - 1)), tz = (int) Math.round(Math.sin(a) * (R - 1));
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = 1; y <= 12; y++) {
                boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
                if (!edge && y < 12) { set(tx + x, y, tz + z, "air"); continue; }
                set(tx + x, y, tz + z, y == 12 ? "gold_block" : y % 4 == 0 ? "gilded_blackstone" : "deepslate_bricks");
            }
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) if ((Math.abs(x) == 3 || Math.abs(z) == 3) && ((x + z) & 1) == 0) set(tx + x, 13, tz + z, "deepslate_brick_wall");
            late(tx, 13, tz, "campfire[lit=true]");
        }
    }

    /** A gate tower whose portal curtain is the way out to the wild. dir: 0=N 1=E 2=S 3=W. */
    private void gate(int gx, int gz, int dir) {
        boolean alongX = dir == 0 || dir == 2;   // the opening spans x for N/S gates
        for (int u = -6; u <= 6; u++) for (int y = 1; y <= 13; y++) for (int w = -2; w <= 2; w++) {
            int x = gx + (alongX ? u : w), z = gz + (alongX ? w : u);
            int au = Math.abs(u);
            boolean open = au <= 3 && y <= 8 || au <= 2 && y == 9;
            // walk-through curtain (portal blocks; AlloyCore cancels the vanilla portal and drops you in the wild)
            if (open) { if (w == 0) late(x, y, z, "nether_portal[axis=" + (alongX ? "x" : "z") + "]"); else set(x, y, z, "air"); continue; }
            if (au >= 6 && y > 11) continue;
            String b = au == 6 || y == 13 ? "deepslate_bricks" : (au == 4 && y <= 9 || y == 10 && au <= 3) ? "gold_block" : "polished_blackstone_bricks";
            if (y == 12 && w == 0 && au <= 5) b = "chiseled_polished_blackstone";
            set(x, y, z, b);
        }
        for (int u = -6; u <= 6; u += 2) { int x = gx + (alongX ? u : -2), z = gz + (alongX ? -2 : u); set(x, 14, z, "deepslate_brick_wall"); }
        for (int s : new int[]{-5, 5}) { int x = gx + (alongX ? s : 0), z = gz + (alongX ? 0 : s); late(x, 14, z, "lantern"); }
    }

    private void braziers() {
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4 + Math.PI / 8;
            int x = (int) Math.round(Math.cos(a) * 24), z = (int) Math.round(Math.sin(a) * 24);
            set(x, 1, z, "gold_block"); set(x, 2, z, "polished_blackstone_wall");
            late(x, 3, z, "campfire[lit=true]");
        }
    }

    /** "FACTIONS" in red-gold letters above the north gate. */
    private void sign() {
        String text = "FACTIONS";
        int scale = 2, spacing = 1, width = BlockFont.width(text, spacing) * scale, x0 = -width / 2, y0 = 22, z = -R - 4;
        BlockFont.draw(text, spacing, (col, row, glyphRow) -> {
            String b = glyphRow <= 1 ? "shroomlight" : glyphRow <= 4 ? "red_nether_bricks" : "nether_bricks";
            for (int sx = 0; sx < scale; sx++) for (int sy = 0; sy < scale; sy++) {
                int x = x0 + col * scale + sx, y = y0 + row * scale + sy;
                set(x, y, z, b); set(x, y, z + 1, b);
                for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) if (get(x + ox, y + oy, z - 1) == null) set(x + ox, y + oy, z - 1, "gold_block");
            }
        });
        for (int s : new int[]{-1, 1}) for (int y = 14; y < 22; y++) set(s * (width / 2 - 4), y, z - 1, "iron_chain[axis=y]");
    }

    // ------------------------------------------------------------------ the warriors

    /** Fill every block inside an oriented box from `from` along unit `dir` for `len`, with half-sizes hw (side) and hd (depth). */
    private interface Mat { String at(double along, double side, double depth); }

    private static double[] v(double x, double y, double z) { return new double[]{x, y, z}; }
    private static double[] add(double[] a, double[] b, double k) { return v(a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k); }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] cross(double[] a, double[] b) { return v(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]); }
    private static double[] norm(double[] a) { double l = Math.sqrt(dot(a, a)); return v(a[0] / l, a[1] / l, a[2] / l); }

    private void beam(double[] from, double[] dir, double len, double[] side, double hw, double hd, Mat m) {
        double[] d = norm(dir);
        double[] s = norm(add(side, d, -dot(side, d)));      // make "side" perpendicular to the beam
        double[] n = cross(d, s);
        double reach = len + Math.max(hw, hd) + 1;
        for (int x = (int) Math.floor(from[0] - reach); x <= from[0] + reach; x++)
            for (int y = (int) Math.floor(from[1] - reach); y <= from[1] + reach; y++)
                for (int z = (int) Math.floor(from[2] - reach); z <= from[2] + reach; z++) {
                    double[] p = v(x + 0.5 - from[0], y + 0.5 - from[1], z + 0.5 - from[2]);
                    double a = dot(p, d), b = dot(p, s), cc = dot(p, n);
                    if (a < 0 || a > len || Math.abs(b) > hw || Math.abs(cc) > hd) continue;
                    String mat = m.at(a / len, b / hw, cc / hd);
                    if (mat != null) c.set(x, y, z, "minecraft:" + mat);
                }
    }

    private static Mat armour(String trim) {
        return (a, s, dp) -> Math.abs(s) > 0.8 && Math.abs(dp) > 0.8 ? trim : "netherite_block";
    }

    /**
     * One warrior. feet = centre between the feet; f = facing; r = their right; weaponTo = where the
     * weapon arm points (the clash point). axe=false: sword. Cape colour marks the side.
     */
    private void warrior(double[] feet, double[] f, double[] r, double[] clash, boolean axe, String cape, String plume) {
        double[] up = v(0, 1, 0);
        double[] hip = add(feet, up, 13);
        // legs: front leg lunges forward, back leg braces behind
        double[] legF = add(add(hip, r, -2.5), f, 0), legB = add(add(hip, r, 2.5), f, 0);
        double[] dF = norm(add(v(0, -1, 0), f, 0.45)), dB = norm(add(v(0, -1, 0), f, -0.5));
        beam(legF, dF, 13.5, r, 2.5, 2.5, armour("gilded_blackstone"));
        beam(legB, dB, 13.5, r, 2.5, 2.5, armour("gilded_blackstone"));
        // boots
        beam(add(legF, dF, 12.5), f, 4, r, 2.6, 1.2, (a, s, d) -> "polished_blackstone");
        beam(add(legB, dB, 12.5), f, 4, r, 2.6, 1.2, (a, s, d) -> "polished_blackstone");
        // torso: leaning slightly into the fight, gold belt and chest emblem
        double[] lean = norm(add(up, f, 0.15));
        beam(hip, lean, 13, r, 5, 3, (a, s, d) -> a < 0.1 ? "gold_block" : (d > 0.75 && Math.abs(s) < 0.3 && a > 0.5 && a < 0.8) ? "gold_block" : Math.abs(s) > 0.85 ? "gilded_blackstone" : "netherite_block");
        double[] neck = add(hip, lean, 13);
        // helmet with a visor slit and a plume
        double[] headBase = add(neck, lean, 0.5);
        beam(headBase, lean, 9, r, 4.5, 4.5, (a, s, d) -> a > 0.9 ? "gilded_blackstone" : "netherite_block");
        // visor slit across the front of the helmet
        double[] eye = add(add(headBase, lean, 4.5), f, 4.3);
        beam(add(eye, r, -3), r, 6, up, 0.5, 0.6, (a, s, d) -> "blackstone");
        double[] crest = add(headBase, lean, 9.5);
        beam(add(crest, f, 3), norm(add(f, up, -0.6)), 9, r, 0.9, 1.2, (a, s, d) -> plume);
        // shoulder pads
        double[] shR = add(neck, r, 6), shL = add(neck, r, -6);
        for (double[] sh : new double[][]{shR, shL}) beam(add(sh, up, -2), up, 4, f, 3.5, 3.5, (a, s, d) -> Math.abs(s) > 0.75 || Math.abs(d) > 0.75 ? "gold_block" : "gilded_blackstone");
        // weapon arm: straight at the clash point, weapon continuing the same line
        double[] toClash = norm(add(clash, shR, -1));
        beam(shR, toClash, 12, f, 2.2, 2.2, armour("gold_block"));
        double[] hand = add(shR, toClash, 12);
        double gripLen = 4, weaponLen = Math.sqrt(dot(add(clash, hand, -1), add(clash, hand, -1))) + 3;
        beam(hand, toClash, gripLen, r, 0.6, 0.6, (a, s, d) -> "dark_oak_log");
        double[] head = add(hand, toClash, gripLen);
        if (!axe) {
            beam(add(head, r, -3.5), r, 7, toClash, 0.6, 0.6, (a, s, d) -> "gold_block");                         // cross-guard
            beam(head, toClash, weaponLen - gripLen, r, 1.4, 0.55, (a, s, d) -> a > 0.92 && Math.abs(s) > 0.3 ? null : Math.abs(s) > 0.6 ? "iron_block" : "polished_deepslate");
        } else {
            beam(head, toClash, weaponLen - gripLen - 4, r, 0.6, 0.6, (a, s, d) -> "dark_oak_log");
            double[] bladeAt = add(head, toClash, weaponLen - gripLen - 6);
            double[] across = norm(cross(toClash, r));
            beam(bladeAt, across, 7, toClash, 3.5, 0.6, (a, s, d) -> a > 0.85 ? "iron_block" : "netherite_block");
        }
        // off arm: sword-bearer swings it back for balance, the axe-bearer raises a shield
        if (!axe) {
            double[] back = norm(add(add(v(0, -1, 0), f, -0.7), r, -0.2));
            beam(shL, back, 12, f, 2.2, 2.2, armour("gold_block"));
        } else {
            double[] fwd = norm(add(f, v(0, -1, 0), 0.35));
            beam(shL, fwd, 11, up, 2.2, 2.2, armour("gold_block"));
            double[] shieldC = add(add(shL, fwd, 10), f, 1.5);
            beam(add(shieldC, up, -7), up, 14, r, 5.5, 0.6, (a, s, d) -> Math.abs(s) > 0.85 || a < 0.06 || a > 0.94 ? "gold_block" : (Math.abs(s) < 0.3 && a > 0.35 && a < 0.65 ? "netherite_block" : cape));
        }
        // cape billowing behind
        double[] capeTop = add(add(neck, f, -3.5), up, -1);
        beam(capeTop, norm(add(v(0, -1, 0), f, -0.55)), 17, r, 5.5, 0.55, (a, s, d) -> a > 0.95 && ((int) (s * 5) & 1) == 0 ? null : cape);
    }

    private void statues() {
        double[] clash = v(0.5, Y + 44, 0.5);
        warrior(v(-10.5, Y + 3, 0.5), v(1, 0, 0), v(0, 0, 1), clash, false, "red_wool", "red_wool");
        warrior(v(11.5, Y + 3, 0.5), v(-1, 0, 0), v(0, 0, -1), clash, true, "blue_wool", "blue_wool");
        // sparks where the weapons meet
        for (int i = 0; i < 14; i++) {
            int x = (int) clash[0] + rnd.nextInt(9) - 4, y = (int) clash[1] + rnd.nextInt(7) - 1, z = (int) clash[2] + rnd.nextInt(9) - 4;
            if (c.get(x, y, z) == null) c.set(x, y, z, "minecraft:" + (rnd.nextBoolean() ? "shroomlight" : "gold_block"));
        }
    }
}
