package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Nightrend daggers (no server needed). */
class NightrendTest {
    private static final double EPS = 1e-9;

    @Test void modifiersGiveTheAgreedNumbers() {
        assertEquals(4.0, NightrendMath.damageModifier(5), EPS);    // 1 base + 4 = 5 damage
        assertEquals(-2.0, NightrendMath.speedModifier(2.0), EPS);  // 4 base - 2 = 2.0 attacks a second
        assertEquals(0.6, NightrendMath.pairBonus(2.0, 2.6), EPS);
        assertEquals(0, NightrendMath.pairBonus(2.6, 2.0), EPS);    // a pair is never slower
    }

    @Test void straightBehindIsBehind() {
        // target faces +Z, attacker is at -Z
        assertTrue(NightrendMath.behind(0, 1, 0, -1, 120));
    }

    @Test void inFrontAndToTheSideAreNot() {
        assertFalse(NightrendMath.behind(0, 1, 0, 1, 120));     // face to face
        assertFalse(NightrendMath.behind(0, 1, 1, 0, 120));     // square to the side
    }

    @Test void arcEdgeIsSixtyDegreesEachSideOfTheBack() {
        double in = Math.toRadians(180 - 59), out = Math.toRadians(180 - 61);
        // angle measured from the facing direction (+Z): 121 degrees away is inside a 120 arc, 119 is outside
        assertTrue(NightrendMath.behind(0, 1, Math.sin(in), Math.cos(in), 120));
        assertFalse(NightrendMath.behind(0, 1, Math.sin(out), Math.cos(out), 120));
    }

    @Test void lengthOfTheVectorsDoesNotMatter() {
        assertTrue(NightrendMath.behind(0, 5, 0, -0.01, 120));
    }

    @Test void onTopOfTheTargetIsNeverBehind() {
        assertFalse(NightrendMath.behind(0, 1, 0, 0, 120));
        assertFalse(NightrendMath.behind(0, 0, 0, -1, 120));
    }

    @Test void behindHitsDoSixtyPercent() {
        assertEquals(3.0, 5 * NightrendMath.hitMultiplier(true, 0.6), EPS);
        assertEquals(5.0, 5 * NightrendMath.hitMultiplier(false, 0.6), EPS);
        assertEquals(1.0, NightrendMath.hitMultiplier(true, 1.7), EPS);    // a bad config can't make it stronger
        assertEquals(0.0, NightrendMath.hitMultiplier(true, -1), EPS);
    }

    @Test void armsAlternateUnlessTheRollRepeats() {
        assertTrue(NightrendMath.nextIsOffHand(false, 0.99, 0.2));    // main -> off
        assertFalse(NightrendMath.nextIsOffHand(true, 0.99, 0.2));    // off -> main
        assertFalse(NightrendMath.nextIsOffHand(false, 0.1, 0.2));    // the 20%: main again
        assertTrue(NightrendMath.nextIsOffHand(true, 0.1, 0.2));      // off again
        assertTrue(NightrendMath.nextIsOffHand(false, 0.0, 0));       // 0 = always alternate
        assertTrue(NightrendMath.nextIsOffHand(false, 0.95, 5));      // a bad config never locks one arm forever
    }

    @Test void ownDrawHoldsLowThenFlipsEvenlyPerTick() {
        int n = NightrendMath.DRAW_TICKS;
        for (int t = 0; t <= NightrendMath.DRAW_HOLD; t++) {        // the hold: upside down and low
            assertEquals(-180, NightrendMath.drawOffset(t / (double) n), EPS);
            assertEquals(1, NightrendMath.drawDrop(t / (double) n), EPS);
        }
        assertEquals(0, NightrendMath.drawOffset(1), EPS);           // ends upright and in place
        assertEquals(0, NightrendMath.drawDrop(1), EPS);
        double max = -999;
        for (int t = 1; t <= n; t++) {
            double a = NightrendMath.drawOffset((t - 1) / (double) n), b = NightrendMath.drawOffset(t / (double) n);
            assertTrue(Math.abs(b - a) <= 22, "tick " + t + " turns " + Math.abs(b - a));   // no big per-tick jumps
            max = Math.max(max, b);
        }
        assertTrue(max > 0 && max < 6, "a small settle past upright, not a wobble");
    }

