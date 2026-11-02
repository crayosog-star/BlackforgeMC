package net.alloymc.core.arcade.games.bedwars;

/**
 * The Golem's entrance as plain maths, so the tests and the offline render use exactly what the game does.
 *
 * <ol>
 *   <li>Tremor (0 to {@link #BREACH}): the hall shakes, the wall at the breach spot cracks wider and wider and pink light
 *   leaks through. The Golem waits inside the rock.</li>
 *   <li>Breach: the wall bursts. A hole {@link #HALF_WIDTH}x2 wide, {@link #HEIGHT}+1 high and {@link #DEPTH} deep is
 *   carved out, its blocks thrown into the hall as rubble, and the Golem lunges out into the opening.</li>
 *   <li>Roar, then a crouch, then a leap across the hall that hangs at the top and comes down hard on the launch pad
 *   (knocks players back, no damage).</li>
 *   <li>Behind it the hole caves in: rubble falls and fills it, back to front and bottom up, until the wall is sealed.</li>
 * </ol>
 * Times are ticks from the start of the intro.
 */
final class GolemIntro {
    private GolemIntro() {}

    static final int BREACH = 50, LUNGE = 8, ROAR = 60, CROUCH = 94, LEAP = 104, LAND = 126, SEAL = 108, SEAL_TICKS = 34, END = 160;
    static final double DEPTH = 6.4, HALF_WIDTH = 4.2, HEIGHT = 7, START_BACK = 4.3, OUT = 1.2, APEX = 6.5;

    /**
     * Is the block whose centre is (dx, dz) from the breach spot, {@code dy} rows above the floor, part of the hole?
     * ({@code inX}, {@code inZ}): unit vector from the wall into the hall. The top is arched.
     */
    static boolean inHole(double dx, double dz, int dy, double inX, double inZ) {
        double into = -(dx * inX + dz * inZ), side = -dx * inZ + dz * inX;
        double w = HALF_WIDTH - (dy >= 5 ? (dy - 4) * 1.1 : 0);
        return dy >= 0 && dy <= HEIGHT && into >= -0.5 && into <= DEPTH && Math.abs(side) <= w;
    }

    /** How far into the wall a hole block is (0 = the face): the hole fills deepest first. */
    static double depth(double dx, double dz, double inX, double inZ) { return -(dx * inX + dz * inZ); }

    /** Fast, a hang in the middle, fast again: time remapping for a leap or a throw. 0 to 1 in, 0 to 1 out. */
    static double hang(double f, double a) { f = Math.max(0, Math.min(1, f)); return f + a * Math.sin(2 * Math.PI * f) / (2 * Math.PI); }

    static double easeOut(double u) { u = Math.max(0, Math.min(1, u)); return 1 - Math.pow(1 - u, 3); }

    /**
     * Where the Golem stands {@code s} ticks into the intro: {x, height above the floor, z}. (bx, bz): the breach spot;
     * (inX, inZ): into the hall; (hx, hz): the middle of the hall, where it lands.
     */
    static double[] position(int s, double bx, double bz, double inX, double inZ, double hx, double hz) {
        double sx = bx - inX * START_BACK, sz = bz - inZ * START_BACK, ox = bx + inX * OUT, oz = bz + inZ * OUT;
        if (s < BREACH) return new double[]{sx, 0, sz};
        if (s < BREACH + LUNGE) { double u = easeOut((s - BREACH) / (double) LUNGE); return new double[]{sx + (ox - sx) * u, 0, sz + (oz - sz) * u}; }
        if (s < LEAP) return new double[]{ox, 0, oz};
        if (s < LAND) {
            double u = hang((s - LEAP) / (double) (LAND - LEAP), 0.75);
            return new double[]{ox + (hx - ox) * u, 4 * APEX * u * (1 - u), oz + (hz - oz) * u};
        }
        return new double[]{hx, 0, hz};
    }
}
