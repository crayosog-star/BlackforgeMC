package net.blackforge.core.survival;

/**
 * How a Forge Allay gets from one point to another: an eased start and stop, so it never snaps into motion, and a small
 * lift in the middle of the trip, so it reads as flying rather than sliding. No Bukkit types, so it can be unit-tested.
 */
final class AllayFlight {
    private AllayFlight() {}

    private static final int MIN_TICKS = 10;
    /** The most it lifts on a long trip, in blocks. */
    static final double MAX_LIFT = 1.5;

    /** Ticks to cover this distance at this average speed in blocks a second. */
    static int ticks(double distance, double speed) {
        if (speed <= 0) throw new IllegalArgumentException("speed must be above 0: " + speed);
        return Math.max(MIN_TICKS, (int) Math.ceil(Math.abs(distance) / speed * 20));
    }

    /** Slow, fast, slow from 0 to 1. Held to the ends past either side, so it can't overshoot. */
    static double ease(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }

    /** Blocks above the straight line at time t: nothing at either end, most in the middle. */
    static double lift(double t, double distance) {
        double c = Math.max(0, Math.min(1, t));
        return Math.min(MAX_LIFT, Math.abs(distance) / 8) * Math.sin(Math.PI * c);
    }

    /** Offset from its idle spot above the Station at this tick: a slow circle with its flock, evenly spaced, and a gentle bob. */
    static double[] hover(int index, int flock, long tick) {
        double angle = 2 * Math.PI * index / Math.max(1, flock) + tick * 0.015;
        return new double[]{Math.cos(angle) * 1.4, Math.sin(tick * 0.05 + index) * 0.25, Math.sin(angle) * 1.4};
    }

    /** Where it is at this tick of the trip, as {x, y, z}. */
    static double[] at(double[] from, double[] to, int tick, int ticks) {
        double t = (double) tick / ticks, e = ease(t);
        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        return new double[]{from[0] + dx * e, from[1] + dy * e + lift(t, Math.sqrt(dx * dx + dy * dy + dz * dz)), from[2] + dz * e};
    }
}
