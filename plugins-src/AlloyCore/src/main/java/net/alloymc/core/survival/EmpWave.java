package net.alloymc.core.survival;

import org.bukkit.Location;

/**
 * The EMP's maths, kept apart so it can be tested without a server: the rise to the detonation, how the wave grows,
 * and what counts as inside it. The blast is a flat-ish disc: everything within {@code radius} blocks sideways of the
 * detonation (so a 20-block-wide circle at the default 10) and within {@link #VERTICAL} blocks up or down.
 */
public final class EmpWave {
    private EmpWave() {}

    public static final double DEFAULT_RADIUS = 10.0;
    /** How far up or down from the detonation still counts (the caster detonates ~5 blocks above the ground). */
    public static final double VERTICAL = 12.0;
    /** The wave takes this many ticks to reach the edge (0.4 s): quick enough to read as instant. */
    public static final int EXPAND_TICKS = 8;
    /** Upward launch speed: about 5 blocks of height, apex after ~0.6 s. */
    public static final double LAUNCH_UP = 0.95;
    /** Detonate once you've nearly stopped rising, or after this many ticks whatever happens (a low ceiling). */
    public static final double APEX_VY = 0.08;
    public static final int MAX_RISE_TICKS = 14;

    /** Ease-out: the wave leaves fast and slows as it reaches the edge. {@code tick} 0..EXPAND_TICKS. */
    public static double radius(int tick, double maxRadius) {
        double x = Math.max(0, Math.min(1, tick / (double) EXPAND_TICKS));
        double inv = 1 - x;
        return maxRadius * (1 - inv * inv * inv);
    }

    /** Is a point {@code (dx, dy, dz)} away from the detonation inside a wave of radius {@code r}? {@code pad}: the thing's own size. */
    public static boolean inside(double dx, double dy, double dz, double r, double pad) {
        return dx * dx + dz * dz <= (r + pad) * (r + pad) && Math.abs(dy) <= VERTICAL;
    }

    /** Same, for two locations; never across worlds. */
    public static boolean reaches(Location center, Location at, double r, double pad) {
        if (center == null || at == null || center.getWorld() != at.getWorld()) return false;
        return inside(at.getX() - center.getX(), at.getY() - center.getY(), at.getZ() - center.getZ(), r, pad);
    }

    public static boolean reaches(Location center, Location at, double r) { return reaches(center, at, r, 0); }

    /** Time to detonate: {@code vy} (blocks risen this tick) has dropped to almost nothing, or the rise has gone on long enough. */
    public static boolean detonateNow(int riseTick, double vy) {
        return riseTick >= MAX_RISE_TICKS || (riseTick >= 3 && vy <= APEX_VY);
    }

    /** {@code n} directions spread evenly over a sphere (Fibonacci lattice): unit {x, y, z}. */
    public static double[][] sphere(int n) {
        double[][] out = new double[n][];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = n == 1 ? 0 : 1 - 2.0 * i / (n - 1);
            double r = Math.sqrt(Math.max(0, 1 - y * y));
            double a = golden * i;
            out[i] = new double[]{r * Math.cos(a), y, r * Math.sin(a)};
        }
        return out;
    }
}
