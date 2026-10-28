package net.alloymc.core.factions;

import net.alloymc.core.arcade.core.MapKit;
import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.Random;

/**
 * Factions spawn: a floating war citadel at night (blackstone, deepslate and gold, battlements, four gate
 * towers whose fog curtains drop you into the wild, corner watchtowers) with an Elden Ring boss-arena mood:
 * <ul>
 *   <li>the two colossal duelling warriors at the centre - a crowned knight with a longsword (crimson cloak)
 *       against a horned knight with a great axe and kite shield (azure cloak), weapons clashing high above
 *       the plaza - sculpted with layered pauldrons, lamed breastplates, tassets, gauntlets and streaming cloaks</li>
 *   <li>a jagged, broken island: rock spurs and crags beyond the walls, magma-veined cliffs, stalactites and
 *       hanging chains beneath</li>
 *   <li>two colossal black swords impaled in the plaza, a Site of Grace before spawn, candles everywhere,
 *       dim hidden light so the fire and gold carry the scene</li>
 * </ul>
 * Absolute coordinates, centred on (0, Y, 0). Everything lies within {@link #EXTENT} blocks of (0, 0).
 */
public final class FactionsBuilder {
    public static final int Y = 190, R = 44;
    public static final int GATE = R - 2;   // gate towers at (0,-GATE) (GATE,0) (0,GATE) (-GATE,0)
    /** Horizontal radius that contains the whole build (rock spurs included). */
    public static final int EXTENT = R + 14;

    private final Canvas c = new Canvas();
    private final Random rnd = new Random(1312);

    public static double[] spawn() { return new double[]{0.5, Y + 1, 30.5}; }
    public static double[] rules() { return new double[]{-9.5, Y + 3.6, 27.5}; }
    public static double[] season() { return new double[]{10.5, Y + 3.6, 27.5}; }
    /** Site of Grace, a few steps in front of spawn. */
    public static double[] grace() { return new double[]{0.5, Y + 1, 23.5}; }
    /** Name plates on the spawn side of the two duelling statues: crimson (west), azure (east). */
    public static double[][] statueLabels() {
        return new double[][]{{-9.5, Y + 5.2, 10.5}, {11.5, Y + 5.2, 10.5}};
    }

    private void set(int x, int y, int z, String b) { c.set(x, Y + y, z, "minecraft:" + b); }
    private void late(int x, int y, int z, String b) { c.setLate(x, Y + y, z, "minecraft:" + b); }
    private String get(int x, int y, int z) { return c.get(x, Y + y, z); }
    private boolean empty(int x, int y, int z) { String b = get(x, y, z); return b == null || b.endsWith(":air"); }

    public static Canvas build() {
        FactionsBuilder b = new FactionsBuilder();
        b.island();
        b.plaza();
        b.walls();
        for (int[] g : new int[][]{{0, -GATE, 0}, {GATE, 0, 1}, {0, GATE, 2}, {-GATE, 0, 3}}) b.gate(g[0], g[1], g[2]);
        b.warriors();
        b.braziers();
        b.swords();
        b.graceShrine();
        b.sign();
        b.candlesAndLight();
        return b.c;
    }

    // ------------------------------------------------------------------ helpers

    private static double angDist(double a, double b) {
        double d = Math.abs(a - b) % (2 * Math.PI);
        return d > Math.PI ? 2 * Math.PI - d : d;
    }

    /** Deterministic 0..1 hash of a block position (for material mottling). */
    private static double h(int x, int y, int z, int s) {
        long k = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ s * 2654435761L;
        k ^= k >>> 13; k *= 0x5bd1e995L; k ^= k >>> 15;
        return (k & 0xFFFFFF) / (double) 0x1000000;
    }

    private static String pick(double v, Object... weighted) {   // "block", weight, "block", weight...
        double total = 0;
        for (int i = 1; i < weighted.length; i += 2) total += ((Number) weighted[i]).doubleValue();
        double t = v * total;
        for (int i = 0; i < weighted.length; i += 2) {
            t -= ((Number) weighted[i + 1]).doubleValue();
            if (t <= 0) return (String) weighted[i];
        }
        return (String) weighted[weighted.length - 2];
    }

    // ------------------------------------------------------------------ ground

