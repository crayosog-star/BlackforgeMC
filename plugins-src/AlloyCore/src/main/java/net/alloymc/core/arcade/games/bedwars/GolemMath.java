package net.alloymc.core.arcade.games.bedwars;

import java.util.Random;

/** The Golem's fight rules that are plain maths, kept apart from Bukkit so they can be unit-tested. */
final class GolemMath {
    private GolemMath() {}

    enum Move { SWIPE, SLAM, BOULDER, ROOTS, CHARGE, ROCKFALL, ERUPTION, SHARDS }

    /** Starting health: a base plus a share for every player in the Undercroft when it wakes (at least one counted). */
    static int health(int playersBelow, int base, int perPlayer) { return base + perPlayer * Math.max(1, playersBelow); }

    /** Phase 1 above half health, 2 down to a fifth, 3 (enraged) below that. */
    static int phase(double health, double max) {
        double f = max <= 0 ? 0 : health / max;
        return f > 0.5 ? 1 : f > 0.2 ? 2 : 3;
    }

    /** Wind-up ticks before a move lands: shorter when enraged. */
    static int telegraph(Move m, int phase) {
        int t = switch (m) { case SWIPE -> 10; case SLAM -> 24; case BOULDER -> 20; case ROOTS -> 24; case CHARGE -> 20; case ROCKFALL -> 16; case ERUPTION -> 22; case SHARDS -> 18; };
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
     * {@code below}: players in the Undercroft. Phase 1 knows Slam, Boulder Hurl, Root Snare, Crystal Eruption and
     * Shard Volley; phase 2 adds Charge and Rockfall. Players up high or far off draw the ranged moves (Shard Volley,
     * Boulder Hurl); a crowd close by draws Slam; mid range draws Crystal Eruption; across the hall draws Charge.
     */
    static Move pick(int phase, double dist, boolean targetHigh, int below, Random rnd) {
        if (targetHigh) {
            int h = rnd.nextInt(10);
            if (h < 5) return Move.SHARDS;
            if (h < 8) return Move.BOULDER;
        }
        int r = rnd.nextInt(100);
        if (phase >= 2) {
            if (r < 12) return Move.ROCKFALL;
            if (dist > 6 && r < 40) return Move.CHARGE;
        }
        if (r >= 40 && r < 62 && dist >= 3 && dist <= 11) return Move.ERUPTION;
        if (dist > 9 && r < 75) return Move.SHARDS;
        if (dist <= 6 && r < 62) return Move.SLAM;
        if (below >= 2 && r < 80) return Move.ROOTS;
        return dist > 6 ? (rnd.nextBoolean() ? Move.BOULDER : Move.SHARDS) : Move.SLAM;
    }

    /** Crystal Eruption: the lanes of spikes, as angles (degrees) off the line to the target. */
    static double[] eruptionLanes(int phase) { return phase >= 3 ? new double[]{-60, -30, 0, 30, 60} : new double[]{-30, 0, 30}; }

    /** Crystal Eruption: spikes along each lane every {@code SPIKE_GAP} blocks, from 1.5 out to 12; each hurts within {@code SPIKE_HIT}. */
    static final double SPIKE_GAP = 1.2, SPIKE_HIT = 0.8, ERUPTION_REACH = 12;

    /**
     * Where a target will be when a shard gets there: aim {@code lead} of the way along its current velocity
     * (0 = where it stands, 1 = full lead). Returns {x, y, z}.
     */
    static double[] leadAim(double px, double py, double pz, double vx, double vy, double vz, double dist, double speed, double lead) {
        double ticks = dist / speed;
        return new double[]{px + vx * ticks * lead, py + Math.max(0, vy) * ticks * lead * 0.5, pz + vz * ticks * lead};
    }

    /** Rimelock: while the Golem is in the cold everything it does is this much slower (1 = normal). */
    static final double CHILL_SLOW = 0.55;

    /** Potion of Darkness: how long the Golem is blinded (it loses every target and wanders) and how slow it is meanwhile. */
    static final int BLIND_TICKS = 100;
    static final double BLIND_SLOW = 0.6;

    /** Shard Volley: damage per shard, the most one player can take from one volley, and how fast a shard flies (blocks a tick). */
    static final double SHARD_DAMAGE = 2, SHARD_CAP = 6;

    /** Ticks a shard takes to go from a crawl to full speed. */
    static final int SHARD_RAMP = 11;

    /**
     * Time-remapped flight: a shard crawls off, then speeds up quickly (speed grows with the cube of time) to its top speed
     * of 2.4 blocks a tick. Slow enough at the start to see it go, savage by the end.
     */
    static double shardSpeed(int ticksFlying) {
        double u = Math.min(1, Math.max(0, ticksFlying) / (double) SHARD_RAMP);
        return Math.min(2.4, 0.08 + 2.4 * u * u * u);
    }

    /** Ticks a shard needs to cover {@code dist} blocks under {@link #shardSpeed}. */
    static double shardTravelTicks(double dist) {
        double left = dist;
        for (int i = 0; i < 200; i++) {
            double v = shardSpeed(i);
            if (left <= v) return i + left / v;
            left -= v;
        }
        return 200 + left / 2.4;
    }

    /** The speed that covers {@code dist} in the same time as the real ramp: what to lead a moving target by. */
    static double shardEffectiveSpeed(double dist) { return dist <= 0 ? shardSpeed(0) : dist / shardTravelTicks(dist); }

    /** Blood: 0 above 30% health, fading to 1 (full blood red) at 20%. */
    static double blood(double health, double max) {
        double f = max <= 0 ? 0 : health / max;
        return Math.max(0, Math.min(1, (0.30 - f) / 0.10));
    }

    static final int BLOOD_RED = 0x8A0303, BLOOD_GLOW = 0xB3000C;

    /** Mix two 0xRRGGBB colours: f = 0 gives a, 1 gives b. */
    static int mix(int a, int b, double f) {
        f = Math.max(0, Math.min(1, f));
        int r = (int) Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return (r << 16) | (g << 8) | bl;
    }

    /** Ticks between special moves: about 4 s in phase 1, 3 s in phase 2, 2.2 s enraged. */
    static int moveGap(int phase) { return phase == 1 ? 80 : phase == 2 ? 60 : 44; }

    /** Where a lobbed boulder is, {@code f} (0..1) of the way from (x0,y0,z0) to (x1,y1,z1), arcing {@code h} high. */
    static double[] arc(double x0, double y0, double z0, double x1, double y1, double z1, double h, double f) {
        return new double[]{x0 + (x1 - x0) * f, y0 + (y1 - y0) * f + 4 * h * f * (1 - f), z0 + (z1 - z0) * f};
    }
}
