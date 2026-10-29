package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Ashen Chain pull maths (no server needed). */
class ChainPullTest {
    private static final double EPS = 1e-9;

    /** Vanilla player motion with no input: air drag 0.91, gravity 0.08 (x0.98), ground friction 0.546. */
    private static double travelled(double vh, double vy) {
        double x = 0, y = 0;
        boolean air = vy > 0;
        for (int i = 0; i < 200 && vh > 1e-3; i++) {
            x += vh; y += vy;
            if (y <= 0 && vy < 0) { y = 0; air = false; }
            vy = air ? (vy - 0.08) * 0.98 : 0;
            vh *= air ? 0.91 : 0.546;
        }
        return x;
    }

    @Test void dragsTargetToJustInFrontOfYou() {
        double[] v = ChainPull.yank(8, 0, 8);   // 8 blocks east of the caster
        assertTrue(v[0] < 0, "pulled back towards the caster (west)");
        assertEquals(0, v[2], EPS);
        assertEquals(6.0, travelled(-v[0], v[1]), 0.1);   // lands ~2 blocks in front
    }

    @Test void longHooksAreCapped() {
        double[] v = ChainPull.yank(0, 12, 12);
        assertEquals(ChainPull.YANK_MAX, travelled(-v[2], v[1]), 0.1);
    }

    @Test void closeTargetsBarelyMove() {
        assertEquals(0.5, ChainPull.yankDistance(1.5), EPS);
        assertEquals(1.0, ChainPull.yankDistance(3.0), EPS);
    }

    @Test void diagonalPullPointsAtCaster() {
        double[] v = ChainPull.yank(3, 4, 5);
        assertEquals(-0.6, v[0] / Math.hypot(v[0], v[2]), 1e-9);
        assertEquals(-0.8, v[2] / Math.hypot(v[0], v[2]), 1e-9);
        assertEquals(ChainPull.YANK_LIFT, v[1], EPS);
    }

    @Test void targetStraightAboveJustLifts() {
        double[] v = ChainPull.yank(0, 0, 5);
        assertEquals(0, v[0], EPS);
        assertEquals(0, v[2], EPS);
    }

    @Test void reelsAtFullSpeedTowardsAnchor() {
        double[] v = ChainPull.reel(0, 0, 10);
        assertEquals(ChainPull.REEL_SPEED, v[2], EPS);
        assertEquals(ChainPull.REEL_ANTI_GRAVITY, v[1], EPS);
    }

    @Test void reelNeverOvershoots() {
        double[] v = ChainPull.reel(0.5, 0, 0);
        assertEquals(0.5, v[0], EPS);
    }

    @Test void reelClimbsToHighAnchors() {
        double[] v = ChainPull.reel(0, 10, 0);
        assertEquals(ChainPull.REEL_SPEED + ChainPull.REEL_ANTI_GRAVITY, v[1], EPS);
    }

    @Test void arrivesWhenClose() {
        assertTrue(ChainPull.arrived(1.5));
        assertFalse(ChainPull.arrived(2.0));
    }

    @Test void grappleReachesFurtherThanYank() {
        assertTrue(ChainPull.GRAPPLE_RANGE > ChainPull.YANK_RANGE);
    }

    // ---- the burning tether on players

    @Test void playersArePulledExactlyOneBlock() {
        assertEquals(1.0, ChainPull.pullStep(8), EPS);
        assertEquals(1.0, ChainPull.pullStep(2), EPS);
        assertEquals(0.5, ChainPull.pullStep(1.5), EPS);   // close already: only up to the gap
        assertEquals(0.0, ChainPull.pullStep(0.6), EPS);   // never into the caster
    }

    @Test void yankStillMatchesAFullDrag() {
        assertArrayEquals(ChainPull.pull(3, 4, ChainPull.yankDistance(5)), ChainPull.yank(3, 4, 5), EPS);
    }

    @Test void tetherSnapsWhenOutRun() {
        assertTrue(ChainPull.tetherHolds(ChainPull.YANK_RANGE));
        assertTrue(ChainPull.tetherHolds(ChainPull.TETHER_SNAP));
        assertFalse(ChainPull.tetherHolds(ChainPull.TETHER_SNAP + 0.01));
    }

    // ---- grapple ease-in

    @Test void grappleStartsSlowAndEasesUpToFullSpeed() {
        assertEquals(ChainPull.REEL_SPEED * ChainPull.REEL_START, ChainPull.reelSpeed(0), EPS);
        assertEquals(ChainPull.REEL_SPEED, ChainPull.reelSpeed(ChainPull.REEL_RAMP_TICKS), EPS);
        assertEquals(ChainPull.REEL_SPEED, ChainPull.reelSpeed(ChainPull.REEL_RAMP_TICKS + 10), EPS);
        for (int t = 1; t <= ChainPull.REEL_RAMP_TICKS; t++)
            assertTrue(ChainPull.reelSpeed(t) > ChainPull.reelSpeed(t - 1), "keeps speeding up at tick " + t);
    }

    @Test void longestGrappleStillArrivesBeforeGivingUp() {
        double travelled = 0;
        int t = 0;
        while (travelled < ChainPull.GRAPPLE_RANGE && t < ChainPull.REEL_MAX_TICKS) travelled += ChainPull.reelSpeed(t++);
        assertTrue(travelled >= ChainPull.GRAPPLE_RANGE - ChainPull.ARRIVE_DIST, "24 blocks covered in " + t + " ticks");
    }
}
