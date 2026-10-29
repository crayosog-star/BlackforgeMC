package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the EMP wave (no server needed). */
class EmpWaveTest {
    private static final double EPS = 1e-9;

    @Test void waveGrowsFromNothingToTheFullRadius() {
        assertEquals(0, EmpWave.radius(0, 10), EPS);
        assertEquals(10, EmpWave.radius(EmpWave.EXPAND_TICKS, 10), EPS);
        assertEquals(10, EmpWave.radius(EmpWave.EXPAND_TICKS + 5, 10), EPS);   // never past the edge
    }

    @Test void waveEasesOut() {
        double prevStep = Double.MAX_VALUE, prev = 0;
        for (int k = 1; k <= EmpWave.EXPAND_TICKS; k++) {
            double r = EmpWave.radius(k, 10), step = r - prev;
            assertTrue(step > 0, "keeps growing");
            assertTrue(step < prevStep, "slows down as it grows");
            prevStep = step;
            prev = r;
        }
    }

    @Test void defaultBlastIsTwentyBlocksAcross() {
        assertTrue(EmpWave.inside(10, 0, 0, EmpWave.DEFAULT_RADIUS, 0));
        assertTrue(EmpWave.inside(-7, -5, 7, EmpWave.DEFAULT_RADIUS, 0));    // 9.9 sideways, on the ground below
        assertFalse(EmpWave.inside(10.1, 0, 0, EmpWave.DEFAULT_RADIUS, 0));
        assertFalse(EmpWave.inside(0, -EmpWave.VERTICAL - 0.1, 0, EmpWave.DEFAULT_RADIUS, 0));
    }

    @Test void padLetsTheWaveCatchAWall() {
        // a 6-block cage whose middle is 14 blocks away: its wall is 8 away, inside a 10-block wave
        assertFalse(EmpWave.inside(14, 0, 0, 10, 0));
        assertTrue(EmpWave.inside(14, 0, 0, 10, 6));
    }

    @Test void detonatesAtTheTopOrAfterTheCap() {
        assertFalse(EmpWave.detonateNow(1, 0.0));                 // not before it's left the ground
        assertFalse(EmpWave.detonateNow(5, 0.5));                 // still rising
        assertTrue(EmpWave.detonateNow(6, 0.05));                 // the apex
        assertTrue(EmpWave.detonateNow(EmpWave.MAX_RISE_TICKS, 0.5));   // a low ceiling can't stall it
    }

    @Test void sphereDirectionsAreUnitAndSpread() {
        double[][] d = EmpWave.sphere(36);
        assertEquals(36, d.length);
        double sx = 0, sy = 0, sz = 0;
        for (double[] v : d) {
            assertEquals(1, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-9);
            sx += v[0]; sy += v[1]; sz += v[2];
        }
        assertEquals(0, sy, 1e-9);                       // top and bottom balance
        assertTrue(Math.abs(sx) < 1.5 && Math.abs(sz) < 1.5);   // no side heavily favoured
    }
}
