package net.blackforge.core.hub;

import org.junit.jupiter.api.Test;

import static net.blackforge.core.hub.FoundryGrace.Step.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Foundry's leave grace (no server needed). */
class FoundryGraceTest {
    private static final long GRACE = 15_000;

    @Test void walkingInStartsATest() {
        assertEquals(ENTER, FoundryGrace.step(true, false, null, 0));
    }

    @Test void standingInsideOrOutsideWithNoTestDoesNothing() {
        assertEquals(NONE, FoundryGrace.step(true, true, null, 0));
        assertEquals(NONE, FoundryGrace.step(false, false, null, 0));
    }

    @Test void steppingOutStartsTheGrace() {
        assertEquals(STEP_OUT, FoundryGrace.step(false, true, null, 1_000));
        assertEquals(1_000 + GRACE, FoundryGrace.deadline(1_000, GRACE));
    }

    @Test void comingBackInTimeKeepsTheTest() {
        long until = FoundryGrace.deadline(0, GRACE);
        assertEquals(BACK, FoundryGrace.step(true, true, until, 5_000));
        assertEquals(BACK, FoundryGrace.step(true, true, until, until + 60_000));   // back in the zone always wins
    }

    @Test void theTestClearsExactlyWhenTheGraceEnds() {
        long until = FoundryGrace.deadline(0, GRACE);
        assertEquals(COUNTDOWN, FoundryGrace.step(false, true, until, 0));
        assertEquals(COUNTDOWN, FoundryGrace.step(false, true, until, until - 1));
        assertEquals(CLEAR, FoundryGrace.step(false, true, until, until));
        assertEquals(CLEAR, FoundryGrace.step(false, true, until, until + 1));
    }

    @Test void noGraceClearsOnTheNextPoll() {
        long until = FoundryGrace.deadline(500, 0);
        assertEquals(CLEAR, FoundryGrace.step(false, true, until, 500));
    }

    @Test void countdownCountsDownAndNeverShowsZero() {
        long until = FoundryGrace.deadline(0, GRACE);
        assertEquals(15, FoundryGrace.secondsLeft(until, 1));   // the first countdown poll is 250 ms after stepping out
        assertEquals(15, FoundryGrace.secondsLeft(until, 250));
        assertEquals(14, FoundryGrace.secondsLeft(until, 1_001));
        assertEquals(1, FoundryGrace.secondsLeft(until, until - 1));
        long last = Long.MAX_VALUE;
        for (long t = 0; t < until; t += 250) {
            long s = FoundryGrace.secondsLeft(until, t);
            assertTrue(s >= 1 && s <= last, "at " + t);
            last = s;
        }
    }
}