    @Test void repeatChanceComesOutRoughlyRight() {
        java.util.Random r = new java.util.Random(1);
        int repeats = 0, n = 100_000;
        boolean side = false;
        for (int i = 0; i < n; i++) { boolean next = NightrendMath.nextIsOffHand(side, r.nextDouble(), 0.2); if (next == side) repeats++; side = next; }
        assertEquals(0.2, repeats / (double) n, 0.01);
    }

    @Test void slashSweepsLeftToRightAndEasesOut() {
        assertEquals(-NightrendMath.SLASH_ARC_DEGREES / 2, NightrendMath.slashAngle(0), EPS);
        assertEquals(NightrendMath.SLASH_ARC_DEGREES / 2, NightrendMath.slashAngle(NightrendMath.SLASH_TICKS), EPS);
        assertEquals(NightrendMath.SLASH_ARC_DEGREES / 2, NightrendMath.slashAngle(NightrendMath.SLASH_TICKS + 3), EPS);   // no overshoot
        double prev = NightrendMath.slashAngle(0), prevStep = Double.MAX_VALUE;
        for (int t = 1; t <= NightrendMath.SLASH_TICKS; t++) {
            double a = NightrendMath.slashAngle(t), step = a - prev;
            assertTrue(step > 0, "keeps moving right");
            assertTrue(step < prevStep, "slows down");
            assertTrue(step < NightrendMath.SLASH_ARC_DEGREES * 0.6, "no single-tick jump across the arc");
            prevStep = step;
            prev = a;
        }
    }

    @Test void flipStartsPointingDownAndEndsUpright() {
        assertEquals(-180, NightrendMath.flipPitch(0), EPS);
        assertEquals(0, NightrendMath.flipPitch(NightrendMath.FLIP_TICKS), EPS);
        assertEquals(0, NightrendMath.flipPitch(NightrendMath.FLIP_TOTAL), EPS);     // stays put while it holds and fades
    }

    @Test void flipSnapsJustPastUprightAndNoMore() {
        double max = -999;
        for (int t = 0; t <= NightrendMath.FLIP_TICKS; t++) max = Math.max(max, NightrendMath.flipPitch(t));
        assertTrue(max > 0, "a little snap past upright");
        assertTrue(max < 15, "but only a little");
    }

    @Test void flipHasNoBigJumpBetweenTicks() {
        // a snappy flip: the first tick turns most, but well under a quarter-turn, and the client blends each tick
        for (int t = 1; t <= NightrendMath.FLIP_TICKS; t++)
            assertTrue(Math.abs(NightrendMath.flipPitch(t) - NightrendMath.flipPitch(t - 1)) < 70, "tick " + t);
    }

    @Test void liftRisesThenHolds() {
        assertEquals(0, NightrendMath.flipLift(0), EPS);
        assertEquals(NightrendMath.FLIP_LIFT, NightrendMath.flipLift(NightrendMath.FLIP_TICKS), EPS);
        assertEquals(NightrendMath.FLIP_LIFT, NightrendMath.flipLift(NightrendMath.FLIP_TOTAL), EPS);
        for (int t = 1; t <= NightrendMath.FLIP_TICKS; t++)
            assertTrue(NightrendMath.flipLift(t) > NightrendMath.flipLift(t - 1));
    }

    @Test void scaleGrowsInWithoutPoppingThenHoldsForTheHandover() {
        assertEquals(0, NightrendMath.flipScale(0), EPS);
        assertEquals(1, NightrendMath.flipScale(NightrendMath.FLIP_TICKS), EPS);
        assertEquals(1, NightrendMath.flipScale(NightrendMath.FLIP_TOTAL), EPS);   // full size when the real daggers take over
        for (int t = 1; t <= NightrendMath.FLIP_TOTAL; t++)
            assertTrue(Math.abs(NightrendMath.flipScale(t) - NightrendMath.flipScale(t - 1)) <= 0.34, "tick " + t);
    }
}
