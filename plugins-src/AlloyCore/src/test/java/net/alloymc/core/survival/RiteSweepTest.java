package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RiteSweepTest {
    private static final double[] NORTH = RiteSweep.facing(180f);   // yaw 180 = facing -z

    @Test
    void coversThirtyBlocksInThirtyTicks() {
        assertEquals(30, RiteSweep.travelTicks());
        assertEquals(RiteSweep.START + 30, RiteSweep.reach(30), 1e-9);
        assertEquals(RiteSweep.START + 30, RiteSweep.reach(500), 1e-9);   // never past the range
    }

    @Test
    void facingIsHorizontalUnitLength() {
        for (float yaw : new float[]{0, 37, 90, 180, -135}) {
            double[] f = RiteSweep.facing(yaw);
            assertEquals(1.0, Math.hypot(f[0], f[1]), 1e-9);
        }
        assertArrayEquals(new double[]{0, -1}, NORTH, 1e-9);
    }

    @Test
    void widthEdge() {
        // 10 blocks ahead, 2.4 to the side: caught; 2.6: not
        assertTrue(RiteSweep.touches(0, 64, 0, NORTH, 9, 11, 2.4, 64, -10));
        assertFalse(RiteSweep.touches(0, 64, 0, NORTH, 9, 11, 2.6, 64, -10));
    }

    @Test
    void neverBehindTheCaster() {
        assertFalse(RiteSweep.touches(0, 64, 0, NORTH, 0, 31, 0, 64, 3));
    }

    @Test
    void notAboveTheWall() {
        // the wall runs from feet - 0.5 to feet + 3.5; a player standing 3.6 up is clear of it
        assertTrue(RiteSweep.touches(0, 64, 0, NORTH, 4, 6, 0, 67.4, -5));
        assertFalse(RiteSweep.touches(0, 64, 0, NORTH, 4, 6, 0, 67.6, -5));
    }

    @Test
    void sweptSlabCatchesWhatAFastWallSkipsOver() {
        // between ticks the wall jumps a whole block; someone in the gap is still caught
        assertTrue(RiteSweep.touches(0, 64, 0, NORTH, RiteSweep.reach(4), RiteSweep.reach(5), 0, 64, -5.5));
    }

    @Test
    void nearerPlayerIsReachedFirst() {
        int first = -1, second = -1;
        for (int t = 1; t <= RiteSweep.travelTicks(); t++) {
            double from = RiteSweep.reach(t - 1), to = RiteSweep.reach(t);
            if (first < 0 && RiteSweep.touches(0, 64, 0, NORTH, from, to, 0, 64, -8)) first = t;
            if (second < 0 && RiteSweep.touches(0, 64, 0, NORTH, from, to, 1, 64, -20)) second = t;
        }
        assertTrue(first > 0 && second > first);
    }

    @Test
    void lookingStraightUpStillSendsTheWaveForward() {
        // only the yaw is used: the same yaw gives the same flat path whatever the pitch
        assertTrue(RiteSweep.touches(0, 64, 0, RiteSweep.facing(180f), 9, 11, 0, 64, -10));
    }
}
