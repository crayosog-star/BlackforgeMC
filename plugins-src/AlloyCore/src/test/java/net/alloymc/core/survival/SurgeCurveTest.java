package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Surge drain and meter (no server needed). */
class SurgeCurveTest {
    private static final double EPS = 1e-9;

    @Test void drainStartsSlowAndRises() {
        assertEquals(0.5, SurgeCurve.rate(0), EPS);
        assertEquals(2.0, SurgeCurve.rate(6), EPS);
        assertTrue(SurgeCurve.rate(3) > SurgeCurve.rate(1));
    }

    @Test void fullSurgeCostsAboutFourHearts() {
        assertEquals(7.5, SurgeCurve.drained(6), EPS);    // 3.75 hearts
        assertEquals(1.5, SurgeCurve.drained(2), EPS);    // 0.75 hearts
        assertEquals(4.0, SurgeCurve.drained(4), EPS);    // 2 hearts
    }

    @Test void drainStopsAfterSixSeconds() {
        assertEquals(SurgeCurve.drained(6), SurgeCurve.drained(9), EPS);
        assertEquals(0, SurgeCurve.drainBetween(6, 7), EPS);
    }

    @Test void ticksAddUpToTheTotal() {
        double sum = 0;
        for (int i = 0; i < 12; i++) sum += SurgeCurve.drainBetween(i * 0.5, (i + 1) * 0.5);
        assertEquals(SurgeCurve.drained(6), sum, 1e-9);
    }

    @Test void meterShowsSecondsLeft() {
        assertEquals("▰▰▰▰▰▰ 6.0s", SurgeCurve.meter(6));
        assertEquals("▰▰▰▰▱▱ 3.2s", SurgeCurve.meter(3.2));
        assertEquals("▱▱▱▱▱▱ 0.0s", SurgeCurve.meter(0));
    }

    @Test void neverBelowHalfHealth() {
        assertEquals(10, SurgeCurve.floor(20), EPS);
        assertEquals(15, SurgeCurve.floor(30), EPS);   // with extra max health (e.g. Overload)
        assertTrue(SurgeCurve.canStart(20, 20));
        assertTrue(SurgeCurve.canStart(12, 20));
        assertFalse(SurgeCurve.canStart(11, 20));
        assertFalse(SurgeCurve.canStart(10, 20));
    }

    @Test void warningInTheLastStretch() {
        assertFalse(SurgeCurve.warning(4.0));
        assertTrue(SurgeCurve.warning(4.6));
    }

    @Test void edgesThrobOnTheHeartbeat() {
        assertEquals(1.0, SurgeCurve.edgeGlow(0, false), EPS);            // full on the beat
        assertEquals(1.0, SurgeCurve.edgeGlow(20, false), EPS);           // every second
        assertTrue(SurgeCurve.edgeGlow(4, false) > SurgeCurve.edgeGlow(3, false));   // the second "dub"
        assertTrue(SurgeCurve.edgeGlow(19, false) < 0.6);                 // eases back to the base between beats
        assertTrue(SurgeCurve.edgeGlow(19, false) >= SurgeCurve.EDGE_BASE);
        assertEquals(1.0, SurgeCurve.edgeGlow(10, true), EPS);            // twice as fast in the last stretch
        for (int t = 0; t < 200; t++) {
            double g = SurgeCurve.edgeGlow(t, t > 90);
            assertTrue(g >= 0 && g <= 1);
        }
    }

    @Test void warningDistanceGivesTheWantedStrength() {
        for (double s : new double[]{0.3, 0.55, 0.9}) {
            int w = SurgeCurve.warningDistance(200, s);
            assertEquals(s, 1 - 200.0 / w, 0.01);                         // what the client will compute
        }
        assertTrue(SurgeCurve.warningDistance(200, 1.0) <= 1_000_000);    // capped, never overflows
        assertTrue(SurgeCurve.warningDistance(0, 0.5) >= 1);              // standing on the edge
    }
}
