package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The Trader's catalog and payout maths (no server needed). */
class SellCatalogTest {
    private static final double EPS = 1e-9;

    @Test void fullPriceUpToTheCap() { assertEquals(64, SellCatalog.payout(1, 0, 64, 1728, 0.25), EPS); }

    @Test void overCapRateAfterTheCap() {
        assertEquals(10 + 2.5, SellCatalog.payout(1, 1718, 20, 1728, 0.25), EPS);
        assertEquals(2.5, SellCatalog.payout(1, 5000, 10, 1728, 0.25), EPS);
    }

    @Test void nothingSoldPaysNothing() { assertEquals(0, SellCatalog.payout(5, 0, 0, 1728, 0.25), EPS); }

    @Test void splittingASaleEarnsNoMore() {
        double whole = SellCatalog.payout(1, 1700, 100, 1728, 0.25);
        double split = SellCatalog.payout(1, 1700, 50, 1728, 0.25) + SellCatalog.payout(1, 1750, 50, 1728, 0.25);
        assertEquals(whole, split, EPS);
    }

    @Test void itemsThatCouldLoopThroughAnotherShopAreNotListed() {
        Set<String> ids = new HashSet<>();
        for (SellCatalog.Item i : SellCatalog.ITEMS) { assertTrue(ids.add(i.id()), i.id()); assertTrue(i.price() > 0, i.id()); }
        for (String banned : List.of("emerald", "redstone", "lapis_lazuli", "ender_pearl", "cooked_beef", "white_wool", "stone", "cobblestone", "bread"))
            assertFalse(ids.contains(banned), banned);
    }

    @Test void catalogFitsTheMenu() { assertTrue(SellCatalog.ITEMS.size() <= 45); }

    @Test void pricesStayBelowTheDailyShop() {   // Daily Shop: diamond $200 each, blaze rod about $56 each
        for (SellCatalog.Item i : SellCatalog.ITEMS) {
            if (i.id().equals("diamond")) assertTrue(i.price() < 200);
            if (i.id().equals("blaze_rod")) assertTrue(i.price() < 56);
        }
    }
}
