package net.blackforge.core.moderation;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the auto-clicker hit counting (no server needed). */
class GuardianTest {
    @Test void normalHitsCount() {
        assertTrue(Guardian.countsAsClick(Set.of()));
        assertTrue(Guardian.countsAsClick(Set.of("some_other_tag")));
    }

    @Test void hitOnARelayBodyIsNotCountedTwice() {
        assertFalse(Guardian.countsAsClick(Set.of(Guardian.RELAY_TAG)));
        assertFalse(Guardian.countsAsClick(Set.of("some_other_tag", Guardian.RELAY_TAG)));
    }
}
