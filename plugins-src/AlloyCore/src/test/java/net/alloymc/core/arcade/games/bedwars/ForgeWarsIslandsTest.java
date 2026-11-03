package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.BuiltMap;
import net.alloymc.core.arcade.core.MapDef;
import net.alloymc.core.arcade.core.Pt;
import net.alloymc.core.arcade.core.TeamColor;
import net.alloymc.core.build.Canvas;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Forge Wars team islands: every team gets the same island turned to face its own way, nothing is built past the
 * island radius, the bed keeps its 2-block clear margin, the gate court opening is never closed, and the map's bounds
 * hold everything.
 */
class ForgeWarsIslandsTest {
    /** A team island's own axes: u runs outward from the Core, v sideways, (0, 0) is the island's centre. */
    private record Frame(int cx, int cz, int ox, int oz) {
        int sx() { return -oz; }
        int sz() { return ox; }
        String at(Canvas c, int u, int y, int v) { return c.get(cx + ox * u + sx() * v, y, cz + oz * u + sz() * v); }
    }

    private static Frame frame(MapDef d, int i) {
        Pt bed = d.point("team." + i + ".bed"), spawn = d.point("team." + i + ".spawn");
        int bx = (int) Math.floor(bed.x()), bz = (int) Math.floor(bed.z());
        int ox = (int) Math.signum(bx - Math.floor(spawn.x())), oz = (int) Math.signum(bz - Math.floor(spawn.z()));
        return new Frame(bx - ox * 3, bz - oz * 3, ox, oz);
    }

    private static boolean empty(String b) { return b == null || b.equals("minecraft:air"); }

    /** The block with its properties and the team's colour taken off, so the same build for two teams reads the same. */
    private static String plain(String b) {
        if (b == null) return "";
        String s = b.replaceAll("\\[.*\\]", "");
        for (TeamColor c : TeamColor.values()) s = s.replace("minecraft:" + c.dye + "_", "minecraft:TEAM_");
        return s;
    }

    @Test void everyTeamHasTheSameIslandTurnedToFaceItsWay() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            Frame first = frame(b.def(), 0);
            for (int i = 1; i < b.def().teams; i++) {
                Frame f = frame(b.def(), i);
                for (int u = -23; u <= 23; u++) for (int v = -23; v <= 23; v++) for (int y = -32; y <= 14; y++)
                    assertEquals(plain(first.at(c, u, y, v)), plain(f.at(c, u, y, v)), b.def().id + ": team " + i + " differs from team 0 at u=" + u + " y=" + y + " v=" + v);
            }
        }
    }

    @Test void nothingIsBuiltPastTheIslandRadius() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            for (int i = 0; i < b.def().teams; i++) {
                Frame f = frame(b.def(), i);
                for (int u = -26; u <= 26; u++) for (int v = -26; v <= 26; v++) {   // the stepping islet starts 27 blocks out, so this looks at the island's own air only
                    if (Math.sqrt(u * u + v * v) <= ForgeWarsMaps.TEAM_R + 0.5) continue;
                    for (int y = -45; y <= 20; y++)
                        assertTrue(empty(f.at(c, u, y, v)), b.def().id + ": team " + i + " has " + f.at(c, u, y, v) + " outside its island at u=" + u + " y=" + y + " v=" + v);
                }
            }
        }
    }

    @Test void theBedKeepsItsClearMargin() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            for (int i = 0; i < b.def().teams; i++) {
                Frame f = frame(b.def(), i);
                for (int u = 1; u <= 6; u++) for (int v = -2; v <= 2; v++) for (int y = 1; y <= 3; y++) {
                    String blk = f.at(c, u, y, v);
                    assertTrue(empty(blk) || blk.contains("_bed"), b.def().id + ": team " + i + " has " + blk + " in the bed's clear margin at u=" + u + " y=" + y + " v=" + v);
                }
            }
        }
    }

    @Test void theGateCourtOpeningIsNeverClosed() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            for (int i = 0; i < b.def().teams; i++) {
                Frame f = frame(b.def(), i);
                for (int u = -21; u <= -12; u++) for (int v = -4; v <= 4; v++) for (int y = 1; y <= 6; y++) {
                    String blk = f.at(c, u, y, v);
                    assertTrue(empty(blk), b.def().id + ": team " + i + " has " + blk + " in the gate opening at u=" + u + " y=" + y + " v=" + v);
                }
            }
        }
    }

    @Test void theGeneratorAndVillagerSpotsDidNotMove() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            for (int i = 0; i < b.def().teams; i++) {
                Frame f = frame(b.def(), i);
                assertPoint(b, "team." + i + ".generator", f, 8, 0);
                assertPoint(b, "team." + i + ".shop", f, -1, 6);
                assertPoint(b, "team." + i + ".upgrades", f, -1, -6);
                assertPoint(b, "team." + i + ".arsenal", f, 5, 7);
                assertPoint(b, "team." + i + ".spawn", f, -4, 0);
            }
        }
    }

    private static void assertPoint(BuiltMap b, String key, Frame f, int u, int v) {
        Pt p = b.def().point(key);
        assertNotNull(p, b.def().id + ": " + key);
        assertEquals(f.cx() + f.ox() * u + f.sx() * v, (int) Math.floor(p.x()), b.def().id + ": " + key + " x");
        assertEquals(f.cz() + f.oz() * u + f.sz() * v, (int) Math.floor(p.z()), b.def().id + ": " + key + " z");
    }

    @Test void theMapBoundsHoldEveryBlock() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            var box = b.def().bounds;
            for (var blocks : java.util.List.of(b.canvas().blocks(), b.canvas().lateBlocks()))
                for (long key : blocks.keySet()) {
                    int x = Canvas.kx(key), z = Canvas.kz(key);
                    assertTrue(x >= box.x1() && x <= box.x2() && z >= box.z1() && z <= box.z2(), b.def().id + ": block outside the bounds at " + x + ", " + z);
                }
        }
    }

    @Test void theDiamondIslandIsWhereTheVeinHangs() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Pt dia = b.def().list("diamond_generators").getFirst(), vein = b.def().list("easter.vein").getLast();
            assertEquals((int) Math.floor(dia.x()), (int) Math.floor(vein.x()), b.def().id + ": the last vein hangs under the first diamond island (x)");
            assertEquals((int) Math.floor(dia.z()), (int) Math.floor(vein.z()), b.def().id + ": the last vein hangs under the first diamond island (z)");
        }
    }
}
