import java.io.*;
import java.util.*;

/** Voxel canvas + shape helpers for the Dragon's Graveyard. Coordinates are relative: x east, z south, y up, 0,0,0 = arena centre top. */
public class Vox {
    static final int X0 = -190, Z0 = -220, W = 380, D = 400, Y0 = -100, H = 300;   // y -100..199
    static final short[] g = new short[W * D * H];
    static final List<String> pal = new ArrayList<>(List.of("air"));
    static final Map<String, Integer> pid = new HashMap<>(Map.of("air", 0));

    interface BF { String at(int x, int y, int z); }

    static int id(String s) {
        Integer i = pid.get(s);
        if (i == null) { i = pal.size(); pal.add(s); pid.put(s, i); }
        return i;
    }
    static boolean in(int x, int y, int z) { return x >= X0 && x < X0 + W && z >= Z0 && z < Z0 + D && y >= Y0 && y < Y0 + H; }
    static int ix(int x, int y, int z) { return ((y - Y0) * D + (z - Z0)) * W + (x - X0); }
    static void set(int x, int y, int z, String b) { if (in(x, y, z)) g[ix(x, y, z)] = (short) id(b); }
    static void setAir(int x, int y, int z, String b) { if (in(x, y, z) && g[ix(x, y, z)] == 0) g[ix(x, y, z)] = (short) id(b); }
    static String get(int x, int y, int z) { return in(x, y, z) ? pal.get(g[ix(x, y, z)]) : "air"; }
    static boolean air(int x, int y, int z) { return !in(x, y, z) || g[ix(x, y, z)] == 0; }

    static double hn(int x, int y, int z) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ 0x9E3779B97F4A7C15L;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return (h >>> 11) * (1.0 / (1L << 53));
    }
    static double vn(double x, double z, double s) {   // smooth value noise 0..1
        double fx = x / s, fz = z / s; int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
        double tx = fx - ix, tz = fz - iz; tx = tx * tx * (3 - 2 * tx); tz = tz * tz * (3 - 2 * tz);
        double a = hn(ix, 7, iz), b = hn(ix + 1, 7, iz), c = hn(ix, 7, iz + 1), d = hn(ix + 1, 7, iz + 1);
        return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * tz;
    }

    static void box(int x0, int y0, int z0, int x1, int y1, int z1, String b) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) set(x, y, z, b);
    }
    static void box(int x0, int y0, int z0, int x1, int y1, int z1, BF f) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) set(x, y, z, f.at(x, y, z));
    }
    static void ell(double cx, double cy, double cz, double rx, double ry, double rz, BF f) {
        for (int y = (int) Math.floor(cy - ry); y <= cy + ry; y++) for (int z = (int) Math.floor(cz - rz); z <= cz + rz; z++) for (int x = (int) Math.floor(cx - rx); x <= cx + rx; x++) {
            double dx = (x + .5 - cx) / rx, dy = (y + .5 - cy) / ry, dz = (z + .5 - cz) / rz;
            if (dx * dx + dy * dy + dz * dz <= 1) set(x, y, z, f.at(x, y, z));
        }
    }
    static void carve(double cx, double cy, double cz, double rx, double ry, double rz) { ell(cx, cy, cz, rx, ry, rz, (x, y, z) -> "air"); }
    static void capsule(double ax, double ay, double az, double bx, double by, double bz, double r0, double r1, BF f) {
        double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        int n = (int) Math.max(1, Math.ceil(len * 1.5));
        for (int i = 0; i <= n; i++) { double t = i / (double) n; double r = r0 + (r1 - r0) * t; ell(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, r, r, r, f); }
    }
    /** Catmull-Rom spline through pts. */
    static double[] cr(double[][] p, double t) {
        int n = p.length - 1; double s = t * n; int i = Math.min(n - 1, (int) s); double u = s - i;
        double[] p0 = p[Math.max(0, i - 1)], p1 = p[i], p2 = p[i + 1], p3 = p[Math.min(n, i + 2)];
        double[] r = new double[3];
        for (int k = 0; k < 3; k++) r[k] = 0.5 * ((2 * p1[k]) + (-p0[k] + p2[k]) * u + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * u * u + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * u * u * u);
        return r;
    }
    interface RF { double at(double t); }
    static void tube(double[][] pts, RF rad, BF f) {
        double len = 0; double[] prev = pts[0];
        for (int i = 1; i <= 80; i++) { double[] c = cr(pts, i / 80.0); len += Math.sqrt(Math.pow(c[0] - prev[0], 2) + Math.pow(c[1] - prev[1], 2) + Math.pow(c[2] - prev[2], 2)); prev = c; }
        int n = (int) Math.ceil(len * 1.6);
        for (int i = 0; i <= n; i++) { double t = i / (double) n; double[] c = cr(pts, t); double r = rad.at(t); ell(c[0], c[1], c[2], r, r, r, f); }
    }
    static double[] tgt(double[][] pts, double t) { double[] a = cr(pts, Math.max(0, t - .01)), b = cr(pts, Math.min(1, t + .01)); double[] d = {b[0] - a[0], b[1] - a[1], b[2] - a[2]}; double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); return new double[]{d[0] / l, d[1] / l, d[2] / l}; }

    // ---------- palettes ----------
    static String pick(int x, int y, int z, String... opts) { return opts[(int) (hn(x, y, z) * opts.length) % opts.length]; }
    static String stone(int x, int y, int z) {
        double h = hn(x, y, z);
        if (h < .52) return "polished_blackstone_bricks"; if (h < .68) return "cracked_polished_blackstone_bricks"; if (h < .80) return "deepslate_bricks";
        if (h < .90) return "blackstone"; if (h < .96) return "cobbled_deepslate"; return "chiseled_polished_blackstone";
    }
    static String bone(int x, int y, int z) {
        double h = hn(x, y, z);
        if (h < .40) return "bone_block[axis=y]"; if (h < .62) return "calcite"; if (h < .80) return "smooth_quartz"; if (h < .92) return "white_terracotta"; return "diorite";
    }
    static String rock(int x, int y, int z) {
        double h = hn(x, y, z);
        if (h < .35) return "blackstone"; if (h < .6) return "basalt[axis=y]"; if (h < .8) return "smooth_basalt"; if (h < .93) return "cobbled_deepslate"; return "magma_block";
    }

    // ---------- finish ----------
    static boolean solid(int x, int y, int z) { return !air(x, y, z); }

    public static void main(String[] a) throws Exception { Graveyard.build(a); }
}
