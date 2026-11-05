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

    @Test void checkComparesFlyingAllaysWithTheConfiguredFlock() {
        assertTrue(FoundryTestRules.flockFlying(true, 5, 5), "a flock of 5 configured, 5 up");
        assertFalse(FoundryTestRules.flockFlying(true, 3, 5), "3 is not the whole flock when 5 are configured");
        assertFalse(FoundryTestRules.flockFlying(false, 3, 3), "the tier change was refused, so the old flock doesn't count");
        assertEquals("4 of 6", FoundryTestRules.flyingDetail(true, 4, 6));
        assertTrue(FoundryTestRules.flyingDetail(false, 1, 3).contains("refused by the allay limit"));
    }

    @Test void checkOnlyCountsAsADemoWhenItPlacedAnAgent() {
        assertTrue(FoundryTestRules.placedAny(1));
        assertTrue(FoundryTestRules.placedAny(4));
        assertFalse(FoundryTestRules.placedAny(0), "every spot taken or at the agent limit");
        assertFalse(FoundryTestRules.placedAny(-1), "a check was already running");
    }
}
