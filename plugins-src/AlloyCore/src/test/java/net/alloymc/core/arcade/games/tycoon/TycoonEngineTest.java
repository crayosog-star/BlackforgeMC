package net.alloymc.core.arcade.games.tycoon;

import net.alloymc.core.arcade.games.tycoon.TycoonEngine.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TycoonEngineTest {
    private static TycoonEngine engine(long seed) { return new TycoonEngine(new Random(seed), s -> {}, t -> {}); }

    @Test
    void boardLayout() {
        TycoonEngine e = engine(1);
        assertEquals(24, e.board.size());
        assertEquals(Type.HEART, e.board.getFirst().type);
        assertEquals(18, e.board.stream().filter(t -> t.type == Type.PROPERTY).count());
        assertEquals(2, e.board.stream().filter(t -> t.type == Type.SMELTER).count());
        assertEquals(3, e.board.stream().filter(t -> t.type == Type.EVENT).count());
        // six districts of three
        Map<District, Long> per = new HashMap<>();
        for (Tile t : e.board) if (t.district != null) per.merge(t.district, 1L, Long::sum);
        assertEquals(6, per.size());
        per.values().forEach(n -> assertEquals(3, n));
    }

    @Test
    void rentGrowsWithDistrictAndBuildings() {
        TycoonEngine e = engine(2);
        Seat a = e.seat(UUID.randomUUID(), "a");
        List<Tile> ash = e.board.stream().filter(t -> t.district == TycoonEngine.ASH).toList();
        e.buy(a, ash.get(0), ash.get(0).price);
        int bare = e.rent(ash.get(0));
        e.buy(a, ash.get(1), ash.get(1).price);
        assertFalse(e.canUpgrade(a, ash.get(0)), "can't build without the whole district");
        e.buy(a, ash.get(2), ash.get(2).price);
        assertTrue(e.rent(ash.get(0)) > bare, "full district pays more");
        assertTrue(e.canUpgrade(a, ash.get(0)));
        e.upgrade(a, ash.get(0));
        assertFalse(e.canUpgrade(a, ash.get(0)), "buildings go up evenly");
        assertTrue(e.canUpgrade(a, ash.get(1)));
        assertEquals((int) Math.round(ash.get(0).baseRent() * TycoonEngine.RENT_MULT[1]), e.rent(ash.get(0)));
    }

    @Test
    void passingTheHeartPaysWages() {
        TycoonEngine e = engine(3);
        Seat a = e.seat(UUID.randomUUID(), "a");
        a.pos = 22;
        e.move(a, 5);
        assertEquals(3, a.pos);
        assertEquals(TycoonEngine.START_CASH + TycoonEngine.WAGES, a.cash);
    }

    @Test
    void shortOfCashSellsOffThenGoesBankrupt() {
        TycoonEngine e = engine(4);
        Seat a = e.seat(UUID.randomUUID(), "a"), b = e.seat(UUID.randomUUID(), "b");
        Tile t = e.board.stream().filter(x -> x.district == TycoonEngine.CROWN).findFirst().orElseThrow();
        e.buy(a, t, t.price);
        a.cash = 10;
        assertTrue(e.pay(a, b, false, 100), "selling the property covers it");
        assertNull(t.owner);
        assertFalse(e.pay(a, b, false, 10_000));
        assertTrue(a.bankrupt);
        assertEquals(0, a.cash);
    }

    /** Plays hundreds of whole games with simple bots: rules never break, and games actually end. */
    @Test
    void simulatedGamesStaySane() {
        int finished = 0;
        for (long seed = 0; seed < 300; seed++) {
            Random r = new Random(seed);
            TycoonEngine e = engine(seed);
            int n = 2 + r.nextInt(5);
            for (int i = 0; i < n; i++) e.seat(UUID.randomUUID(), "p" + i);
            int turn = 0;
            for (int step = 0; step < 1500 && e.solvent().size() > 1; step++) {
                Seat s = e.seats.get(turn++ % e.seats.size());
                if (s.bankrupt) continue;
                if (s.skip > 0) { s.skip--; continue; }
                int roll;
                do {
                    roll = e.roll();
                    assertTrue(roll >= 1 && roll <= 10);
                    e.move(s, roll);
                    resolve(e, s, r);
                } while (roll == 10 && !s.bankrupt);
                if (!s.bankrupt) for (Tile t : e.owned(s)) if (e.canUpgrade(s, t) && s.cash > t.district.upgradeCost() + 150) e.upgrade(s, t);
                check(e);
            }
            if (e.solvent().size() == 1) finished++;
        }
        System.out.println("tycoon sim: " + finished + "/300 games ended in a single winner within 1500 turns");
        // simple bots never trade, so they rarely finish a district; real games also end on the 25-minute clock
        assertTrue(finished > 40, "bankruptcies should still happen, got " + finished + "/300");
    }

    private static void resolve(TycoonEngine e, Seat s, Random r) {
        Landing l = e.land(s);
        Tile t = e.tile(s.pos);
        switch (l) {
            case BUY_OFFER -> {
                if (s.cash > t.price + 100 || r.nextBoolean()) e.buy(s, t, t.price);
                else {   // auction: a random rival bids
                    List<Seat> others = e.solvent().stream().filter(o -> o != s && o.cash > t.price / 2).toList();
                    if (!others.isEmpty()) e.buy(others.get(r.nextInt(others.size())), t, t.price / 2);
                }
            }
            case EVENT -> {
                int before = s.pos;
                e.apply(s, e.draw());
                if (!s.bankrupt && s.pos != before) resolve(e, s, r);
            }
            case SMELTER -> { if (r.nextInt(3) == 0 && e.smelt(s)) { e.move(s, e.roll()); resolve(e, s, r); } }
            default -> {}
        }
    }

    private static void check(TycoonEngine e) {
        for (Seat s : e.seats) {
            assertTrue(s.cash >= 0, "negative cash");
            if (s.bankrupt) assertTrue(e.owned(s).isEmpty());
        }
        assertTrue(e.pool >= 0);
        for (Tile t : e.board) {
            assertTrue(t.level >= 0 && t.level <= 3);
            if (t.level > 0) assertTrue(e.ownsDistrict(t.owner, t.district), "building without the district");
        }
        // buildings stay even within a district (never more than one apart)
        for (District d : List.of(TycoonEngine.ASH, TycoonEngine.COPPER, TycoonEngine.IRON, TycoonEngine.GOLD, TycoonEngine.EMBER, TycoonEngine.CROWN)) {
            IntSummaryStatistics st = e.board.stream().filter(t -> t.district == d).mapToInt(t -> t.level).summaryStatistics();
            assertTrue(st.getMax() - st.getMin() <= 1, d.name() + " uneven");
        }
    }
}