    /** Radius of the island edge in direction a: jagged, with rock spurs, never cutting into the walls or gates. */
    static double outline(double a) {
        double r = R + 3 + 2.5 * Math.sin(3 * a + 0.7) + 1.8 * Math.sin(5 * a + 2.1) + 1.2 * Math.sin(9 * a + 0.4);
        for (int k = 0; k < 9; k++) {   // spurs of rock jutting out
            double s = k * 0.7 + 0.35 + (k % 2) * 0.15;
            double d = angDist(a, s);
            r += (7 + (k % 3) * 3.5) * Math.exp(-(d / 0.085) * (d / 0.085));
        }
        return Math.max(r, R + 2);
    }

    private void island() {
        for (int x = -EXTENT; x <= EXTENT; x++) for (int z = -EXTENT; z <= EXTENT; z++) {
            double d = Math.hypot(x, z), a = Math.atan2(z, x);
            double edge = outline(a) + (MapKit.noise(x / 4.0, z / 4.0, 9) - 0.5) * 3;
            if (d > edge) continue;
            int depth = (int) ((edge - d) * 0.8) + 3 + rnd.nextInt(3) + (int) (MapKit.noise(x / 3.0, z / 3.0, 14) * 8);
            if (h(x, 0, z, 1) < 0.05) depth += 6 + (int) (h(x, 1, z, 2) * 10);   // stalactites
            // beyond the walls the ground breaks up into crags of black rock
            int top = 0;
            if (d > R + 0.5) {
                double crag = MapKit.noise(x / 3.5, z / 3.5, 15);
                top = crag > 0.5 ? (int) ((crag - 0.5) * 22) : 0;
                if (d > outline(a) - 4) top += (int) (h(x, 0, z, 6) * 3);   // ragged lip
                if (Math.min(Math.abs(x), Math.abs(z)) <= 5) top = 0;   // keep the ground in front of the gates level
            }
            for (int y = -depth; y <= top; y++) {
                String b;
                if (y > 0) b = pick(h(x, y, z, 3), "blackstone", 4, "basalt", 3, "polished_basalt", 1, "gilded_blackstone", 0.25);
                else if (y == 0) b = d > R + 0.5 ? pick(h(x, y, z, 4), "blackstone", 4, "cobbled_deepslate", 2, "magma_block", 0.4) : "polished_deepslate";
                else if (y == -1) b = "deepslate_bricks";
                else b = pick(h(x, y, z, 5), "blackstone", 8, "basalt", 3, "deepslate", 3, "magma_block", 0.7, "gilded_blackstone", 0.15);
                set(x, y, z, b);
            }
        }
        // chains hanging from the underside, some with a lantern at the end
        for (int i = 0; i < 22; i++) {
            double a = rnd.nextDouble() * 2 * Math.PI, rr = R * (0.4 + rnd.nextDouble() * 0.5);
            int x = (int) Math.round(Math.cos(a) * rr), z = (int) Math.round(Math.sin(a) * rr);
            int y = -1;
            while (y > -60 && !empty(x, y, z)) y--;
            int len = 4 + rnd.nextInt(10);
            for (int j = 0; j < len; j++) late(x, y - j, z, "iron_chain[axis=y]");
            if (rnd.nextBoolean()) late(x, y - len, z, "lantern[hanging=true]");
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

    private interface Sdf { double d(double x, double y, double z); }
    private interface Mat { String at(int x, int y, int z); }

    /** Fills every block whose centre is inside the shape (world coordinates, absolute y). */
    private void solid(double x1, double y1, double z1, double x2, double y2, double z2, Sdf s, Mat m) {
        for (int x = (int) Math.floor(x1); x <= x2; x++)
            for (int y = (int) Math.floor(y1); y <= y2; y++)
                for (int z = (int) Math.floor(z1); z <= z2; z++)
                    if (s.d(x + 0.5, y + 0.5, z + 0.5) <= 0) {
                        String b = m.at(x, y, z);
                        if (b != null) c.set(x, y, z, "minecraft:" + b);
                    }
    }

    /** Tapered capsule from a (radius ra) to b (radius rb), world coordinates. */
    private void cone(double[] a, double[] b, double ra, double rb, Mat m) {
        double pad = Math.max(ra, rb) + 1;
        solid(Math.min(a[0], b[0]) - pad, Math.min(a[1], b[1]) - pad, Math.min(a[2], b[2]) - pad,
                Math.max(a[0], b[0]) + pad, Math.max(a[1], b[1]) + pad, Math.max(a[2], b[2]) + pad,
                (x, y, z) -> capsule(x, y, z, a, b, ra, rb), m);
    }

    private static double capsule(double x, double y, double z, double[] a, double[] b, double ra, double rb) {
        double bx = b[0] - a[0], by = b[1] - a[1], bz = b[2] - a[2];
        double px = x - a[0], py = y - a[1], pz = z - a[2];
        double len2 = bx * bx + by * by + bz * bz;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, (px * bx + py * by + pz * bz) / len2));
        return Math.sqrt(sq(px - bx * t) + sq(py - by * t) + sq(pz - bz * t)) - (ra + (rb - ra) * t);
    }

