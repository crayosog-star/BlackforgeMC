package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Potion of Gaia timing (no server needed). */
class BloomCurveTest {
    private static final double EPS = 1e-9;

    @Test void startsSmall() {
        assertEquals(BloomCurve.START_RADIUS, BloomCurve.radius(0), EPS);
    }

    @Test void startsSlowAndSpeedsUp() {
        double firstSecond = BloomCurve.radius(1.0) - BloomCurve.radius(0);
        double lastHalfSecond = BloomCurve.radius(2.5 - 1e-9) - BloomCurve.radius(2.0);
        assertTrue(firstSecond < 0.25, "barely grows in the first second");
        assertTrue(lastHalfSecond > 1.2, "grows fast at the end");
        for (double t = 0.1; t < 2.4; t += 0.1) {
            double before = BloomCurve.radius(t) - BloomCurve.radius(t - 0.1);
            double after = BloomCurve.radius(t + 0.1) - BloomCurve.radius(t);
            assertTrue(after >= before - EPS, "growth keeps speeding up at " + t);
        }
    }

    @Test void reachesFullSizeAtTheEndOfGrow() {
        assertEquals(BloomCurve.RADIUS, BloomCurve.radius(2.5 - 1e-12), 1e-6);
    }

    @Test void bloomOvershootsThenSettles() {
        assertEquals(BloomCurve.BLOOM_RADIUS, BloomCurve.radius(BloomCurve.BLOOM_PEAK), EPS);
        assertTrue(BloomCurve.radius(2.6) > BloomCurve.RADIUS);
        assertEquals(BloomCurve.RADIUS, BloomCurve.radius(3.0), EPS);
        assertEquals(BloomCurve.RADIUS, BloomCurve.radius(8.0), EPS);
    }

    @Test void risesExactlyEightBlocks() {
        assertEquals(0, BloomCurve.height(3.0, 99), EPS);
        assertEquals(8, BloomCurve.height(5.5, 99), EPS);
        assertEquals(8, BloomCurve.height(9.0, 99), EPS);
        assertEquals(4, BloomCurve.height(4.25, 99), 1e-6);   // halfway in time = halfway up
    }

    @Test void riseNeverGoesDown() {
        double last = 0;
        for (double t = 3.0; t <= 5.5; t += 0.05) {
            double h = BloomCurve.height(t, 99);
            assertTrue(h >= last - EPS);
            last = h;
        }
    }

    @Test void lowCeilingCapsTheRise() {
        assertEquals(3, BloomCurve.height(6.0, 3), EPS);
        assertTrue(BloomCurve.height(4.0, 3) <= 3);
    }

    @Test void riseSpeedMatchesTheHeight() {
        double t = 4.0, dt = 1e-4;
        double numeric = (BloomCurve.height(t + dt, 99) - BloomCurve.height(t - dt, 99)) / (2 * dt);
        assertEquals(numeric, BloomCurve.riseSpeed(t, 99), 1e-4);
        assertEquals(0, BloomCurve.riseSpeed(2.0, 99), EPS);
        assertEquals(0, BloomCurve.riseSpeed(6.0, 99), EPS);
    }

    @Test void phasesInOrder() {
        assertEquals(BloomCurve.Phase.GROW, BloomCurve.phase(1));
        assertEquals(BloomCurve.Phase.BLOOM, BloomCurve.phase(2.8));
        assertEquals(BloomCurve.Phase.RISE, BloomCurve.phase(4));
        assertEquals(BloomCurve.Phase.HOLD, BloomCurve.phase(8));
        assertEquals(BloomCurve.Phase.FADE, BloomCurve.phase(12));
        assertEquals(BloomCurve.Phase.DONE, BloomCurve.phase(13));
    }

    @Test void fadesToNothing() {
        assertEquals(0, BloomCurve.radius(13.0), EPS);
        assertTrue(BloomCurve.radius(12.0) < BloomCurve.RADIUS);
    }
}
