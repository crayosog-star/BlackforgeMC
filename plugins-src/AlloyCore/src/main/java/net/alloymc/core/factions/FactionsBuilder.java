package net.alloymc.core.factions;

import net.alloymc.core.arcade.core.MapKit;
import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.Random;

/**
 * Factions spawn - "the Colosseum of Fallen Kings", an Elden Ring style boss arena in the sky:
 * <ul>
 *   <li>an irregular, jagged floating island with rolling golden grass and rocky terraces, cliffs and stalactites
 *       underneath, gate promontories at N/E/S/W and a long northern outcrop</li>
 *   <li>a worn sandstone colosseum floor (the arena) inlaid with a gold rune sigil, ringed by broken columns,
 *       fallen drums and ruined arches hung with lanterns</li>
 *   <li>the two colossal duelling warriors at the centre - a crowned knight with a longsword (crimson cloak) and a
 *       horned knight with a great axe and kite shield (azure cloak) - their weapons clashing high above the arena,
 *       sculpted with layered pauldrons, tassets, lamed breastplates, gauntlets and streaming cloaks</li>
 *   <li>a giant glowing golden Erdtree on the northern outcrop, its roots spilling over the cliffs</li>
 *   <li>colossal swords impaled in the ground, floating rock fragments with ruins and minor golden trees</li>
 *   <li>a Site of Grace in front of spawn, candles, lanterns and hidden light blocks for warm lighting</li>
 * </ul>
 * The four gates (walk-through portal curtains that drop you into the wild) keep their positions.
 * Absolute coordinates, centred on (0, Y, 0). Everything lies within {@link #EXTENT} blocks of (0, 0).
 */
public final class FactionsBuilder {
    public static final int Y = 190, R = 44;
    public static final int GATE = R - 2;   // gate arches at (0,-GATE) (GATE,0) (0,GATE) (-GATE,0)
    /** Horizontal radius that contains the whole build (outcrop, Erdtree canopy, floating isles). */
    public static final int EXTENT = 100;
    static final int ARENA = 25;             // radius of the flat colosseum floor
    static final int TREE_Z = -68;           // Erdtree trunk on the northern outcrop

    private final Canvas c = new Canvas();
    private final Random rnd = new Random(1312);
    private final int[][] height = new int[2 * EXTENT + 1][2 * EXTENT + 1];   // surface height per column (Integer.MIN_VALUE = no island)

    public static double[] spawn() { return new double[]{0.5, Y + 1, 30.5}; }
    public static double[] rules() { return new double[]{-9.5, Y + 3.6, 27.5}; }
    public static double[] season() { return new double[]{10.5, Y + 3.6, 27.5}; }
    /** Site of Grace, between spawn and the arena. */
    public static double[] grace() { return new double[]{0.5, Y + 1, 22.5}; }
    /** Name-plate positions on the spawn side of the two duelling statues: crimson (west), azure (east). */
    public static double[][] statueLabels() {
        return new double[][]{{-9.5, Y + 5.2, 10.0}, {11.5, Y + 5.2, 10.0}};
    }

    private void set(int x, int y, int z, String b) { c.set(x, Y + y, z, "minecraft:" + b); }
    private void late(int x, int y, int z, String b) { c.setLate(x, Y + y, z, "minecraft:" + b); }
    private String get(int x, int y, int z) { return c.get(x, Y + y, z); }
    private boolean empty(int x, int y, int z) { String b = get(x, y, z); return b == null || b.endsWith(":air"); }

