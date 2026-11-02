package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.BuiltMap;
import net.alloymc.core.arcade.core.Pt;
import net.alloymc.core.build.Canvas;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Forge Wars easter eggs: every map has the Smith's three hammers, a pink ore vein for every line of the Golem's
 * story, two cracked walls and the jukebox; the secret rooms are sealed until you break the crack and can be walked
 * into after; the song stays inside the note block's range.
 */
class ForgeWarsEggsTest {
    private static final int FLOOR = ForgeWarsMaps.CAVE_FLOOR, STAND = FLOOR + 1;

    private static boolean passable(String b) { return ForgeWarsCaveTest.Walk.passable(b); }

    @Test void everyMapHasEveryEgg() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            String id = b.def().id;
            assertEquals(ForgeLore.HAMMERS, b.def().list("easter.hammer").size(), id + ": hammers");
            assertEquals(ForgeLore.VEIN_LINES.size(), b.def().list("easter.vein").size(), id + ": one vein per line of the story");
            assertEquals(2, b.def().list("easter.crack").size(), id + ": cracked walls");
            assertNotNull(b.def().point("easter.jukebox"), id + ": jukebox");
            Pt j = b.def().point("easter.jukebox");
            assertEquals("minecraft:jukebox", b.canvas().get((int) j.x(), (int) j.y(), (int) j.z()), id + ": jukebox block");
        }
    }

    @Test void hammersFloatInOpenAir() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            for (Pt h : b.def().list("easter.hammer")) {
                int x = (int) Math.floor(h.x()), y = (int) Math.floor(h.y()), z = (int) Math.floor(h.z());
                assertTrue(passable(c.get(x, y, z)) && passable(c.get(x, y + 1, z)), b.def().id + ": hammer at " + h + " is stuck in " + c.get(x, y, z));
                assertFalse(passable(c.get(x, y - 1, z)), b.def().id + ": hammer at " + h + " floats over nothing");
            }
        }
    }

    @Test void veinsGlowAndCanBeTouched() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Canvas c = b.canvas();
            for (Pt v : b.def().list("easter.vein")) {
                int x = (int) v.x(), y = (int) v.y(), z = (int) v.z();
                assertTrue(String.valueOf(c.get(x, y, z)).startsWith("minecraft:pearlescent_froglight"), b.def().id + ": vein at " + v + " is " + c.get(x, y, z));
                boolean face = false;
                for (int[] n : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}})
                    face |= passable(c.get(x + n[0], y + n[1], z + n[2])) || String.valueOf(c.get(x + n[0], y + n[1], z + n[2])).contains("amethyst_cluster");
                assertTrue(face, b.def().id + ": vein at " + v + " is buried with no face showing");
            }
        }
    }

    @Test void noOtherCrackedBricksUnderground() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            Set<Long> cracks = new java.util.HashSet<>();
            for (Pt p : b.def().list("easter.crack")) for (int dy = 0; dy <= 1; dy++) cracks.add(Canvas.key((int) p.x(), (int) p.y() + dy, (int) p.z()));
            for (var e : b.canvas().blocks().entrySet()) {
                if (!e.getValue().contains("cracked_stone_bricks") || Canvas.ky(e.getKey()) >= 0) continue;
                assertTrue(cracks.contains(e.getKey()), b.def().id + ": a cracked brick underground that isn't a secret at "
                        + Canvas.kx(e.getKey()) + "," + Canvas.ky(e.getKey()) + "," + Canvas.kz(e.getKey()));
            }
        }
    }

    @Test void secretRoomsAreSealedUntilTheCrackIsBroken() {
        for (BuiltMap b : new ForgeWars().builtInMaps()) {
            String id = b.def().id;
            Canvas c = b.canvas();
            List<Pt> cracks = b.def().list("easter.crack");
            for (Pt p : cracks) for (int dy = 0; dy <= 1; dy++)
                assertEquals("minecraft:cracked_stone_bricks", c.get((int) p.x(), (int) p.y() + dy, (int) p.z()), id + ": crack at " + p);
            // the Smith's Rest: the hammer over the anvil, stand in front of it
            Pt anvilHammer = b.def().list("easter.hammer").get(2);
            int ax = (int) Math.floor(anvilHammer.x()), az = (int) Math.floor(anvilHammer.z());
            assertEquals(STAND + 1, (int) anvilHammer.y(), id + ": third hammer should be over the anvil on the cave floor");
            // the vault: in front of the jukebox
            Pt j = b.def().point("easter.jukebox");
            int jx = (int) j.x(), jy = (int) j.y(), jz = (int) j.z();

            ForgeWarsCaveTest.Walk w = new ForgeWarsCaveTest.Walk(c);
            assertFalse(w.has(w.reach(3, STAND, 0, 1.0), ax, STAND, az - 1), id + ": the Smith's Rest can be reached without breaking the crack");
            Set<Long> fromStairs = w.reach(ForgeWarsMaps.TUNNEL_TOP + 2, 1, 0, 1.0);
            assertFalse(w.has(fromStairs, jx, jy, jz - 1), id + ": the song vault can be reached without breaking the crack");

            for (Pt p : cracks) for (int dy = 0; dy <= 1; dy++) c.set((int) p.x(), (int) p.y() + dy, (int) p.z(), "minecraft:air");
            ForgeWarsCaveTest.Walk open = new ForgeWarsCaveTest.Walk(c);
            assertTrue(open.has(open.reach(3, STAND, 0, 0.5), ax, STAND, az - 1), id + ": can't walk from the hall into the Smith's Rest once the crack is broken");
            assertTrue(open.has(open.reach(ForgeWarsMaps.TUNNEL_TOP + 2, 1, 0, 0.5), jx, jy, jz - 1), id + ": can't walk from the crypt stairs into the song vault once the crack is broken");
        }
    }

    @Test void theSmithsSongFitsTheNoteBlock() {
        assertEquals(0, ForgeLore.semis("F#3"));
        assertEquals(24, ForgeLore.semis("F#5"));
        assertEquals(8, ForgeLore.semis("D4"));
        assertEquals(1f, ForgeLore.pitch("F#4"), 1e-6);
        int last = -1;
        for (ForgeLore.Note n : ForgeLore.SONG) {
            assertTrue(n.tick() >= last, "notes in order");
            last = n.tick();
            assertTrue(n.pitch() >= 0.5f - 1e-4 && n.pitch() <= 2f + 1e-4, "pitch out of the note block's range: " + n);
            assertTrue(n.volume() > 0 && n.volume() <= 1, "volume: " + n);
            assertTrue(n.sound().startsWith("minecraft:"), n.sound());
        }
        assertEquals(0, ForgeLore.SONG.get(0).tick(), "starts straight away");
        assertTrue(ForgeLore.length() >= 200 && ForgeLore.length() <= 400, "about 10-20 s, is " + ForgeLore.length() + " ticks");
    }
}
