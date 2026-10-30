package net.alloymc.core.arcade.games.bedwars;

import java.util.Random;

/** The Golem's fight rules that are plain maths, kept apart from Bukkit so they can be unit-tested. */
final class GolemMath {
    private GolemMath() {}

    enum Move { SWIPE, SLAM, BOULDER, ROOTS, CHARGE, ROCKFALL }

    /** Starting health: a base plus a share for every player in the Undercroft when it wakes (at least one counted). */
    static int health(int playersBelow, int base, int perPlayer) { return base + perPlayer * Math.max(1, playersBelow); }

    /** Phase 1 above half health, 2 down to a fifth, 3 (enraged) below that. */
    static int phase(double health, double max) {
        double f = max <= 0 ? 0 : health / max;
        return f > 0.5 ? 1 : f > 0.2 ? 2 : 3;
    }

    /** Wind-up ticks before a move lands: shorter when enraged. */
    static int telegraph(Move m, int phase) {
        int t = switch (m) { case SWIPE -> 10; case SLAM -> 24; case BOULDER -> 20; case ROOTS -> 24; case CHARGE -> 20; case ROCKFALL -> 16; };
        return phase >= 3 ? Math.max(6, t * 2 / 3) : t;
    }

    /**
     * The Ground Slam shockwave: a ring that leaves the Golem at {@code speed} blocks a tick. It catches a player
     * {@code dist} blocks away at tick {@code t} when the ring is within half its width of them and they're on the
     * ground (a player who has jumped, {@code lift} more than 0.6 above the floor, lets it pass under).
     */
    static boolean ringHits(double dist, int t, double speed, double width, double lift, double maxRadius) {
        double r = t * speed;
        return r <= maxRadius && Math.abs(dist - r) <= width / 2 && lift < 0.6;
    }

    /** Keep (x, z), measured from the middle of the hall, inside a circle of radius {@code r}. */
    static double[] leash(double x, double z, double r) {
        double d = Math.hypot(x, z);
        if (d <= r || d == 0) return new double[]{x, z};
        return new double[]{x / d * r, z / d * r};
    }

    /** True if a point (dx, dz) from the Golem is within {@code halfAngle} degrees of where it faces (yaw, Minecraft style). */
    static boolean inFront(float yaw, double dx, double dz, double halfAngle) {
        double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));
        double d = Math.hypot(dx, dz);
        if (d < 1e-6) return true;
        double cos = (fx * dx + fz * dz) / d;
        return cos >= Math.cos(Math.toRadians(halfAngle));
    }

    /** Yaw (Minecraft style: 0 faces +z) that looks from the origin towards (dx, dz). */
    static float yawTo(double dx, double dz) { return (float) Math.toDegrees(Math.atan2(-dx, dz)); }

    /**
     * Pick the next special move. {@code dist}: to the target; {@code targetHigh}: the target is up on a ramp or ledge;
     * {@code below}: players in the Undercroft. Phase 1 knows Slam, Boulder Hurl and Root Snare; phase 2 adds Charge
     * and Rockfall. Players up high draw Boulder Hurl; a crowd close by draws Slam; a target across the hall draws Charge.
     */
    static Move pick(int phase, double dist, boolean targetHigh, int below, Random rnd) {
        if (targetHigh && rnd.nextInt(3) > 0) return Move.BOULDER;
        int r = rnd.nextInt(100);
        if (phase >= 2) {
            if (r < 12) return Move.ROCKFALL;
            if (dist > 6 && r < 45) return Move.CHARGE;
        }
        if (dist <= 6 && r < 60) return Move.SLAM;
        if (below >= 2 && r < 80) return Move.ROOTS;
        return dist > 6 ? Move.BOULDER : Move.SLAM;
    }

    /** Ticks between special moves: about 4 s in phase 1, 3 s in phase 2, 2.2 s enraged. */
    static int moveGap(int phase) { return phase == 1 ? 80 : phase == 2 ? 60 : 44; }

    /** Where a lobbed boulder is, {@code f} (0..1) of the way from (x0,y0,z0) to (x1,y1,z1), arcing {@code h} high. */
    static double[] arc(double x0, double y0, double z0, double x1, double y1, double z1, double h, double f) {
        return new double[]{x0 + (x1 - x0) * f, y0 + (y1 - y0) * f + 4 * h * f * (1 - f), z0 + (z1 - z0) * f};
    }
}
