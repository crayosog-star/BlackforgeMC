package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.BuiltMap;
import net.alloymc.core.arcade.core.Pt;
import net.alloymc.core.build.Canvas;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Forge Wars Core: every team can get through the ruined keep to the plaza and the emerald generators; the
 * Undercroft below can be walked in and out of without jumping, its launch shaft is clear, its floor can't drop you
 * into the void, the Black Market villager stands free, and every restock has premium deals.
 */
class ForgeWarsCaveTest {
    private static final int FLOOR = ForgeWarsMaps.CAVE_FLOOR, STAND = FLOOR + 1, OUT = ForgeWarsMaps.TUNNEL_TOP + 2;

    @Test void youCanWalkOutOfTheCaveUpBothStairwaysWithoutJumping() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Walk w = new Walk(b.canvas());
            Set<Long> r = w.reach(3, STAND, 0, 0.5);   // half-block steps and stairs only: no jumps
            assertTrue(w.has(r, OUT, 1, 0), b.def().id + ": can't walk from the cave floor up the east stairway");
            assertTrue(w.has(r, -OUT, 1, 0), b.def().id + ": can't walk from the cave floor up the west stairway");
            assertFalse(w.has(w.reach(3, STAND, 0, 0), OUT, 1, 0), b.def().id + ": walk model is broken (reached the top with no climbing at all)");
        }
    }

    @Test void youCanWalkFromTheSurfaceToTheBlackMarketCounter() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Walk w = new Walk(b.canvas());
            Pt m = b.def().point("black_market");
            int mx = (int) Math.floor(m.x()), mz = (int) Math.floor(m.z());
            assertTrue(w.has(w.reach(OUT, 1, 0, 1.0), mx, STAND, mz + 2), b.def().id + ": no way down to the Black Market counter");
        }
    }

    @Test void everyTeamCanWalkThroughTheRuinsToThePlazaAndTheEmeraldGenerators() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Walk w = new Walk(b.canvas());
            int teams = b.def().teams;
            for (int i = 0; i < teams; i++) {
                double a = Math.PI * 2 * i / teams - Math.PI / 2;
                int[] start = null;   // where a bridge from this team lands: the first standable spot in from the edge
                for (double r = 27.5; r > 20 && start == null; r -= 0.5) {
                    int x = (int) Math.round(Math.cos(a) * r), z = (int) Math.round(Math.sin(a) * r);
                    if (w.stands(x, z).contains(2)) start = new int[]{x, z};
                }
                assertNotNull(start, b.def().id + " team " + i + ": nowhere to land on the Core");
                Set<Long> noJump = w.reach(start[0], 1, start[1], 0.5), jump = w.reach(start[0], 1, start[1], 1.0);
                assertTrue(w.has(noJump, 3, 1, 3) || w.has(noJump, -3, 1, -3), b.def().id + " team " + i + ": can't walk through the gate to the plaza without jumping");
                for (Pt g : b.def().list("emerald_generators")) if (g.y() > 0)
                    assertTrue(w.has(jump, (int) Math.floor(g.x()), (int) Math.floor(g.y()), (int) Math.floor(g.z())), b.def().id + " team " + i + ": can't reach emerald generator " + g);
            }
        }
    }

    @Test void marketVillagerStandsFreeOnTheStallFloor() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            Pt m = b.def().point("black_market");
            int x = (int) Math.floor(m.x()), y = (int) Math.floor(m.y()), z = (int) Math.floor(m.z());
            assertEquals(STAND, y, b.def().id + ": villager isn't at cave floor level");
            assertEquals("minecraft:spruce_planks", c.get(x, y - 1, z), b.def().id + ": villager isn't on the stall floor");
            assertTrue(Walk.passable(c.get(x, y, z)) && Walk.passable(c.get(x, y + 1, z)), b.def().id + ": villager is stuck in a block");
        }
    }

    @Test void launchShaftIsClearFromThePadToThePlaza() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            assertNotNull(b.def().point("launch_pad"), b.def().id + " has no launch_pad point");
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                assertEquals("minecraft:slime_block", c.get(x, FLOOR, z), b.def().id + ": pad isn't slime at " + x + "," + z);
                for (int y = STAND; y <= 2; y++) {
                    String bl = c.get(x, y, z);
                    assertTrue(bl == null || bl.equals("minecraft:air"), b.def().id + ": shaft blocked at " + x + "," + y + "," + z + " by " + bl);
                }
            }
        }
    }

    @Test void caveFloorNeverOpensIntoTheVoidAndSitsAboveIt() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            assertTrue(b.def().voidY < FLOOR - 10, b.def().id + ": void level " + b.def().voidY + " would kill players on the cave floor");
            for (int x = -18; x <= 18; x++) for (int z = -18; z <= 18; z++)
                if ("minecraft:air".equals(c.get(x, STAND, z)))
                    assertFalse(Walk.passable(c.get(x, FLOOR, z)), b.def().id + ": hole in the cave floor at " + x + "," + z);
        }
    }

    @Test void blackMarketAlwaysShowsPremiumDealsAndNoDuplicates() {
        Random r = new Random(1);
        for (int round = 0; round < 50; round++) for (int n = 1; n <= BlackMarket.POOL.size(); n++) {
            List<BlackMarket.Deal> d = BlackMarket.roll(r, n);
            assertEquals(n, d.size());
            assertEquals(n, new HashSet<>(d.stream().map(BlackMarket.Deal::id).toList()).size(), "duplicate deal in a roll of " + n);
            assertTrue(d.stream().filter(BlackMarket.Deal::premium).count() >= Math.min(BlackMarket.PREMIUM_PER_RESTOCK, n), "too few premium deals in a roll of " + n);
        }
        for (BlackMarket.Deal d : BlackMarket.POOL) assertTrue(d.emeralds() > 0, d.id() + " is free");
        assertEquals(BlackMarket.POOL.size(), new HashSet<>(BlackMarket.POOL.stream().map(BlackMarket.Deal::id).toList()).size(), "duplicate deal ids");
    }

    /**
     * A small model of walking: you stand on full blocks or on bottom slabs/stairs (half a block up) with two passable
     * blocks above, and step to a neighbouring column if it's at most {@code maxRise} higher. Walking onto stairs is a
     * step-up (no jump), and dropping lands you on the highest stand below.
     */
    static final class Walk {
        final Canvas c;
        Walk(Canvas c) { this.c = c; }

        static boolean passable(String b) {
            if (b == null || b.equals("minecraft:air") || b.contains("water")) return true;
            for (String s : new String[]{"carpet", "short_grass", "fern", "petals", "roots", "vines", "lily", "spore_blossom", "lantern", "candle"})
                if (b.contains(s)) return true;
            return false;
        }

        static boolean half(String b) { return b != null && (b.contains("slab[type=bottom]") || b.contains("stairs")); }

        /** Standing heights in this column, doubled so half blocks are whole numbers. */
        List<Integer> stands(int x, int z) {
            List<Integer> l = new ArrayList<>();
            for (int y = FLOOR - 6; y <= 4; y++) {
                String b = c.get(x, y, z);
                if (passable(b) || !passable(c.get(x, y + 1, z)) || !passable(c.get(x, y + 2, z))) continue;
                l.add(half(b) ? y * 2 + 1 : (y + 1) * 2);
            }
            return l;
        }

        static long key(int x, int s2, int z) { return (((long) (x + 512)) << 40) | (((long) (s2 + 4096)) << 20) | (z + 512); }

        boolean has(Set<Long> r, int x, int y, int z) { return r.contains(key(x, y * 2, z)); }

        Set<Long> reach(int x0, int y0, int z0, double maxRise) {
            Set<Long> seen = new HashSet<>();
            ArrayDeque<int[]> q = new ArrayDeque<>();
            int s0 = y0 * 2, rise = (int) Math.round(maxRise * 2);
            assertTrue(stands(x0, z0).contains(s0), "start isn't standable at " + x0 + "," + y0 + "," + z0);
            q.add(new int[]{x0, s0, z0});
            seen.add(key(x0, s0, z0));
            while (!q.isEmpty()) {
                int[] n = q.poll();
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = n[0] + d[0], nz = n[2] + d[1];
                    if (Math.abs(nx) > 40 || Math.abs(nz) > 40) continue;
                    List<Integer> st = stands(nx, nz);
                    for (int s : st) {
                        boolean stair = s % 2 != 0 && String.valueOf(c.get(nx, Math.floorDiv(s, 2), nz)).contains("stairs");
                        if (s - n[1] > (stair ? Math.max(rise, 2) : rise)) continue;
                        if (s < n[1] && st.stream().anyMatch(o -> o > s && o <= n[1])) continue;   // you land on the first stand below
                        if (seen.add(key(nx, s, nz))) q.add(new int[]{nx, s, nz});
                    }
                }
            }
            return seen;
        }
    }
}
