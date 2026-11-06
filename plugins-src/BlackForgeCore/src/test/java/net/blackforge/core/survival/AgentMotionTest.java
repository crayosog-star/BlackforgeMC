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
