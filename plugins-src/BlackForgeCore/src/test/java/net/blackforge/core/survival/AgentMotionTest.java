package net.blackforge.core.survival;

import net.blackforge.core.survival.AgentMotion.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for how Agents move (no server needed). */
class AgentMotionTest {
    @Test void everyJobEndsWhereItStarted() {
        for (Kind k : Kind.values()) {
            AgentPose end = AgentMotion.work(k, 1);
            assertEquals(0, end.travel(), 1e-9);
            assertTrue(Math.abs(end.lean()) < 0.01, k + " lean");
            assertTrue(end.arm() < 1.0, k + " arm");
        }
    }

    @Test void nothingJumpsBetweenTicks() {
        for (Kind k : Kind.values()) {
            int ticks = AgentMotion.ticks(k, 8);
            for (int i = 1; i <= ticks; i++) {
                AgentPose a = AgentMotion.work(k, (i - 1.0) / ticks), b = AgentMotion.work(k, (double) i / ticks);
                assertTrue(Math.abs(a.arm() - b.arm()) < 1.5, k + " arm at " + i);   // a strike is meant to be fast, and the client blends it
                assertTrue(Math.abs(a.travel() - b.travel()) < 0.25, k + " travel at " + i);
            }
        }
    }

    @Test void noPartOfAJobJumpsBetweenTheStepsAPlayerSees() {
        for (Kind k : Kind.values()) {
            int ticks = AgentMotion.ticks(k, 8);
            for (int i = 2; i <= ticks; i += 2) {
                AgentPose a = AgentMotion.work(k, (i - 2.0) / ticks), b = AgentMotion.work(k, (double) i / ticks);
                assertTrue(Math.abs(a.arm() - b.arm()) < 1.25, k + " arm at " + i + ": " + Math.abs(a.arm() - b.arm()));   // past this the tool visibly leaves the hand mid-swing
                assertTrue(Math.abs(a.lean() - b.lean()) < 0.4, k + " lean at " + i);
                assertTrue(Math.abs(a.bob() - b.bob()) < 0.06, k + " bob at " + i);
            }
        }
    }

    @Test void aBlendStartsAndEndsExactlyOnItsFrames() {
        double[] a = {0, 0.3, 2.0, 1, -1, 0.5}, b = {0.1, 0, 0.5, 0, 0, 1.0};
        assertArrayEquals(a, AgentMotion.blend(a, b, 0), 1e-9);
        assertArrayEquals(b, AgentMotion.blend(a, b, 1), 1e-9);
        for (int i = 1; i <= AgentMotion.BLEND_TICKS; i++) {
            double[] was = AgentMotion.blend(a, b, (i - 1.0) / AgentMotion.BLEND_TICKS), now = AgentMotion.blend(a, b, (double) i / AgentMotion.BLEND_TICKS);
            assertTrue(Math.abs(was[2] - now[2]) < 1.0, "arm at " + i);
        }
    }

    @Test void aBlendTurnsTheShortWayRound() {
        double[] a = new double[AgentMotion.FRAME_SIZE], b = new double[AgentMotion.FRAME_SIZE];
        a[AgentMotion.AIM] = Math.PI - 0.1;
        b[AgentMotion.AIM] = -Math.PI + 0.1;
        double half = AgentMotion.blend(a, b, 0.5)[AgentMotion.AIM];
        assertEquals(Math.PI, half, 1e-9, "through the back, not all the way round the front");
    }

    @Test void aJobAlwaysFitsItsAgentsCycle() {
        for (Kind k : Kind.values()) for (int interval = 1; interval <= 10; interval++) {
            int t = AgentMotion.ticks(k, interval);
            assertTrue(t >= AgentMotion.MIN_TICKS, k + " at " + interval);
            assertTrue(t <= interval * 20 - 2 || t == AgentMotion.MIN_TICKS, k + " at " + interval);
        }
    }

    @Test void onlyTheFarmerAndGrinderLeaveTheirPost() {
        assertEquals(0, AgentMotion.reach(Kind.MINER, 40, 5), 1e-9);
        assertEquals(0, AgentMotion.reach(Kind.FISHERMAN, 40, 5), 1e-9);
        assertTrue(AgentMotion.reach(Kind.FARMER, 44, 5) > 1);
        assertTrue(AgentMotion.reach(Kind.GRINDER, 20, 5) <= 1);
    }

    @Test void theyStopShortOfTheTarget() {
        assertEquals(0.4, AgentMotion.reach(Kind.FARMER, 44, 1), 1e-9);
        assertEquals(0, AgentMotion.reach(Kind.FARMER, 44, 0.2), 1e-9);
    }

    @Test void outOfFuelItSlumps() {
        assertTrue(AgentMotion.idle(100, 1, false).lean() > 0.2);
        assertTrue(Math.abs(AgentMotion.idle(100, 1, true).lean()) < 0.1);
    }

    @Test void idleDriftsSlowly() {
        for (long t = 1; t < 400; t++) assertTrue(Math.abs(AgentMotion.idle(t, 3, true).arm() - AgentMotion.idle(t - 1, 3, true).arm()) < 0.02);
    }
}
