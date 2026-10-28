package net.alloymc.core.survival;

/**
 * Pure maths for the Gale Bellows wind burst: who is inside the cone, and how hard they get pushed.
 * No Bukkit types, so it can be unit tested without a server. Vectors are passed as plain doubles.
 */
public final class WindCone {
    private WindCone() {}

    /** How far the burst reaches: 3 blocks from the caster's eyes to the nearest edge of the target's hitbox. */
    public static final double RANGE = 3.0;
    /** Half the cone's width: 35 degrees either side of where you look, so a 70 degree cone. */
    public static final double HALF_ANGLE_DEG = 35.0;
    /** Point-blank: within this distance of your eyes the cone opens to 80 degrees either side, so huggers can't dodge it. */
    public static final double CLOSE = 1.2;
    public static final double CLOSE_HALF_ANGLE_DEG = 80.0;
    /** The slash is a wave: it starts this far from your eyes and rolls out to {@link #RANGE} over {@link #WAVE_TICKS} ticks. */
    public static final double WAVE_START = 0.6;
    public static final int WAVE_TICKS = 4;
    /** Horizontal push speed before any reductions. */
    public static final double PUSH = 1.4;
    /** Upward lift for targets standing on the ground. */
    public static final double LIFT = 0.45;
    /** A target pushed again within this window takes reduced push (stops two players juggling one target). */
    public static final long JUGGLE_WINDOW_MS = 3000;
    public static final double JUGGLE_FACTOR = 0.5;
    /**
     * Knockback resistance and the juggle rule never cut the push below this share of full strength. 0.45 throws a
     * grounded player about 3.8 blocks (full strength is ~11), so every hit knocks back at least 3 blocks.
     */
    public static final double MIN_SCALE = 0.45;

    private static final double COS_HALF = Math.cos(Math.toRadians(HALF_ANGLE_DEG));
    private static final double COS_CLOSE = Math.cos(Math.toRadians(CLOSE_HALF_ANGLE_DEG));

    /** Radius of the wave's front on tick {@code tick} (0-based): 1.2, 1.8, 2.4, 3.0 blocks. */
    public static double waveRadius(int tick) {
        return WAVE_START + (RANGE - WAVE_START) * (tick + 1) / WAVE_TICKS;
    }

    /** The tick on which the wave's front reaches something {@code dist} blocks from your eyes (0 = right away). */
    public static int waveTick(double dist) {
        int k = (int) Math.ceil((dist - WAVE_START) / (RANGE - WAVE_START) * WAVE_TICKS) - 1;
        return Math.max(0, Math.min(WAVE_TICKS - 1, k));
    }

    /**
     * True if the point (dx, dy, dz), given relative to the caster's eyes, is in range and inside the cone
     * around the look direction (lx, ly, lz). The look vector does not need to be normalised.
     */
    public static boolean inCone(double lx, double ly, double lz, double dx, double dy, double dz) {
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > RANGE) return false;
        if (dist < 1e-6) return true;   // standing inside the caster
        double look = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (look < 1e-9) return false;
        double cos = (lx * dx + ly * dy + lz * dz) / (look * dist);
        return cos >= (dist <= CLOSE ? COS_CLOSE : COS_HALF);
    }

    /**
     * True if any part of the target's vertical span is in the cone. Checking only the centre would miss a
     * target right in front of you: from 1 block away, their centre sits ~36 degrees below your eye line.
     * (dx, dz): horizontal offset of the target from the eyes; minDy/maxDy: their feet and head height relative to the eyes.
     */
    public static boolean hits(double lx, double ly, double lz, double dx, double dz, double minDy, double maxDy) {
        return hits(lx, ly, lz, dx, dz, minDy, maxDy, 0);
    }

    /**
     * As above, measured to the near side of a hitbox {@code halfWidth} wide either side of (dx, dz), so a player whose
     * hitbox edge is 3 blocks away counts as 3 blocks away (like a sword's reach), not 3.3.
     */
    public static boolean hits(double lx, double ly, double lz, double dx, double dz, double minDy, double maxDy, double halfWidth) {
        double h = Math.sqrt(dx * dx + dz * dz);
        double s = h > 1e-9 ? Math.max(0, h - halfWidth) / h : 0;
        double ex = dx * s, ez = dz * s;
        int samples = 5;
        for (int i = 0; i < samples; i++) {
            double dy = minDy + (maxDy - minDy) * i / (samples - 1);
            if (inCone(lx, ly, lz, ex, dy, ez)) return true;
        }
        return false;
    }

    /**
     * The push velocity {x, y, z}. Direction is the horizontal line from caster to target, so off-centre targets
     * fan outward; if the target is straight above or below, the caster's horizontal look direction is used.
     *
     * @param kbResistance the target's knockback resistance (0..1), as in vanilla, but never below {@link #MIN_SCALE}
     * @param airborne     targets already in the air get no extra lift (stops sky-high launches)
     * @param juggled      target was pushed within {@link #JUGGLE_WINDOW_MS}
     */
    public static double[] push(double dx, double dz, double lookX, double lookZ, double kbResistance, boolean airborne, boolean juggled) {
        double h = Math.sqrt(dx * dx + dz * dz);
        double ux, uz;
        if (h > 0.05) { ux = dx / h; uz = dz / h; }
        else {
            double lh = Math.sqrt(lookX * lookX + lookZ * lookZ);
            if (lh < 1e-9) { ux = 0; uz = 0; } else { ux = lookX / lh; uz = lookZ / lh; }
        }
        double scale = Math.max(MIN_SCALE, (1.0 - Math.max(0.0, Math.min(1.0, kbResistance))) * (juggled ? JUGGLE_FACTOR : 1.0));
        double y = airborne ? 0.0 : LIFT * scale;
        return new double[] {ux * PUSH * scale, y, uz * PUSH * scale};
    }
}
