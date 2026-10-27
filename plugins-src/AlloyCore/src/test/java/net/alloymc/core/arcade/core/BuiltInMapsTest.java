package net.alloymc.core.arcade.core;

import net.alloymc.core.arcade.Games;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Every shipped game mode registers cleanly and every shipped map passes its own game's validation. */
class BuiltInMapsTest {
    @Test void everyGameHasSeveralValidMapsInItsOwnPool() {
        List<ArcadeGame> games = Games.all();
        assertTrue(games.size() >= 17, "expected all arcade modes, got " + games.size());
        Set<String> ids = new HashSet<>();
        for (ArcadeGame g : games) {
            assertTrue(ids.add(g.id), "duplicate game id " + g.id);
            assertFalse(g.variants().isEmpty(), g.id + " has no variants");
            List<BuiltMap> maps = g.builtInMaps();
            assertTrue(maps.size() >= 2, g.id + " needs multiple maps, has " + maps.size());
            Set<String> mapIds = new HashSet<>();
            for (BuiltMap b : maps) {
                MapDef d = b.def();
                assertEquals(g.id, d.game, "map " + d.id + " is filed under the wrong game");
                assertTrue(mapIds.add(d.id), "duplicate map id " + d.id);
                assertTrue(MapDef.errors(d.validate(g.requirements())).isEmpty(), g.id + "/" + d.id + ": " + d.validate(g.requirements()));
                assertTrue(b.canvas().size() > 0, g.id + "/" + d.id + " is empty");
                // every variant of the game has at least one map that can host it at its minimum size
                for (Variant v : g.variants()) assertTrue(maps.stream().anyMatch(m -> m.def().supports(v) && m.def().fits(v, Math.max(v.minPlayers(), m.def().minPlayers))),
                        g.id + " variant " + v.id() + " has no compatible map");
            }
        }
    }

    @Test void differentMapsUseDifferentSpawns() {
        for (ArcadeGame g : Games.all()) {
            List<BuiltMap> maps = g.builtInMaps();
            if (maps.size() < 2) continue;
            // every positional list and point (spawns, plots, chests, team bases...) - not just "spawns"
            String a = maps.get(0).def().lists + "" + maps.get(0).def().points, b = maps.get(1).def().lists + "" + maps.get(1).def().points;
            assertNotEquals(a, b, g.id + ": maps share identical spawn configuration");
        }
    }
}
