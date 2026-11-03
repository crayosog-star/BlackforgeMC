package net.alloymc.core.arcade.games.bedwars;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Forge Wars ships with production defaults, not testing ones (FW-002). */
class ForgeWarsSettingsTest {
    private final Map<String, Object> s = new ForgeWars().defaultSettings();

    private int i(String key) {
        Object o = s.get(key);
        assertInstanceOf(Number.class, o, key);
        return ((Number) o).intValue();
    }

    @Test void playersStartWithNoEmeralds() {
        assertEquals(0, i("starting-emeralds"));
    }

    @Test void golemIsOnWithSaneNumbers() {
        assertEquals(1, i("golem-enabled"));
        assertTrue(i("golem-health-base") > 0);
        assertTrue(i("golem-respawn-seconds") >= 60, "respawn isn't a short test timer");
        assertTrue(i("golem-heart-seconds") > 0);
        for (String k : new String[]{"golem-health-percent", "golem-damage-percent", "golem-damage-boost-percent", "golem-attack-speed-percent"})
            assertTrue(i(k) >= 100, k);
    }

    @Test void arsenalAndBlackMarketDefaults() {
        assertEquals(2, i("arsenal-carry-limit"));
        assertTrue(i("black-market-slots") > 0);
        assertTrue(i("black-market-restock-seconds") >= 60);
        for (ForgeArsenal.Price p : ForgeArsenal.PRICES) assertTrue(i("arsenal-price-" + p.id()) > 0, p.id());
    }
}
