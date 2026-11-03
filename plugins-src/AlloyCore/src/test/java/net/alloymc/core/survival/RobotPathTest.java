package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** RobotPath A* against a fake block world (no server needed). Floors are solid blocks; a robot stands one above. */
class RobotPathTest {

    /** Blocks are air unless marked solid or liquid. */
    private static final class Grid implements RobotPath.WorldAccess {
        final Set<List<Integer>> solid = new HashSet<>(), liquid = new HashSet<>();
        Grid solid(int x, int y, int z) { solid.add(List.of(x, y, z)); return this; }
        Grid liquid(int x, int y, int z) { liquid.add(List.of(x, y, z)); return this; }
        Grid floor(int x0, int x1, int y, int z0, int z1) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) solid(x, y, z);
            return this;
        }
        public boolean isSolid(int x, int y, int z) { return solid.contains(List.of(x, y, z)); }
        public boolean isLiquid(int x, int y, int z) { return liquid.contains(List.of(x, y, z)); }
    }

    /** Every waypoint is one horizontal block from the last, climbs at most 1 and drops at most 2. */
    private static void assertWalkable(List<int[]> path) {
        for (int i = 1; i < path.size(); i++) {
            int[] a = path.get(i - 1), b = path.get(i);
            assertEquals(1, Math.abs(a[0] - b[0]) + Math.abs(a[2] - b[2]), "one horizontal step at " + i);
            int dy = b[1] - a[1];
            assertTrue(dy <= 1 && dy >= -2, "dy " + dy + " at " + i);
        }
    }

    private static boolean visits(List<int[]> path, int x, int y, int z) {
        return path.stream().anyMatch(p -> p[0] == x && p[1] == y && p[2] == z);
    }

    @Test void flatPathOfFiveSteps() {
        Grid w = new Grid().floor(0, 10, 0, 0, 0);
        List<int[]> path = new RobotPath(w).find(0, 1, 0, 5, 1, 0);
        assertEquals(6, path.size());   // start + 5 moves
        for (int i = 0; i <= 5; i++) assertArrayEquals(new int[]{i, 1, 0}, path.get(i));
    }

    @Test void startEqualsGoal() {
        Grid w = new Grid().floor(0, 0, 0, 0, 0);
        List<int[]> path = new RobotPath(w).find(0, 1, 0, 0, 1, 0);
        assertEquals(1, path.size());
    }

    @Test void stepsUpOneBlock() {
        Grid w = new Grid().floor(0, 2, 0, 0, 0).floor(3, 5, 1, 0, 0);
        List<int[]> path = new RobotPath(w).find(0, 1, 0, 5, 2, 0);
        assertEquals(6, path.size());
        assertTrue(visits(path, 2, 1, 0) && visits(path, 3, 2, 0));
        assertWalkable(path);
    }

    @Test void cannotStepUpTwoBlocks() {
        Grid w = new Grid().floor(0, 2, 0, 0, 0).floor(3, 5, 1, 0, 0).floor(3, 5, 2, 0, 0);
        assertTrue(new RobotPath(w).find(0, 1, 0, 5, 3, 0).isEmpty());
    }

    @Test void stepUpNeedsHeadroom() {
        Grid w = new Grid().floor(0, 2, 0, 0, 0).floor(3, 5, 1, 0, 0).solid(2, 2, 0);   // ceiling over the last low block
        assertTrue(new RobotPath(w).find(0, 1, 0, 5, 2, 0).isEmpty());
    }

    @Test void dropsTwoBlocks() {
        Grid w = new Grid().floor(0, 2, 2, 0, 0).floor(3, 5, 0, 0, 0);
        List<int[]> path = new RobotPath(w).find(0, 3, 0, 5, 1, 0);
        assertEquals(6, path.size());
        assertTrue(visits(path, 2, 3, 0) && visits(path, 3, 1, 0));
        assertWalkable(path);
    }

    @Test void wontDropThreeBlocks() {
        Grid w = new Grid().floor(0, 2, 3, 0, 0).floor(3, 5, 0, 0, 0);
        assertTrue(new RobotPath(w).find(0, 4, 0, 5, 1, 0).isEmpty());
    }

    @Test void walksAroundLiquid() {
        Grid w = new Grid().floor(0, 6, 0, -2, 2).liquid(3, 1, 0);
        List<int[]> path = new RobotPath(w).find(0, 1, 0, 6, 1, 0);
        assertFalse(path.isEmpty());
        assertFalse(visits(path, 3, 1, 0));
        assertEquals(9, path.size());   // 6 across + 1 out + 1 back
        assertWalkable(path);
    }

    @Test void neverStandsOnLiquid() {
        // a lane whose middle is water instead of floor: no way across
        Grid w = new Grid().floor(0, 2, 0, 0, 0).floor(4, 6, 0, 0, 0).liquid(3, 0, 0);
        assertTrue(new RobotPath(w).find(0, 1, 0, 6, 1, 0).isEmpty());
    }

    @Test void blockedReturnsEmpty() {
        Grid w = new Grid().floor(0, 6, 0, -2, 2);
        for (int z = -2; z <= 2; z++) for (int y = 1; y <= 3; y++) w.solid(3, y, z);   // 3-high wall across the whole floor
        assertTrue(new RobotPath(w).find(0, 1, 0, 6, 1, 0).isEmpty());
    }

    @Test void goalYouCantStandOnReturnsEmpty() {
        Grid w = new Grid().floor(0, 6, 0, 0, 0);
        assertTrue(new RobotPath(w).find(0, 1, 0, 6, 2, 0).isEmpty());   // floating in the air
    }

    @Test void maxTwentyFourSteps() {
        Grid w = new Grid().floor(0, 40, 0, 0, 0);
        assertEquals(RobotPath.MAX_STEPS + 1, new RobotPath(w).find(0, 1, 0, 24, 1, 0).size());
        assertTrue(new RobotPath(w).find(0, 1, 0, 25, 1, 0).isEmpty());
    }

    @Test void detourOverTwentyFourStepsReturnsEmpty() {
        // goal only 4 blocks away in a straight line, but a wall forces a detour longer than 24 moves
        Grid w = new Grid().floor(-15, 15, 0, -15, 15);
        for (int z = -14; z <= 15; z++) for (int y = 1; y <= 2; y++) w.solid(2, y, z);
        assertTrue(new RobotPath(w).find(0, 1, 0, 4, 1, 0).isEmpty());
    }

    @Test void worksAtNegativeCoordinates() {
        Grid w = new Grid().floor(-105, -100, -64, -7, -7);
        List<int[]> path = new RobotPath(w).find(-100, -63, -7, -105, -63, -7);
        assertEquals(6, path.size());
        assertArrayEquals(new int[]{-105, -63, -7}, path.get(5));
    }
}
