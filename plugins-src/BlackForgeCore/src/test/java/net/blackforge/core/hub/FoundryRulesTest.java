package net.blackforge.core.hub;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Foundry's bounds and block ownership (no server needed). */
class FoundryRulesTest {
    @Test void zoneIsClearOfTheExistingIsland() {
        assertFalse(FoundryZone.contains(0.5, TestArenaBuilder.TARGET_Z));
        assertFalse(FoundryZone.contains(TestArenaBuilder.SPAWN_X, TestArenaBuilder.SPAWN_Z));
        assertFalse(FoundryZone.contains(0.5, -44.5));
    }

    @Test void zoneEdgesAreInclusiveByBlock() {
        assertTrue(FoundryZone.contains(0.5, -46.5));
        assertTrue(FoundryZone.contains(0.5, -45.01));
        assertTrue(FoundryZone.contains(-30, -96));
        assertTrue(FoundryZone.contains(30.99, -95.5));
        assertFalse(FoundryZone.contains(31, -70));
        assertFalse(FoundryZone.contains(-30.01, -70));
        assertFalse(FoundryZone.contains(0, -96.01));
    }

    @Test void entryIsInsideAndChunksCoverTheZone() {
        assertTrue(FoundryZone.contains(FoundryZone.ENTRY_X, FoundryZone.ENTRY_Z));
        assertTrue(FoundryZone.MIN_CHUNK_Z * 16 <= FoundryZone.MIN_Z);
    }

    @Test void onlyTheOwnerMayBreakAndTheIslandIsNobodys() {
        PlacedBlocks p = new PlacedBlocks();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        PlacedBlocks.Pos at = new PlacedBlocks.Pos(1, 101, -60);
        assertFalse(p.mayBreak(at, a));
        p.add(at, a);
        assertTrue(p.mayBreak(at, a));
        assertFalse(p.mayBreak(at, b));
    }

    @Test void leavingClearsOnlyYourOwnBlocks() {
        PlacedBlocks p = new PlacedBlocks();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        PlacedBlocks.Pos x = new PlacedBlocks.Pos(1, 101, -60), y = new PlacedBlocks.Pos(2, 101, -60);
        p.add(x, a);
        p.add(y, b);
        assertEquals(java.util.List.of(x), p.removeAll(a));
        assertNull(p.owner(x));
        assertEquals(b, p.owner(y));
        assertTrue(p.removeAll(a).isEmpty());
    }
}
