package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static net.blackforge.core.survival.SunderingMath.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for The Sundering (no server needed). */
class SunderingMathTest {
    private static final double EPS = 1e-9;

    @Test void everyPitAndPillarIsInsideTheArena() {
        for (int i = 0; i < PITS; i++) {
            int[] c = pit(i);
            assertTrue(Math.hypot(c[0] - CX, c[1] - CZ) + ANCHOR_RADIUS + 2 < RADIUS);
            for (int j = 0; j < CHAINS; j++) {
                int[] a = anchor(i, j);
                assertEquals(ANCHOR_RADIUS, Math.hypot(a[0] - c[0], a[1] - c[1]), 1.0);
            }
        }
        assertFalse(inPit(CX, CZ));
    }

    @Test void fourChainsSnapInOrderBeforeTheBreakOut() {
        int next = 0;
        for (int t = 0; t <= BREAK_OUT; t++) {
            int s = snapAt(t);
            if (s < 0) continue;
            assertEquals(next++, s);
            assertTrue(t > TREMOR_END && t < BREAK_OUT);
        }
        assertEquals(CHAINS, next);
    }

    @Test void slumpEasesWithoutJumping() {
        assertEquals(1, slump(0), EPS);
        assertEquals(0, slump(BREAK_OUT), EPS);
        for (int t = 1; t <= BREAK_OUT; t++) {
            assertTrue(slump(t) <= slump(t - 1) + EPS);
            assertTrue(slump(t - 1) - slump(t) < 0.02, "no jump between ticks");
        }
    }

    @Test void leapStartsAndEndsOnTheGroundAndArcs() {
        assertArrayEquals(new double[]{0, 100, 0}, leap(0, 100, 0, 70, 101, 0, 0, 100, 30), EPS);
        assertArrayEquals(new double[]{70, 101, 0}, leap(0, 100, 0, 70, 101, 0, 100, 100, 30), EPS);
        assertTrue(leap(0, 100, 0, 70, 101, 0, 50, 100, 30)[1] > 125);
        for (int s = 1; s <= 100; s++) {
            double[] a = leap(0, 100, 0, 70, 101, 0, s - 1, 100, 30), b = leap(0, 100, 0, 70, 101, 0, s, 100, 30);
            assertTrue(Math.hypot(b[0] - a[0], b[1] - a[1]) < 2.5, "no teleport-looking steps");
        }
    }

    @Test void aCrowdTakesAboutAsLongAsOnePlayerAndHitsNeverGrow() {
        assertEquals(600, 1500 / (12 * incomingMultiplier(1500, 1, 600, 12)), EPS);
        assertEquals(600, 1500 / (25 * 12 * incomingMultiplier(1500, 25, 600, 12)), EPS);
        assertEquals(1, incomingMultiplier(100000, 1, 600, 12), EPS);
        assertEquals(incomingMultiplier(1500, 1, 600, 12), incomingMultiplier(1500, 0, 600, 12), EPS);
    }

    @Test void attacksSpeedUpAtEachPhase() {
        assertTrue(cadenceSeconds(0.5, 0.5) < cadenceSeconds(0.9, 0.5));
        assertTrue(cadenceSeconds(0.2, 0.5) < cadenceSeconds(0.5, 0.5));
    }

    @Test void sharesAreSortedAndAddUp() {
        Map<String, Double> dmg = new LinkedHashMap<>();
        dmg.put("small", 25.0);
        dmg.put("big", 75.0);
        Map<String, Double> s = shares(dmg);
        assertEquals("big", s.keySet().iterator().next());
        assertEquals(0.75, s.get("big"), EPS);
        assertTrue(shares(Map.of("x", 0.0)).isEmpty());
    }

    @Test void underTheMinimumGetsNothingAndTheWholePoolIsStillPaid() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("a", 0.6);
        s.put("b", 0.395);
        s.put("tagger", 0.005);
        Map<String, Double> e = eligible(s, 0.01);
        assertFalse(e.containsKey("tagger"));
        assertEquals(1, e.values().stream().mapToDouble(Double::doubleValue).sum(), EPS);
        long paid = e.values().stream().mapToLong(v -> payout(25000, v)).sum();
        assertTrue(paid <= 25000 && paid >= 24998);
    }

    @Test void rollsFollowShares() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("a", 0.75);
        s.put("b", 0.25);
        assertEquals("a", rollWinner(s, 0.0));
        assertEquals("a", rollWinner(s, 0.74));
        assertEquals("b", rollWinner(s, 0.75));
        assertEquals("b", rollWinner(s, 0.999999));
        assertNull(rollWinner(Map.<String, Double>of(), 0.5));
    }

    @Test void theSameBossNeverWakesTwiceInARowAndAllCanWake() {
        Set<Integer> seen = new HashSet<>();
        for (double r = 0; r < 1; r += 0.01) {
            int i = pickBoss(5, 2, r);
            assertNotEquals(2, i);
            assertTrue(i >= 0 && i < 5);
            seen.add(i);
        }
        assertEquals(4, seen.size());
        assertTrue(pickBoss(5, -1, 0.999) < 5);
    }
}
