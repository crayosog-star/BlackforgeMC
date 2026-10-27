package net.alloymc.core.arcade.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the arcade framework (no server needed). */
class ArcadeCoreTest {
    private static final Variant SOLO = Variant.ffa("solo", "Solo", 2, 16);

    static MapDef map(String game, String id) {
        MapDef m = new MapDef(game, id);
        m.bounds = Box.of(-20, -10, -20, 20, 30, 20);
        m.spectator = Pt.of(0, 10, 0);
        m.minPlayers = 2; m.maxPlayers = 8;
        for (int i = 0; i < 8; i++) m.add("spawns", Pt.of(i * 2, 1, 0));
        return m;
    }

    // ------------------------------------------------------------------ validation

    @Test void validMapHasNoErrors() {
        assertTrue(MapDef.errors(map("spleef", "a").validate(new MapRequirements().list("spawns", MapRequirements.PER_PLAYER))).isEmpty());
    }

    @Test void missingBoundsSpectatorAndPointsAreReported() {
        MapDef m = new MapDef("bedwars", "broken");
        assertFalse(MapDef.errors(m.validate(null)).isEmpty());
        m.bounds = Box.of(-5, 0, -5, 5, 10, 5);
        List<String> p = MapDef.errors(m.validate(new MapRequirements().point("centre").list("spawns", 4).teamPoint("bed")));
        assertTrue(p.stream().anyMatch(s -> s.contains("spectator")));
        assertTrue(p.stream().anyMatch(s -> s.contains("centre")));
        assertTrue(p.stream().anyMatch(s -> s.contains("spawns")));
        assertTrue(p.stream().anyMatch(s -> s.contains("teams")));
    }

    @Test void pointsOutsideBoundsAreErrors() {
        MapDef m = map("x", "y");
        m.point("goal", Pt.of(100, 0, 0));
        assertTrue(MapDef.errors(m.validate(new MapRequirements().point("goal"))).stream().anyMatch(s -> s.contains("outside")));
    }

    @Test void overlappingSpawnsAreAWarningNotAnError() {
        MapDef m = map("x", "y");
        m.add("spawns", Pt.of(0, 1, 0));
        List<String> p = m.validate(null);
        assertTrue(p.stream().anyMatch(s -> s.startsWith("warning:") && s.contains("overlap")));
        assertTrue(MapDef.errors(p).isEmpty());
    }

    @Test void teamPointsArePerTeam() {
        MapDef m = map("bedwars", "t");
        m.teams = 2;
        m.point("team.0.bed", Pt.of(1, 1, 1));
        List<String> e = MapDef.errors(m.validate(new MapRequirements().teamPoint("bed")));
        assertEquals(1, e.size());
        assertTrue(e.getFirst().contains("team.1.bed"));
    }

    // ------------------------------------------------------------------ selection

    @Test void picksOnlyFromItsOwnPoolAndNeverDisabledOrInvalid() {
        MapSelector s = new MapSelector(2);
        MapDef a = map("spleef", "a"), b = map("spleef", "b"), bad = map("spleef", "bad"), off = map("spleef", "off");
        off.enabled = false;
        Random r = new Random(1);
        for (int i = 0; i < 300; i++) {
            MapDef m = s.pick("spleef", List.of(a, b, bad, off), SOLO, 4, Set.of(), x -> x != bad, r).orElseThrow();
            assertNotSame(bad, m);
            assertNotSame(off, m);
            assertEquals("spleef", m.game);
        }
    }

    @Test void avoidsTheLastMapWhenAnotherExists() {
        MapSelector s = new MapSelector(1);
        MapDef a = map("g", "a"), b = map("g", "b");
        Random r = new Random(7);
        String last = null;
        for (int i = 0; i < 50; i++) {
            MapDef m = s.pick("g", List.of(a, b), SOLO, 4, Set.of(), x -> true, r).orElseThrow();
            if (last != null) assertNotEquals(last, m.id, "same map twice in a row");
            s.played("g", m.id);
            last = m.id;
        }
    }

    @Test void recentMapsAreTrackedPerGame() {
        MapSelector s = new MapSelector(1);
        s.played("bedwars", "a");
        MapDef sa = map("skywars", "a"), sb = map("skywars", "b");
        // bedwars history must not stop skywars picking its own "a"
        Set<String> seen = new HashSet<>();
        Random r = new Random(3);
        for (int i = 0; i < 40; i++) seen.add(s.pick("skywars", List.of(sa, sb), SOLO, 4, Set.of(), x -> true, r).orElseThrow().id);
        assertTrue(seen.contains("a"));
    }

