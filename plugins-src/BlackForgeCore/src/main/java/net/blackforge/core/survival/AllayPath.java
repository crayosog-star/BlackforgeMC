package net.blackforge.core.survival;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Finds a way for a Forge Allay around solid blocks: a straight line when it's clear, otherwise a search over the blocks,
 * straightened and with its corners rounded so it flies rather than walks a grid. No Bukkit types, so it can be unit-tested.
 */
final class AllayPath {
    private AllayPath() {}

    /** Can an allay be in this block? */
    @FunctionalInterface interface Free { boolean at(int x, int y, int z); }

    private static final double SAMPLE_STEP = 0.25;
    private static final int ROUND_PASSES = 2, BOW_SEGMENTS = 12;
    private static final double MIN_BOW_LENGTH = 3, MAX_BOW = 2.5;
    private static final double[] BOW_SIDE = {1, -1, 0.45}, BOW_UP = {0.4, 0.8, 1.0};
    private static final int[][] STEPS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** A flight along points, measured so it can be followed at any speed. */
    static final class Route {
        final double[][] points; final double[] along; final double length;

        Route(List<double[]> pts) {
            points = pts.toArray(new double[0][]);
            along = new double[points.length];
            for (int i = 1; i < points.length; i++) along[i] = along[i - 1] + dist(points[i - 1], points[i]);
            length = along[points.length - 1];
        }

        /** Where it is this far along the route, held to the ends. */
        double[] at(double d) {
            if (d <= 0) return points[0].clone();
            for (int i = 1; i < points.length; i++) {
                if (d > along[i]) continue;
                double span = along[i] - along[i - 1], t = span <= 0 ? 0 : (d - along[i - 1]) / span;
                double[] a = points[i - 1], b = points[i];
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
            }
            return points[points.length - 1].clone();
        }

        boolean straight() { return points.length == 2; }
    }

