package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LabMathTest {
    @Test void urnLocksForOwnerThenOpens() {
        long lock = 600_000;
        assertTrue(LabMath.urnOpenFor(true, 0, 1, lock));
        assertFalse(LabMath.urnOpenFor(false, 0, lock - 1, lock));
        assertTrue(LabMath.urnOpenFor(false, 0, lock, lock));
        assertEquals(600, LabMath.urnLockLeft(0, 0, lock));
        assertEquals(1, LabMath.urnLockLeft(0, lock - 1, lock));
        assertEquals(0, LabMath.urnLockLeft(0, lock + 5000, lock));
        assertEquals("4:05", LabMath.clock(245));
        assertEquals("0:00", LabMath.clock(-3));
        assertTrue(LabMath.urnExpired(0, 3_600_000, 3_600_000));
    }

    @Test void drawPotAndWinner() {
        assertEquals(900, LabMath.drawPot(10, 100, 0.10), 1e-9);
        assertEquals(1000, LabMath.drawPot(10, 100, -1), 1e-9);
        assertEquals(-1, LabMath.drawWinner(List.of(), 0.5));
        assertEquals(-1, LabMath.drawWinner(List.of(0, 0), 0.5));
        assertEquals(0, LabMath.drawWinner(List.of(1, 3), 0.0));
        assertEquals(0, LabMath.drawWinner(List.of(1, 3), 0.24));
        assertEquals(1, LabMath.drawWinner(List.of(1, 3), 0.25));
        assertEquals(1, LabMath.drawWinner(List.of(1, 3), 1.0));
        assertEquals(2, LabMath.drawWinner(List.of(1, 0, 1), 0.9));
    }

    @Test void drawPotKeepsWhatWasPaid() {
        // 10 tickets at 100 with a 10% cut, then the price goes to 200 and the cut to 0 before 1 more ticket:
        // the pot is 900 + 200, not 11 x 200.
        double pot = LabMath.drawPotAfterBuy(0, 1000, 0.10);
        assertEquals(900, pot, 1e-9);
        assertEquals(1100, LabMath.drawPotAfterBuy(pot, 200, 0), 1e-9);
        assertEquals(1000, LabMath.drawPotAfterBuy(0, 1000, -1), 1e-9);
        assertEquals(0, LabMath.drawPotAfterBuy(0, 1000, 2), 1e-9);
        assertEquals(0.03, LabMath.drawPotAfterBuy(0.01, 0.02, 0), 1e-9);
    }

    @Test void pricesFallWithSalesAndRecover() {
        assertEquals(1, LabMath.priceFactor(0, 2000, 0.3), 1e-9);
        assertEquals(0.5, LabMath.priceFactor(2000, 2000, 0.3), 1e-9);
        assertEquals(0.3, LabMath.priceFactor(1e9, 2000, 0.3), 1e-9);
        assertEquals(500, LabMath.decay(1000, 6, 6), 1e-9);
        assertEquals(1000, LabMath.decay(1000, 0, 6), 1e-9);
        double prev = 1e9;
        for (int h = 0; h < 48; h++) { double d = LabMath.decay(1000, h, 6); assertTrue(d < prev); prev = d; }
    }

    @Test void bonds() {
        assertEquals(10_800, LabMath.bondPayout(10_000, 8), 1e-9);
        assertFalse(LabMath.bondMatured(0, 3 * 86_400_000L - 1, 3));
        assertTrue(LabMath.bondMatured(0, 3 * 86_400_000L, 3));
        assertTrue(LabMath.bondFits(40_000, 10_000, 50_000, 1000));
        assertFalse(LabMath.bondFits(40_000, 10_001, 50_000, 1000));
        assertFalse(LabMath.bondFits(0, 999, 50_000, 1000));
    }

    @Test void ashfallSchedule() {
        assertFalse(LabMath.ashfallNight(0, 5));
        assertTrue(LabMath.ashfallNight(5, 5));
        assertTrue(LabMath.ashfallTomorrow(4, 5));
        assertFalse(LabMath.ashfallNight(7, 0));
        assertTrue(LabMath.isNight(13000));
        assertFalse(LabMath.isNight(23000));
        assertTrue(LabMath.isNight(24000 * 3 + 18000));
    }

    @Test void riftWavesGrowAndScaleEases() {
        assertTrue(LabMath.riftWave(2, 1) > LabMath.riftWave(1, 1));
        assertTrue(LabMath.riftWave(1, 3) > LabMath.riftWave(1, 1));
        assertEquals(0, LabMath.riftScale(0, 20), 1e-9);
        assertEquals(1, LabMath.riftScale(20, 20), 1e-9);
        assertEquals(1, LabMath.riftScale(99, 20), 1e-9);
        double prev = 0;
        for (int t = 1; t <= 20; t++) {
            double s = LabMath.riftScale(t, 20);
            assertTrue(s >= prev && s - prev < 0.2, "no jump at tick " + t);
            prev = s;
        }
    }

    @Test void contracts() {
        assertEquals(2000, LabMath.contractEscrow(64, 31.25), 1e-9);
        assertEquals(50, LabMath.contractFee(2000, 1, 50), 1e-9);
        assertEquals(200, LabMath.contractFee(20_000, 1, 50), 1e-9);
        assertEquals(10, LabMath.contractFill(64, 54, 30));
        assertEquals(0, LabMath.contractFill(64, 64, 30));
        assertEquals(5, LabMath.contractFill(64, 0, 5));
    }
}
