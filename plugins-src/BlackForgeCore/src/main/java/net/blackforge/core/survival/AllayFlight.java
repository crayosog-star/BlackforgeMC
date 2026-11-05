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

    /** How a flock idles at its Station: the circle when there's room, otherwise packed into whatever space there is. */
    enum Hover { CIRCLE, ROW_X, ROW_Z, STACK, CENTRE }

    /** Between allays packed side by side or stacked. */
    static final double TIGHT_SPACING = 0.7;
    /** Half an allay's width and its height, with a little to spare. */
    private static final double HALF_WIDTH = 0.2, HEIGHT = 0.6;
    private static final Hover[] TIGHT_TRIES = {Hover.ROW_X, Hover.ROW_Z, Hover.STACK};

    /** Offset from the tight idle spot for this layout. A row is centred on it; a stack goes up from it. */
    static double[] tightOffset(Hover h, int index, int flock) {
        double o = (index - (flock - 1) / 2.0) * TIGHT_SPACING;
        return switch (h) {
            case ROW_X -> new double[]{o, 0, 0};
            case ROW_Z -> new double[]{0, 0, o};
            case STACK -> new double[]{0, index * TIGHT_SPACING, 0};
            default -> new double[]{0, 0, 0};
        };
    }

    /**
     * The first packed layout that keeps every allay's whole body out of the blocks: a row along x, a row along z, then a
     * stack. If none fits, they share the centre, since a teleport ignores blocks and would show them half inside a wall.
     */
    static Hover tightLayout(double[] centre, int flock, AllayPath.Free free) {
        for (Hover h : TIGHT_TRIES) {
            boolean fits = true;
            for (int i = 0; i < flock && fits; i++) {
                double[] o = tightOffset(h, i, flock);
                fits = bodyFree(new double[]{centre[0] + o[0], centre[1] + o[1], centre[2] + o[2]}, free);
            }
            if (fits) return h;
        }
        return Hover.CENTRE;
    }

    /** Is an allay standing at this spot (its feet) clear of blocks on every side? */
    static boolean bodyFree(double[] p, AllayPath.Free free) {
        for (double dx : new double[]{-HALF_WIDTH, HALF_WIDTH}) for (double dz : new double[]{-HALF_WIDTH, HALF_WIDTH}) for (double dy : new double[]{0, HEIGHT})
            if (!free.at((int) Math.floor(p[0] + dx), (int) Math.floor(p[1] + dy), (int) Math.floor(p[2] + dz))) return false;
        return true;
    }

    /** A colour around the rainbow as packed RGB, for a Steward's trail. {@code t} wraps, so it can just keep counting. */
    static int rainbow(double t) {
        double h = (t - Math.floor(t)) * 6;
        int sector = (int) h;
        double f = h - sector;
        double[] rgb = switch (sector) {
            case 0 -> new double[]{1, f, 0};
            case 1 -> new double[]{1 - f, 1, 0};
            case 2 -> new double[]{0, 1, f};
            case 3 -> new double[]{0, 1 - f, 1};
            case 4 -> new double[]{f, 0, 1};
            default -> new double[]{1, 0, 1 - f};
        };
        return (int) Math.round(rgb[0] * 255) << 16 | (int) Math.round(rgb[1] * 255) << 8 | (int) Math.round(rgb[2] * 255);
    }

    /** Where a flock member idles above the Station block at (x, y, z): its packed spot, or its place on the slow circle. */
    static double[] idleSpot(double x, double y, double z, Hover h, int index, int flock, long tick) {
        if (h != Hover.CIRCLE) {
            double[] c = tightSpot(x, y, z), o = tightOffset(h, index, flock);
            return new double[]{c[0] + o[0], c[1] + o[1], c[2] + o[2]};
        }
        double[] o = hover(index, flock, tick);
        return new double[]{x + 0.5 + o[0], y + 2.2 + o[1], z + 0.5 + o[2]};
    }

    /** The middle of a packed flock, just above the Station block at (x, y, z). */
    static double[] tightSpot(double x, double y, double z) { return new double[]{x + 0.5, y + 1.3, z + 0.5}; }

    /** Ticks between wing poses. */
    static final int FLAP_TICKS = 2;
    /** Closed, half, swept, half: it steps through each one, because a jump between two far-apart poses looks like two pairs of wings. */
    private static final int[] FLAP_ORDER = {0, 1, 2, 1};
    /** The top tier is a little bigger. */
    static final float STEWARD_SCALE = 1.15f;

    /** Does this allay change wing pose this tick? Its index staggers a flock so they don't flap in step. */
    static boolean flapsNow(long now, int index) { return (now + index) % FLAP_TICKS == 0; }

    /** The step after this one in the flap cycle. */
    static int nextFlap(int flap) { return (flap + 1) % FLAP_ORDER.length; }

    /** Which of a tier's three wing models this step of the cycle shows. */
    static int flapFrame(int flap) { return FLAP_ORDER[flap]; }

    /** How big a tier's skin is drawn. */
    static float skinScale(int tier) { return tier >= 3 ? STEWARD_SCALE : 1f; }

    /** How far a skin turns about z: half a turn when the allay is flipped upside down, otherwise none. */
    static float skinRoll(boolean flip) { return flip ? (float) Math.PI : 0f; }

    /** Which way is to one side of travel along (dx, dz), as {x, z}: along x when it's moving straight up or down. */
    static double[] side(double dx, double dz) {
        double flat = Math.hypot(dx, dz);
        return flat < 1e-6 ? new double[]{1, 0} : new double[]{-dz / flat, dx / flat};
    }

    /** Where it is at this tick of the trip, as {x, y, z}. */
    static double[] at(double[] from, double[] to, int tick, int ticks) {
        double t = (double) tick / ticks, e = ease(t);
        double dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
        return new double[]{from[0] + dx * e, from[1] + dy * e + lift(t, Math.sqrt(dx * dx + dy * dy + dz * dz)), from[2] + dz * e};
    }
}
