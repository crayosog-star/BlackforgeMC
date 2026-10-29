package net.alloymc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArcadeIntentsTest {
    @Test void freshPickIsReturned() {
        assertEquals("forgewars", ArcadeIntents.parse("forgewars;1000", 1000 + 5_000));
    }

    @Test void stalePickIsIgnored() {
        assertNull(ArcadeIntents.parse("forgewars;1000", 1000 + ArcadeIntents.MAX_AGE_MS + 1));
    }

    @Test void pickFromTheFutureIsIgnored() {
        assertNull(ArcadeIntents.parse("forgewars;9000", 1000));   // clock skew or a hand-edited file
    }

    @Test void brokenValuesAreIgnored() {
        assertNull(ArcadeIntents.parse("forgewars", 1000));
        assertNull(ArcadeIntents.parse(";1000", 1000));
        assertNull(ArcadeIntents.parse("forgewars;soon", 1000));
    }
}
