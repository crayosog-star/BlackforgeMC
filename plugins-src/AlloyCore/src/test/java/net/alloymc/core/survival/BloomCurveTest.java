package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static net.alloymc.core.survival.BloomCurve.*;

/**
 * Pure-logic tests for the Potion of Gaia timing (no server needed). Times are written against the phase constants,
 * so retiming the potion doesn't mean rewriting the tests.
 */
class BloomCurveTest {
    private static final double EPS = 1e-9;

    @Test void leavesTheGroundAtThreeSeconds() {
        assertEquals(3.0, HARDEN_END, EPS);                    // rises 1.5 s sooner than v2 (4.5 s)
        assertEquals(0, height(HARDEN_END, 99), EPS);
        assertTrue(height(HARDEN_END + 0.2, 99) > 0);
    }

    @Test void phaseBoundariesAreInOrder() {
        double[] b = {0, GROW_END, BLOOM_PEAK, BLOOM_END, HARDEN_END, RISE_END, HOLD_END, FADE_END};
        for (int i = 1; i < b.length; i++) assertTrue(b[i] > b[i - 1], "boundary " + i);
    }

    @Test void startsSmall() {
        assertEquals(START_RADIUS, radius(0), EPS);
    }

    @Test void startsSlowAndSpeedsUp() {
        double firstPart = radius(0.4 * GROW_END) - radius(0);
        double lastPart = radius(GROW_END - 1e-9) - radius(0.8 * GROW_END);
        assertTrue(firstPart < 0.25, "barely grows at first");
        assertTrue(lastPart > 1.2, "grows fast at the end");
        double step = GROW_END / 25;
        for (double t = step; t < GROW_END - step; t += step) {
            double before = radius(t) - radius(t - step);
            double after = radius(t + step) - radius(t);
            assertTrue(after >= before - EPS, "growth keeps speeding up at " + t);
        }
    }

    @Test void reachesFullSizeAtTheEndOfGrow() {
        assertEquals(RADIUS, radius(GROW_END - 1e-12), 1e-6);
    }

    @Test void bloomOvershootsThenSettles() {
        assertEquals(BLOOM_RADIUS, radius(BLOOM_PEAK), EPS);
        assertTrue(radius((GROW_END + BLOOM_PEAK) / 2) > RADIUS);
        assertEquals(RADIUS, radius(BLOOM_END), EPS);
        assertEquals(RADIUS, radius(RISE_END + 1), EPS);
    }

    @Test void risesExactlyEightBlocks() {
        assertEquals(0, height(HARDEN_END, 99), EPS);    // nothing rises until it has hardened
        assertEquals(8, height(RISE_END, 99), EPS);
        assertEquals(8, height(RISE_END + 3, 99), EPS);
        assertEquals(4, height((HARDEN_END + RISE_END) / 2, 99), 1e-6);   // halfway in time = halfway up
    }

    @Test void riseNeverGoesDown() {
        double last = 0;
        for (double t = HARDEN_END; t <= RISE_END; t += 0.05) {
            double h = height(t, 99);
            assertTrue(h >= last - EPS);
            last = h;
        }
    }

    @Test void lowCeilingCapsTheRise() {
        assertEquals(3, height(RISE_END + 1, 3), EPS);
        assertTrue(height((HARDEN_END + RISE_END) / 2, 3) <= 3);
    }

    @Test void riseSpeedMatchesTheHeight() {
        double t = HARDEN_END + 0.4 * (RISE_END - HARDEN_END), dt = 1e-4;
        double numeric = (height(t + dt, 99) - height(t - dt, 99)) / (2 * dt);
        assertEquals(numeric, riseSpeed(t, 99), 1e-4);
        assertEquals(0, riseSpeed(BLOOM_END, 99), EPS);
        assertEquals(0, riseSpeed(RISE_END + 1, 99), EPS);
    }

    @Test void phasesInOrder() {
        assertEquals(Phase.GROW, phase(GROW_END / 2));
        assertEquals(Phase.BLOOM, phase((GROW_END + BLOOM_END) / 2));
        assertEquals(Phase.HARDEN, phase((BLOOM_END + HARDEN_END) / 2));
        assertEquals(Phase.RISE, phase((HARDEN_END + RISE_END) / 2));
        assertEquals(Phase.HOLD, phase((RISE_END + HOLD_END) / 2));
        assertEquals(Phase.FADE, phase((HOLD_END + FADE_END) / 2));
        assertEquals(Phase.DONE, phase(FADE_END));
    }

    @Test void hardensFromTheCentreOutward() {
        double span = HARDEN_END - BLOOM_END, early = BLOOM_END + 0.27 * span;
        assertEquals(0, hardness(BLOOM_END), EPS);
        assertEquals(1, hardness(HARDEN_END), EPS);
        assertFalse(cellHard(GROW_END / 2, 0));               // still pure mist
        assertTrue(cellHard(early, 0.5));                     // centre first...
        assertFalse(cellHard(early, 3.0));                    // ...edge later
        assertTrue(cellHard(HARDEN_END, RADIUS));
    }

    @Test void fadesToNothing() {
        assertEquals(0, radius(FADE_END), EPS);
        assertTrue(radius(FADE_END - 0.5) < RADIUS);
        assertEquals(RADIUS, radius(HOLD_END - 1), EPS);
    }
}