    public static Canvas build() {
        FactionsBuilder b = new FactionsBuilder();
        b.island();
        b.arena();
        b.graceShrine();
        b.ruins();
        for (int[] g : new int[][]{{0, -GATE, 0}, {GATE, 0, 1}, {0, GATE, 2}, {-GATE, 0, 3}}) b.gate(g[0], g[1], g[2]);
        b.warriors();
        b.erdtree();
        b.swords();
        b.isles();
        b.sign();
        b.lights();
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

    private int surf(int x, int z) {
        if (Math.abs(x) > EXTENT || Math.abs(z) > EXTENT) return Integer.MIN_VALUE;
        return height[x + EXTENT][z + EXTENT];
    }

    // ------------------------------------------------------------------ island

    /** Radius of the island's edge in direction a (radians, atan2(z, x)). */
    static double outline(double a) {
        double r = 43 + 7 * Math.sin(2 * a + 0.6) + 5 * Math.sin(3 * a + 1.9) + 3 * Math.sin(5 * a + 0.3) + 2 * Math.sin(7 * a + 2.2);
        for (double g : new double[]{-Math.PI / 2, 0, Math.PI / 2, Math.PI}) r = Math.max(r, 49 - 34 * angDist(a, g));   // gate promontories
        double n = angDist(a, -Math.PI / 2);
        r += 38 * Math.exp(-(n / 0.36) * (n / 0.36));                                                                   // northern outcrop
        return Math.max(r, ARENA + 9);
    }

    private void island() {
        for (int[] row : height) java.util.Arrays.fill(row, Integer.MIN_VALUE);
        for (int x = -EXTENT; x <= EXTENT; x++) for (int z = -EXTENT; z <= EXTENT; z++) {
            double d = Math.hypot(x, z), a = Math.atan2(z, x);
            double edge = outline(a) + (MapKit.noise(x / 8.0, z / 8.0, 5) - 0.5) * 7;
            if (d > edge) continue;
            // surface: flat arena and gate roads, rolling rocky terraces elsewhere, a mound under the Erdtree
            int hgt = 0;
            if (d > ARENA + 1) {
                double t = Math.min(1, (d - ARENA - 1) / 8);
                double n = MapKit.noise(x / 11.0, z / 11.0, 77) * 0.7 + MapKit.noise(x / 4.0, z / 4.0, 78) * 0.3;
                double hh = t * (0.5 + n * 9);
                double crag = MapKit.noise(x / 6.0, z / 6.0, 79);
                if (crag > 0.68 && d > ARENA + 8) hh += (crag - 0.68) * 45;           // rocky crags
                double road = Math.min(Math.abs(x), Math.abs(z));
                if (road < 7) hh *= Math.max(0, (road - 3.5) / 3.5);   // gate roads stay flat
                double plaza = Math.hypot(x, z - 29);
                if (plaza < 16) hh *= Math.max(0, (plaza - 12) / 4);    // flat spawn plaza
                double tm = Math.hypot(x, z - TREE_Z);
                hh += 8 * Math.exp(-(tm / 11) * (tm / 11));               // Erdtree mound
                hh = Math.min(hh, Math.max(0, (edge - d) * 1.4));        // soften right at the cliff edge
                hgt = (int) Math.round(hh);
            }
            // sunken ledges where the cliff breaks away
            double ledge = MapKit.noise(a * 7, 3.3, 81);   // whole radial strips, so every ledge opens onto the void
            if (d > edge - 9 && ledge > 0.64 && Math.min(Math.abs(x), Math.abs(z)) > 8 && Math.hypot(x, z - TREE_Z) > 30) hgt = -6 - (int) ((ledge - 0.64) * 20);
            height[x + EXTENT][z + EXTENT] = hgt;
            int depth = (int) (3 + (edge - d) * 0.85 + MapKit.noise(x / 5.0, z / 5.0, 13) * 9 + (h(x, 0, z, 1) < 0.06 ? 6 : 0));
            depth = Math.max(depth, 3 - hgt);
            for (int y = -depth; y <= hgt; y++) {
                String b;
                if (y == hgt) b = d <= ARENA + 1 ? "smooth_sandstone" : Math.hypot(x, z - 29) < 11.5 && hgt == 0
                        ? (MapKit.noise(x / 2.5, z / 2.5, 24) > 0.72 ? "grass_block" : ((x >> 1) + (z >> 1) & 1) == 0 ? "cut_sandstone" : "smooth_sandstone") : hgt >= 7 || hgt >= 4 && MapKit.noise(x / 5.0, z / 5.0, 41) > 0.55
                        ? pick(h(x, y, z, 2), "tuff", 3, "stone", 2, "andesite", 2, "mossy_cobblestone", 1)
                        : pick(MapKit.noise(x / 6.0, z / 6.0, 31), "grass_block", 7, "coarse_dirt", 1.2, "rooted_dirt", 0.8, "moss_block", 1);
                else if (y >= hgt - 2) b = "dirt";
                else if (y > -depth + 2 || y > -6) b = pick(h(x, y, z, 3), "stone", 4, "tuff", 3, "andesite", 2, "deepslate", 1, "calcite", 0.4, "raw_gold_block", 0.08);
                else b = pick(h(x, y, z, 4), "tuff", 2, "deepslate", 2, "cobbled_deepslate", 1);
                set(x, y, z, b);
            }
            // grass tufts
            if (d > ARENA + 2 && "minecraft:grass_block".equals(get(x, hgt, z))) {
                double g = h(x, 1, z, 5);
                if (g < 0.22) late(x, hgt + 1, z, "short_grass");
                else if (g < 0.25) late(x, hgt + 1, z, "fern");
            }
        }
        // golden roots dangling beneath the island
        for (int i = 0; i < 26; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, rr = outline(a) * (0.55 + rnd.nextDouble() * 0.35);
            int x = (int) (Math.cos(a) * rr), z = (int) (Math.sin(a) * rr);
            int bottom = -1;
            while (bottom > -70 && !empty(x, bottom, z)) bottom--;
            double[] top = {x, bottom + 3, z};
            double[] tip = {x + rnd.nextGaussian() * 3, bottom - 8 - rnd.nextInt(14), z + rnd.nextGaussian() * 3};
            cone(top, tip, 1.3, 0.35, (px, py, pz) -> rootMat(px, py, pz));
        }
    }

    /** Highest solid block in a column at or below maxY (relative), or MIN_VALUE if none. */
    private int top(int x, int z, int maxY) {
        for (int y = maxY; y >= -60; y--) if (!empty(x, y, z)) return y;
        return Integer.MIN_VALUE;
    }

    private static String rootMat(int x, int y, int z) {
        return pick(h(x, y, z, 9), "stripped_oak_wood", 5, "stripped_birch_wood", 2, "gold_block", 1, "ochre_froglight", 1);
    }

    // ------------------------------------------------------------------ the colosseum floor

    private void arena() {
        for (int x = -ARENA - 1; x <= ARENA + 1; x++) for (int z = -ARENA - 1; z <= ARENA + 1; z++) {
            double d = Math.hypot(x, z), a = Math.atan2(z, x);
            if (d > ARENA + 0.5) continue;
            if (d > ARENA - 2 && MapKit.noise(x / 2.5, z / 2.5, 23) > 0.7) continue;   // crumbling edge, grass creeping in
            // big worn flagstones in rings
            int ring = (int) (d / 3.2), seg = (int) ((a + Math.PI) / (2 * Math.PI) * (12 + ring * 6));
            String b = ((ring + seg) & 1) == 0 ? "smooth_sandstone" : "cut_sandstone";
            double wear = MapKit.noise(x / 3.5, z / 3.5, 21);
            if (wear > 0.74) b = "sandstone";
            if (wear < 0.06) b = "stone_bricks";
            if (MapKit.noise(x / 5.0, z / 5.0, 22) > 0.86) b = "mossy_stone_bricks";
            if (h(x, 0, z, 6) < 0.025) b = "cracked_stone_bricks";
            if (d > ARENA - 1.2) b = "chiseled_sandstone";                         // border band
            if (sigil(x + 0.5, z + 0.5)) b = h(x, 0, z, 7) < 0.15 ? "raw_gold_block" : "gold_block";
            set(x, 0, z, b);
        }
        // low curb around the arena, broken where the roads leave
        for (int x = -ARENA - 2; x <= ARENA + 2; x++) for (int z = -ARENA - 2; z <= ARENA + 2; z++) {
            double d = Math.hypot(x, z);
            if (d <= ARENA + 0.5 || d > ARENA + 1.5 || Math.min(Math.abs(x), Math.abs(z)) <= 4) continue;
            if (h(x, 1, z, 8) < 0.12) continue;   // crumbled
            set(x, 1, z, h(x, 1, z, 9) < 0.5 ? "cut_sandstone" : "smooth_sandstone");
            if (h(x, 2, z, 10) < 0.05) late(x, 2, z, "candle[candles=" + (1 + (int) (h(x, 3, z, 11) * 4)) + ",lit=true]");
        }
    }

    /** The gold rune sigil inlaid in the arena floor (inspired by the Elden Ring's interlocking arcs). */
    private static boolean sigil(double x, double z) {
        double w = 0.55;
        if (Math.abs(Math.hypot(x, z) - 13) < w) return true;                                   // outer ring
        if (Math.abs(Math.hypot(x, z + 4.5) - 8.5) < w) return true;                            // upper ring
        if (Math.abs(Math.hypot(x, z - 4.5) - 8.5) < w) return true;                            // lower ring
        if (Math.abs(Math.hypot(x, z) - 3.2) < w) return true;                                  // core
        if (Math.abs(x) < 0.6 && Math.abs(z) < 21) return true;                                 // the spine
        double big = Math.hypot(x, z - 10);
        if (Math.abs(big - 20) < w && z < -2 && Math.abs(x) < 17) return true;                 // great arc
        double small = Math.hypot(x, z + 13);
        return Math.abs(small - 6) < w && z > -13;                                               // crown arc
    }

    private void graceShrine() {
        double[] g = grace();
        int gx = (int) Math.floor(g[0]), gz = (int) Math.floor(g[2]);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            double d = Math.hypot(x, z);
            if (d > 2.3) continue;
            set(gx + x, 0, gz + z, d < 0.5 ? "gold_block" : d < 1.5 ? "chiseled_sandstone" : "polished_tuff");
        }
        late(gx, 1, gz, "light[level=15]");
    }

