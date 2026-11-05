package net.blackforge.core.survival;

import net.blackforge.core.survival.AllayPath.Free;
import net.blackforge.core.survival.AllayPath.Route;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for how a Forge Allay gets around blocks (no server needed). */
class AllayPathTest {
    private static final double[] A = {0.5, 64.5, 0.5}, B = {10.5, 64.5, 0.5};
    private static final Free OPEN = (x, y, z) -> true;
    /** A wall at x = 5, from y = 60 to 70 and z = -3 to 3, with open air round it. */
    private static final Free WALL = (x, y, z) -> !(x == 5 && y >= 60 && y <= 70 && z >= -3 && z <= 3);

    @Test void straightWhenClear() {
        Route r = AllayPath.route(A, B, OPEN, 2_000);
        assertTrue(r.straight());
        assertEquals(10.0, r.length, 1e-9);
    }

    @Test void clearSeesTheWall() {
        assertTrue(AllayPath.clear(A, B, OPEN));
        assertFalse(AllayPath.clear(A, B, WALL));
    }

    @Test void goesAroundAWall() {
        Route r = AllayPath.route(A, B, WALL, 5_000);
        assertNotNull(r);
        assertFalse(r.straight());
        assertArrayEquals(A, r.at(0), 1e-9);
        assertArrayEquals(B, r.at(r.length), 1e-9);
        for (double d = 0; d <= r.length; d += 0.1) {
            double[] p = r.at(d);
            assertTrue(WALL.at((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])), "in the wall at " + d);
        }
    }

    @Test void flockTakesDifferentPaths() {
        double[][] middles = new double[3][];
        for (int i = 0; i < 3; i++) {
            Route r = AllayPath.bowed(A, B, i, OPEN);
            assertFalse(r.straight());
            assertArrayEquals(A, r.at(0), 1e-9);
            assertArrayEquals(B, r.at(r.length), 1e-9);
            middles[i] = r.at(r.length / 2);
        }
        assertTrue(AllayPath.dist(middles[0], middles[1]) > 1);
        assertTrue(AllayPath.dist(middles[1], middles[2]) > 1);
        assertTrue(AllayPath.dist(middles[0], middles[2]) > 1);
    }

    @Test void bowGivesUpRatherThanHitAnything() {
        Free post = (x, y, z) -> !(x == 5 && y >= 60 && y <= 70 && z >= 1 && z <= 4);
        assertTrue(AllayPath.clear(A, B, post));
        assertTrue(AllayPath.bowed(A, B, 0, post).straight());
    }

    @Test void shortTripsStayStraight() {
        assertTrue(AllayPath.bowed(A, new double[]{2.5, 64.5, 0.5}, 0, OPEN).straight());
    }

    @Test void negativeCoordinatesWork() {
        double[] a = {-20.5, 64.5, -60.5}, b = {-10.5, 64.5, -60.5};
        Free wall = (x, y, z) -> !(x == -15 && y >= 60 && y <= 70 && z >= -63 && z <= -57);
        Route r = AllayPath.route(a, b, wall, 5_000);
        assertNotNull(r);
        for (double d = 0; d <= r.length; d += 0.1) {
            double[] p = r.at(d);
            assertTrue(wall.at((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2])), "in the wall at " + d);
        }
    }

    @Test void sealedRoomHasNoRoute() {
        Free sealed = (x, y, z) -> x >= 0 && x <= 2 && y >= 64 && y <= 66 && z >= 0 && z <= 2;
        assertNull(AllayPath.route(A, B, sealed, 2_000));
    }

    @Test void searchStopsAtItsLimit() {
        assertNull(AllayPath.route(A, new double[]{30.5, 64.5, 0.5}, (x, y, z) -> x != 20, 50));
    }

    @Test void nudgeFindsTheNearestFreeBlock() {
        Free solid = (x, y, z) -> !(x == 0 && y == 64 && z == 0);
        assertArrayEquals(new double[]{1.5, 64.5, 0.5}, AllayPath.nudge(A, solid), 1e-9);
        assertSame(A, AllayPath.nudge(A, OPEN));
    }

    @Test void noJumpAlongARoute() {
        Route r = AllayPath.route(A, B, WALL, 5_000);
        double[] last = r.at(0);
        for (double d = 0.1; d <= r.length; d += 0.1) {
            double[] p = r.at(d);
            assertTrue(AllayPath.dist(last, p) <= 0.1 + 1e-9);
            last = p;
        }
    }

    @Test void aMissIsRememberedThenForgotten() {
        AllayPath.Misses m = new AllayPath.Misses(100);
        String k = AllayPath.Misses.key("claim-1", A, B);
        assertFalse(m.recent(k, 0));
        m.add(k, 100);
        assertTrue(m.recent(k, 150));
        assertTrue(m.recent(AllayPath.Misses.key("claim-1", new double[]{0.9, 64.1, 0.2}, new double[]{10.9, 64.1, 0.2}), 199));   // same blocks
        assertFalse(m.recent(AllayPath.Misses.key("claim-2", A, B), 150));                                // another base
        assertFalse(m.recent(AllayPath.Misses.key("claim-1", A, new double[]{20.5, 64.5, 0.5}), 150));   // another target
        assertFalse(m.recent(k, 200));
        assertEquals(0, m.size());   // forgotten, not kept forever
    }

    @Test void aMissFromOneStartDoesNotBlockAnother() {
        double[] sealed = {3.5, 64.5, 3.5};
        assertNotEquals(AllayPath.Misses.key("claim-1", sealed, B), AllayPath.Misses.key("claim-1", A, B));
        AllayPath.Misses m = new AllayPath.Misses(100);
        m.add(AllayPath.Misses.key("claim-1", sealed, B), 0);   // one allay boxed in found no way to B
        assertTrue(m.recent(AllayPath.Misses.key("claim-1", sealed, B), 50));
        assertFalse(m.recent(AllayPath.Misses.key("claim-1", A, B), 50), "another allay's trip to B still searches, not straight through walls");
    }

    @Test void missesStayBounded() {
        AllayPath.Misses m = new AllayPath.Misses(20);
        for (int i = 0; i < 5_000; i++) m.add(AllayPath.Misses.key("b", A, new double[]{i, 64, 0}), i);
        assertTrue(m.size() <= 1_024);
        assertTrue(m.recent(AllayPath.Misses.key("b", A, new double[]{4_999, 64, 0}), 5_000));
    }

    /** A one-block-wide corridor that winds back and forth, with nothing open but itself, so straightening can only skip along a leg. */
    private static List<double[]> snake(int length, Set<Long> cells) {
        List<double[]> pts = new ArrayList<>();
        int x = 0, z = 0, dz = 1;
        while (pts.size() < length) {
            pts.add(new double[]{x + 0.5, 64.5, z + 0.5});
            cells.add((long) x * 1_000 + z);
            int step = pts.size() % 8;   // six along z, then two along x
            if (step >= 1 && step <= 6) z += dz;
            else { x++; if (step == 0) dz = -dz; }
        }
        return pts;
    }

    @Test void straighteningHasAWorkLimit() {
        Set<Long> cells = new HashSet<>();
        List<double[]> grid = snake(400, cells);
        int[] used = {0};
        Free corridor = (x, y, z) -> { used[0]++; return y == 64 && cells.contains((long) x * 1_000 + z); };
        List<double[]> out = AllayPath.straighten(grid, new AllayPath.Budget(corridor, 2_000));
        assertTrue(used[0] <= 2_000 + 1_000, "looked up " + used[0]);   // the budget, plus at most one line check past it
        assertArrayEquals(grid.get(0), out.get(0), 1e-9);
        assertArrayEquals(grid.get(grid.size() - 1), out.get(out.size() - 1), 1e-9);
        for (int i = 1; i < out.size(); i++) assertTrue(AllayPath.clear(out.get(i - 1), out.get(i), corridor), "cut through a wall at " + i);
        used[0] = 0;
        AllayPath.straighten(grid, new AllayPath.Budget(corridor, Integer.MAX_VALUE));
        assertTrue(used[0] > 20_000, "the snake should be expensive without a limit: " + used[0]);
    }

    @Test void aSpentBudgetKeepsTheGridRoute() {
        Set<Long> cells = new HashSet<>();
        List<double[]> grid = snake(20, cells);
        List<double[]> out = AllayPath.straighten(grid, new AllayPath.Budget(OPEN, 0));
        assertEquals(grid.size(), out.size());
        assertEquals(2, AllayPath.straighten(grid, new AllayPath.Budget(OPEN, 1_000)).size());   // open air: straight across
    }
}