    private static double ellipsoid(double x, double y, double z, double cx, double cy, double cz, double rx, double ry, double rz) {
        double k = Math.sqrt(sq((x - cx) / rx) + sq((y - cy) / ry) + sq((z - cz) / rz));
        return (k - 1) * Math.min(rx, Math.min(ry, rz));
    }

    private static double sq(double v) { return v * v; }

    /** A statue's local frame: lx = its right, ly = up, lz = forward (toward its opponent). f = (fx, 0, fz). */
    private record Pose(double bx, double by, double bz, double fx, double fz) {
        double[] w(double lx, double ly, double lz) { return new double[]{bx - fz * lx + fx * lz, by + ly, bz + fx * lx + fz * lz}; }
        double[] w(double[] l) { return w(l[0], l[1], l[2]); }
        double[] l(double x, double y, double z) { double dx = x - bx, dz = z - bz; return new double[]{-fz * dx + fx * dz, y - by, fx * dx + fz * dz}; }
    }

    /** Fills a shape given in a statue's local frame; lo/hi bound it (local). */
    private void part(Pose P, double[] lo, double[] hi, Sdf local, Mat m) {
        double minX = 1e9, minZ = 1e9, maxX = -1e9, maxZ = -1e9;
        for (double lx : new double[]{lo[0], hi[0]}) for (double lz : new double[]{lo[2], hi[2]}) {
            double[] q = P.w(lx, 0, lz);
            minX = Math.min(minX, q[0]); maxX = Math.max(maxX, q[0]); minZ = Math.min(minZ, q[2]); maxZ = Math.max(maxZ, q[2]);
        }
        solid(minX - 1, P.by() + lo[1] - 1, minZ - 1, maxX + 1, P.by() + hi[1] + 1, maxZ + 1, (x, y, z) -> {
            double[] q = P.l(x, y, z);
            return local.d(q[0], q[1], q[2]);
        }, m);
    }

    private void limb(Pose P, double[] a, double[] b, double ra, double rb, Mat m) { cone(P.w(a), P.w(b), ra, rb, m); }

    private void ball(Pose P, double[] c, double rx, double ry, double rz, Mat m) {
        double r = Math.max(rx, Math.max(ry, rz)) + 1;
        part(P, new double[]{c[0] - r, c[1] - r, c[2] - r}, new double[]{c[0] + r, c[1] + r, c[2] + r},
                (x, y, z) -> ellipsoid(x, y, z, c[0], c[1], c[2], rx, ry, rz), m);
    }

