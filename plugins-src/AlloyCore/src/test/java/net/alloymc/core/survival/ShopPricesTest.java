package net.alloymc.core.survival;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.*;

/** Trader prices and limits from the real bundled shop.yml, plus the amount parser and AH fee (no server needed). */
class ShopPricesTest {
    private static final double EPS = 1e-9;
    private static ShopPrices shop;

    @BeforeAll static void load() throws Exception {
        try (InputStream in = ShopPricesTest.class.getResourceAsStream("/shop.yml")) {
            assertNotNull(in, "shop.yml on the classpath");
            Map<String, Object> root = new Yaml().load(in);
            shop = new ShopPrices(root);
        }
    }

    @Test void pricesFromShopYml() {
        assertEquals(0.40, shop.sellPrice("wheat").getAsDouble(), EPS);
        assertEquals(2.50, shop.sellPrice("blaze_rod").getAsDouble(), EPS);
        assertEquals(10.00, shop.sellPrice("diamond").getAsDouble(), EPS);
        assertEquals(0.40, shop.sellPrice("WHEAT").getAsDouble(), EPS);   // Material.name() works too
    }

    @Test void excludedItemsHaveNoPrice() {
        for (String no : List.of("emerald", "cobblestone", "stone", "iron_ingot", "echo_shard", "spawner", "coal_block", "cooked_beef"))
            assertEquals(OptionalDouble.empty(), shop.sellPrice(no), no);
        assertNull(shop.category("emerald"));
        assertEquals(0, shop.dailyLimit("emerald"));
    }

    @Test void dailyLimits() {
        assertEquals(500, shop.dailyLimit("blaze_rod"));
        assertEquals(500, shop.dailyLimit("ender_pearl"));
        assertEquals(5000, shop.dailyLimit("wheat"));       // category default
        assertEquals(3000, shop.dailyLimit("bone"));
        assertEquals(1000, shop.dailyLimit("cod"));
        assertEquals(3000, shop.dailyLimit("oak_log"));
        assertEquals(2000, shop.dailyLimit("diamond"));
        assertEquals(0, shop.itemLimit("wheat"));
        assertEquals(3000, shop.categoryLimit("mob_drops"));
    }

    @Test void categories() {
        assertEquals("crops", shop.category("wheat"));
        assertEquals("mob_drops", shop.category("blaze_rod"));
        assertEquals("fishing", shop.category("cod"));
        assertEquals("wood", shop.category("cherry_log"));
        assertEquals("mining", shop.category("raw_iron"));
        assertEquals(List.of("crops", "mob_drops", "fishing", "wood", "mining"), shop.categories());
    }

    @Test void catalogMatchesEconomyDoc() {
        assertEquals(45, shop.items().size());
        assertEquals(11, shop.itemsIn("crops").size());
        assertEquals(14, shop.itemsIn("mob_drops").size());
        assertEquals(4, shop.itemsIn("fishing").size());
        assertEquals(8, shop.itemsIn("wood").size());
        assertEquals(8, shop.itemsIn("mining").size());
    }

    @Test void badEntriesAreSkipped() {
        ShopPrices s = new ShopPrices(Map.of("shop", Map.of(
                "free", Map.of("price", 0, "category", "crops"),
                "noprice", Map.of("category", "crops"),
                "ok", Map.of("price", 2, "category", "Crops"))));
        assertTrue(s.sellPrice("free").isEmpty());
        assertTrue(s.sellPrice("noprice").isEmpty());
        assertEquals(2.0, s.sellPrice("ok").getAsDouble(), EPS);
        assertEquals("crops", s.category("ok"));
        assertEquals(0, s.dailyLimit("ok"));   // no limits section = unlimited
    }

    @Test void allowedRespectsItemAndCategoryLimits() {
        assertEquals(64, ShopPrices.allowed(64, 0, 0, 0, 5000));
        assertEquals(10, ShopPrices.allowed(64, 0, 0, 4990, 5000));      // category nearly full
        assertEquals(0, ShopPrices.allowed(64, 0, 0, 5000, 5000));
        assertEquals(20, ShopPrices.allowed(64, 480, 500, 480, 3000));   // blaze rod sub-limit
        assertEquals(5, ShopPrices.allowed(64, 100, 500, 2995, 3000));   // category tighter than item
        assertEquals(64, ShopPrices.allowed(64, 0, 0, 0, 0));            // unlimited
        assertEquals(0, ShopPrices.allowed(-3, 0, 0, 0, 0));
    }

    @Test void dayRollsOverAtMidnightUtc() {
        long day = 86_400_000L;
        assertEquals(ShopPrices.utcDay(20_000 * day), ShopPrices.utcDay(20_000 * day + day - 1));
        assertEquals(ShopPrices.utcDay(20_000 * day) + 1, ShopPrices.utcDay(20_001 * day));
    }

    @Test void payoutRoundsToCents() {
        assertEquals(1.0, ShopPrices.payout(0.10, 10), EPS);
        assertEquals(0.15, ShopPrices.payout(0.05, 3), EPS);
        assertEquals(2000.0, ShopPrices.payout(0.40, 5000), EPS);
    }

    @Test void parseAmountSuffixes() {
        assertEquals(1500, ShopPrices.parseAmount("1.5k"), EPS);
        assertEquals(1_500_000, ShopPrices.parseAmount("1.5m"), EPS);
        assertEquals(2_000_000, ShopPrices.parseAmount("2m"), EPS);
        assertEquals(500, ShopPrices.parseAmount("500"), EPS);
        assertEquals(1500, ShopPrices.parseAmount("$1,500"), EPS);
        assertEquals(2500, ShopPrices.parseAmount("2.5K"), EPS);
        assertEquals(-1, ShopPrices.parseAmount("abc"), EPS);
        assertEquals(-1, ShopPrices.parseAmount(""), EPS);
        assertEquals(-1, ShopPrices.parseAmount("-5"), EPS);
        assertEquals(-1, ShopPrices.parseAmount("NaN"), EPS);
    }

    @Test void auctionListingFeeIsFivePercentRoundedUp() {
        assertEquals(5.00, AuctionHouse.listingFee(100), EPS);
        assertEquals(0.05, AuctionHouse.listingFee(1), EPS);
        assertEquals(0.51, AuctionHouse.listingFee(10.01), EPS);   // 0.5005 rounds up
        assertEquals(75.00, AuctionHouse.listingFee(1500), EPS);
    }

    @Test void auctionGroupForEachServer() {
        assertEquals("survival", AuctionHouse.groupFor("survival", "auctions.yml"));
        assertEquals("skyblock", AuctionHouse.groupFor("skyblock", "auctions.yml"));
        assertEquals("oneblock", AuctionHouse.groupFor("skyblock", "auctions-oneblock.yml"));
        assertNull(AuctionHouse.groupFor("hub", "auctions.yml"));
    }
}