    @Test void singleMapIsAlwaysAllowed() {
        MapSelector s = new MapSelector(3);
        MapDef only = map("g", "only");
        s.played("g", "only");
        assertEquals("only", s.pick("g", List.of(only), SOLO, 4, Set.of(), x -> true, new Random()).orElseThrow().id);
    }

    @Test void excludedFailedMapsAreSkippedAndEmptyPoolGivesNothing() {
        MapSelector s = new MapSelector(0);
        MapDef a = map("g", "a"), b = map("g", "b");
        for (int i = 0; i < 30; i++) assertEquals("b", s.pick("g", List.of(a, b), SOLO, 4, Set.of("a"), x -> true, new Random(i)).orElseThrow().id);
        assertTrue(s.pick("g", List.of(a, b), SOLO, 4, Set.of("a", "b"), x -> true, new Random()).isEmpty());
        assertTrue(s.pick("g", List.of(), SOLO, 4, Set.of(), x -> true, new Random()).isEmpty());
    }

    @Test void respectsPlayerCountsVariantsAndTeamCapacity() {
        MapSelector s = new MapSelector(0);
        MapDef small = map("g", "small"); small.maxPlayers = 4;
        MapDef sumoOnly = map("g", "sumo"); sumoOnly.variants.add("sumo_1v1");
        Variant duo = Variant.teams("doubles", "Doubles", 8, 2, 3);
        MapDef fourTeams = map("g", "four"); fourTeams.teams = 4; fourTeams.maxPlayers = 16;
        assertTrue(s.eligible(List.of(small), SOLO, 6, Set.of(), x -> true).isEmpty());
        assertTrue(s.eligible(List.of(sumoOnly), SOLO, 2, Set.of(), x -> true).isEmpty());
        assertEquals(1, s.eligible(List.of(fourTeams), duo, 8, Set.of(), x -> true).size());
        assertTrue(s.eligible(List.of(fourTeams), duo, 10, Set.of(), x -> true).isEmpty(), "10 players don't fit 4 bases of 2");
    }

    @Test void selectionIsRandomAndRoughlyFair() {
        MapSelector s = new MapSelector(0);
        List<MapDef> pool = List.of(map("g", "a"), map("g", "b"), map("g", "c"));
        Map<String, Integer> n = new HashMap<>();
        Random r = new Random(42);
        for (int i = 0; i < 3000; i++) n.merge(s.pick("g", pool, SOLO, 4, Set.of(), x -> true, r).orElseThrow().id, 1, Integer::sum);
        for (int c : n.values()) assertTrue(c > 850 && c < 1150, "unfair spread " + n);
    }

    @Test void weightsAreHonoured() {
        MapSelector s = new MapSelector(0);
        MapDef a = map("g", "a"), b = map("g", "b");
        b.weight = 3;
        int nb = 0;
        Random r = new Random(5);
        for (int i = 0; i < 4000; i++) if (s.pick("g", List.of(a, b), SOLO, 4, Set.of(), x -> true, r).orElseThrow().id.equals("b")) nb++;
        assertTrue(nb > 2800 && nb < 3200, "weight 3 should win ~75%: " + nb);
    }

    // ------------------------------------------------------------------ instances

    @Test void simultaneousMatchesGetSeparateSlotsEvenOnTheSameMap() {
        SlotAllocator s = new SlotAllocator(3);
        int a = s.acquire("M1"), b = s.acquire("M2"), c = s.acquire("M3");
        assertEquals(3, new HashSet<>(List.of(a, b, c)).size());
        assertEquals(-1, s.acquire("M4"), "full");
        s.release(b, "M2");
        assertEquals(b, s.acquire("M5"));
        s.release(a, "WRONG");   // only the owner can release
        assertEquals("M1", s.ownerOf(a));
    }

    @Test void slotRegionsNeverOverlap() {
        Box biggest = Box.of(-450, -50, -450, 450, 250, 450);
        for (int i = 0; i < 20; i++) {
            Box a = new Box(biggest.x1() + SlotAllocator.originX(i), 0, 0, biggest.x2() + SlotAllocator.originX(i), 0, 0);
            Box b = new Box(biggest.x1() + SlotAllocator.originX(i + 1), 0, 0, biggest.x2() + SlotAllocator.originX(i + 1), 0, 0);
            assertFalse(a.intersects(b));
            assertEquals(i, SlotAllocator.slotAt(SlotAllocator.originX(i) + 300));
            assertEquals(i, SlotAllocator.slotAt(SlotAllocator.originX(i) - 300));
        }
    }

