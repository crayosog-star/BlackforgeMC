package net.alloymc.core.survival;

/**
 * Pure maths for the Ashen Chain: how hard a hooked target is dragged in, and how the caster is reeled to an anchor.
 * No Bukkit types, so it can be unit tested without a server. Vectors are plain doubles; velocities are blocks per tick.
 */
public final class ChainPull {
    private ChainPull() {}

    /** How far the chain reaches when it hits a block (you drag yourself there). */
    public static final double GRAPPLE_RANGE = 24;
    /** How far the chain reaches when it hooks a player or mob (you drag them to you). */
    public static final double YANK_RANGE = 12;
    /** A dragged target lands about this far in front of the caster. */
    public static final double YANK_STOP = 2.0;
    /** The most a target is ever dragged in one hook. */
    public static final double YANK_MAX = 10.0;
    /** Upward hop at the start of a drag, so they come off the ground. */
    public static final double YANK_LIFT = 0.42;
    /**
     * Ground distance travelled per unit of starting horizontal speed with {@link #YANK_LIFT} (vanilla air drag 0.91,
     * gravity 0.08, then ground friction 0.546). Measured by simulation: 0.5 blocks/tick -> 3.98 blocks.
     */
    public static final double BLOCKS_PER_SPEED = 7.95;

    /** How fast the caster is reeled in towards the anchor. */
    public static final double REEL_SPEED = 1.1;
    /** Added to the upward speed every tick while reeling, to cancel gravity. */
    public static final double REEL_ANTI_GRAVITY = 0.08;
    /** Reeling stops this close to the anchor. */
    public static final double ARRIVE_DIST = 1.6;
    /** Reeling gives up after this many ticks (2 s). */
    public static final int REEL_MAX_TICKS = 40;

    /** A hooked player is pulled exactly this far towards the caster: a step, not a drag. */
    public static final double PLAYER_PULL = 1.0;
    /** The pull never brings them closer than this to the caster (no stepping into each other). */
    public static final double PULL_MIN_GAP = 1.0;
    /** The burning tether on a hooked player snaps if they get further than this from the caster. */
    public static final double TETHER_SNAP = YANK_RANGE + 4;

    /** How far a player standing {@code dist} blocks (horizontally) from the caster is pulled: 1 block, less if already close. */
    public static double pullStep(double dist) {
        return Math.max(0, Math.min(PLAYER_PULL, dist - PULL_MIN_GAP));
    }

    /** The tether holds while they're within {@link #TETHER_SNAP} of the caster. */
    public static boolean tetherHolds(double dist) { return dist <= TETHER_SNAP; }

    /** How far a target {@code dist} blocks away gets dragged towards the caster. */
    public static double yankDistance(double dist) {
        return Math.max(0.5, Math.min(YANK_MAX, dist - YANK_STOP));
    }

    /**
     * The drag velocity {x, y, z} for a target at horizontal offset (dx, dz) from the caster (target minus caster),
     * {@code dist} blocks away in total. Points from the target back to the caster.
     */
    public static double[] yank(double dx, double dz, double dist) {
        return pull(dx, dz, yankDistance(dist));
    }

    /** The velocity that hops a target at offset (dx, dz) from the caster about {@code blocks} towards them. */
    public static double[] pull(double dx, double dz, double blocks) {
        double h = Math.sqrt(dx * dx + dz * dz);
        double speed = blocks / BLOCKS_PER_SPEED;
        if (h < 0.05) return new double[] {0, YANK_LIFT, 0};   // straight above/below: just lift
        return new double[] {-dx / h * speed, YANK_LIFT, -dz / h * speed};
    }

    /** The reel velocity {x, y, z} for a caster at offset (dx, dy, dz) from the anchor (anchor minus caster). */
    public static double[] reel(double dx, double dy, double dz) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-6) return new double[] {0, REEL_ANTI_GRAVITY, 0};
        double s = Math.min(REEL_SPEED, d) / d;   // never overshoot in one tick
        return new double[] {dx * s, dy * s + REEL_ANTI_GRAVITY, dz * s};
    }

    /** True once the caster is close enough to the anchor to let go. */
    public static boolean arrived(double dist) { return dist <= ARRIVE_DIST; }
}
