package net.blackforge.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LabRulesTest {
    @Test void tallyCountsGoodAndBad() {
        assertArrayEquals(new int[]{2, 1}, LabRules.tally(Arrays.asList(1, -1, 1, 0, null)));
        assertArrayEquals(new int[]{0, 0}, LabRules.tally(List.of()));
    }

    @Test void keepLastTrimsOldest() {
        assertEquals(List.of("c", "d"), LabRules.keepLast(List.of("a", "b", "c", "d"), 2));
        assertEquals(List.of("a"), LabRules.keepLast(List.of("a"), 5));
        assertTrue(LabRules.keepLast(List.of("a"), 0).isEmpty());
    }

    @Test void startingWithIgnoresCase() {
        assertEquals(List.of("ash-urns", "ashfall"), LabRules.startingWith(List.of("ash-urns", "ashfall", "sit"), "AS"));
        assertEquals(3, LabRules.startingWith(List.of("a", "b", "c"), "").size());
    }

    @Test void labHasItsOwnWallet() {
        assertEquals("survival2", NetEconomy.walletForMode("survival", "survival2"));
        assertEquals("survival", NetEconomy.walletForMode("survival", null));
        assertEquals("Survival 2", NetEconomy.walletName("survival2"));
    }
}