    // ------------------------------------------------------------------ teams, state, rewards, ratings

    @Test void teamsAreBalanced() {
        List<Integer> ps = new ArrayList<>();
        for (int i = 0; i < 7; i++) ps.add(i);
        List<List<Integer>> t = TeamBalancer.split(ps, 4, 4);
        int min = t.stream().mapToInt(List::size).min().orElse(0), max = t.stream().mapToInt(List::size).max().orElse(0);
        assertTrue(max - min <= 1);
        assertEquals(7, t.stream().mapToInt(List::size).sum());
        assertEquals(2, TeamBalancer.split(List.of(1, 2), 8, 1).size());
        assertThrows(IllegalArgumentException.class, () -> TeamBalancer.split(List.of(1, 2, 3), 1, 2));
    }

    @Test void lifecycleTransitionsAreChecked() {
        MatchState.Machine m = new MatchState.Machine(MatchState.MAP_LOADING);
        m.go(MatchState.TELEPORTING_PLAYERS);
        m.go(MatchState.STARTING);
        m.go(MatchState.IN_PROGRESS);
        assertThrows(IllegalStateException.class, () -> m.go(MatchState.STARTING));
        m.go(MatchState.ENDING);
        m.go(MatchState.RESETTING);
        m.go(MatchState.AVAILABLE);
        assertThrows(IllegalStateException.class, () -> m.go(MatchState.IN_PROGRESS));
    }

    @Test void rewardsArePaidOnce() {
        RewardLedger l = new RewardLedger(100);
        UUID u = UUID.randomUUID();
        assertTrue(l.claim("M1", u, "win"));
        assertFalse(l.claim("M1", u, "win"));
        assertTrue(l.claim("M1", u, "played"));
        assertTrue(l.claim("M2", u, "win"));
    }

    @Test void eloMovesPointsFromLoserToWinner() {
        int[] n = Ratings.after(1000, 1000, 1);
        assertEquals(1016, n[0]);
        assertEquals(984, n[1]);
        int[] upset = Ratings.after(900, 1300, 1);
        assertTrue(upset[0] - 900 > 16, "beating a stronger player gains more");
    }

    // ------------------------------------------------------------------ files

    @Test void mapFilesRoundTrip() {
        MapDef m = map("bedwars", "round");
        m.teams = 4; m.weight = 3; m.enabled = false; m.voidY = -22;
        m.point("team.0.bed", Pt.of(1.5, 1, 2.5, 90));
        m.region("team.0.goal", Box.of(-1, -2, -3, 1, 0, 3));
        m.set("rounds", 3);
        m.templateX = 2200;
        YamlConfiguration y = new YamlConfiguration();
        // write the same fields MapStore.save writes, then read them back
        y.set("enabled", m.enabled); y.set("weight", m.weight); y.set("teams", m.teams); y.set("bounds", m.bounds.toString()); y.set("void-y", m.voidY);
        y.set("spectator-spawn", m.spectator.toString()); y.set("points.team.0.bed", m.point("team.0.bed").toString());
        y.set("lists.spawns", m.list("spawns").stream().map(Pt::toString).toList()); y.set("regions.team.0.goal", m.region("team.0.goal").toString());
        y.set("settings.rounds", 3); y.set("template.x", 2200); y.set("min-players", 2); y.set("max-players", 8);
        MapDef r = MapStore.read("bedwars", "round", YamlConfiguration.loadConfiguration(new java.io.StringReader(y.saveToString())));
        assertFalse(r.enabled);
        assertEquals(3, r.weight);
        assertEquals(4, r.teams);
        assertEquals(m.bounds, r.bounds);
        assertEquals(90f, r.point("team.0.bed").yaw());
        assertEquals(m.region("team.0.goal"), r.region("team.0.goal"));
        assertEquals(8, r.list("spawns").size());
        assertEquals(3, ((Number) r.settings.get("rounds")).intValue());
        assertEquals(2200, r.templateX);
    }

    @Test void pointAndBoxParsing() {
        assertEquals(new Pt(1.5, 2, -3.5, 90, 10), Pt.parse(" 1.5, 2, -3.5, 90, 10 "));
        assertEquals(Box.of(-1, 0, -1, 1, 2, 1), Box.parse("1,2,1 ; -1,0,-1"));
        assertTrue(Box.of(0, 0, 0, 2, 2, 2).contains(2.9, 1, 0));
        assertFalse(Box.of(0, 0, 0, 2, 2, 2).contains(3.0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Pt.parse("1,2"));
    }
}
