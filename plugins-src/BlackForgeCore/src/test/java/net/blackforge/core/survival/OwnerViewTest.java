package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OwnerViewTest {
    @Test
    void sizesRoundUpToWholeRows() {
        assertEquals(9, OwnerView.viewSize(3));    // furnace
        assertEquals(9, OwnerView.viewSize(5));    // hopper
        assertEquals(9, OwnerView.viewSize(9));    // dispenser
        assertEquals(27, OwnerView.viewSize(27));  // chest
        assertEquals(54, OwnerView.viewSize(54));  // double chest
    }

    @Test
    void sizesStayValid() {
        assertEquals(9, OwnerView.viewSize(0));
        assertEquals(54, OwnerView.viewSize(60));
    }
}
