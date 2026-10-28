package net.alloymc.core.survival;

/**
 * Rimelock's cage geometry, kept free of Bukkit so it can be unit tested: who counts as inside, and where each glass
 * panel of the round wall sits. The cage is an upright cylinder of {@link #RADIUS} around where the caster landed.
 */
final class CageShape {
    private CageShape() {}

    static final double RADIUS = 6.0;
    static final double WALL_HEIGHT = 5.0;   // what you see; the barrier itself reaches ABOVE
    static final double BELOW = 3.0, ABOVE = 24.0;
    static final double BODY = 0.35;         // half a player's width: you stop at the glass, not halfway through it
    static final int SEGMENTS = 32;          // glass panels round the wall

    /** A player standing at (x, y, z) is in the cage. Horizontal check only uses the centre of their feet. */
    static boolean inside(double cx, double baseY, double cz, double x, double y, double z) {
        return insideXZ(cx, cz, x, z, RADIUS - BODY) && y >= baseY - BELOW && y <= baseY + ABOVE;
    }

    static boolean insideXZ(double cx, double cz, double x, double z, double r) {
        double dx = x - cx, dz = z - cz;
        return dx * dx + dz * dz <= r * r;
    }

    /** Projectiles are thin: they bounce off the glass itself, at the full radius. */
    static boolean projectileInside(double cx, double baseY, double cz, double x, double y, double z) {
        return insideXZ(cx, cz, x, z, RADIUS) && y >= baseY - BELOW && y <= baseY + ABOVE;
    }

    /** Angle (radians, round from +X towards +Z) of the middle of panel {@code i}. */
    static double panelAngle(int i) { return (i + 0.5) * 2 * Math.PI / SEGMENTS; }

    /** Angle of the edge between panel {@code i - 1} and panel {@code i} (where the posts stand). */
    static double edgeAngle(int i) { return i * 2 * Math.PI / SEGMENTS; }

    /** Width of one panel: the chord across its slice of the circle, a hair wider so there are no gaps. */
    static double panelWidth() { return 2 * RADIUS * Math.sin(Math.PI / SEGMENTS) * 1.03; }

    /**
     * The turn about +Y (JOML rotateY, radians) that lays a panel's local X axis along the wall at angle {@code phi},
     * so the glass faces the middle. rotateY(t) sends +X to (cos t, 0, -sin t); the wall's direction at phi is
     * (-sin phi, 0, cos phi), which gives t = -phi - pi/2.
     */
    static double panelYaw(double phi) { return -phi - Math.PI / 2; }
}