    private static double[] v(double x, double y, double z) { return new double[]{x, y, z}; }
    private static double[] plus(double[] a, double[] b, double k) { return v(a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k); }
    private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    private static double[] unit(double[] a) { double l = Math.sqrt(dot(a, a)); return v(a[0] / l, a[1] / l, a[2] / l); }
    private static double[] cross(double[] a, double[] b) { return v(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]); }

    /** Where the two warriors' weapons meet, high above the middle of the arena. */
    private static final double[] CLASH = {0.5, Y + 40, 0.5};

    private void warriors() {
        warrior(new Pose(-10.0, Y + 4, 0.5, 1, 0), false, new String[]{"red_wool", "red_terracotta", "red_concrete"});
        warrior(new Pose(11.0, Y + 4, 0.5, -1, 0), true, new String[]{"blue_wool", "blue_concrete", "light_blue_terracotta"});
        // sparks frozen where the weapons meet
        for (int i = 0; i < 26; i++) {
            double a = rnd.nextDouble() * 2 * Math.PI, e = rnd.nextDouble() * Math.PI - Math.PI / 2, d = 2 + rnd.nextDouble() * 5;
            int x = (int) Math.floor(CLASH[0] + Math.cos(a) * Math.cos(e) * d), y = (int) Math.floor(CLASH[1] + 1 + Math.sin(e) * d * 0.8), z = (int) Math.floor(CLASH[2] + Math.sin(a) * Math.cos(e) * d);
            if (c.get(x, y, z) == null) c.set(x, y, z, "minecraft:" + (i % 3 == 0 ? "gold_block" : i % 3 == 1 ? "shroomlight" : "ochre_froglight"));
        }
    }

    /**
     * One of the two duelling warriors, mid-strike: lunging stance, weapon arm driving the blade up into the clash.
     * axe = false: a crowned knight with a longsword, off arm thrown back. axe = true: a horned knight with a
     * great axe, bracing behind a kite shield. The cloak streams out behind.
     */
    private void warrior(Pose P, boolean axe, String[] cloth) {
        // round, three-tier plinth
        int px = (int) Math.floor(P.bx()), pz = (int) Math.floor(P.bz());
        for (int tier = 1; tier <= 3; tier++) {
            double rr = 10.5 - tier;
            for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
                double d = Math.hypot(x, z);
                if (d > rr) continue;
                boolean rim = d > rr - 1;
                String b = tier == 2 && rim ? "gold_block" : rim ? "chiseled_polished_blackstone" : "polished_blackstone_bricks";
                if (rim && tier != 2 && h(px + x, tier, pz + z, 18) < 0.12) b = "cracked_polished_blackstone_bricks";
                set(px + x, tier, pz + z, b);
            }
        }
        Mat armour = (x, y, z) -> pick(h(x, y, z, 20), "polished_deepslate", 60, "deepslate_tiles", 24, "deepslate_bricks", 8, "cracked_deepslate_tiles", 5, "polished_blackstone", 3);
        Mat dark = (x, y, z) -> h(x, y, z, 27) < 0.3 ? "polished_blackstone" : "blackstone";
        Mat gold = (x, y, z) -> h(x, y, z, 21) < 0.2 ? "raw_gold_block" : "gold_block";
        Mat cape = (x, y, z) -> pick(h(x, y, z, 22), cloth[0], 55, cloth[1], 30, cloth[2], 15);
        double[] clash = P.l(CLASH[0], CLASH[1], CLASH[2]);

        // ---- legs: front leg bent into the lunge, back leg driving
        double[][] legs = {{-2.0, 12.6, 1.2, -2.3, 7.2, 5.2, -2.1, 1.4, 5.9}, {2.0, 12.6, -0.3, 2.4, 7.0, -3.3, 2.5, 1.4, -6.3}};
        for (double[] L : legs) {
            double[] hip = v(L[0], L[1], L[2]), knee = v(L[3], L[4], L[5]), ankle = v(L[6], L[7], L[8]);
            limb(P, hip, knee, 2.35, 1.8, armour);
            limb(P, knee, ankle, 1.7, 1.25, armour);
            double[] cop = plus(knee, v(0, 0.2, 1), 0.6);
            ball(P, cop, 1.65, 1.45, 1.4, (x, y, z) -> P.l(x + 0.5, y + 0.5, z + 0.5)[2] > cop[2] + 0.6 ? gold.at(x, y, z) : armour.at(x, y, z));
            ball(P, v(ankle[0], 0.85, ankle[2] + 0.8), 1.5, 0.95, 2.4, dark);
        }
        // ---- pelvis and a skirt of tassets (gilded hem)
        ball(P, v(0, 12.8, 0.5), 3.4, 2.0, 2.5, armour);
        part(P, v(-6, 9, -4), v(6, 15, 5), (x, y, z) -> {
            if (y < 9.6 || y > 14.2) return 1;
            double rr = 3.9 + (14.2 - y) * 0.22;
            double k = Math.hypot(x / rr, (z - 0.5) / (rr * 0.8));
            return k <= 1 && k >= (rr - 0.9) / rr ? -1 : 1;
        }, (x, y, z) -> {
            double[] q = P.l(x + 0.5, y + 0.5, z + 0.5);
            boolean seam = ((int) Math.floor((Math.atan2(q[2], q[0]) + Math.PI) / (2 * Math.PI) * 14)) % 2 == 0;
            return q[1] < 10.5 ? gold.at(x, y, z) : seam ? "deepslate_tiles" : "polished_deepslate";
        });
        // ---- torso leaning into the blow: belt, overlapping lames, gold collar
        double[] waist = v(0, 13.8, 0.7), chest = v(0, 19.6, 2.3);
        part(P, v(-6, 12, -4), v(6, 24, 7), (x, y, z) -> Math.min(capsule(x, y, (z - 1.5) / 0.75 + 1.5, waist, chest, 3.2, 4.3) * 0.75,
                ellipsoid(x, y, z, chest[0], chest[1], chest[2], 4.6, 3.3, 3.0)), (x, y, z) -> {
            double[] q = P.l(x + 0.5, y + 0.5, z + 0.5);
            if (q[1] > 13.5 && q[1] < 14.6) return gold.at(x, y, z);                               // belt
            if (Math.abs(q[0]) < 0.6 && q[2] > chest[2] + 1.2 && q[1] > 15) return "gold_block";      // ridge
            if (q[1] > 21.6 && Math.abs(q[0]) < 3.4) return gold.at(x, y, z);                      // collar
            return q[1] > 15 && q[1] < 19.5 && ((int) Math.floor(q[1] * 0.8)) % 2 == 0 ? "deepslate_tiles" : "polished_deepslate";
        });
        // ---- gorget and helm (visor slit toward the enemy)
        limb(P, v(0, 22.3, 3.0), v(0, 24.4, 3.6), 2.1, 1.7, armour);
        double[] hc = v(0, 26.3, 4.1);
        ball(P, hc, 2.3, 2.7, 2.5, (x, y, z) -> {
            double[] q = P.l(x + 0.5, y + 0.5, z + 0.5);
            if (Math.abs(q[1] - hc[1] + 0.1) < 0.55 && q[2] > hc[2] + 1.2 && Math.abs(q[0]) < 1.7) return "black_concrete";
            if (q[2] > hc[2] + 1.5 && Math.abs(q[0]) < 0.5 && q[1] < hc[1] - 0.6) return "polished_blackstone";
            return armour.at(x, y, z);
        });
        if (!axe) {
            // crown of spikes and a crimson plume streaming back
            for (int k = 0; k < 7; k++) {
                double a = Math.PI * k / 6;   // front half-circle, facing the enemy
                double[] base = v(hc[0] + Math.cos(a) * 2.0, hc[1] + 1.9, hc[2] + Math.sin(a) * 1.9);
                limb(P, base, plus(base, v(Math.cos(a) * 0.3, 1.6 + 1.0 * Math.sin(a), Math.sin(a) * 0.3), 1), 0.55, 0.15, gold);
            }
            limb(P, v(0, 28.6, 3.9), v(0, 28.7, 0.8), 0.95, 0.85, cape);
            limb(P, v(0, 28.7, 0.8), v(0.6, 26.4, -2.8), 0.85, 0.35, cape);
        } else {
            // curling horns
            for (int s : new int[]{-1, 1}) {
                double[] a = v(s * 2.0, 27.4, 3.7), b = v(s * 3.7, 29.4, 3.0), c2 = v(s * 3.9, 31.6, 1.5);
                limb(P, a, b, 0.75, 0.55, (x, y, z) -> "bone_block");
                limb(P, b, c2, 0.55, 0.2, (x, y, z) -> "bone_block");
            }
        }
        // ---- pauldrons, three layered plates each, the lowest gilded
        for (int s : new int[]{-1, 1}) {
            double[][] layers = {{4.6, 22.2, 2.6, 2.7, 2.0, 2.9}, {5.2, 20.9, 2.6, 2.4, 1.5, 2.7}, {5.6, 19.8, 2.6, 2.1, 1.2, 2.4}};
            for (int i = 0; i < 3; i++) {
                double[] L = layers[i];
                ball(P, v(s * L[0], L[1], L[2]), L[3], L[4], L[5], i == 2 ? gold : armour);
            }
        }
        // ---- weapon arm, driven straight up into the clash
        double[] sh = v(4.9, 21.4, 2.6);
        double[] dir = unit(plus(clash, sh, -1));
        double[] elbow = plus(plus(sh, dir, 5.8), v(1, 0, 0), 0.7), hand = plus(sh, dir, 11);
        limb(P, sh, elbow, 1.75, 1.45, armour);
        ball(P, elbow, 1.55, 1.55, 1.55, armour);
        limb(P, elbow, hand, 1.4, 1.2, (x, y, z) -> {
            double[] q = P.l(x + 0.5, y + 0.5, z + 0.5);
            return Math.sqrt(sq(q[0] - hand[0]) + sq(q[1] - hand[1]) + sq(q[2] - hand[2])) < 2.2 ? gold.at(x, y, z) : armour.at(x, y, z);
        });
        ball(P, hand, 1.3, 1.3, 1.3, dark);
        double[] across = v(1, 0, 0), flat = unit(cross(dir, across));
        double reach = Math.sqrt(dot(plus(clash, hand, -1), plus(clash, hand, -1)));
        if (!axe) {
            limb(P, plus(hand, dir, -2.2), plus(hand, dir, 2.4), 0.55, 0.55, (x, y, z) -> "dark_oak_wood");   // grip
            ball(P, plus(hand, dir, -2.8), 0.9, 0.9, 0.9, gold);                                               // pommel
            double[] guard = plus(hand, dir, 2.6);
            limb(P, plus(guard, across, -3.4), plus(guard, across, 3.4), 0.55, 0.55, gold);                     // cross-guard
            limb(P, plus(plus(guard, across, -3.4), dir, 0.9), plus(guard, across, -3.4), 0.5, 0.5, gold);
            limb(P, plus(plus(guard, across, 3.4), dir, 0.9), plus(guard, across, 3.4), 0.5, 0.5, gold);
            double len = reach + 3.5 - 2.6;
            part(P, v(-3, guard[1] - 4, guard[2] - 4), v(3, CLASH[1] - P.by() + 6, clash[2] + 6), (x, y, z) -> {
                double[] p = v(x - guard[0], y - guard[1], z - guard[2]);
                double t = dot(p, dir);
                if (t < 0 || t > len) return 1;
                double w = 1.3 * Math.min(1, (len - t) / 3.5);
                return Math.abs(p[0]) <= w && Math.abs(dot(p, flat)) <= 0.5 ? -1 : 1;
            }, (x, y, z) -> {
                double[] q = P.l(x + 0.5, y + 0.5, z + 0.5);
                return Math.abs(q[0] - guard[0]) < 0.45 ? "polished_deepslate" : pick(h(x, y, z, 24), "iron_block", 50, "polished_andesite", 30, "smooth_stone", 20);
            });
        } else {
            limb(P, plus(hand, dir, -3.2), plus(clash, dir, -0.5), 0.55, 0.55, (x, y, z) -> "dark_oak_wood");  // haft
            ball(P, plus(hand, dir, -3.6), 0.8, 0.8, 0.8, gold);
            double[] head = plus(clash, dir, -2.2);
            // crescent blade swept back from the haft, in the plane of the swing
            part(P, v(-2, head[1] - 7, head[2] - 7), v(2, head[1] + 7, head[2] + 7), (x, y, z) -> {
                double[] p = v(x - head[0], y - head[1], z - head[2]);
                double side = -dot(p, flat), along = dot(p, dir);
                if (Math.abs(p[0]) > 0.55 || side < 0.4) return 1;
                double rr = Math.hypot(side - 0.4, along);
                return rr <= 4.2 && Math.hypot(side + 2.6, along) >= 4.1 ? -1 : 1;   // a crescent: big disc minus offset disc
            }, (x, y, z) -> {
                double[] q = P.l(x + 0.5, y + 0.5, z + 0.5);
                double side = -dot(plus(q, head, -1), flat);
                return side > 3.2 ? "iron_block" : pick(h(x, y, z, 28), "netherite_block", 60, "polished_deepslate", 40);
            });
            limb(P, plus(head, dir, -1.2), plus(head, dir, 1.2), 0.9, 0.9, gold);   // collar on the haft
        }
        // ---- off arm
        double[] sl = v(-4.9, 21.2, 2.6);
        if (!axe) {   // flung back for balance
            double[] el = v(-7.0, 17.6, -0.3), hd = v(-7.8, 14.6, -3.0);
            limb(P, sl, el, 1.7, 1.45, armour);
            ball(P, el, 1.5, 1.5, 1.5, armour);
            limb(P, el, hd, 1.4, 1.2, armour);
            ball(P, hd, 1.25, 1.25, 1.25, dark);
        } else {      // braced behind a kite shield
            double[] el = v(-5.4, 16.6, 4.6), hd = v(-3.6, 15.8, 6.9);
            limb(P, sl, el, 1.7, 1.45, armour);
            ball(P, el, 1.5, 1.5, 1.5, armour);
            limb(P, el, hd, 1.4, 1.2, armour);
            double cx = -2.6, cy = 15.2, sz = 8.0, turn = Math.toRadians(35) * (P.fx() < 0 ? 1 : -1);
            double nu = Math.cos(turn), nw = Math.sin(turn);   // face axis (u) and normal (w) in the lx/lz plane
            part(P, v(cx - 6, cy - 10, sz - 5), v(cx + 6, cy + 8, sz + 5), (x, y, z) -> {
                double u = (x - cx) * nu - (z - sz) * nw, w = (x - cx) * nw + (z - sz) * nu;
                if (Math.abs(w) > 0.55 || y > cy + 6.2 || y < cy - 8.5) return 1;
                double half = y > cy - 1.5 ? 3.8 - Math.max(0, y - cy - 4.6) * 1.2 : 3.8 * (y - (cy - 8.5)) / 7.0;   // kite outline
                return Math.abs(u) <= half ? -1 : 1;
            }, (x, y, z) -> {
                double[] q = P.l(x + 0.5, y + 0.5, z + 0.5);
                double dx = (q[0] - cx) * nu - (q[2] - sz) * nw, dy = q[1] - cy;
                double half = dy > -1.5 ? 3.8 - Math.max(0, dy - 4.6) * 1.2 : 3.8 * (dy + 8.5) / 7.0;
                if (Math.abs(dx) > half - 0.8 || dy > 5.6 || dy < -7.6) return gold.at(x, y, z);   // rim
                if (Math.hypot(dx, dy - 1) < 1.3) return "netherite_block";                        // boss
                if (Math.abs(dx) < 0.5 || Math.abs(dy - 1) < 0.5) return "gold_block";            // cross
                return cape.at(x, y, z);
            });
        }
        // ---- the cloak, streaming out behind and to one side
        part(P, v(-10, 3, -18), v(10, 23, 2), (x, y, z) -> {
            if (y < 3.5 || y > 22.4) return 1;
            double drop = 22.4 - y;
            double width = 4.4 + drop * 0.16;
            if (Math.abs(x) > width) return 1;
            double back = 0.2 - drop * 0.55 - drop * drop * 0.006 + 0.75 * Math.sin(x * 1.3 + y * 0.2);   // billowing away from the fight
            if (y < 5.0 && h((int) Math.floor(x * 3), 0, (int) Math.floor(z * 3), 25) < 0.4) return 1;   // tattered hem
            return Math.abs(z - back) <= 0.7 ? -1 : 1;
        }, cape);
        // candles burning around the plinth
        for (int i = 0; i < 10; i++) {
            double a = i * 2 * Math.PI / 10 + 0.3;
            int x = px + (int) Math.round(Math.cos(a) * 8.6), z = pz + (int) Math.round(Math.sin(a) * 8.6);
            if (empty(x, 2, z) && !empty(x, 1, z)) late(x, 2, z, "candle[candles=" + (2 + i % 3) + ",lit=true]");
        }
    }

    // ------------------------------------------------------------------ colossal black swords

    private void swords() {
        for (double[] s : new double[][]{{-2.36, 27}, {-0.78, 27}}) {
            double a = s[0], rr = s[1];
            int gx = (int) Math.round(Math.cos(a) * rr), gz = (int) Math.round(Math.sin(a) * rr);
            double tilt = 0.3;
            double[] dir = {Math.cos(a) * Math.sin(tilt), Math.cos(tilt), Math.sin(a) * Math.sin(tilt)};   // leaning outward
            double[] side = {-Math.sin(a), 0, Math.cos(a)};
            double[] tip = {gx + 0.5 - dir[0] * 4, Y - 4, gz + 0.5 - dir[2] * 4};
            double len = 22;
            double[] guardAt = {tip[0] + dir[0] * len, tip[1] + dir[1] * len, tip[2] + dir[2] * len};
            solid(Math.min(tip[0], guardAt[0]) - 4, tip[1] - 1, Math.min(tip[2], guardAt[2]) - 4,
                    Math.max(tip[0], guardAt[0]) + 4, guardAt[1] + 1, Math.max(tip[2], guardAt[2]) + 4, (x, y, z) -> {
                        double px = x - tip[0], py = y - tip[1], pz = z - tip[2];
                        double along = px * dir[0] + py * dir[1] + pz * dir[2];
                        if (along < 0 || along > len) return 1;
                        double across = px * side[0] + pz * side[2];
                        double nx = px - dir[0] * along - side[0] * across, ny = py - dir[1] * along, nz = pz - dir[2] * along - side[2] * across;
                        double w = 1.9 * Math.min(1, along / 6);
                        return Math.abs(across) <= w && Math.sqrt(nx * nx + ny * ny + nz * nz) <= 0.6 ? -1 : 1;
                    }, (x, y, z) -> {
                        double across = (x + 0.5 - tip[0]) * side[0] + (z + 0.5 - tip[2]) * side[2];
                        if (Math.abs(across) < 0.55) return h(x, y, z, 40) < 0.25 ? "magma_block" : "crying_obsidian";   // a smouldering fuller
                        return pick(h(x, y, z, 41), "netherite_block", 45, "polished_blackstone", 35, "blackstone", 20);
                    });
            double[] gl = {guardAt[0] - side[0] * 4.2, guardAt[1], guardAt[2] - side[2] * 4.2}, gr = {guardAt[0] + side[0] * 4.2, guardAt[1], guardAt[2] + side[2] * 4.2};
            cone(gl, gr, 0.7, 0.7, (x, y, z) -> h(x, y, z, 42) < 0.2 ? "raw_gold_block" : "gold_block");
            double[] gripEnd = {guardAt[0] + dir[0] * 5.5, guardAt[1] + dir[1] * 5.5, guardAt[2] + dir[2] * 5.5};
            cone(guardAt, gripEnd, 0.65, 0.65, (x, y, z) -> "dark_oak_wood");
            cone(gripEnd, gripEnd, 1.25, 1.25, (x, y, z) -> "gold_block");
            // the floor cracked open around the blade, glowing
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
                if (Math.hypot(x, z) <= 3.2 && h(gx + x, 0, gz + z, 43) < 0.45) set(gx + x, 0, gz + z, h(gx + x, 1, gz + z, 44) < 0.4 ? "magma_block" : "cracked_deepslate_tiles");
        }
    }

    /** Site of Grace: a small gold-inlaid circle before spawn (the glow itself is particles, see FactionsModule). */
    private void graceShrine() {
        double[] g = grace();
        int gx = (int) Math.floor(g[0]), gz = (int) Math.floor(g[2]);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            double d = Math.hypot(x, z);
            if (d > 2.3) continue;
            set(gx + x, 0, gz + z, d < 0.5 ? "gold_block" : d < 1.5 ? "chiseled_polished_blackstone" : "gilded_blackstone");
        }
        late(gx, 1, gz, "light[level=13]");
    }

    /** Candles on the battlements and around the braziers; dim hidden light so the fire and gold carry the scene. */
    private void candlesAndLight() {
        for (int x = -R - 1; x <= R + 1; x++) for (int z = -R - 1; z <= R + 1; z++) {
            double d = Math.hypot(x, z);
            if (d <= R - 1.5 || d > R - 0.5) continue;
            if (empty(x, 6, z) && !empty(x, 5, z) && h(x, 6, z, 60) < 0.12) late(x, 6, z, "candle[candles=" + (1 + (int) (h(x, 7, z, 61) * 4)) + ",lit=true]");
        }
        for (int x = -R; x <= R; x += 6) for (int z = -R; z <= R; z += 6) {
            if (Math.hypot(x, z) > R - 3 || !empty(x, 3, z) || !empty(x, 2, z) || empty(x, 0, z)) continue;
            late(x, 3, z, "light[level=6]");
        }
    }

}
