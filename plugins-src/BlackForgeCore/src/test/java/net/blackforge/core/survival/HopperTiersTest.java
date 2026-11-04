package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for hopper tiers (no server needed). */
class HopperTiersTest {
    @Test void tiersRunFromVanillaToEightTimesVanilla() {
        assertEquals(2.5, HopperTiers.itemsPerSecond(HopperTiers.DELAYS[0]), 1e-9);
        assertEquals(5, HopperTiers.itemsPerSecond(HopperTiers.DELAYS[1]), 1e-9);
        assertEquals(20, HopperTiers.itemsPerSecond(HopperTiers.DELAYS[2]), 1e-9);
    }

    @Test void upgradesGetFarMoreExpensive() {
        assertEquals(1, HopperTiers.upgradesToLeave(1, HopperTiers.UPGRADES_NEEDED));
        assertEquals(10, HopperTiers.upgradesToLeave(2, HopperTiers.UPGRADES_NEEDED));
        assertEquals(0, HopperTiers.upgradesToLeave(3, HopperTiers.UPGRADES_NEEDED));
    }

    @Test void tiersStayInRange() {
        assertEquals(1, HopperTiers.clampTier(0));
        assertEquals(3, HopperTiers.clampTier(9));
    }

    @Test void positionsSurviveTheChunkList() {
        for (int[] p : new int[][]{{0, -64, 0}, {15, 319, 15}, {-1, 70, -16}, {31, -1, 17}}) {
            int packed = HopperTiers.pack(p[0], p[1], p[2]);
            assertEquals(p[0] & 15, HopperTiers.localX(packed));
            assertEquals(p[2] & 15, HopperTiers.localZ(packed));
            assertEquals(p[1], HopperTiers.y(packed));
        }
    }
}
