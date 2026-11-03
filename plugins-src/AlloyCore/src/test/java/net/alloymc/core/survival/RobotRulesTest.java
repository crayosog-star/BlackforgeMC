package net.alloymc.core.survival;

import net.alloymc.core.survival.RobotRules.FuelCell;
import net.alloymc.core.survival.RobotRules.SegmentColor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for robot fishing pace, fuel meter, triggers and charge blending (no server needed). */
class RobotRulesTest {
    private static final double EPS = 1e-9;
    private static final long MIN = 60_000L, HOUR = 60 * MIN;

    @Test void fishIntervalPerTier() {
        assertEquals(180_000, RobotRules.fishIntervalMs(1));
        assertEquals(150_000, RobotRules.fishIntervalMs(2));
        assertEquals(120_000, RobotRules.fishIntervalMs(3));
        assertEquals(90_000, RobotRules.fishIntervalMs(4));
        assertEquals(60_000, RobotRules.fishIntervalMs(5));
        assertEquals(20, RobotRules.fishPerHour(1));
        assertEquals(60, RobotRules.fishPerHour(5));
        assertThrows(IllegalArgumentException.class, () -> RobotRules.fishIntervalMs(0));
        assertThrows(IllegalArgumentException.class, () -> RobotRules.fishIntervalMs(6));
    }

    @Test void litSegmentsAtThresholds() {
        assertEquals(0, RobotRules.litSegments(0));
        assertEquals(0, RobotRules.litSegments(5 * MIN - 1));
        assertEquals(1, RobotRules.litSegments(5 * MIN));
        assertEquals(2, RobotRules.litSegments(15 * MIN));
        assertEquals(4, RobotRules.litSegments(HOUR));
        assertEquals(6, RobotRules.litSegments(24 * HOUR - 1));
        assertEquals(7, RobotRules.litSegments(24 * HOUR));
        assertEquals(7, RobotRules.litSegments(48 * HOUR));
    }

    @Test void segmentColours() {
        assertEquals(SegmentColor.RED, RobotRules.segmentColor(1));
        assertEquals(SegmentColor.RED, RobotRules.segmentColor(2));
        assertEquals(SegmentColor.YELLOW, RobotRules.segmentColor(3));
        assertEquals(SegmentColor.YELLOW, RobotRules.segmentColor(4));
        assertEquals(SegmentColor.GREEN, RobotRules.segmentColor(5));
        assertEquals(SegmentColor.GREEN, RobotRules.segmentColor(7));
        assertThrows(IllegalArgumentException.class, () -> RobotRules.segmentColor(0));
        assertThrows(IllegalArgumentException.class, () -> RobotRules.segmentColor(8));
    }

    @Test void fetchFuelAtTwoMinutesOfBurnOrEmpty() {
        assertTrue(RobotRules.shouldFetchFuel(120_000, 1000));     // exactly 2 min at full burn
        assertFalse(RobotRules.shouldFetchFuel(120_001, 1000));
        assertTrue(RobotRules.shouldFetchFuel(30_000, 250));       // Fisherman: 2 min of burn = 30 s of fuel
        assertFalse(RobotRules.shouldFetchFuel(30_001, 250));
        assertTrue(RobotRules.shouldFetchFuel(0, 1000));
        assertTrue(RobotRules.shouldFetchFuel(0, 0));
    }

    @Test void storeAtThresholdOrWhenFull() {
        assertTrue(RobotRules.shouldStore(List.of(3, 20), 20, false));
        assertFalse(RobotRules.shouldStore(List.of(3, 19), 20, false));
        assertTrue(RobotRules.shouldStore(List.of(1), 20, true));
        assertTrue(RobotRules.shouldStore(List.of(), 20, true));
        assertFalse(RobotRules.shouldStore(List.of(), 20, false));
    }

    @Test void blendedChargeRateIsFuelWeighted() {
        assertEquals(2.0, RobotRules.blendedChargeRate(List.of(new FuelCell(1, HOUR), new FuelCell(5, HOUR))), EPS);
        assertEquals(3.0, RobotRules.blendedChargeRate(List.of(new FuelCell(5, 24 * HOUR))), EPS);
        assertEquals(1.0, RobotRules.blendedChargeRate(List.of()), EPS);
        // the old exploit: a tank of cheap fuel topped up with a little tier V fuel must not all burn at x3
        double r = RobotRules.blendedChargeRate(List.of(new FuelCell(1, 24 * HOUR), new FuelCell(5, HOUR)));
        assertEquals((24 * 1.0 + 1 * 3.0) / 25, r, EPS);
    }

    @Test void blendRateOnlyWeighsNewlyAcceptedFuel() {
        assertEquals(3.0, RobotRules.blendRate(0, 1.0, HOUR, 3.0), EPS);              // empty tank takes the new rate
        assertEquals(2.0, RobotRules.blendRate(HOUR, 1.0, HOUR, 3.0), EPS);
        assertEquals(1.5, RobotRules.blendRate(3 * HOUR, 1.25, HOUR, 2.25), EPS);
        // blending one cell at a time matches blending the whole list at once
        double step = RobotRules.blendRate(0, 1.0, 30 * MIN, RobotRules.rateForTier(1));
        step = RobotRules.blendRate(30 * MIN, step, 24 * HOUR, RobotRules.rateForTier(5));
        assertEquals(RobotRules.blendedChargeRate(List.of(new FuelCell(1, 30 * MIN), new FuelCell(5, 24 * HOUR))), step, EPS);
    }

    @Test void tierRatesPreserved() {
        double[] expected = {1.0, 1.25, 1.5, 2.0, 3.0};
        for (int t = 1; t <= 5; t++) assertEquals(expected[t - 1], RobotRules.rateForTier(t), EPS);
    }

    @Test void lowestTierCellFirst() {
        assertEquals(Optional.empty(), RobotRules.lowestTierCell(List.of()));
        FuelCell a = new FuelCell(3, HOUR), b = new FuelCell(1, 30 * MIN), c = new FuelCell(1, 30 * MIN);
        Optional<FuelCell> pick = RobotRules.lowestTierCell(List.of(a, b, c));
        assertTrue(pick.isPresent());
        assertSame(b, pick.get());   // first on ties
    }
}
