package net.blackforge.core.arcade.games.bedwars;

/**
 * The curves behind the Golem's thrown and falling things (shards, boulder, rocks), as plain maths so the tests check what
 * the game does. Everything a projectile does with its body (turning, spinning, stretching) is eased and updated every
 * tick; only its path is allowed to be sharp.
 *
 * <p>A shard's timeline from the tick it flies off its crystal: {@link #PEEL} ticks swooping up to a spot above and behind
 * the Golem, {@link #CHARGE} ticks hovering there and charging (swelling, spinning faster, light gathering in), a smooth
 * {@link #TURN} onto its target (still tracking), then {@link #LOCK} ticks pulled back like a drawn bow pointing at where
 * the target was (so a quick player can dodge), then it crawls forward and whips away ({@link GolemMath#shardSpeed}).
 */
final class GolemProjectile {
    private GolemProjectile() {}

    static final int PEEL = 8, CHARGE = 10, TURN = 5, LOCK = 4, FIRE_DELAY = PEEL + CHARGE + TURN + LOCK;

    /** A roll faster than this per tick (radians) would alias: a smoothly turning shard would look like it spins backwards. */
    static final double MAX_ROLL = 1.2;

    /** How fast a shard rolls about its own length (radians per tick) at this flight speed: lazy while it creeps, a blur at full speed. */
    static double rollRate(double speed) { return Math.min(MAX_ROLL, 0.15 + 0.5 * speed); }

    /** How much longer a shard looks at this speed (1 = at rest): it stretches into a streak as it whips forward. */
    static double stretch(double speed) { return 1 + 0.9 * Math.min(1, Math.max(0, speed) / 2.4); }

    /** Smoothstep 0 to 1. */
    static double smooth(double u) { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); }

    /** Where a shard's turn onto its target is, 0 to 1, {@code ticksIntoTurn} ticks in: eases in and out. */
    static double turn(double ticksIntoTurn) { return smooth(ticksIntoTurn / TURN); }

    /** Spherical interpolation between two unit vectors (f = 0 gives a, 1 gives b), constant angular speed in f. */
    static double[] slerp(double[] a, double[] b, double f) {
        double dot = Math.max(-1, Math.min(1, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]));
        if (dot > 0.9995) return norm(new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f});
        if (dot < -0.9995) {   // pointing the opposite way: turn through any sideways direction
            double[] side = Math.abs(a[1]) < 0.9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
            double[] p = norm(new double[]{a[1] * side[2] - a[2] * side[1], a[2] * side[0] - a[0] * side[2], a[0] * side[1] - a[1] * side[0]});
            double ang = Math.PI * f;
            return new double[]{a[0] * Math.cos(ang) + p[0] * Math.sin(ang), a[1] * Math.cos(ang) + p[1] * Math.sin(ang), a[2] * Math.cos(ang) + p[2] * Math.sin(ang)};
        }
        double th = Math.acos(dot), sa = Math.sin((1 - f) * th) / Math.sin(th), sb = Math.sin(f * th) / Math.sin(th);
        return new double[]{a[0] * sa + b[0] * sb, a[1] * sa + b[1] * sb, a[2] * sa + b[2] * sb};
    }

    private static double[] norm(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return l < 1e-9 ? new double[]{0, 0, 1} : new double[]{v[0] / l, v[1] / l, v[2] / l};
    }

    /**
     * The boulder's tumble (radians) at {@code f} of its flight (the time-remapped progress, so it spins fast off the hand,
     * slows at the top of the arc and picks up again as it comes down).
     */
    static double boulderSpin(double f) { return Math.max(0, Math.min(1, f)) * 11; }

    /** A falling rock's tumble (radians) {@code u} (0..1) of the way down. */
    static double rockSpin(double u) { return Math.max(0, Math.min(1, u)) * 3.2; }

    /** A warning ring closing in on the spot: slow at first, quick at the end. {@code f} 0..1. */
    static double ringRadius(double f, double wide, double tight) {
        f = Math.max(0, Math.min(1, f));
        return tight + (wide - tight) * (1 - f * f);
    }

    /** The swoop off the back: bursts out, overshoots its spot a little and settles. 0 to 1 (peaks about 1.07 on the way). */
    static double peel(double u) { return GolemAnim.easeOutBack(Math.max(0, Math.min(1, u)), 1.1); }

    /** Charge level 0 to 1 {@code u} of the way through the charge: builds slowly, then quickly. */
    static double charge(double u) { u = Math.max(0, Math.min(1, u)); return u * u * u; }

    /** How far back a shard is drawn (0..1) {@code u} of the way through the lock: it eases back like a bowstring. */
    static double pullBack(double u) { u = Math.max(0, Math.min(1, u)); return u * u; }

    /**
     * Where shard {@code i} of {@code n} hovers, relative to the Golem (x sideways, y up, z forward; negative z is behind
     * it): a wide fan over and behind it. Neighbours alternate between a near and a far arc and step up and down, so the
     * shards spread out in all three directions instead of bunching up on its back. Mirror-symmetric left to right.
     */
    static double[] hoverSlot(int i, int n) {
        double f = n <= 1 ? 0.5 : i / (double) (n - 1), a = Math.toRadians(-82 + 164 * f);
        int k = Math.min(i, n - 1 - i);   // the same for a shard and its mirror image
        double r = k % 2 == 0 ? 5.0 : 6.6, lift = (k % 3) * 0.9;
        return new double[]{Math.sin(a) * r, 7.0 + 1.8 * Math.cos(a) + lift, -2.4 - Math.cos(a) * r * 0.55};
    }

    /** How far a falling rock has dropped (0..1) {@code u} of the way down: a slow start, a savage finish. */
    static double rockDrop(double u) { u = Math.max(0, Math.min(1, u)); return u * u * (0.4 + 0.6 * u); }
}
