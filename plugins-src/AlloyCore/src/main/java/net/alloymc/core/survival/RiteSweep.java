package net.alloymc.core.survival;

/**
 * The maths of the Last Rite wave, kept free of server code so it can be unit-tested (see RiteSweepTest).
 *
 * The wave is a flat wall, {@link #WIDTH} wide and {@link #HEIGHT} tall, that starts {@link #START} blocks in front of
 * the caster and travels along the caster's facing (always horizontal, whatever their pitch) at {@link #SPEED} blocks
 * a second, for {@link #RANGE} blocks. Each tick it sweeps the slab between where it was and where it is now, so a
 * fast wall can't skip over anyone. A player is caught when the centre of their feet is inside the swept slab and
 * inside the wall's width, and their body (feet to head) overlaps the wall's height.
 */
public final class RiteSweep {
    private RiteSweep() {}

    public static final double START = 1.0, RANGE = 30.0, SPEED = 20.0, WIDTH = 5.0, HEIGHT = 4.0;
    public static final double BODY = 1.8, BELOW = 0.5;          // the wall reaches half a block under the caster's feet

    /** Horizontal facing from a Minecraft yaw (degrees): {dx, dz}, unit length. Pitch is ignored on purpose. */
    public static double[] facing(float yaw) {
        double r = Math.toRadians(yaw);
        return new double[]{-Math.sin(r), Math.cos(r)};
    }

    /** How far along the path (blocks from the caster) the wall is after {@code ticks} ticks; capped at the range. */
    public static double reach(int ticks) {
        return Math.min(START + RANGE, START + SPEED * ticks / 20.0);
    }

    /** Ticks the wall needs to cover its whole range. */
    public static int travelTicks() { return (int) Math.ceil(RANGE / SPEED * 20); }

    /** Distance of point (px, pz) along the facing from the origin (negative = behind the caster). */
    public static double along(double ox, double oz, double[] f, double px, double pz) {
        return (px - ox) * f[0] + (pz - oz) * f[1];
    }

    /** Sideways distance of point (px, pz) from the wave's centre line (always positive). */
    public static double lateral(double ox, double oz, double[] f, double px, double pz) {
        return Math.abs((px - ox) * -f[1] + (pz - oz) * f[0]);
    }

    /**
     * Is a player standing at (px, py, pz) touched by the wall as it moves from {@code from} to {@code to} blocks along
     * the path? (ox, oy, oz) is the caster's feet when the wave launched.
     */
    public static boolean touches(double ox, double oy, double oz, double[] f, double from, double to, double px, double py, double pz) {
        double a = along(ox, oz, f, px, pz);
        if (a < Math.min(from, to) || a > Math.max(from, to)) return false;
        if (lateral(ox, oz, f, px, pz) > WIDTH / 2) return false;
        double bottom = oy - BELOW, top = bottom + HEIGHT;
        return py + BODY > bottom && py < top;
    }
}
