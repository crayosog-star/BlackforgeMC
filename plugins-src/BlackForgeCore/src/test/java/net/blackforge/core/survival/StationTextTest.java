package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for text drawn on menu art (no server needed). */
class StationTextTest {
    private static int total(String s) {
        int n = 0;
        for (char c : s.toCharArray()) n += c >= 0xF110 ? -(1 << (c - 0xF110)) : 1 << (c - 0xF100);
        return n;
    }

    @Test void shiftsAddUpExactly() {
        for (int px : new int[]{0, 1, -1, 7, -8, 127, 128, 255, 256, -177, 600}) assertEquals(px, total(StationText.shift(px)));
    }

    @Test void pricesFitUnderACard() {
        for (int price : new int[]{10_000, 35_000, 100_000, 300_000, 1_500_000})
            assertTrue(StationText.width(StationText.money(price)) <= 34, "fits a 36 px card");
        assertEquals("$35K", StationText.money(35_000));
        assertEquals("$1.5M", StationText.money(1_500_000));
        assertEquals("$500", StationText.money(500));
    }
}
