package net.alloymc.core.survival;

/**
 * Pure maths for the Ashen Chain escape through Rimelock's rim crystals. No Bukkit types, so it can be unit tested.
 * The 8 crystals float over the glowing posts ({@link CageShape#edgeAngle} every 4th panel), {@link #HEIGHT} blocks above
 * where the caster landed. {@link #rayHit} is the aim test; {@link #vaultSpeed} and {@link #vaultLift} shape the short
 * carry over the rim after the reel arrives (speeds are blocks per tick).
 */
final class CrystalAnchor {
    private CrystalAnchor() {}

    /** Crystals round the rim: one over every 4th post. */
    static final int COUNT = CageShape.SEGMENTS / 4;
    /** A crystal floats this far above the cage's base (the glass top plus a block). */
    static final double HEIGHT = CageShape.WALL_HEIGHT + 1.0;
    /** Upward speed at the start of the vault; it eases to nothing so gravity takes over without a jolt. */
    static final double VAULT_LIFT = 0.3;
    /** The vault ends at this share of its starting outward speed (it eases down, it never stops dead). */
    static final double VAULT_END_SHARE = 0.3;

    /** Angle (radians) of crystal {@code k}, which stands over a post. */
    static double angle(int k) { return CageShape.edgeAngle(k * 4); }

    static double x(double cx, int k) { return cx + Math.cos(angle(k)) * CageShape.RADIUS; }

    static double y(double baseY) { return baseY + HEIGHT; }

    static double z(double cz, int k) { return cz + Math.sin(angle(k)) * CageShape.RADIUS; }

    /**
     * Where along an aim ray the crystal's hit sphere is first touched: the ray starts at (ox, oy, oz) and points
     * along the unit vector (dx, dy, dz); the sphere is centred on (sx, sy, sz). Returns the distance along the ray, 0
     * if the ray starts inside the sphere, or -1 for a miss, a sphere behind the ray, or one further than
     * {@code maxDist}.
     */
    static double rayHit(double ox, double oy, double oz, double dx, double dy, double dz,
                         double sx, double sy, double sz, double radius, double maxDist) {
        double mx = sx - ox, my = sy - oy, mz = sz - oz;
        double along = mx * dx + my * dy + mz * dz;                 // how far along the ray the sphere's middle is
        double off2 = mx * mx + my * my + mz * mz - along * along;  // squared distance from the ray to the middle
        double r2 = radius * radius;
        if (off2 > r2) return -1;
        double half = Math.sqrt(r2 - off2);
        double enter = along - half, leave = along + half;
        if (leave < 0) return -1;                                    // all of it is behind the start
        double hit = Math.max(0, enter);
        return hit > maxDist ? -1 : hit;
    }

    /** Ease-out: fast at first, flattening towards 1. {@code t} is clamped to 0..1. */
    static double easeOut(double t) {
        double c = Math.max(0, Math.min(1, t));
        return 1 - (1 - c) * (1 - c);
    }

    /** Smoothstep: slow, then fast, then slow. {@code t} is clamped to 0..1. */
    static double smooth(double t) {
        double c = Math.max(0, Math.min(1, t));
        return c * c * (3 - 2 * c);
    }

    /** Outward speed {@code tick} ticks into a vault of {@code ticks} ticks: {@code start} at first, easing down to a share of it. */
    static double vaultSpeed(int tick, int ticks, double start) {
        if (ticks <= 0) return 0;
        return start * (1 - (1 - VAULT_END_SHARE) * easeOut(tick / (double) ticks));
    }

    /** Upward speed {@code tick} ticks into a vault of {@code ticks} ticks: {@link #VAULT_LIFT} easing down to nothing. */
    static double vaultLift(int tick, int ticks) {
        if (ticks <= 0) return 0;
        return VAULT_LIFT * (1 - smooth(tick / (double) ticks));
    }
}