    static double dist(double[] a, double[] b) {
        return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2));
    }

    /** Is the line between these clear? The block it starts in doesn't count, so one that's somehow in a block can still leave. */
    static boolean clear(double[] a, double[] b, Free free) {
        int n = Math.max(1, (int) Math.ceil(dist(a, b) / SAMPLE_STEP));
        int sx = cell(a[0]), sy = cell(a[1]), sz = cell(a[2]);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            int x = cell(a[0] + (b[0] - a[0]) * t), y = cell(a[1] + (b[1] - a[1]) * t), z = cell(a[2] + (b[2] - a[2]) * t);
            if ((x != sx || y != sy || z != sz) && !free.at(x, y, z)) return false;
        }
        return true;
    }

    /** The spot itself if it's free, or the centre of the nearest free block touching it, so a target in a tight spot can still be reached. */
    static double[] nudge(double[] to, Free free) {
        int x = cell(to[0]), y = cell(to[1]), z = cell(to[2]);
        if (free.at(x, y, z)) return to;
        for (int[] s : STEPS) {
            if (free.at(x + s[0], y + s[1], z + s[2])) return new double[]{x + s[0] + 0.5, y + s[1] + 0.5, z + s[2] + 0.5};
        }
        return to;
    }

    /**
     * A clear flight that curves its own way, so a flock doesn't fly in single file: each allay bows out to a different side
     * and height. If the curve would hit anything, or the trip is too short to bother, it's the straight line.
     */
    static Route bowed(double[] from, double[] to, int index, Free free) {
        double length = dist(from, to);
        if (length < MIN_BOW_LENGTH) return new Route(List.of(from, to));
        double side = BOW_SIDE[index % BOW_SIDE.length] * Math.min(length * 0.2, MAX_BOW), up = BOW_UP[index % BOW_UP.length] * Math.min(length * 0.12, MAX_BOW);
        double flat = Math.hypot(to[0] - from[0], to[2] - from[2]);
        double sx = flat < 1e-6 ? 1 : -(to[2] - from[2]) / flat, sz = flat < 1e-6 ? 0 : (to[0] - from[0]) / flat;
        List<double[]> pts = new ArrayList<>();
        pts.add(from);
        for (int i = 1; i < BOW_SEGMENTS; i++) {
            double t = (double) i / BOW_SEGMENTS, s = Math.sin(Math.PI * t);
            pts.add(new double[]{from[0] + (to[0] - from[0]) * t + sx * side * s, from[1] + (to[1] - from[1]) * t + up * s, from[2] + (to[2] - from[2]) * t + sz * side * s});
        }
        pts.add(to);
        for (int i = 1; i < pts.size(); i++) if (!clear(pts.get(i - 1), pts.get(i), free)) return new Route(List.of(from, to));
        return new Route(pts);
    }

    /** Whether a search is worth starting: true when the straight line is clear and no search is needed. */
    static boolean needsSearch(double[] from, double[] to, Free free) { return !clear(from, to, free); }

    /** A way between two spots, or null if there's none within {@code maxNodes} blocks of searching. */
    static Route route(double[] from, double[] to, Free free, int maxNodes) {
        if (clear(from, to, free)) return new Route(List.of(from, to));
        List<double[]> grid = search(from, to, free, maxNodes);
        if (grid == null) return null;
        Budget work = new Budget(free, SMOOTH_LOOKUPS);
        return new Route(round(straighten(grid, work), work));
    }

    /** Block lookups straightening and rounding a route may make between them, so a long winding one can't cost a tick. */
    static final int SMOOTH_LOOKUPS = 3_000;

    /** Counts block lookups. Once it's spent, smoothing stops and keeps the points it has, which are already clear. */
    static final class Budget implements Free {
        private final Free free; private int left;
        Budget(Free free, int lookups) { this.free = free; this.left = lookups; }
        public boolean at(int x, int y, int z) { left--; return free.at(x, y, z); }
        boolean spent() { return left <= 0; }
    }

    /**
     * Trips that found no way lately, by base, start block and target block, so a sealed base or a target past the search
     * limit doesn't search again on every trip: those fly straight until the entry is forgotten, after {@code ttl} ticks.
     * The start counts too, so one allay stuck in a sealed spot doesn't send every other trip to that block through walls.
     */
    static final class Misses {
        private static final int MAX = 1024;
        private final long ttl;
        private final Map<String, Long> until = new HashMap<>();

        Misses(long ttl) { this.ttl = ttl; }

        static String key(String base, double[] from, double[] to) {
            return base + '|' + cell(from[0]) + ',' + cell(from[1]) + ',' + cell(from[2]) + '|' + cell(to[0]) + ',' + cell(to[1]) + ',' + cell(to[2]);
        }

        /** Did a search for this fail less than {@code ttl} ticks ago? */
        boolean recent(String key, long now) {
            Long u = until.get(key);
            if (u == null) return false;
            if (now < u) return true;
            until.remove(key);
            return false;
        }

        void add(String key, long now) {
            if (until.size() >= MAX) until.values().removeIf(u -> u <= now);
            if (until.size() >= MAX) until.clear();   // only if a thousand fresh misses at once: dropping them just means searching again
            until.put(key, now + ttl);
        }

        int size() { return until.size(); }
    }

    private record Node(long key, int x, int y, int z, double score) {}

    /** A* over blocks: straight-line distance guides it, a little greedily, because the route gets straightened afterwards anyway. */
    private static List<double[]> search(double[] from, double[] to, Free free, int maxNodes) {
        int sx = cell(from[0]), sy = cell(from[1]), sz = cell(from[2]), gx = cell(to[0]), gy = cell(to[1]), gz = cell(to[2]);
        long start = key(sx, sy, sz), goal = key(gx, gy, gz);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.score(), b.score()));
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Long> came = new HashMap<>();
        Set<Long> done = new HashSet<>();
        cost.put(start, 0.0);
        open.add(new Node(start, sx, sy, sz, 0));
        while (!open.isEmpty() && done.size() < maxNodes) {
            Node n = open.poll();
            if (!done.add(n.key())) continue;
            if (n.key() == goal) return walkBack(came, start, goal, from, to);
            double c = cost.get(n.key());
            for (int[] s : STEPS) {
                int x = n.x() + s[0], y = n.y() + s[1], z = n.z() + s[2];
                long k = key(x, y, z);
                if (done.contains(k) || !(k == goal || free.at(x, y, z))) continue;
                double next = c + 1;
                Double known = cost.get(k);
                if (known != null && known <= next) continue;
                cost.put(k, next);
                came.put(k, n.key());
                double h = Math.sqrt(Math.pow(x - gx, 2) + Math.pow(y - gy, 2) + Math.pow(z - gz, 2));
                open.add(new Node(k, x, y, z, next + 1.2 * h));
            }
        }
        return null;
    }

    private static List<double[]> walkBack(Map<Long, Long> came, long start, long goal, double[] from, double[] to) {
        List<double[]> out = new ArrayList<>();
        out.add(to);
        for (Long k = came.get(goal); k != null && k != start; k = came.get(k)) out.add(centre(k));
        out.add(from);
        Collections.reverse(out);
        return out;
    }

    /** Skips every point it can see past, until the budget runs out; after that it keeps the grid route as it is, just less smooth. */
    static List<double[]> straighten(List<double[]> pts, Budget free) {
        List<double[]> out = new ArrayList<>();
        int i = 0;
        out.add(pts.get(0));
        while (i < pts.size() - 1) {
            int far = pts.size() - 1;
            while (far > i + 1 && (free.spent() || !clear(pts.get(i), pts.get(far), free))) {
                if (free.spent()) { far = i + 1; break; }
                far--;
            }
            out.add(pts.get(far));
            i = far;
        }
        return out;
    }

    /** Cuts each corner, keeping a cut only if it stays out of the blocks, and always keeping the two ends. A spent budget keeps the corner. */
    private static List<double[]> round(List<double[]> pts, Budget free) {
        List<double[]> cur = pts;
        for (int pass = 0; pass < ROUND_PASSES; pass++) {
            ArrayDeque<double[]> out = new ArrayDeque<>();
            out.add(cur.get(0));
            for (int i = 1; i < cur.size() - 1; i++) {
                double[] a = lerp(cur.get(i), cur.get(i - 1), 0.25), b = lerp(cur.get(i), cur.get(i + 1), 0.25);
                if (!free.spent() && clear(a, b, free)) { out.add(a); out.add(b); }
                else out.add(cur.get(i));
            }
            out.add(cur.get(cur.size() - 1));
            cur = new ArrayList<>(out);
        }
        return cur;
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    private static int cell(double v) { return (int) Math.floor(v); }

    /** Packs a block position: 26 bits each way for x and z, and 12 for y. */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static double[] centre(long k) {
        int x = (int) (k >> 38), z = (int) (k << 26 >> 38), y = (int) (k << 52 >> 52);
        return new double[]{x + 0.5, y + 0.5, z + 0.5};
    }
}
