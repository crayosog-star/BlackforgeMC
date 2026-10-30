package net.alloymc.core.arcade.games.bedwars;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
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

    @Test void newOreMovesShowUpAndRangedPlayersGetRangedMoves() {
        Random r = new Random(5);
        Set<GolemMath.Move> phase1 = EnumSet.noneOf(GolemMath.Move.class);
        for (int i = 0; i < 3000; i++) phase1.add(GolemMath.pick(1, r.nextDouble() * 14, r.nextBoolean(), 1 + r.nextInt(6), r));
        assertTrue(phase1.containsAll(EnumSet.of(GolemMath.Move.ERUPTION, GolemMath.Move.SHARDS)), "phase 1 uses the ore moves: " + phase1);
        int ranged = 0;
        for (int i = 0; i < 1000; i++) {
            GolemMath.Move m = GolemMath.pick(1, 10, true, 2, r);
            if (m == GolemMath.Move.SHARDS || m == GolemMath.Move.BOULDER) ranged++;
        }
        assertTrue(ranged >= 750, "a player up on a ledge should mostly draw shards or boulders, got " + ranged + "/1000");
        assertTrue(GolemMath.telegraph(GolemMath.Move.ERUPTION, 1) >= 20 && GolemMath.telegraph(GolemMath.Move.SHARDS, 1) >= 16, "ore moves need a readable wind-up");
    }

    @Test void eruptionLeavesRoomBetweenTheLanes() {
        for (int phase = 1; phase <= 3; phase++) {
            double[] lanes = GolemMath.eruptionLanes(phase);
            for (int i = 1; i < lanes.length; i++) {
                double gap = Math.toRadians(lanes[i] - lanes[i - 1]);
                // five blocks out, the middle of the gap is this far from each lane; a player (0.3 half-width) fits if it beats the hit radius
                double half = 5 * Math.sin(gap / 2);
                assertTrue(half > GolemMath.SPIKE_HIT + 0.3, "phase " + phase + ": no room between lanes " + i + " five blocks out");
            }
            assertTrue(GolemMath.SPIKE_GAP <= 2 * GolemMath.SPIKE_HIT, "spikes along a lane leave holes you'd fall through by luck");
        }
    }

    @Test void shardsLeadAMovingTarget() {
        assertArrayEquals(new double[]{3, 1, 4}, GolemMath.leadAim(3, 1, 4, 0, 0, 0, 11, 1.1, 0.6), 1e-9);
        double[] a = GolemMath.leadAim(0, 0, 0, 0.2, 0, 0, 11, 1.1, 0.6);
        assertTrue(a[0] > 0.5 && a[0] < 2, "aims a little ahead of a strafing player: " + a[0]);
        assertTrue(GolemMath.CHILL_SLOW > 0.3 && GolemMath.CHILL_SLOW < 0.8, "Rimelock should slow it noticeably but not freeze it");
    }

    // ------------------------------------------------------------------ animation

    private static double[] centre(GolemModel.Part p, GolemAnim.Pose pose) { return GolemAnim.point(p, pose, 0.5, 0.5, 0.5); }

    @Test void restPoseIsTheModelAsDesigned() {
        for (GolemModel.Part p : GolemModel.PARTS) {
            double[] c = centre(p, GolemAnim.Pose.REST);
            assertArrayEquals(new double[]{p.cx(), p.cy(), p.cz()}, c, 1e-6, "part moved at rest: " + p);
        }
    }

    @Test void jointsTurnPartsWithoutStretchingThem() {
        GolemAnim.Pose slam = GolemAnim.windup(GolemMath.Move.SLAM);
        for (GolemModel.Part p : GolemModel.PARTS) {
            if (p.group() != GolemModel.Group.ARM_R) continue;
            double[] a = centre(p, GolemAnim.pose().build()), b = centre(p, GolemAnim.pose().arms(-150, 0, -15).build());
            double[] s = GolemAnim.SHOULDER_R;
            assertEquals(Math.hypot(Math.hypot(a[0] - s[0], a[1] - s[1]), a[2] - s[2]), Math.hypot(Math.hypot(b[0] - s[0], b[1] - s[1]), b[2] - s[2]), 1e-6, "arm part changed length from the shoulder");
            double[][] tf = GolemAnim.transform(p, slam);
            double q = Math.sqrt(tf[1][0] * tf[1][0] + tf[1][1] * tf[1][1] + tf[1][2] * tf[1][2] + tf[1][3] * tf[1][3]);
            assertEquals(1, q, 1e-9, "rotation isn't a unit quaternion");
        }
        // legs stay planted however it leans
        for (GolemModel.Part p : GolemModel.PARTS) if (p.group() == GolemModel.Group.LEGS)
            assertArrayEquals(new double[]{p.cx(), p.cy(), p.cz()}, centre(p, GolemAnim.stunned(7)), 1e-6);
    }

    /** The lowest point of the fists (the last part of each arm, nearest the floor at rest). */
    private static GolemModel.Part fist(GolemModel.Group arm) {
        GolemModel.Part best = null;
        for (GolemModel.Part p : GolemModel.PARTS) if (p.group() == arm && (best == null || p.cy() < best.cy())) best = p;
        return best;
    }

    @Test void movesLookLikeWhatTheyAre() {
        GolemModel.Part fistR = fist(GolemModel.Group.ARM_R), fistL = fist(GolemModel.Group.ARM_L);
        double shoulderY = GolemAnim.SHOULDER_R[1];
        // slam and rockfall wind-ups: both fists above its shoulders; the strike brings them down in front of it
        for (GolemMath.Move m : new GolemMath.Move[]{GolemMath.Move.SLAM, GolemMath.Move.ROCKFALL}) {
            GolemAnim.Pose up = GolemAnim.windup(m), down = GolemAnim.strike(m, 5);
            assertTrue(centre(fistR, up)[1] > shoulderY + 1 && centre(fistL, up)[1] > shoulderY + 1, m + ": fists aren't raised overhead");
            assertTrue(centre(fistR, down)[1] < 1.2 && centre(fistR, down)[2] > 1, m + ": fists don't come down in front");
        }
        // swipe: the right arm goes out to its right (-x) on the wind-up and comes across the front on the strike
        assertTrue(centre(fistR, GolemAnim.windup(GolemMath.Move.SWIPE))[0] < centre(fistR, GolemAnim.Pose.REST)[0] - 1, "swipe wind-up doesn't pull the arm out");
        double[] hitR = centre(fistR, GolemAnim.strike(GolemMath.Move.SWIPE, 5));
        assertTrue(hitR[2] > 2 && hitR[0] > centre(fistR, GolemAnim.Pose.REST)[0], "swipe doesn't come across the front: " + Arrays.toString(hitR));
        // boulder: the right hand goes up and back over its head
        double[] cock = centre(fistR, GolemAnim.windup(GolemMath.Move.BOULDER));
        assertTrue(cock[1] > shoulderY + 1.5 && cock[2] < 1.5, "boulder wind-up isn't over its head: " + Arrays.toString(cock));
        // shards: the ore tips back
        GolemModel.Part spike = GolemModel.PARTS.stream().filter(p -> p.group() == GolemModel.Group.ORE && p.cy() > 5).findFirst().orElseThrow();
        GolemAnim.Pose volley = GolemAnim.windup(GolemMath.Move.SHARDS);
        GolemAnim.Pose noOre = new GolemAnim.Pose(volley.lift(), volley.bob(), volley.leanX(), volley.leanZ(), volley.twist(), volley.armL(), volley.armR(), volley.head(), GolemAnim.Rot.ZERO, 1);
        assertTrue(centre(spike, volley)[2] < centre(spike, noOre)[2] - 0.5, "ore doesn't tip back for the volley");
        // nothing pokes into the ceiling (23 blocks of hall) or far under the floor
        for (GolemMath.Move m : GolemMath.Move.values())
            for (GolemAnim.Pose pose : new GolemAnim.Pose[]{GolemAnim.windup(m), GolemAnim.strike(m, 3)})
                for (GolemModel.Part p : GolemModel.PARTS) {
                    double[] c = centre(p, pose);
                    assertTrue(c[1] < 14 && c[1] > -1.5, m + " puts " + p.block() + " at y " + c[1]);
                }
    }

    @Test void blendingIsContinuous() {
        GolemAnim.Pose a = GolemAnim.walk(3, 1), b = GolemAnim.windup(GolemMath.Move.ERUPTION);
        assertEquals(a, GolemAnim.lerp(a, b, 0));
        assertEquals(b.leanX(), GolemAnim.lerp(a, b, 1).leanX(), 1e-9);
        assertEquals(0, GolemAnim.smooth(0), 1e-9);
        assertEquals(1, GolemAnim.smooth(1), 1e-9);
        double rise = GolemAnim.rising(0).lift();
        assertTrue(rise < -GolemModel.height(), "starts fully under the floor");
        assertEquals(0, GolemAnim.rising(1).lift(), 1e-9);
    }

    @Test void boulderArcStartsAndEndsWhereItShould() {
        double[] a = GolemMath.arc(0, 4, 0, 10, 0, 0, 4, 0), b = GolemMath.arc(0, 4, 0, 10, 0, 0, 4, 1), mid = GolemMath.arc(0, 4, 0, 10, 0, 0, 4, 0.5);
        assertArrayEquals(new double[]{0, 4, 0}, a, 1e-9);
        assertArrayEquals(new double[]{10, 0, 0}, b, 1e-9);
        assertTrue(mid[1] > 4, "it arcs up on the way");
    }
}
