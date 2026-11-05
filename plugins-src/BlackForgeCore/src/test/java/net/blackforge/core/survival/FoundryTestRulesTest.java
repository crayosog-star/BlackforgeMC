package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Foundry's test decisions (no server needed). */
class FoundryTestRulesTest {
    @Test void aPlayerInTheLeaveGraceKeepsTheIslandOccupied() {
        assertTrue(FoundryTestRules.occupied(false, true));
        assertTrue(FoundryTestRules.occupied(true, false));
        assertTrue(FoundryTestRules.occupied(true, true));
        assertFalse(FoundryTestRules.occupied(false, false));
    }

    @Test void demoAgentsOnlyGoOnFreeSpotsThePlayerCouldUse() {
        assertTrue(FoundryTestRules.demoSpotFree(false, true, true));
        assertFalse(FoundryTestRules.demoSpotFree(true, true, true), "an agent's already there");
        assertFalse(FoundryTestRules.demoSpotFree(false, false, true), "no room");
        assertFalse(FoundryTestRules.demoSpotFree(false, true, false), "another tester's block");
    }

    @Test void checkRestoresTheTierOnlyIfStillTestingAndUnchanged() {
        assertTrue(FoundryTestRules.restoreAfterCheck(true, FoundryTestRules.CHECK_TIER));
        assertFalse(FoundryTestRules.restoreAfterCheck(false, FoundryTestRules.CHECK_TIER), "the island emptied");
        assertFalse(FoundryTestRules.restoreAfterCheck(true, 0), "reset when it emptied, then nobody came back");
        assertFalse(FoundryTestRules.restoreAfterCheck(true, 1), "a new tester got Tier I");
        assertFalse(FoundryTestRules.restoreAfterCheck(true, 2), "another tester set a tier");
    }
}
