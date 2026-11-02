package net.alloymc.core.arcade.games.bedwars;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The Golem's maths and its model: sizes, phases, the slam ring, the leash and which moves it picks when. */
class GolemTest {
    @Test void modelIsAboutSevenAndAHalfBlocksTallAndFitsTheHall() {
        double h = GolemModel.height(), w = GolemModel.halfWidth();
        assertTrue(h >= 6.5 && h <= 8, "Golem should be about 7.5 blocks tall, is " + h);
        assertTrue(w <= 4.2, "Golem is " + (2 * w) + " blocks wide, too wide for the gaps between the pillars");
        // it can stand anywhere on its leash (7.5 blocks) without its arms reaching the hall wall (radius ~12 at the narrowest)
        assertTrue(7.5 + w < 12, "Golem's arms poke into the wall at the edge of its leash");
    }

    @Test void modelHasEveryPartTheFightNeeds() {
        Set<GolemModel.Group> groups = EnumSet.noneOf(GolemModel.Group.class);
        for (GolemModel.Part p : GolemModel.PARTS) {
            groups.add(p.group());
            assertTrue(p.sx() > 0 && p.sy() > 0 && p.sz() > 0, "zero-size part " + p);
            assertTrue(p.block().startsWith("minecraft:"), "block id without namespace: " + p.block());
        }
        assertEquals(EnumSet.allOf(GolemModel.Group.class), groups, "every animated group has parts");
        assertTrue(GolemModel.PARTS.size() <= 60, "too many display entities for one boss: " + GolemModel.PARTS.size());
        // the ore glows hot pink, the heart-stone and eyes green
        assertTrue(GolemModel.PARTS.stream().anyMatch(p -> p.group() == GolemModel.Group.ORE && p.glow() == GolemModel.ORE_PINK && p.bright()));
        assertTrue(GolemModel.PARTS.stream().anyMatch(p -> p.group() == GolemModel.Group.HEART && p.glow() == GolemModel.HEART_GREEN));
        // the ore grows out of its back (z < 0), not its chest
        for (GolemModel.Part p : GolemModel.PARTS) if (p.group() == GolemModel.Group.ORE) assertTrue(p.cz() <= 0.6, "ore on the front: " + p);
    }

    @Test void phasesAndHealthScaling() {
        assertEquals(1, GolemMath.phase(100, 100));
        assertEquals(1, GolemMath.phase(51, 100));
        assertEquals(2, GolemMath.phase(50, 100));
        assertEquals(2, GolemMath.phase(21, 100));
        assertEquals(3, GolemMath.phase(20, 100));
        assertEquals(360, GolemMath.health(0, 300, 60), "nobody below still counts as one");
        assertEquals(540, GolemMath.health(4, 300, 60));
        assertTrue(GolemMath.telegraph(GolemMath.Move.SLAM, 3) < GolemMath.telegraph(GolemMath.Move.SLAM, 1), "enraged wind-ups are shorter");
        for (GolemMath.Move m : GolemMath.Move.values()) assertTrue(GolemMath.telegraph(m, 3) >= 6, m + " wind-up too short to react to");
    }

    @Test void slamRingCanBeJumped() {
        // ring at 0.3 blocks/tick reaches 3 blocks at tick 10
        assertTrue(GolemMath.ringHits(3.0, 10, 0.3, 1.4, 0, 12), "standing player 3 blocks out gets hit at tick 10");
        assertFalse(GolemMath.ringHits(3.0, 10, 0.3, 1.4, 1.0, 12), "a jumping player lets it pass under");
        assertFalse(GolemMath.ringHits(3.0, 3, 0.3, 1.4, 0, 12), "not there yet");
        assertFalse(GolemMath.ringHits(13.0, 44, 0.3, 1.4, 0, 12), "past its reach");
    }

    @Test void leashKeepsItInTheHall() {
        double[] in = GolemMath.leash(3, 4, 9.5);
        assertArrayEquals(new double[]{3, 4}, in, 1e-9);
        double[] out = GolemMath.leash(30, 40, 9.5);
        assertEquals(9.5, Math.hypot(out[0], out[1]), 1e-9);
        assertEquals(0.6, out[0] / 9.5, 1e-9, "pulled straight back towards the middle");
    }

    @Test void facingAndYaw() {
        assertEquals(0, GolemMath.yawTo(0, 5), 1e-6, "yaw 0 faces +z");
        assertTrue(GolemMath.inFront(0, 0, 3, 60));
        assertFalse(GolemMath.inFront(0, 0, -3, 60), "behind it");
        assertTrue(GolemMath.inFront(GolemMath.yawTo(4, 1), 4, 1, 10), "faces what it turned to");
    }

    @Test void phaseTwoMovesOnlyInPhaseTwo() {
        Random r = new Random(3);
        for (int i = 0; i < 2000; i++) {
            GolemMath.Move m = GolemMath.pick(1, r.nextDouble() * 12, r.nextBoolean(), 1 + r.nextInt(6), r);
            assertNotEquals(GolemMath.Move.CHARGE, m);
            assertNotEquals(GolemMath.Move.ROCKFALL, m);
            assertNotEquals(GolemMath.Move.SWIPE, m, "swipes are its melee, not a picked special");
        }
        Set<GolemMath.Move> seen = EnumSet.noneOf(GolemMath.Move.class);
        for (int i = 0; i < 2000; i++) seen.add(GolemMath.pick(2, r.nextDouble() * 12, r.nextBoolean(), 1 + r.nextInt(6), r));
        assertTrue(seen.containsAll(EnumSet.of(GolemMath.Move.SLAM, GolemMath.Move.BOULDER, GolemMath.Move.ROOTS, GolemMath.Move.CHARGE, GolemMath.Move.ROCKFALL)), "phase 2 uses every special: " + seen);
    }

    @Test void boulderArcStartsAndEndsWhereItShould() {
        double[] a = GolemMath.arc(0, 4, 0, 10, 0, 0, 4, 0), b = GolemMath.arc(0, 4, 0, 10, 0, 0, 4, 1), mid = GolemMath.arc(0, 4, 0, 10, 0, 0, 4, 0.5);
        assertArrayEquals(new double[]{0, 4, 0}, a, 1e-9);
        assertArrayEquals(new double[]{10, 0, 0}, b, 1e-9);
        assertTrue(mid[1] > 4, "it arcs up on the way");
    }
}
