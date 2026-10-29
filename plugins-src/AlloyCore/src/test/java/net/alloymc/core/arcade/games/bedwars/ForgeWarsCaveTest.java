package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.BuiltMap;
import net.alloymc.core.arcade.core.Pt;
import net.alloymc.core.build.Canvas;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The Black Market cave under the Forge Wars Core: reachable on foot, sealed from the void, and a sane shop roll. */
class ForgeWarsCaveTest {
    private static boolean solid(Canvas c, int x, int y, int z) { String b = c.get(x, y, z); return b != null && !b.equals("minecraft:air"); }

    @Test void marketVillagerStandsOnTheStallFloorInsideTheCave() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Pt m = b.def().point("black_market");
            assertNotNull(m, b.def().id + " has no black_market point");
            int x = (int) Math.floor(m.x()), y = (int) Math.floor(m.y()), z = (int) Math.floor(m.z());
            assertEquals(ForgeWarsMaps.CAVE_FLOOR + 1, y, b.def().id + ": villager isn't at cave floor level");
            assertEquals("minecraft:spruce_planks", b.canvas().get(x, y - 1, z), b.def().id + ": villager isn't on the stall floor");
            assertFalse(solid(b.canvas(), x, y, z) || solid(b.canvas(), x, y + 1, z), b.def().id + ": villager is stuck in a block");
        }
    }

    @Test void stairsRunUnbrokenFromTheSurfaceToTheCaveFloor() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            for (int side : new int[]{1, -1}) {
                int steps = ForgeWarsMaps.TUNNEL_TOP - 5;
                for (int s = 0; s <= steps; s++) {
                    int x = side * (ForgeWarsMaps.TUNNEL_TOP - s), y = -s;
                    for (int z = -1; z <= 1; z++) {
                        String block = c.get(x, y, z);
                        assertNotNull(block, b.def().id + ": missing step at " + x + "," + y + "," + z);
                        assertTrue(block.contains("stairs"), b.def().id + ": step at " + x + "," + y + "," + z + " is " + block);
                        for (int h = 1; h <= 2; h++) assertFalse(solid(c, x, y + h, z) && !c.get(x, y + h, z).contains("roots") && !c.get(x, y + h, z).contains("vines"),
                                b.def().id + ": no headroom above step " + x + "," + y + "," + z + " (" + c.get(x, y + h, z) + ")");
                    }
                }
                // the last step lands on cave floor
                assertTrue(solid(c, side * 4, ForgeWarsMaps.CAVE_FLOOR, 0), b.def().id + ": no floor at the bottom of the stairs");
            }
        }
    }

    @Test void caveFloorNeverOpensIntoTheVoid() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            for (int x = -15; x <= 15; x++) for (int z = -15; z <= 15; z++)
                if ("minecraft:air".equals(c.get(x, ForgeWarsMaps.CAVE_FLOOR + 1, z)))   // standing room in the cave
                    assertTrue(solid(c, x, ForgeWarsMaps.CAVE_FLOOR, z), b.def().id + ": hole in the cave floor at " + x + "," + z);
        }
    }

    @Test void blackMarketRollsDistinctDeals() {
        Random r = new Random(1);
        for (int n = 1; n <= BlackMarket.POOL.size(); n++) {
            List<BlackMarket.Deal> d = BlackMarket.roll(r, n);
            assertEquals(n, d.size());
            assertEquals(n, new HashSet<>(d.stream().map(BlackMarket.Deal::id).toList()).size(), "duplicate deal in a roll of " + n);
        }
        assertEquals(BlackMarket.POOL.size(), BlackMarket.roll(r, 99).size(), "asking for more deals than exist returns the whole pool");
        for (BlackMarket.Deal d : BlackMarket.POOL) assertTrue(d.emeralds() > 0, d.id() + " is free");
        assertEquals(BlackMarket.POOL.size(), new HashSet<>(BlackMarket.POOL.stream().map(BlackMarket.Deal::id).toList()).size(), "duplicate deal ids");
    }
}
