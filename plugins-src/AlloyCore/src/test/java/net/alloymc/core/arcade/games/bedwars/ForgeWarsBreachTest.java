package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.BuiltMap;
import net.alloymc.core.arcade.core.Pt;
import net.alloymc.core.build.Canvas;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Golem's entrance: it breaks out of the Undercroft wall and leaps to the pad. The hole it carves must only ever
 * open onto the hall (never into a tunnel, a secret room or the ramps above), and its leap must clear the pillars.
 */
class ForgeWarsBreachTest {
    private static final int FLOOR = ForgeWarsMaps.CAVE_FLOOR, STAND = FLOOR + 1;
    private static final String[] BUILT = {"brick", "slab", "stairs", "planks", "smooth_stone", "froglight", "shroomlight", "lantern",
            "candle", "jukebox", "anvil", "glass", "spruce", "bookshelf", "emerald", "slime", "wall"};

    private static boolean built(String b) {
        if (b == null) return false;
        for (String s : BUILT) if (b.contains(s)) return true;
        return false;
    }

    private static boolean air(String b) { return b == null || b.equals("minecraft:air"); }

    @Test void breachIsAtTheWallOnTheHallFloor() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Pt p = b.def().point("golem_breach");
            assertNotNull(p, b.def().id + ": no golem_breach point");
            assertEquals(STAND, (int) p.y(), b.def().id + ": breach isn't at floor level");
            double r = Math.hypot(p.x(), p.z());
            assertTrue(r > 10 && r < 17, b.def().id + ": breach isn't out at the wall: " + r);
            double inX = -p.x() / r, inZ = -p.z() / r;
            // just behind the breach spot is rock
            int bx = (int) Math.floor(p.x() - inX * 1.5), bz = (int) Math.floor(p.z() - inZ * 1.5);
            assertFalse(air(b.canvas().get(bx, STAND + 1, bz)), b.def().id + ": no wall behind the breach");
        }
    }

    @Test void theHoleOnlyEverOpensOntoTheHall() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            Pt p = b.def().point("golem_breach");
            double r = Math.hypot(p.x(), p.z()), inX = -p.x() / r, inZ = -p.z() / r;
            int solid = 0;
            for (int x = (int) p.x() - 14; x <= (int) p.x() + 14; x++)
                for (int z = (int) p.z() - 14; z <= (int) p.z() + 14; z++)
                    for (int dy = -1; dy <= GolemIntro.HEIGHT + 1; dy++) {
                        double dx = x + 0.5 - p.x(), dz = z + 0.5 - p.z();
                        String bl = c.get(x, STAND + dy, z);
                        if (GolemIntro.inHole(dx, dz, dy, inX, inZ)) {
                            assertFalse(built(bl), b.def().id + ": the hole would carve through something built at " + x + "," + (STAND + dy) + "," + z + ": " + bl);
                            if (!air(bl)) solid++;
                            continue;
                        }
                        // one block round the hole: any air here must be the hall itself (cave floor under it), nothing else
                        boolean near = false;
                        for (int[] o : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}})
                            near |= GolemIntro.inHole(dx + o[0], dz + o[2], dy + o[1], inX, inZ);
                        if (!near || !air(bl)) continue;
                        String under = String.valueOf(c.get(x, FLOOR, z));
                        assertFalse(built(under) || under.contains("smooth_stone"), b.def().id + ": the hole would open into a room at " + x + "," + (STAND + dy) + "," + z + " (floor " + under + ")");
                        assertTrue(GolemIntro.depth(dx, dz, inX, inZ) < 1.5 || !air(c.get(x, FLOOR, z)), b.def().id + ": open space behind the wall at " + x + "," + z);
                    }
            assertTrue(solid > 150, b.def().id + ": the hole hardly carves anything (" + solid + " blocks): is it in the wall?");
        }
    }

    @Test void theLeapClearsThePillars() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            Pt p = b.def().point("golem_breach"), pad = b.def().point("launch_pad");
            double r = Math.hypot(p.x(), p.z()), inX = -p.x() / r, inZ = -p.z() / r;
            for (int s = GolemIntro.BREACH; s <= GolemIntro.LAND; s++) {
                double[] at = GolemIntro.position(s, p.x(), p.z(), inX, inZ, pad.x(), pad.z());
                // its body (2.8 wide, 5.4 tall) never passes through anything solid
                for (double ox = -1.2; ox <= 1.2; ox += 0.6) for (double oz = -1.2; oz <= 1.2; oz += 0.6)
                    for (double h = 0.2; h <= 5.2; h += 0.5) {
                        int x = (int) Math.floor(at[0] + ox), y = (int) Math.floor(STAND + at[1] + h), z = (int) Math.floor(at[2] + oz);
                        if (GolemIntro.inHole(x + 0.5 - p.x(), z + 0.5 - p.z(), y - STAND, inX, inZ)) continue;   // carved by then
                        assertTrue(ForgeWarsCaveTest.Walk.passable(c.get(x, y, z)), b.def().id + ": at tick " + s + " the Golem's body is inside " + c.get(x, y, z) + " at " + x + "," + y + "," + z);
                    }
            }
        }
    }
}
