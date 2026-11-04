package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for how a Forge Allay flies (no server needed). */
class AllayFlightTest {
    private static final double EPS = 1e-9;
    private static final double[] A = {0, 64, 0}, B = {12, 64, 0};

    @Test void ticksFollowSpeed() {
        assertEquals(40, AllayFlight.ticks(12, 6));
        assertEquals(10, AllayFlight.ticks(1, 6));      // a short hop still eases
        assertThrows(IllegalArgumentException.class, () -> AllayFlight.ticks(12, 0));
    }

    @Test void startsAndEndsExactly() {
        assertArrayEquals(A, AllayFlight.at(A, B, 0, 40), EPS);
        assertArrayEquals(B, AllayFlight.at(A, B, 40, 40), EPS);
    }

    @Test void easingStaysInRange() {
        assertEquals(0, AllayFlight.ease(-1), EPS);
        assertEquals(0.5, AllayFlight.ease(0.5), EPS);
        assertEquals(1, AllayFlight.ease(2), EPS);
    }

    @Test void neverGoesBackwardsOrPastTheEnd() {
        double last = -1;
        for (int i = 0; i <= 40; i++) {
            double x = AllayFlight.at(A, B, i, 40)[0];
            assertTrue(x >= last, "went backwards at tick " + i);
            assertTrue(x <= B[0] + EPS, "overshot at tick " + i);
            last = x;
        }
    }

    @Test void noJumpBetweenTicks() {
        // eased travel peaks at 1.5x its average speed: 0.45 blocks a tick here, plus a little lift
        for (int i = 1; i <= 40; i++) {
            double[] a = AllayFlight.at(A, B, i - 1, 40), b = AllayFlight.at(A, B, i, 40);
            double d = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));
            assertTrue(d < 0.5, "jumped " + d + " at tick " + i);
        }
    }

    @Test void startsSlowly() {
        assertTrue(AllayFlight.at(A, B, 1, 40)[0] < 0.05);
        assertTrue(AllayFlight.at(A, B, 20, 40)[0] - AllayFlight.at(A, B, 19, 40)[0] > 0.4);
    }

    @Test void hoverDriftsSlowly() {
        for (long t = 1; t < 2_000; t++) {
            double[] a = AllayFlight.hover(1, 3, t - 1), b = AllayFlight.hover(1, 3, t);
            double d = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));
            assertTrue(d < 0.05, "jumped " + d + " at tick " + t);
        }
    }

    @Test void flockSpreadsOut() {
        double[] a = AllayFlight.hover(0, 3, 0), b = AllayFlight.hover(1, 3, 0);
        assertTrue(Math.hypot(a[0] - b[0], a[2] - b[2]) > 2);
    }

    @Test void rainbowGoesAround() {
        assertEquals(0xFF0000, AllayFlight.rainbow(0));
        assertEquals(0x00FF00, AllayFlight.rainbow(1.0 / 3));
        assertEquals(0x0000FF, AllayFlight.rainbow(2.0 / 3));
        assertEquals(AllayFlight.rainbow(0.25), AllayFlight.rainbow(5.25));
        assertEquals(AllayFlight.rainbow(0.25), AllayFlight.rainbow(-0.75));
    }

    @Test void liftsInTheMiddleOnly() {
        assertEquals(0, AllayFlight.lift(0, 12), EPS);
        assertEquals(0, AllayFlight.lift(1, 12), EPS);
        assertEquals(AllayFlight.MAX_LIFT, AllayFlight.lift(0.5, 12), EPS);
        assertEquals(AllayFlight.MAX_LIFT, AllayFlight.lift(0.5, 100), EPS);
        assertEquals(0.5, AllayFlight.lift(0.5, 4), EPS);
    }
}
