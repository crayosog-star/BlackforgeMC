package net.blackforge.core.survival;

import net.blackforge.core.survival.AllayPath.Free;
import net.blackforge.core.survival.AllayPath.Route;
import org.junit.jupiter.api.Test;

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
}
