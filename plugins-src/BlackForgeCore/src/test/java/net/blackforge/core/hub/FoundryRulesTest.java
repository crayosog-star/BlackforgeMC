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

    @Test void everyBenchIsOnTheIsland() {
        for (int[] at : new int[][]{{FoundryZone.FORGE_X, FoundryZone.FORGE_Z}, {FoundryZone.LANE_X + 1, FoundryZone.LANE_Z[0]}, {FoundryZone.LANE_X + 2, FoundryZone.LANE_Z[2]},
                {FoundryZone.FARM_X1, FoundryZone.FARM_Z2}, {FoundryZone.FARM_X1, FoundryZone.FARM_Z1}, {FoundryZone.POND_X1, FoundryZone.POND_Z1}, {FoundryZone.MINE_X2, FoundryZone.MINE_Z},
                {FoundryZone.PEN_X1, FoundryZone.PEN_Z1}, {FoundryZone.PEN_X2, FoundryZone.PEN_Z1}, {FoundryZone.SUPPLY_X[0], FoundryZone.SUPPLY_Z}, {FoundryZone.SUPPLY_X[4], FoundryZone.SUPPLY_Z}})
            assertTrue(Math.hypot(at[0] - FoundryZone.ISLAND_X, at[1] - FoundryZone.ISLAND_Z) < FoundryZone.ISLAND_R - 1, at[0] + "," + at[1]);
        assertTrue(FoundryZone.contains(FoundryZone.ISLAND_X - FoundryZone.ISLAND_R, FoundryZone.ISLAND_Z));
        assertTrue(FoundryZone.contains(FoundryZone.ISLAND_X, FoundryZone.ISLAND_Z - FoundryZone.ISLAND_R));
    }

    @Test void supplyChestsAndMineWallAreFoundAtTheirSpots() {
        assertEquals(2, FoundryZone.supplyAt(0, TestArenaBuilder.Y + 1, FoundryZone.SUPPLY_Z));
        assertEquals(-1, FoundryZone.supplyAt(1, TestArenaBuilder.Y + 1, FoundryZone.SUPPLY_Z));
        assertEquals(-1, FoundryZone.supplyAt(0, TestArenaBuilder.Y, FoundryZone.SUPPLY_Z));
        assertTrue(FoundryZone.inMineWall(FoundryZone.MINE_X1, TestArenaBuilder.Y + 1, FoundryZone.MINE_Z));
        assertFalse(FoundryZone.inMineWall(FoundryZone.MINE_X1, TestArenaBuilder.Y, FoundryZone.MINE_Z));
    }

    @Test void eachBenchIsRecognisedForItsTip() {
        int y = TestArenaBuilder.Y;
        assertEquals("supply", FoundryZone.benchAt(FoundryZone.SUPPLY_X[0], y + 1, FoundryZone.SUPPLY_Z));
        assertEquals("forge", FoundryZone.benchAt(FoundryZone.FORGE_X, y + 1, FoundryZone.FORGE_Z));
        assertEquals("lane", FoundryZone.benchAt(FoundryZone.LANE_X + 1, y + 1, FoundryZone.LANE_Z[1]));
        assertEquals("mine", FoundryZone.benchAt(FoundryZone.MINE_X1, y + 2, FoundryZone.MINE_Z));
        assertEquals("farm", FoundryZone.benchAt(FoundryZone.FARM_X1, y, FoundryZone.FARM_Z1));
        assertEquals("pond", FoundryZone.benchAt(FoundryZone.POND_X2, y, FoundryZone.POND_Z2));
        assertEquals("pen", FoundryZone.benchAt(FoundryZone.PEN_X1, y + 1, FoundryZone.PEN_Z1));
        assertNull(FoundryZone.benchAt(0, y, -70));
    }

    @Test void everyBenchDeliversToTheSharedForge() {
        String shared = FoundryZone.regionAt(FoundryZone.FORGE_X, FoundryZone.FORGE_Z);
        assertEquals(FoundryZone.SHARED_REGION, shared);
        for (int[] at : new int[][]{{FoundryZone.FARM_X1, FoundryZone.FARM_Z1}, {FoundryZone.POND_X1, FoundryZone.POND_Z1}, {FoundryZone.MINE_X2, FoundryZone.MINE_Z},
                {FoundryZone.PEN_X1, FoundryZone.PEN_Z1}, {FoundryZone.LANE_X, FoundryZone.LANE_Z[0]}}) {
            assertEquals(shared, FoundryZone.regionAt(at[0], at[1]), at[0] + "," + at[1]);
            assertTrue(Math.hypot(at[0] - FoundryZone.FORGE_X, at[1] - FoundryZone.FORGE_Z) < 40, "out of delivery range");
        }
        assertNull(FoundryZone.regionAt(0, 0));
    }

    @Test void theBuildPlotIsOnTheIslandAndClearOfEveryBench() {
        assertTrue(FoundryZone.PLOT_X1 > 1, "clear of the gold path");
        for (int x = FoundryZone.PLOT_X1; x <= FoundryZone.PLOT_X2; x++)
            for (int z = FoundryZone.PLOT_Z1; z <= FoundryZone.PLOT_Z2; z++) {
                assertTrue(Math.hypot(x - FoundryZone.ISLAND_X, z - FoundryZone.ISLAND_Z) < FoundryZone.ISLAND_R - 1, x + "," + z);
                for (int y = TestArenaBuilder.Y; y <= TestArenaBuilder.Y + 2; y++) assertNull(FoundryZone.benchAt(x, y, z), x + "," + z);
                assertNotEquals(FoundryZone.SHARED_REGION, FoundryZone.regionAt(x, z));
            }
    }

    @Test void theForgeIsInTheMiddleWithRoomForTheFlockToCircle() {
        assertEquals(FoundryZone.ISLAND_X, FoundryZone.FORGE_X);
        assertEquals(FoundryZone.ISLAND_Z, FoundryZone.FORGE_Z);
        assertTrue(FoundryZone.PILLAR_OFFSET > 2, "the circle check needs 2 blocks clear each way");
        assertTrue(FoundryZone.PLAZA_R > FoundryZone.PILLAR_OFFSET);
    }

    @Test void theAllayBenchIsWithinATierOneAllaysReach() {
        assertTrue(Math.hypot(FoundryZone.ALLAY_BENCH_X - FoundryZone.FORGE_X, FoundryZone.ALLAY_BENCH_Z - FoundryZone.FORGE_Z) + 2 < 12);
    }

    @Test void theBuildPlotHasRoomForSeveralBases() {
        assertNotEquals(FoundryZone.regionAt(FoundryZone.PLOT_X1, FoundryZone.PLOT_Z1), FoundryZone.regionAt(FoundryZone.PLOT_X2, FoundryZone.PLOT_Z2));
        assertEquals(FoundryZone.regionAt(FoundryZone.PLOT_X1, FoundryZone.PLOT_Z1), FoundryZone.regionAt(FoundryZone.PLOT_X1 + 1, FoundryZone.PLOT_Z1 + 1));
    }

    @Test void everyTeleportSpotIsOpenFloorOnTheIsland() {
        for (var e : FoundryZone.SPOTS.entrySet()) {
            int x = e.getValue()[0], z = e.getValue()[1];
            assertTrue(Math.hypot(x - FoundryZone.ISLAND_X, z - FoundryZone.ISLAND_Z) < FoundryZone.ISLAND_R - 1, e.getKey());
            assertNull(FoundryZone.benchAt(x, TestArenaBuilder.Y + 1, z), e.getKey());
        }
    }

    @Test void demoAgentsStandBesideTheirBenches() {
        assertEquals(4, FoundryZone.DEMO.length);
        for (int[] at : FoundryZone.DEMO) assertNull(FoundryZone.benchAt(at[0], TestArenaBuilder.Y + 1, at[1]), at[0] + "," + at[1]);
        assertTrue(FoundryZone.inMineWall(FoundryZone.DEMO[0][0], TestArenaBuilder.Y + 1, FoundryZone.DEMO[0][1] - 1), "the Miner faces the wall");
    }

    @Test void aTierOneGrinderAgainstAnyFenceReachesAMob() {
        double midX = (FoundryZone.PEN_X1 + FoundryZone.PEN_X2) / 2 + 0.5, midZ = (FoundryZone.PEN_Z1 + FoundryZone.PEN_Z2) / 2 + 0.5;
        int reach = 2;
        for (int[] s : FoundryZone.PEN_SPOTS) {   // inside the fence
            assertTrue(midX + s[0] > FoundryZone.PEN_X1 + 1 && midX + s[0] < FoundryZone.PEN_X2, "x " + s[0]);
            assertTrue(midZ + s[1] > FoundryZone.PEN_Z1 + 1 && midZ + s[1] < FoundryZone.PEN_Z2, "z " + s[1]);
        }
        for (int i = FoundryZone.PEN_X1; i <= FoundryZone.PEN_X2; i++) {   // the north and south sides, then east and west
            assertTrue(reaches(i + 0.5, FoundryZone.PEN_Z2 + 1.5, midX, midZ, reach), "south at " + i);
            assertTrue(reaches(i + 0.5, FoundryZone.PEN_Z1 - 0.5, midX, midZ, reach), "north at " + i);
        }
        for (int i = FoundryZone.PEN_Z1; i <= FoundryZone.PEN_Z2; i++) {
            assertTrue(reaches(FoundryZone.PEN_X2 + 1.5, i + 0.5, midX, midZ, reach), "east at " + i);
            assertTrue(reaches(FoundryZone.PEN_X1 - 0.5, i + 0.5, midX, midZ, reach), "west at " + i);
        }
    }

    private static boolean reaches(double x, double z, double midX, double midZ, int reach) {
        for (int[] s : FoundryZone.PEN_SPOTS)
            if (Math.abs(midX + s[0] - x) <= reach && Math.abs(midZ + s[1] - z) <= reach) return true;
        return false;
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
