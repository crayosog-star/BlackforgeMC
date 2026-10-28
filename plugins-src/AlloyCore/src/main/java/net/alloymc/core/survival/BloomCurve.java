package net.alloymc.core.survival;

/**
 * Timing for the Potion of Gaia: how big the mist is and how high the platform is at each moment.
 * Pure maths (no Bukkit), so it can be unit tested. Time is in seconds after the splash.
 *
 *   grow   0.0 - 2.5   radius 0.5 -> 3.5, cubic ease-in: barely moves at first, then faster and faster
 *   bloom  2.5 - 3.0   overshoots to 4.6, then snaps back to 3.5
 *   rise   3.0 - 5.5   platform climbs 8 blocks, easing in and out
 *   hold   5.5 - 11.5  stays at the top
 *   fade  11.5 - 13.0  shrinks away
 */
public final class BloomCurve {
    private BloomCurve() {}

    public static final double GROW_END = 2.5, BLOOM_PEAK = 2.75, BLOOM_END = 3.0, RISE_END = 5.5, HOLD_END = 11.5, FADE_END = 13.0;
    public static final double START_RADIUS = 0.5, RADIUS = 3.5, BLOOM_RADIUS = 4.6;
    public static final double MAX_RISE = 8.0;

    public enum Phase { GROW, BLOOM, RISE, HOLD, FADE, DONE }

    public static Phase phase(double t) {
        if (t < GROW_END) return Phase.GROW;
        if (t < BLOOM_END) return Phase.BLOOM;
        if (t < RISE_END) return Phase.RISE;
        if (t < HOLD_END) return Phase.HOLD;
        if (t < FADE_END) return Phase.FADE;
        return Phase.DONE;
    }

    /** Mist radius in blocks. */
    public static double radius(double t) {
        if (t <= 0) return START_RADIUS;
        if (t < GROW_END) { double u = t / GROW_END; return START_RADIUS + (RADIUS - START_RADIUS) * u * u * u; }
        if (t < BLOOM_PEAK) return RADIUS + (BLOOM_RADIUS - RADIUS) * (t - GROW_END) / (BLOOM_PEAK - GROW_END);
        if (t < BLOOM_END) return BLOOM_RADIUS - (BLOOM_RADIUS - RADIUS) * (t - BLOOM_PEAK) / (BLOOM_END - BLOOM_PEAK);
        if (t < HOLD_END) return RADIUS;
        if (t < FADE_END) return RADIUS * (1 - (t - HOLD_END) / (FADE_END - HOLD_END));
        return 0;
    }

    /** How far the mist has grown, 0..1, for the rising pitch of the chimes. */
    public static double growProgress(double t) {
        return Math.max(0, Math.min(1, t / GROW_END));
    }

    /** Platform height above the ground in blocks, capped by {@code cap} (headroom above the cloud). */
    public static double height(double t, double cap) {
        double max = Math.min(MAX_RISE, cap);
        if (t <= BLOOM_END) return 0;
        if (t >= RISE_END) return max;
        double u = (t - BLOOM_END) / (RISE_END - BLOOM_END);
        return max * u * u * (3 - 2 * u);   // smoothstep: eases in and out, so riders aren't jolted
    }

    /** Rising speed in blocks per second at time t (the derivative of {@link #height}). */
    public static double riseSpeed(double t, double cap) {
        if (t <= BLOOM_END || t >= RISE_END) return 0;
        double max = Math.min(MAX_RISE, cap), span = RISE_END - BLOOM_END;
        double u = (t - BLOOM_END) / span;
        return max * 6 * u * (1 - u) / span;
    }
}
