package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The Gods-eye tracker arrow (no server needed). */
class CompassTest {
    @Test void straightAhead() {
        assertEquals("↑", Compass.arrow(0f, 0, 10));     // facing south, target south
        assertEquals("↑", Compass.arrow(90f, -10, 0));   // facing west, target west
    }

    @Test void behind() { assertEquals("↓", Compass.arrow(0f, 0, -10)); }

    @Test void rightAndLeft() {
        // facing south (+z), your right hand points west (-x)
        assertEquals("→", Compass.arrow(0f, -10, 0));
        assertEquals("←", Compass.arrow(0f, 10, 0));
    }

    @Test void diagonals() {
        assertEquals("↗", Compass.arrow(0f, -10, 10));   // ahead and to the right
        assertEquals("↖", Compass.arrow(0f, 10, 10));
    }

    @Test void yawWrapsAround() {
        assertEquals("↑", Compass.arrow(360f, 0, 10));
        assertEquals("↑", Compass.arrow(-270f, -10, 0));   // -270 is the same as 90 (west)
        assertEquals("↓", Compass.arrow(180f, 0, 10));
    }
}
