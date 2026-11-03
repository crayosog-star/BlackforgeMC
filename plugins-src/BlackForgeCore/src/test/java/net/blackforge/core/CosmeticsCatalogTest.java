package net.blackforge.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticsCatalogTest {
    @Test void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Cosmetics.Cosmetic c : Cosmetics.ALL) assertTrue(seen.add(c.id()), "duplicate id " + c.id());
    }

    @Test void everyCosmeticIsDrawnBySomething() {
        for (Cosmetics.Cosmetic c : Cosmetics.ALL) assertTrue(CosmeticFx.implemented(c), c.id() + " has no implementation");
    }

    @Test void everyRarityCanBePulledInEveryPool() {
        for (Cosmetics.Rarity r : Cosmetics.Rarity.values())
            assertTrue(Cosmetics.ALL.stream().anyMatch(c -> c.crate() && c.season() == null && c.rarity() == r), "no permanent crate cosmetic of rarity " + r);
    }

    @Test void crateOnlyItemsCannotBeBoughtAndShopItemsAreNotInTheCrate() {
        for (Cosmetics.Cosmetic c : Cosmetics.ALL) {
            if (c.crate()) assertEquals(-1, c.price(), c.id());
            else assertFalse(c.crate(), c.id());
        }
    }

    @Test void everyCategoryHasEnoughToCollect() {
        for (Cosmetics.Kind k : Cosmetics.Kind.values()) {
            long n = Cosmetics.of(k).size();
            assertTrue(n >= 5 && n <= 45, k + " has " + n);
        }
    }

    @Test void rarityWeightsAddUpToOneHundred() {
        int total = 0;
        for (Cosmetics.Rarity r : Cosmetics.Rarity.values()) total += r.weight;
        assertEquals(100, total);
    }

    @Test void everyLimitedCosmeticBelongsToAKnownSeason() {
        Set<String> known = Set.of("hallow", "frost", "emberheart", "bloomforge", "sunforge", "anniversary", "founder");
        for (Cosmetics.Cosmetic c : Cosmetics.ALL) if (c.season() != null) assertTrue(known.contains(c.season()), c.id() + " has unknown season " + c.season());
    }

    @Test void limitedCosmeticsAreNeverBoughtWithScrap() {
        for (Cosmetics.Cosmetic c : Cosmetics.ALL) if (c.season() != null) assertEquals(-1, c.price(), c.id());
    }

    @Test void eachSeasonHasEnoughToChaseAndAtLeastOneRarePlusPull() {
        for (String s : new String[]{"hallow", "frost", "emberheart", "bloomforge", "sunforge", "anniversary"}) {
            var items = Cosmetics.ofSeason(s);
            assertTrue(items.stream().filter(Cosmetics.Cosmetic::crate).count() >= 4, s);
            assertTrue(items.stream().anyMatch(c -> c.crate() && c.rarity() == Cosmetics.Rarity.LEGENDARY), s + " needs a legendary");
        }
    }

    @Test void everyEventSeasonHasAChampionCape() {
        for (String s : new String[]{"hallow", "frost", "emberheart", "bloomforge", "sunforge", "anniversary"}) {
            Cosmetics.Cosmetic c = Cosmetics.byId("c_champ_" + s);
            assertNotNull(c, s);
            assertTrue(Cosmetics.awardOnly(c), s);
            assertEquals(Cosmetics.Kind.CAPE, c.kind());
        }
    }

    @Test void foundersAndChampionsAreNeverInTheCrateOrTheShop() {
        for (Cosmetics.Cosmetic c : Cosmetics.ALL) if (Cosmetics.awardOnly(c)) { assertFalse(c.crate(), c.id()); assertEquals(-1, c.price(), c.id()); }
        assertTrue(Cosmetics.awardOnly(Cosmetics.byId("c_founder")));
        assertFalse(Cosmetics.awardOnly(Cosmetics.byId("t_haunt")));
    }

    @Test void seasonPointsOnlyCountRealActivity() {
        assertEquals(5, SeasonBoard.points("koth"));
        assertEquals(0, SeasonBoard.points("friends"));
        assertEquals(0, SeasonBoard.points("playtime"));
    }
}