    // ------------------------------------------------------------------ ruins ring

    private void ruins() {
        int n = 26;
        boolean[] tall = new boolean[n];
        int[] tops = new int[n];
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n + 0.05;
            boolean onRoad = false;
            for (double g : new double[]{-Math.PI / 2, 0, Math.PI / 2, Math.PI}) if (angDist(a, g) < 0.17) onRoad = true;
            if (onRoad) continue;
            int cx = (int) Math.round(Math.cos(a) * (ARENA + 5)), cz = (int) Math.round(Math.sin(a) * (ARENA + 5));
            int base = surf(cx, cz);
            if (base == Integer.MIN_VALUE) continue;
            double roll = rnd.nextDouble();
            int hgt = roll < 0.25 ? 2 + rnd.nextInt(2) : roll < 0.55 ? 6 + rnd.nextInt(5) : 13 + rnd.nextInt(4);
            column(cx, base, cz, hgt);
            tall[i] = hgt >= 13;
            tops[i] = base + hgt;
            if (roll < 0.25) fallenDrum(cx, cz, a);
            if (hgt < 13 && rnd.nextDouble() < 0.6) late(cx, base + hgt + 1, cz, "candle[candles=" + (2 + rnd.nextInt(3)) + ",lit=true]");
        }
        // arches between neighbouring tall columns, each with a hanging lantern
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (!tall[i] || !tall[j]) continue;
            double a1 = i * 2 * Math.PI / n + 0.05, a2 = j * 2 * Math.PI / n + 0.05;
            double[] p1 = {Math.cos(a1) * (ARENA + 5), 0, Math.sin(a1) * (ARENA + 5)}, p2 = {Math.cos(a2) * (ARENA + 5), 0, Math.sin(a2) * (ARENA + 5)};
            int top = Math.min(tops[i], tops[j]);
            double span = Math.hypot(p2[0] - p1[0], p2[2] - p1[2]);
            for (double s = 0; s <= 1; s += 0.5 / span) {
                double x = p1[0] + (p2[0] - p1[0]) * s, z = p1[2] + (p2[2] - p1[2]) * s;
                double sag = Math.sqrt(Math.max(0, 0.25 - (s - 0.5) * (s - 0.5))) * 2;   // 0..1 semicircle
                int under = (int) Math.round(top - 3 + sag * 2.5);
                for (int y = under; y <= top + 1; y++) set((int) Math.floor(x), y, (int) Math.floor(z), y == top + 1 ? "smooth_sandstone_slab[type=bottom]" : "cut_sandstone");
            }
            int mx = (int) Math.floor((p1[0] + p2[0]) / 2), mz = (int) Math.floor((p1[2] + p2[2]) / 2);
            int y = top - 1;
            while (y > top - 4 && !empty(mx, y, mz)) y--;
            late(mx, y, mz, "iron_chain[axis=y]");
            late(mx, y - 1, mz, "lantern[hanging=true]");
        }
    }

    private void column(int cx, int base, int cz, int hgt) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            double d = Math.hypot(x, z);
            if (d > 2.3) continue;
            set(cx + x, base + 1, cz + z, "chiseled_tuff");   // plinth
            if (d > 1.6) continue;
            for (int y = base + 2; y <= base + hgt; y++) {
                boolean topLayer = y == base + hgt;
                if (topLayer && hgt < 13 && h(cx + x, y, cz + z, 12) < 0.45) continue;   // broken, jagged top
                String b = topLayer && hgt >= 13 ? "chiseled_sandstone" : (y - base) % 4 == 0 ? "smooth_sandstone" : "cut_sandstone";
                if (h(cx + x, y, cz + z, 13) < 0.12) b = "sandstone";
                if (y < base + 4 && h(cx + x, y, cz + z, 14) < 0.25) b = "mossy_stone_bricks";
                set(cx + x, y, cz + z, b);
            }
        }
    }

    /** A toppled column drum lying on the grass. */
    private void fallenDrum(int cx, int cz, double a) {
        double dir = a + Math.PI / 2 + (rnd.nextDouble() - 0.5);
        double len = 4 + rnd.nextInt(4);
        double sx = cx + Math.cos(a) * 3, sz = cz + Math.sin(a) * 3;
        int base = surf((int) sx, (int) sz);
        if (base == Integer.MIN_VALUE) return;
        double[] p = {sx, Y + base + 2.0, sz}, q = {sx + Math.cos(dir) * len, Y + base + 2.0, sz + Math.sin(dir) * len};
        cone(p, q, 1.35, 1.35, (x, y, z) -> h(x, y, z, 15) < 0.2 ? "mossy_stone_bricks" : "cut_sandstone");
    }

    // ------------------------------------------------------------------ gates

    /** A pointed stone archway whose portal curtain is the way out to the wild. dir: 0=N 1=E 2=S 3=W. */
    private void gate(int gx, int gz, int dir) {
        boolean alongX = dir == 0 || dir == 2;
        for (int u = -7; u <= 7; u++) for (int y = 1; y <= 17; y++) for (int w = -2; w <= 2; w++) {
            int x = gx + (alongX ? u : w), z = gz + (alongX ? w : u);
            int au = Math.abs(u);
            boolean open = au <= 3 && y <= 8 || au <= 2 && y == 9;
            if (open) { if (w == 0) late(x, y, z, "nether_portal[axis=" + (alongX ? "x" : "z") + "]"); else set(x, y, z, "air"); continue; }
            // pointed (gothic) arch: two arcs meeting at the apex
            double arcIn = Math.hypot(au + 3.5, y - 8.5), arcOut = Math.hypot(au + 3.5, y - 7.5);
            boolean pier = au >= 4 && au <= 6 && y <= 11;
            boolean arch = y > 8 && arcIn >= 7.0 && arcOut <= 11.0 && au <= 6;
            if (!pier && !arch) continue;
            if (Math.abs(w) == 2 && !(pier && y <= 2)) continue;   // slimmer arch, wide footing
            String b = h(x, y, z, 16) < 0.15 ? "cracked_stone_bricks" : h(x, y, z, 17) < 0.25 ? "mossy_stone_bricks" : "stone_bricks";
            if (pier && (y == 1 || y == 11)) b = "chiseled_stone_bricks";
            if (arch && au <= 1 && y >= 15) b = "gold_block";   // keystone
            if (pier && au == 4 && y >= 3 && y <= 9 && w == 0) b = "gold_block";   // gilded jamb
            set(x, y, z, b);
        }
        for (int s : new int[]{-5, 5}) {
            int x = gx + (alongX ? s : 0), z = gz + (alongX ? 0 : s);
            late(x, 12, z, "candle[candles=4,lit=true]");
        }
        // flat road under the arch
        for (int u = -4; u <= 4; u++) for (int w = -3; w <= 3; w++) {
            int x = gx + (alongX ? u : w), z = gz + (alongX ? w : u);
            set(x, 0, z, Math.abs(u) <= 1 ? "cut_sandstone" : "smooth_sandstone");
        }
    }

    // ------------------------------------------------------------------ the statues

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
                String b = tier == 2 && rim ? "gold_block" : rim ? "chiseled_tuff" : "tuff_bricks";
                if (rim && tier != 2 && h(px + x, tier, pz + z, 18) < 0.12) b = "mossy_stone_bricks";
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
        // moss creeping onto the plinth
        for (int i = 0; i < 16; i++) {
            int x = px + rnd.nextInt(17) - 8, z = pz + rnd.nextInt(17) - 8;
            for (int y = 3; y >= 1; y--) if (!empty(x, y, z)) { if (empty(x, y + 1, z) && Math.hypot(x - px, z - pz) > 6.5) late(x, y + 1, z, "moss_carpet"); break; }
        }
    }

    // ------------------------------------------------------------------ the Erdtree

    private void erdtree() {
        int base = Math.max(0, surf(0, TREE_Z));
        double tx = 0.5, ty = Y + base, tz = TREE_Z + 0.5;
        Mat bark = (x, y, z) -> pick(h(x, y, z, 30), "stripped_oak_wood", 50, "stripped_birch_wood", 18, "honeycomb_block", 10, "gold_block", 10, "ochre_froglight", 12);
        // a trunk of six twisting strands, flaring at the roots
        int strands = 7;
        for (int k = 0; k < strands; k++) {
            double[] prev = null;
            for (double ly = -2; ly <= 60; ly += 3) {
                double phi = k * 2 * Math.PI / strands + ly * 0.05;
                double rr = 4.6 * (1 - ly / 85) + 1.2 + (ly < 8 ? (8 - ly) * 0.9 : 0);
                double[] p = {tx + Math.cos(phi) * rr, ty + ly, tz + Math.sin(phi) * rr};
                double rad = 2.9 - ly / 60 * 1.4;
                if (prev != null) cone(prev, p, rad + 0.1, rad, bark);
                prev = p;
            }
        }
        // solid heartwood so there are no pockets between the strands
        cone(new double[]{tx, ty - 3, tz}, new double[]{tx, ty + 40, tz}, 5.6, 3.6, bark);
        // branches spreading into the canopy
        double[] crown = {tx, ty + 66, tz};
        for (int k = 0; k < 9; k++) {
            double a = k * 2 * Math.PI / 9 + 0.3, from = 36 + (k % 3) * 7;
            double[] p = {tx + Math.cos(a) * 2, ty + from, tz + Math.sin(a) * 2};
            double[] q = {tx + Math.cos(a) * (18 + k % 4 * 3), ty + 58 + (k % 3) * 4, tz + Math.sin(a) * (18 + k % 4 * 3)};
            cone(p, q, 1.9, 0.8, bark);
        }
        // canopy: a cloud of golden, glowing foliage
        Mat leaf = (x, y, z) -> {   // colours in clusters, not per-block speckle
            double n = MapKit.noise((x + y * 0.7) / 3.5, (z - y * 0.5) / 3.5, 31);
            if (h(x, y, z, 32) < 0.06) return "ochre_froglight";
            return n < 0.3 ? "yellow_terracotta" : n < 0.5 ? "honeycomb_block" : n < 0.68 ? "gold_block" : n < 0.82 ? "yellow_concrete" : "ochre_froglight";
        };
        Random r = new Random(77);
        for (int i = 0; i < 90; i++) {
            double a = r.nextDouble() * 2 * Math.PI, d = Math.sqrt(r.nextDouble()) * 29;
            double cx = crown[0] + Math.cos(a) * d, cz = crown[2] + Math.sin(a) * d;
            double cy = crown[1] - 2 + r.nextDouble() * 9 * (1 - d / 34) - (d > 20 ? (d - 20) * 0.5 : 0);
            double rad = 4 + r.nextDouble() * 4;
            solid(cx - rad, cy - rad, cz - rad, cx + rad, cy + rad, cz + rad, (x, y, z) -> {
                double dd = Math.sqrt(sq(x + 0.5 - cx) + sq((y + 0.5 - cy) * 1.3) + sq(z + 0.5 - cz)) - rad;
                return dd <= 0 && dd > -3.2 ? -1 : 1;   // hollow shell keeps the block count sane
            }, leaf);
            // glowing drips hanging from the underside
            if (r.nextDouble() < 0.55) {
                int x = (int) Math.floor(cx + r.nextGaussian() * rad * 0.4), z = (int) Math.floor(cz + r.nextGaussian() * rad * 0.4);
                int y = (int) Math.floor(cy - rad / 1.3);
                while (y < cy && c.get(x, y, z) == null) y++;
                int len = 3 + r.nextInt(7);
                for (int j = 1; j <= len; j++)
                    if (c.get(x, y - j, z) == null) c.setLate(x, y - j, z, "minecraft:" + (j == len ? "cave_vines[berries=true]" : "cave_vines_plant[berries=" + (r.nextBoolean() ? "true" : "false") + "]"));
            }
        }
        // great roots crawling across the outcrop and spilling over the cliffs
        for (int k = 0; k < 11; k++) {
            double a = k * 2 * Math.PI / 11 + 0.2;
            double[] p = {tx + Math.cos(a) * 3, ty + 1, tz + Math.sin(a) * 3};
            double reach = 14 + (k % 4) * 5;
            double[] q = {tx + Math.cos(a) * reach, ty - 1, tz + Math.sin(a) * reach};
            double[] e = {tx + Math.cos(a) * (reach + 7), ty - 14 - k % 3 * 5, tz + Math.sin(a) * (reach + 7)};
            cone(p, q, 2.0, 1.2, bark);
            cone(q, e, 1.2, 0.4, bark);
        }
        // fill the gaps under and between the roots so nobody gets wedged in them
        for (int x = -20; x <= 20; x++) for (int z = TREE_Z - 20; z <= TREE_Z + 20; z++) {
            int g = surf(x, z);
            if (g == Integer.MIN_VALUE) continue;
            int top = -1;
            for (int y = base + 12; y > g; y--) if (!empty(x, y, z)) { top = y; break; }
            for (int y = g + 1; y < top; y++) if (empty(x, y, z)) set(x, y, z, rootMat(x, Y + y, z));
        }
        // raise any hollow among the roots that is walled in on all four sides until it can be climbed out of
        for (int pass = 0; pass < 16; pass++) {
            boolean changed = false;
            for (int x = -20; x <= 20; x++) for (int z = TREE_Z - 20; z <= TREE_Z + 20; z++) {
                int t = top(x, z, base + 14);
                if (t == Integer.MIN_VALUE) continue;
                // a narrow crevasse: both neighbours on one axis tower over it
                int ex = Math.min(top(x + 1, z, base + 14), top(x - 1, z, base + 14)), ez = Math.min(top(x, z + 1, base + 14), top(x, z - 1, base + 14));
                if (ex != Integer.MIN_VALUE && ex >= t + 2 || ez != Integer.MIN_VALUE && ez >= t + 2) { set(x, t + 1, z, rootMat(x, Y + t + 1, z)); changed = true; }
            }
            if (!changed) break;
        }
    }

    // ------------------------------------------------------------------ colossal swords

    private void swords() {
        double[][] at = {{0.78, 40}, {2.33, 38}, {-2.45, 41}, {-0.72, 40}};
        for (double[] s : at) {
            double a = s[0], rr = s[1];
            int gx = (int) Math.round(Math.cos(a) * rr), gz = (int) Math.round(Math.sin(a) * rr);
            int g = surf(gx, gz);
            if (g == Integer.MIN_VALUE) continue;
            double tilt = 0.32;
            double[] dir = {Math.cos(a) * Math.sin(tilt), Math.cos(tilt), Math.sin(a) * Math.sin(tilt)};   // leaning outward
            double[] side = {-Math.sin(a), 0, Math.cos(a)};
            double[] tip = {gx + 0.5 - dir[0] * 4, Y + g - 4, gz + 0.5 - dir[2] * 4};
            double len = 24;
            double[] guardAt = {tip[0] + dir[0] * len, tip[1] + dir[1] * len, tip[2] + dir[2] * len};
            // blade: flat, tapering to the point, darker fuller down the middle
            solid(Math.min(tip[0], guardAt[0]) - 4, tip[1] - 1, Math.min(tip[2], guardAt[2]) - 4,
                    Math.max(tip[0], guardAt[0]) + 4, guardAt[1] + 1, Math.max(tip[2], guardAt[2]) + 4, (x, y, z) -> {
                        double px = x + 0.5 - tip[0], py = y + 0.5 - tip[1], pz = z + 0.5 - tip[2];
                        double along = px * dir[0] + py * dir[1] + pz * dir[2];
                        if (along < 0 || along > len) return 1;
                        double across = px * side[0] + pz * side[2];
                        double nx = px - dir[0] * along - side[0] * across, ny = py - dir[1] * along, nz = pz - dir[2] * along - side[2] * across;
                        double thick = Math.sqrt(nx * nx + ny * ny + nz * nz);
                        double w = 2.0 * Math.min(1, along / 6);
                        return Math.abs(across) <= w && thick <= 0.6 ? -1 : 1;
                    }, (x, y, z) -> {
                        double px = x + 0.5 - tip[0], pz = z + 0.5 - tip[2];
                        double across = px * side[0] + pz * side[2];
                        return Math.abs(across) < 0.55 ? "polished_deepslate" : pick(h(x, y, z, 40), "iron_block", 55, "polished_andesite", 30, "smooth_stone", 15);
                    });
            // crossguard, grip and pommel
            double[] gl = {guardAt[0] - side[0] * 4.5, guardAt[1], guardAt[2] - side[2] * 4.5}, gr = {guardAt[0] + side[0] * 4.5, guardAt[1], guardAt[2] + side[2] * 4.5};
            cone(gl, gr, 0.75, 0.75, (x, y, z) -> h(x, y, z, 41) < 0.2 ? "raw_gold_block" : "gold_block");
            double[] gripEnd = {guardAt[0] + dir[0] * 6, guardAt[1] + dir[1] * 6, guardAt[2] + dir[2] * 6};
            cone(guardAt, gripEnd, 0.7, 0.7, (x, y, z) -> "dark_oak_wood");
            double[] pom = {gripEnd[0] + dir[0], gripEnd[1] + dir[1], gripEnd[2] + dir[2]};
            cone(pom, pom, 1.3, 1.3, (x, y, z) -> "gold_block");
            // cracked earth around the impact
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
                int sy = surf(gx + x, gz + z);
                if (sy == Integer.MIN_VALUE || Math.hypot(x, z) > 4.2 || h(gx + x, 0, gz + z, 42) < 0.4) continue;
                String cur = get(gx + x, sy, gz + z);
                if (cur != null && cur.contains("grass")) set(gx + x, sy, gz + z, h(gx + x, 1, gz + z, 43) < 0.5 ? "coarse_dirt" : "tuff");
            }
        }
    }

    // ------------------------------------------------------------------ floating isles

    private void isles() {
        Random r = new Random(4040);
        int placed = 0;
        for (int tries = 0; tries < 200 && placed < 11; tries++) {
            double a = r.nextDouble() * 2 * Math.PI, d = 58 + r.nextDouble() * 34;
            if (d < outline(a) + 12) continue;   // keep a gap from the main island's edge
            int cx = (int) (Math.cos(a) * d), cz = (int) (Math.sin(a) * d);
            if (Math.hypot(cx, cz) > EXTENT - 10) continue;
            int cy = -18 + r.nextInt(46);
            int rad = 4 + r.nextInt(6);
            isle(cx, cy, cz, rad, placed % 5, r);
            placed++;
        }
    }

    private void isle(int cx, int cy, int cz, int rad, int kind, Random r) {
        for (int x = -rad - 2; x <= rad + 2; x++) for (int z = -rad - 2; z <= rad + 2; z++) {
            double d = Math.hypot(x, z), edge = rad + (MapKit.noise((cx + x) / 3.0, (cz + z) / 3.0, 50) - 0.5) * 3;
            if (d > edge) continue;
            int depth = (int) (2 + (edge - d) * 1.6 + h(cx + x, 0, cz + z, 51) * 3);
            for (int y = -depth; y <= 0; y++)
                set(cx + x, cy + y, cz + z, y == 0 ? (h(cx + x, 0, cz + z, 52) < 0.75 ? "grass_block" : "moss_block") : y > -3 ? "dirt" : pick(h(cx + x, y, cz + z, 53), "stone", 3, "tuff", 2, "andesite", 1));
            if (h(cx + x, 1, cz + z, 54) < 0.2) late(cx + x, cy + 1, cz + z, "short_grass");
        }
        switch (kind) {
            case 0 -> column(cx, cy, cz, 5 + r.nextInt(8));
            case 1 -> {   // minor golden tree
                double[] base = {cx + 0.5, Y + cy + 1, cz + 0.5}, top = {cx + 0.5, Y + cy + 8, cz + 0.5};
                cone(base, top, 1.1, 0.6, (x, y, z) -> pick(h(x, y, z, 55), "stripped_oak_wood", 3, "stripped_birch_wood", 1));
                solid(cx - 5, Y + cy + 6, cz - 5, cx + 6, Y + cy + 14, cz + 6, (x, y, z) -> Math.sqrt(sq(x + 0.5 - top[0]) + sq((y + 0.5 - top[1] - 1.5) * 1.4) + sq(z + 0.5 - top[2])) - 4.2,
                        (x, y, z) -> pick(h(x, y, z, 56), "honeycomb_block", 3, "gold_block", 2, "ochre_froglight", 2, "yellow_terracotta", 2));
            }
            case 2 -> {   // a lone ruined arch
                for (int y = 1; y <= 9; y++) for (int s : new int[]{-3, 3}) set(cx + s, cy + y, cz, y % 3 == 0 ? "smooth_sandstone" : "cut_sandstone");
                for (int x = -3; x <= 3; x++) if (h(cx + x, 10, cz, 57) < 0.8) set(cx + x, cy + 10, cz, "cut_sandstone");
                late(cx, cy + 9, cz, "lantern[hanging=true]");
            }
            case 3 -> {   // a candle shrine
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(cx + x, cy + 1, cz + z, "chiseled_tuff");
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) late(cx + x, cy + 2, cz + z, "candle[candles=" + (1 + (int) (h(cx + x, 0, cz + z, 58) * 4)) + ",lit=true]");
            }
            default -> {  // a broken greatsword, buried at an angle
                double[] p = {cx + 0.5, Y + cy - 2, cz + 0.5}, q = {cx + 3.5, Y + cy + 9, cz + 1.5};
                cone(p, q, 0.9, 0.9, (x, y, z) -> pick(h(x, y, z, 59), "iron_block", 2, "polished_andesite", 1));
            }
        }
    }

    // ------------------------------------------------------------------ the sign

    /** "FACTIONS" in gold letters above the north gate, hung on chains. */
    private void sign() {
        String text = "FACTIONS";
        int scale = 2, spacing = 1, width = BlockFont.width(text, spacing) * scale, x0 = -width / 2, y0 = 47, z = -R - 6;
        BlockFont.draw(text, spacing, (col, row, glyphRow) -> {
            String b = glyphRow <= 1 ? "gold_block" : glyphRow <= 4 ? "raw_gold_block" : "gilded_blackstone";
            for (int sx = 0; sx < scale; sx++) for (int sy = 0; sy < scale; sy++) {
                int x = x0 + col * scale + sx, y = y0 + row * scale + sy;
                set(x, y, z, b); set(x, y, z + 1, b);
                for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) if (get(x + ox, y + oy, z - 1) == null) set(x + ox, y + oy, z - 1, "polished_blackstone");
            }
        });
        for (int s : new int[]{-1, 1}) for (int y = 62; y < 76; y++) if (get(s * (width / 2 - 4), y, z - 1) == null) set(s * (width / 2 - 4), y, z - 1, "iron_chain[axis=y]");   // hung from the canopy
    }

    // ------------------------------------------------------------------ lighting

    /** Hidden light blocks for a warm glow everywhere (invisible, no collision), plus lanterns on posts along the roads. */
    private void lights() {
        for (int x = -EXTENT; x <= EXTENT; x += 5) for (int z = -EXTENT; z <= EXTENT; z += 5) {
            int s = surf(x, z);
            if (s == Integer.MIN_VALUE) continue;
            int y = s + 3;
            if (empty(x, y, z) && empty(x, y - 1, z)) late(x, y, z, "light[level=" + (Math.hypot(x, z) <= ARENA ? 12 : 9) + "]");
        }
        for (int g = 0; g < 4; g++) for (int d = ARENA + 4; d <= GATE - 6; d += 5) for (int side : new int[]{-4, 4}) {
            int x = g == 0 || g == 2 ? side : (g == 1 ? d : -d), z = g == 0 ? -d : g == 2 ? d : side;
            int s = surf(x, z);
            if (s == Integer.MIN_VALUE || !empty(x, s + 1, z)) continue;
            set(x, s + 1, z, "polished_tuff_wall");
            set(x, s + 2, z, "polished_tuff_wall");
            late(x, s + 3, z, "lantern");
        }
    }
}
