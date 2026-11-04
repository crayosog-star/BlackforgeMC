package net.blackforge.core.raft;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RaftTest {
    @Test void storyIsTheSameEveryTimeAndAtTheRightDistances() {
        for (int cell = 0; cell < 4; cell++)
            for (int k = 0; k < 4; k++) {
                int[] a = RaftStructures.storyPos(42L, cell, k), b = RaftStructures.storyPos(42L, cell, k);
                assertArrayEquals(a, b);
                assertEquals(RaftStructures.STORY_DIST[k], Math.hypot(a[0] - RaftStructures.originX(cell), a[1]), 2.0);
                assertTrue(Math.abs(a[0] - RaftStructures.originX(cell)) < 9000 && Math.abs(a[1]) < 9000);
            }
    }

    @Test void structuresAreDeterministicAndKeepClearOfTheRaft() {
        int n = 0;
        for (int cx = -20; cx <= 20; cx++) for (int cz = -20; cz <= 20; cz++) {
            var a = RaftStructures.touching(7L, cx * 16, cz * 16);
            var b = RaftStructures.touching(7L, cx * 16, cz * 16);
            assertEquals(a.size(), b.size());
            for (var in : a) { n++; assertTrue(Math.hypot(in.cx, in.cz) > 100 || in.kind().equals("none"), "a structure crowds the starting raft: " + in.kind()); }
        }
        assertTrue(n > 0);
    }

    @Test void towerHoldsItsRadioWhereTheStoryExpectsIt() {
        int[] p = RaftStructures.storyPos(7L, 0, 0);
        int[] radio = RaftStructures.radioAt(7L, 0, 0);
        var in = RaftStructures.touching(7L, (p[0] + 2) >> 4 << 4, p[1] >> 4 << 4).stream().filter(i -> i.kind().equals("tower")).findFirst().orElseThrow();
        boolean found = in.blocksIn(radio[0] >> 4, radio[2] >> 4).stream().anyMatch(b -> b.x() == radio[0] && b.y() == radio[1] && b.z() == radio[2] && b.data().contains("jukebox"));
        assertTrue(found, "the radio jukebox is missing from the tower");
    }

    @Test void everyRecipeUsesKnownItemsAndKeys() {
        for (var r : RaftRecipes.ALL) {
            assertTrue(RaftItems.exists(r.result()), r.id());
            for (String k : r.cost().keySet()) assertTrue(k.equals("plank") || k.equals("iron") || k.equals("coal") || RaftItems.exists(k), r.id() + " " + k);
            for (String k : r.research().keySet()) assertTrue(k.equals("plank") || k.equals("iron") || k.equals("coal") || RaftItems.exists(k), r.id() + " research " + k);
        }
        for (String id : RaftTeam.STARTER_BLUEPRINTS) assertNotNull(RaftRecipes.byId(id));
    }

    @Test void teamSurvivesASaveAndLoad() {
        RaftTeam t = new RaftTeam(UUID.randomUUID(), "Test raft", 3);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) t.tiles.add(RaftTeam.pack(60000 + x, z));
        RaftTeam.Station s = new RaftTeam.Station("purifier", 60000, 63, 0);
        s.salt = 3; s.fuel = 5;
        t.stations.put(s.key(), s);
        t.unlocked.add("sail");
        t.towers = 2;
        t.thirst.put(t.owner, 11);
        YamlConfiguration y = new YamlConfiguration();
        t.save(y.createSection("t"));
        RaftTeam u = RaftTeam.load(y.getConfigurationSection("t"));
        assertEquals(t.tiles, u.tiles);
        assertEquals(1, u.stations.size());
        assertEquals(3, u.stationAt(60000, 63, 0).salt);
        assertTrue(u.unlocked.contains("sail"));
        assertEquals(2, u.towers);
        assertEquals(11, u.thirst.get(t.owner));
        assertEquals(t.centre()[0], u.centre()[0]);
    }
}
