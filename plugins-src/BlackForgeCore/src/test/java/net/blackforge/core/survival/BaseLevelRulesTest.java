package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for base levels (no server needed). */
class BaseLevelRulesTest {
    private static final BaseLevelRules.Table T = BaseLevelRules.Table.DEFAULT;

    private static BaseLevelRules.Table withDeliveries(long... deliveries) {
        return new BaseLevelRules.Table(deliveries, T.money(), T.essence(), T.jobLevels(), T.allayLevels(), T.hopperLevels(), T.hopperMoney(), T.hopperEssence());
    }

    @Test void defaultCostsMatchThePlan() {
        assertEquals(new BaseLevelRules.Cost(2_000, 25_000, 128), T.cost(2));
        assertEquals(new BaseLevelRules.Cost(12_000, 100_000, 512), T.cost(3));
        assertEquals(new BaseLevelRules.Cost(52_000, 400_000, 2_048), T.cost(4));
        assertEquals(new BaseLevelRules.Cost(202_000, 2_000_000, 8_192), T.cost(5));
        assertThrows(IllegalArgumentException.class, () -> T.cost(1));
        assertThrows(IllegalArgumentException.class, () -> T.cost(6));
    }

    @Test void levellingUpNeedsTheDeliveryTarget() {
        assertFalse(T.canLevelUp(1, 1_999));
        assertTrue(T.canLevelUp(1, 2_000));
        assertFalse(T.canLevelUp(2, 2_000));
        assertFalse(T.canLevelUp(5, Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> T.canLevelUp(0, 0));
    }

    @Test void progressStaysBetweenZeroAndOne() {
        assertEquals(0, T.progress(1, 0), 1e-9);
        assertEquals(0.5, T.progress(1, 1_000), 1e-9);
        assertEquals(1, T.progress(1, 99_999), 1e-9);
        assertEquals(0, T.progress(1, -5), 1e-9);
        assertEquals(1, T.progress(5, 0), 1e-9);
    }

    @Test void farmerComesFirstAndGrinderLast() {
        assertEquals(1, T.jobLevel("farmer"));
        assertEquals(2, T.jobLevel("miner"));
        assertEquals(3, T.jobLevel("fisherman"));
        assertEquals(4, T.jobLevel("grinder"));
        assertTrue(T.jobUnlocked(1, "farmer"));
        assertFalse(T.jobUnlocked(1, "miner"));
        assertTrue(T.jobUnlocked(4, "grinder"));
        assertThrows(IllegalArgumentException.class, () -> T.jobLevel("baker"));
    }

    @Test void gathererUnlocksAtLevelThree() {
        assertEquals(3, T.allayLevel(1));
        assertEquals(4, T.allayLevel(2));
        assertEquals(5, T.allayLevel(3));
        assertFalse(T.allayUnlocked(2, 1));
        assertTrue(T.allayUnlocked(3, 1));
        assertFalse(T.allayUnlocked(4, 3));
        assertThrows(IllegalArgumentException.class, () -> T.allayLevel(4));
    }

    @Test void hopperTiersFollowLevelsAndTierThreeCostsFarMore() {
        assertEquals(1, T.hopperLevel(1));
        assertEquals(2, T.hopperLevel(2));
        assertEquals(4, T.hopperLevel(3));
        assertTrue(T.hopperUnlocked(2, 2));
        assertFalse(T.hopperUnlocked(3, 3));
        assertEquals(new BaseLevelRules.Price(150_000, 512), T.hopperCost(2));
        assertEquals(new BaseLevelRules.Price(1_500_000, 8_192), T.hopperCost(3));
        assertTrue(T.hopperCost(3).money() >= 10 * T.hopperCost(2).money());
        assertThrows(IllegalArgumentException.class, () -> T.hopperCost(1));
    }

    @Test void floorKeepsWhatABaseAlreadyHas() {
        assertEquals(1, T.floor(List.of(), 0, 0));
        assertEquals(1, T.floor(List.of("farmer"), 0, 1));
        assertEquals(4, T.floor(List.of("farmer", "grinder"), 0, 1));
        assertEquals(5, T.floor(List.of("miner"), 3, 0));
        assertEquals(4, T.floor(List.of(), 1, 3));
    }

    @Test void aBadTableIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> withDeliveries(100, 200, 300));
        assertThrows(IllegalArgumentException.class, () -> withDeliveries(0, 200, 300, 400));
        assertThrows(IllegalArgumentException.class, () -> withDeliveries(100, 100, 300, 400));
        assertThrows(IllegalArgumentException.class, () -> new BaseLevelRules.Table(T.deliveries(), T.money(), T.essence(), new int[]{2, 2, 3, 4}, T.allayLevels(), T.hopperLevels(), T.hopperMoney(), T.hopperEssence()));
        assertThrows(IllegalArgumentException.class, () -> new BaseLevelRules.Table(T.deliveries(), T.money(), T.essence(), T.jobLevels(), new int[]{5, 4, 3}, T.hopperLevels(), T.hopperMoney(), T.hopperEssence()));
        assertThrows(IllegalArgumentException.class, () -> new BaseLevelRules.Table(T.deliveries(), new int[]{-1, 1, 2, 3}, T.essence(), T.jobLevels(), T.allayLevels(), T.hopperLevels(), T.hopperMoney(), T.hopperEssence()));
    }
}
