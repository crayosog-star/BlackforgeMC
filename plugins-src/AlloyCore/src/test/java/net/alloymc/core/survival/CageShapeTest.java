package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for Rimelock's cage shape (no server needed). Cage centred on 0,64,0. */
class CageShapeTest {
    private static final double Y = 64;

    @Test void centreIsInside() { assertTrue(CageShape.inside(0, Y, 0, 0, Y, 0)); }

    @Test void justInsideTheGlassIsInside() { assertTrue(CageShape.inside(0, Y, 0, CageShape.RADIUS - CageShape.BODY - 0.01, Y, 0)); }

    @Test void touchingTheGlassFromOutsideIsOutside() {
        // half your body past the wall line means you're still outside: walking in takes a real step through
        assertFalse(CageShape.inside(0, Y, 0, CageShape.RADIUS - CageShape.BODY + 0.05, Y, 0));
        assertFalse(CageShape.inside(0, Y, 0, 0, Y, -CageShape.RADIUS));
    }

    @Test void diagonalUsesTrueDistance() {
        double d = (CageShape.RADIUS - CageShape.BODY) / Math.sqrt(2);
        assertTrue(CageShape.inside(0, Y, 0, d - 0.01, Y, d - 0.01));
        assertFalse(CageShape.inside(0, Y, 0, d + 0.05, Y, d + 0.05));
    }

    @Test void heightLimits() {
        assertTrue(CageShape.inside(0, Y, 0, 1, Y + CageShape.WALL_HEIGHT + 3, 1));   // jumping above the glass is still in
        assertFalse(CageShape.inside(0, Y, 0, 1, Y + CageShape.ABOVE + 1, 1));
        assertFalse(CageShape.inside(0, Y, 0, 1, Y - CageShape.BELOW - 1, 1));
    }

    @Test void projectilesBounceAtTheGlassItself() {
        assertTrue(CageShape.projectileInside(0, Y, 0, CageShape.RADIUS - 0.1, Y + 1, 0));
        assertFalse(CageShape.projectileInside(0, Y, 0, CageShape.RADIUS + 0.1, Y + 1, 0));
    }

    @Test void panelsLieAlongTheWall() {
        for (int i = 0; i < CageShape.SEGMENTS; i++) {
            double phi = CageShape.panelAngle(i), t = CageShape.panelYaw(phi);
            // JOML rotateY(t) turns +X into (cos t, 0, -sin t); that must be the wall's direction at phi
            assertEquals(-Math.sin(phi), Math.cos(t), 1e-9, "panel " + i + " x");
            assertEquals(Math.cos(phi), -Math.sin(t), 1e-9, "panel " + i + " z");
        }
    }

    @Test void panelsCoverTheCircleWithNoGaps() {
        double chord = 2 * CageShape.RADIUS * Math.sin(Math.PI / CageShape.SEGMENTS);
        assertTrue(CageShape.panelWidth() > chord);
        assertTrue(CageShape.panelWidth() < chord * 1.1);
    }
}
