package net.alloymc.core.survival;

/**
 * Surge timing and drain maths (no Bukkit, unit tested). Time t is seconds since the surge began.
 * The drain rises linearly from 0.5 to 2.0 health per second over the 6 s, about 3.75 hearts for a full surge.
 */
public final class SurgeCurve {
    private SurgeCurve() {}

    public static final double MAX_SECONDS = 6.0;
    public static final double START_RATE = 0.5, END_RATE = 2.0;   // health (half-hearts) per second
    public static final double FLOOR_FRACTION = 0.5;                // never drains below half your max health
    public static final double START_MARGIN = 2.0;                  // need at least 1 heart above that floor to start
    public static final double WARNING_SECONDS = 1.5;               // last stretch: petals turn to ash

    /** The lowest the drain can take you: half your max health. */
    public static double floor(double maxHealth) { return maxHealth * FLOOR_FRACTION; }

    /** Surging costs something, so you need at least a heart above the floor to start. */
    public static boolean canStart(double health, double maxHealth) { return health >= floor(maxHealth) + START_MARGIN; }

    /** Drain rate at time t, in health per second. */
    public static double rate(double t) {
        double u = Math.max(0, Math.min(MAX_SECONDS, t));
        return START_RATE + (END_RATE - START_RATE) * u / MAX_SECONDS;
    }

    /** Total health drained from 0 to t (the integral of {@link #rate}). */
    public static double drained(double t) {
        double u = Math.max(0, Math.min(MAX_SECONDS, t));
        return START_RATE * u + (END_RATE - START_RATE) * u * u / (2 * MAX_SECONDS);
    }

    /** Health to take between t0 and t1 (one drain tick). */
    public static double drainBetween(double t0, double t1) {
        return Math.max(0, drained(t1) - drained(t0));
    }

    /** The action-bar meter: six cells, one per second left, e.g. "▰▰▰▱▱▱ 3.2s". */
    public static String meter(double secondsLeft) {
        double left = Math.max(0, Math.min(MAX_SECONDS, secondsLeft));
        int full = (int) Math.ceil(left - 1e-9);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < (int) MAX_SECONDS; i++) sb.append(i < full ? '▰' : '▱');
        return sb + " " + String.format(java.util.Locale.ROOT, "%.1f", left) + "s";
    }

    public static boolean warning(double t) { return t >= MAX_SECONDS - WARNING_SECONDS; }

    // ------------------------------------------------------------------ the red screen edges

    public static final double EDGE_BASE = 0.55, EDGE_BASE_WARN = 0.3;   // edge strength between heartbeats
    public static final double EDGE_MAX = 0.995;                         // 1.0 would need an infinite warning distance

    /**
     * Strength (0..1) of the red screen edges at a surge tick: a "lub-dub" throb on every heartbeat (every 20 ticks,
     * every 10 in the last stretch, matching the heartbeat sound), easing back to a steady base glow in between.
     */
    public static double edgeGlow(int ticks, boolean warn) {
        int k = Math.floorMod(ticks, warn ? 10 : 20);
        double lub = Math.exp(-k / 2.0);
        double dub = k >= 4 ? 0.8 * Math.exp(-(k - 4) / 2.0) : 0;
        double base = warn ? EDGE_BASE_WARN : EDGE_BASE;
        return base + (1 - base) * Math.max(lub, dub);
    }

    /**
     * World-border warning distance that gives the wanted edge strength. The client tints the screen edges red by
     * 1 - (distance to border / warning distance), so solve for the warning distance.
     */
    public static int warningDistance(double distToEdge, double strength) {
        double s = Math.max(0, Math.min(EDGE_MAX, strength));
        double d = Math.max(1, distToEdge);
        return (int) Math.min(1_000_000, Math.ceil(d / (1 - s)));
    }
}
