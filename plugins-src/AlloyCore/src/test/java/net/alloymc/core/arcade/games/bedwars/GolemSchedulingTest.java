package net.alloymc.core.arcade.games.bedwars;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FW-003 melee lock: with a player always in arm's reach, the Golem must still use its special moves. Drives
 * {@link Golem.Pacing} exactly the way Golem.walk() and the SWIPE action do (no server needed).
 *
 * Numbers at the default 150% attack speed: swipe cooldown 30 / 1.5 = 20 walking ticks; special countdown
 * (moveGap + 0..19) / 1.5 = 53..66 in phase 1, 40..53 in phase 2, 29..42 enraged.
 */
class GolemSchedulingTest {
    private static final int SWIPE_CD = 20;

    /**
     * Simulates {@code walkTicks} walking ticks with the target always within 4.1 blocks and on the floor.
     * Returns the moves in order ("SWIPE" or "SPECIAL"). Moves themselves take no walking ticks (the Golem is in
     * WINDUP/ACT then), exactly as in the game. {@code oldRule} = the pre-fix behaviour (every swipe restarts the countdown).
     */
    private static List<String> melee(int gap, int walkTicks, boolean oldRule) {
        Golem.Pacing p = new Golem.Pacing(gap);
        List<String> moves = new ArrayList<>();
        for (int i = 0; i < walkTicks; i++) {
            p.tick();
            if (p.specialDue()) { moves.add("SPECIAL"); p.restart(gap); continue; }   // start(pick) ... end() restarts it
            if (p.swipeReady()) {
                moves.add("SWIPE");
                boolean restart = p.swiped(SWIPE_CD);
                if (restart || oldRule) p.restart(gap);
            }
        }
        return moves;
    }

    private static int maxSwipesInARow(List<String> moves) {
        int run = 0, max = 0;
        for (String m : moves) { run = m.equals("SWIPE") ? run + 1 : 0; max = Math.max(max, run); }
        return max;
    }

    @Test void enragedGolemUsesASpecialAfterTwoSwipes() {
        // phase 3, shortest countdown (29): swipe, swipe, then the 3rd pick is a special
        List<String> moves = melee(29, 200, false);
        assertEquals(List.of("SWIPE", "SWIPE", "SPECIAL"), moves.subList(0, 3));
        assertTrue(moves.stream().filter("SPECIAL"::equals).count() >= 5, moves.toString());
    }

    @Test void specialsKeepComingAtCloseRangeInEveryPhase() {
        for (int gap : new int[]{29, 42, 40, 53, 66}) {
            List<String> moves = melee(gap, 2_000, false);
            long specials = moves.stream().filter("SPECIAL"::equals).count();
            assertTrue(specials >= 2_000 / gap - 1, "gap " + gap + ": only " + specials + " specials");
            // between two specials it swings at most once per cooldown that fits in the countdown
            assertTrue(maxSwipesInARow(moves) <= gap / SWIPE_CD + 1, "gap " + gap + ": " + maxSwipesInARow(moves) + " swipes in a row");
            assertTrue(moves.contains("SWIPE"), "it still swipes at close range");
        }
    }

    @Test void oldRuleLockedOutEverySpecial() {
        // regression marker: restarting the countdown on every swipe never lets a special through
        for (int gap : new int[]{29, 53, 66}) assertFalse(melee(gap, 2_000, true).contains("SPECIAL"), "gap " + gap);
    }

    @Test void inBetweenSwipeKeepsTheCountdown() {
        Golem.Pacing p = new Golem.Pacing(53);
        for (int i = 0; i < 10; i++) { p.tick(); assertFalse(p.specialDue()); }
        assertTrue(p.swipeReady());
        assertFalse(p.swiped(SWIPE_CD), "an in-between swipe must not restart the special countdown");
        assertEquals(43, p.nextMove());
        assertFalse(p.swipeReady());
    }

    @Test void swipeThatWasTheSpecialPickRestartsTheCountdown() {
        Golem.Pacing p = new Golem.Pacing(1);
        assertTrue(p.specialDue());          // GolemMath.pick(...) may return SWIPE at close range
        assertTrue(p.swiped(SWIPE_CD));       // so the swipe's end() restarts the countdown as before
    }
}
