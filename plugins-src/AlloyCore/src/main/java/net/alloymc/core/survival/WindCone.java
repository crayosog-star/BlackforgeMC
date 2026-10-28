package net.alloymc.core.survival;

/**
 * Pure maths for the Gale Bellows wind burst: who is inside the cone, and how hard they get pushed.
 * No Bukkit types, so it can be unit tested without a server. Vectors are passed as plain doubles.
 */
public final class WindCone {
    private WindCone() {}

    /** How far the burst reaches, measured from the caster's eyes (about 2 blocks in front of the body). */
    public static final double RANGE = 2.6;
    /** Half the cone's width: 35 degrees either side of where you look, so a 70 degree cone. */
    public static final double HALF_ANGLE_DEG = 35.0;
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
        return cos >= COS_HALF;
    }

    /**
     * True if any part of the target's vertical span is in the cone. Checking only the centre would miss a
     * target right in front of you: from 1 block away, their centre sits ~36 degrees below your eye line.
     * (dx, dz): horizontal offset of the target from the eyes; minDy/maxDy: their feet and head height relative to the eyes.
     */
    public static boolean hits(double lx, double ly, double lz, double dx, double dz, double minDy, double maxDy) {
        int samples = 5;
        for (int i = 0; i < samples; i++) {
            double dy = minDy + (maxDy - minDy) * i / (samples - 1);
            if (inCone(lx, ly, lz, dx, dy, dz)) return true;
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
