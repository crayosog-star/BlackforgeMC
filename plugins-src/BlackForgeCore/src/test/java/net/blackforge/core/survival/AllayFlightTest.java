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

    // a Station at block (0, 64, 0): its packed flock sits in block (0, 65, 0)
    private static final double[] TIGHT = {0.5, 65.3, 0.5};

    private static void allFit(AllayFlight.Hover h, int flock, AllayPath.Free free) {
        for (int i = 0; i < flock; i++) {
            double[] o = AllayFlight.tightOffset(h, i, flock);
            assertTrue(AllayFlight.bodyFree(new double[]{TIGHT[0] + o[0], TIGHT[1] + o[1], TIGHT[2] + o[2]}, free), h + " allay " + i + " is in a block");
        }
    }

    @Test void openSpaceIsARowAlongX() {
        assertEquals(AllayFlight.Hover.ROW_X, AllayFlight.tightLayout(TIGHT, 3, (x, y, z) -> true));
        assertArrayEquals(new double[]{-0.7, 0, 0}, AllayFlight.tightOffset(AllayFlight.Hover.ROW_X, 0, 3), EPS);
        assertArrayEquals(new double[]{0.7, 0, 0}, AllayFlight.tightOffset(AllayFlight.Hover.ROW_X, 2, 3), EPS);
    }

    @Test void aNookRunningAlongZTurnsTheRow() {
        AllayPath.Free nook = (x, y, z) -> x == 0;   // walls either side in x
        assertFalse(AllayFlight.bodyFree(new double[]{TIGHT[0] + 0.7, TIGHT[1], TIGHT[2]}, nook));   // the old spot was half in the wall
        AllayFlight.Hover h = AllayFlight.tightLayout(TIGHT, 3, nook);
        assertEquals(AllayFlight.Hover.ROW_Z, h);
        allFit(h, 3, nook);
    }

    @Test void aShaftStacksThem() {
        AllayPath.Free shaft = (x, y, z) -> x == 0 && z == 0;
        AllayFlight.Hover h = AllayFlight.tightLayout(TIGHT, 4, shaft);
        assertEquals(AllayFlight.Hover.STACK, h);
        allFit(h, 4, shaft);
    }

    @Test void aCubbyKeepsThemInTheMiddle() {
        AllayPath.Free cubby = (x, y, z) -> x == 0 && z == 0 && y == 65;
        AllayFlight.Hover h = AllayFlight.tightLayout(TIGHT, 3, cubby);
        assertEquals(AllayFlight.Hover.CENTRE, h);
        allFit(h, 3, cubby);
    }

    @Test void tightSpotIsJustAboveTheStation() {
        assertArrayEquals(TIGHT, AllayFlight.tightSpot(0, 64, 0), EPS);
    }

    @Test void packedFlockSitsSideBySide() {
        assertArrayEquals(new double[]{-0.2, 65.3, 0.5}, AllayFlight.idleSpot(0, 64, 0, AllayFlight.Hover.ROW_X, 0, 3, 99), EPS);
        assertArrayEquals(new double[]{0.5, 65.3, 0.5}, AllayFlight.idleSpot(0, 64, 0, AllayFlight.Hover.ROW_X, 1, 3, 99), EPS);
        assertArrayEquals(new double[]{0.5, 65.3, 1.2}, AllayFlight.idleSpot(0, 64, 0, AllayFlight.Hover.ROW_Z, 2, 3, 99), EPS);
        assertArrayEquals(new double[]{0.5, 66.7, 0.5}, AllayFlight.idleSpot(0, 64, 0, AllayFlight.Hover.STACK, 2, 3, 99), EPS);
        assertArrayEquals(TIGHT, AllayFlight.idleSpot(0, 64, 0, AllayFlight.Hover.CENTRE, 2, 3, 99), EPS);
        // neighbours are exactly one spacing apart, whatever the flock size
        for (int flock = 2; flock <= 5; flock++) for (int i = 1; i < flock; i++) {
            double[] a = AllayFlight.idleSpot(0, 64, 0, AllayFlight.Hover.ROW_X, i - 1, flock, 0), b = AllayFlight.idleSpot(0, 64, 0, AllayFlight.Hover.ROW_X, i, flock, 0);
            assertEquals(AllayFlight.TIGHT_SPACING, b[0] - a[0], EPS);
        }
    }

    @Test void packedFlockDoesNotMoveOverTime() {
        assertArrayEquals(AllayFlight.idleSpot(10, 70, -5, AllayFlight.Hover.ROW_Z, 1, 2, 0),
                AllayFlight.idleSpot(10, 70, -5, AllayFlight.Hover.ROW_Z, 1, 2, 12_345), EPS);
    }

    @Test void circlingFlockFollowsTheHover() {
        double[] h = AllayFlight.hover(1, 3, 40), spot = AllayFlight.idleSpot(10, 70, -5, AllayFlight.Hover.CIRCLE, 1, 3, 40);
        assertArrayEquals(new double[]{10.5 + h[0], 72.2 + h[1], -4.5 + h[2]}, spot, EPS);
    }

    @Test void wingsStepThroughEveryPoseInOrder() {
        int flap = 0;
        int[] seen = new int[8];
        for (int i = 0; i < seen.length; i++) { flap = AllayFlight.nextFlap(flap); seen[i] = AllayFlight.flapFrame(flap); }
        assertArrayEquals(new int[]{1, 2, 1, 0, 1, 2, 1, 0}, seen);   // never closed straight to swept
        assertEquals(0, AllayFlight.flapFrame(0));
    }

    @Test void flockFlapsOutOfStep() {
        assertTrue(AllayFlight.flapsNow(10, 0));
        assertFalse(AllayFlight.flapsNow(11, 0));
        assertNotEquals(AllayFlight.flapsNow(10, 0), AllayFlight.flapsNow(10, 1));
        int flaps = 0;
        for (long t = 0; t < 20; t++) if (AllayFlight.flapsNow(t, 3)) flaps++;
        assertEquals(20 / AllayFlight.FLAP_TICKS, flaps);
    }

    @Test void skinSizeAndFlip() {
        assertEquals(1f, AllayFlight.skinScale(2));
        assertEquals(AllayFlight.STEWARD_SCALE, AllayFlight.skinScale(3));
        assertEquals(1.15f, AllayFlight.STEWARD_SCALE);
        assertEquals(0f, AllayFlight.skinRoll(false));
        assertEquals((float) Math.PI, AllayFlight.skinRoll(true));
    }

    @Test void ribbonsSitEitherSideOfTravel() {
        assertArrayEquals(new double[]{0, 1}, AllayFlight.side(3, 0), EPS);
        assertArrayEquals(new double[]{-1, 0}, AllayFlight.side(0, 2), EPS);
        assertArrayEquals(new double[]{1, 0}, AllayFlight.side(0, 0), EPS);   // straight up or down
        double[] s = AllayFlight.side(1, 1);
        assertEquals(1, Math.hypot(s[0], s[1]), EPS);
        assertEquals(0, s[0] * 1 + s[1] * 1, EPS);   // square to the way it's going
    }
}
