package net.blackforge.core.arcade.games.bedwars;

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

    @Test void timingCurvesFeelRight() {
        // wind-up: arrives by 75% and holds; strike: overshoots a little and settles; hang: fast, slow in the middle, fast
        assertEquals(1, GolemAnim.windupCurve(0.75), 1e-9);
        assertEquals(1, GolemAnim.windupCurve(1), 1e-9);
        assertEquals(0, GolemAnim.strikeCurve(0), 1e-9);
        assertEquals(1, GolemAnim.strikeCurve(1), 1e-9);
        double peak = 0;
        for (double u = 0; u <= 1; u += 0.01) peak = Math.max(peak, GolemAnim.strikeCurve(u));
        assertTrue(peak > 1.02 && peak < 1.12, "strike overshoot should be small but visible: " + peak);
        assertEquals(0, GolemIntro.hang(0, 0.75), 1e-9);
        assertEquals(1, GolemIntro.hang(1, 0.75), 1e-9);
        double prev = -1, fastStart = GolemIntro.hang(0.1, 0.75) - GolemIntro.hang(0, 0.75), slowMid = GolemIntro.hang(0.55, 0.75) - GolemIntro.hang(0.45, 0.75);
        for (double f = 0; f <= 1; f += 0.01) { double h = GolemIntro.hang(f, 0.75); assertTrue(h >= prev, "hang goes backwards"); prev = h; }
        assertTrue(fastStart > 2 * slowMid, "a leap should hang at the top");
    }

    @Test void introLeapLandsOnThePadAndStaysUnderTheCeiling() {
        double[] end = GolemIntro.position(GolemIntro.LAND, 12, 4, -0.95, -0.3, 0.5, 0.5);
        assertArrayEquals(new double[]{0.5, 0, 0.5}, end, 1e-6);
        double top = 0, lastX = GolemIntro.position(GolemIntro.LEAP, 12, 4, -0.95, -0.3, 0.5, 0.5)[0];
        for (int s = GolemIntro.LEAP; s <= GolemIntro.LAND; s++) {
            double[] p = GolemIntro.position(s, 12, 4, -0.95, -0.3, 0.5, 0.5);
            top = Math.max(top, p[1]);
            assertTrue(Math.abs(p[0] - lastX) < 1.6, "leap jumps " + Math.abs(p[0] - lastX) + " blocks in one tick");
            lastX = p[0];
        }
        assertTrue(top + GolemModel.height() < 22, "the Golem would hit the ceiling at the top of its leap");
        assertTrue(GolemIntro.SEAL >= GolemIntro.LEAP, "the hole shouldn't close before it's out");
        assertTrue(GolemIntro.SEAL + GolemIntro.SEAL_TICKS <= GolemIntro.END, "seal finishes before the fight starts");
        // inside the wall, before the breach, it's far enough back to be hidden
        assertTrue(GolemIntro.START_BACK > 3.6 * GolemModel.SCALE * 0.9, "its head would poke out of the wall before the breach");
    }

    @Test void bloodRedCreepsInBelowThirtyPercent() {
        assertEquals(0, GolemMath.blood(31, 100), 1e-9);
        assertEquals(0, GolemMath.blood(30, 100), 1e-9);
        assertEquals(0.5, GolemMath.blood(25, 100), 1e-9);
        assertEquals(1, GolemMath.blood(20, 100), 1e-9);
        assertEquals(1, GolemMath.blood(3, 100), 1e-9);
        assertEquals(GolemModel.ORE_PINK, GolemMath.mix(GolemModel.ORE_PINK, GolemMath.BLOOD_GLOW, 0));
        assertEquals(GolemMath.BLOOD_GLOW, GolemMath.mix(GolemModel.ORE_PINK, GolemMath.BLOOD_GLOW, 1));
        int half = GolemMath.mix(0xFF0000, 0x0000FF, 0.5);
        assertEquals(0x80, half >> 16, 1);
        assertEquals(0x80, half & 255, 1);
    }

    @Test void shardsAreFairAndWhipForward() {
        assertTrue(GolemMath.SHARD_DAMAGE <= 2 && GolemMath.SHARD_CAP <= 6, "a volley shouldn't do more than 3 hearts to one player");
        assertTrue(GolemMath.shardSpeed(0) < 0.3, "shards creep off for a moment");
        assertEquals(2.4, GolemMath.shardSpeed(30), 1e-9);
        for (int i = 1; i < 30; i++) assertTrue(GolemMath.shardSpeed(i) >= GolemMath.shardSpeed(i - 1), "shards never slow down");
        long spikes = GolemModel.PARTS.stream().filter(p -> p.group() == GolemModel.Group.ORE && p.glow() >= 0 && p.sy() > 0.5).count();
        assertTrue(spikes >= 8 && spikes <= 12, "one shard per crystal on its back: " + spikes);
    }

    @Test void introPosesFlowWithoutJumps() {
        GolemAnim.Pose prev = GolemAnim.intro(0);
        for (int s = 1; s <= GolemIntro.END; s++) {
            GolemAnim.Pose p = GolemAnim.intro(s);
            // snappy is fine (the roar whips back ~20 degrees a frame); a break in the motion isn't
            assertTrue(Math.abs(p.armR().x() - prev.armR().x()) < 75, "arm jumps at intro tick " + s);
            assertTrue(Math.abs(p.leanX() - prev.leanX()) < 30, "body jumps at intro tick " + s);
            prev = p;
        }
    }

    @Test void boulderArcStartsAndEndsWhereItShould() {
        double[] a = GolemMath.arc(0, 4, 0, 10, 0, 0, 4, 0), b = GolemMath.arc(0, 4, 0, 10, 0, 0, 4, 1), mid = GolemMath.arc(0, 4, 0, 10, 0, 0, 4, 0.5);
        assertArrayEquals(new double[]{0, 4, 0}, a, 1e-9);
        assertArrayEquals(new double[]{10, 0, 0}, b, 1e-9);
        assertTrue(mid[1] > 4, "it arcs up on the way");
    }

    @Test void deathThroesFlowAndTheShakingDiesDown() {
        GolemAnim.Pose prev = GolemAnim.dying(0);
        for (int s = 1; s <= GolemFinale.DIE; s++) {
            GolemAnim.Pose p = GolemAnim.dying(s);
            assertTrue(Math.abs(p.armR().x() - prev.armR().x()) < 40, "arm jumps at death tick " + s);
            assertTrue(Math.abs(p.leanX() - prev.leanX()) < 12, "body jumps at death tick " + s);
            assertTrue(Math.abs(p.bob() - prev.bob()) < 0.25, "body drops at death tick " + s);
            prev = p;
        }
        GolemAnim.Pose end = GolemAnim.dying(GolemFinale.DIE);
        assertTrue(Math.abs(end.leanZ()) < 0.2, "the shudder has died away: " + end.leanZ());
        assertTrue(end.bob() < -0.5 && end.leanX() > 15, "it ends on its knees, head down");
    }

    @Test void crystalsGoDarkOneByOneInsideTheDeath() {
        Set<Integer> ticks = new java.util.HashSet<>();
        for (int i = 0; i < GolemModel.PARTS.size(); i++) {
            int t = GolemFinale.lightsOut(i, GolemModel.PARTS.size());
            assertTrue(t >= 14 && t < GolemFinale.DIE - 4, "lights out at " + t);
            ticks.add(t);
        }
        assertTrue(ticks.size() >= 15, "they go out over time, not all at once: " + ticks.size() + " different ticks");
    }

    @Test void theHallStirsMoreAndMoreBeforeTheGolemWakes() {
        assertEquals(0, GolemFinale.stir(31), 1e-9);
        assertEquals(0, GolemFinale.stir(0), 1e-9);
        double last = -1;
        int every = 99, dust = -1;
        for (int left = GolemFinale.STIR_SECONDS; left >= 1; left--) {
            double v = GolemFinale.stir(left);
            assertTrue(v >= last && v >= 0 && v <= 1, "it only builds: " + left);
            assertTrue(GolemFinale.rumbleEvery(left) <= every, "rumbles come quicker: " + left);
            assertTrue(GolemFinale.dustPerSecond(left) >= dust, "more dust: " + left);
            last = v; every = GolemFinale.rumbleEvery(left); dust = GolemFinale.dustPerSecond(left);
        }
        assertTrue(GolemFinale.stir(1) > 0.9, "nearly at its peak a second before");
    }

    @Test void shardsRollAndStretchSmoothlyWithSpeed() {
        double lastRoll = 0, lastStretch = 0;
        for (int i = 0; i < 40; i++) {
            double v = GolemMath.shardSpeed(i), r = GolemProjectile.rollRate(v), st = GolemProjectile.stretch(v);
            assertTrue(r >= lastRoll && st >= lastStretch, "faster flight never rolls or stretches less: " + i);
            assertTrue(r <= GolemProjectile.MAX_ROLL && r < Math.PI / 2, "no aliasing roll: " + r);
            assertTrue(st >= 1 && st <= 2, "stretch stays sensible: " + st);
            lastRoll = r; lastStretch = st;
        }
        assertEquals(1, GolemProjectile.stretch(0), 1e-9);
    }

    @Test void shardTurnsOntoItsTargetWithoutASnap() {
        double[] a = {0, 1, 0}, b = {0.6, 0, 0.8};
        double[] prev = a;
        double maxStep = 0, first = 0, last = 0;
        for (int k = 1; k <= GolemProjectile.TURN; k++) {
            double[] v = GolemProjectile.slerp(a, b, GolemProjectile.turn(k));
            assertEquals(1, Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), 1e-9, "stays a unit vector");
            double step = Math.acos(Math.max(-1, Math.min(1, prev[0] * v[0] + prev[1] * v[1] + prev[2] * v[2])));
            if (k == 1) first = step;
            if (k == GolemProjectile.TURN) last = step;
            maxStep = Math.max(maxStep, step);
            prev = v;
        }
        assertArrayEquals(b, GolemProjectile.slerp(a, b, 1), 1e-9);
        assertTrue(first < maxStep && last < maxStep, "it eases in and out of the turn");
        double[] flip = GolemProjectile.slerp(new double[]{0, 0, 1}, new double[]{0, 0, -1}, 0.5);
        assertEquals(1, Math.sqrt(flip[0] * flip[0] + flip[1] * flip[1] + flip[2] * flip[2]), 1e-9, "turning right round still works");
        assertEquals(GolemProjectile.FIRE_DELAY, GolemProjectile.PEEL + GolemProjectile.CHARGE + GolemProjectile.TURN + GolemProjectile.LOCK);
    }

    @Test void boulderTumbleFollowsItsFlightAndNeverAliases() {
        for (int ticks : new int[]{14, 20, 26}) {
            double prev = 0;
            for (int k = 1; k <= ticks; k++) {
                double f = GolemIntro.hang(k / (double) ticks, 0.6), a = GolemProjectile.boulderSpin(f);
                assertTrue(a >= prev, "it never un-spins");
                assertTrue(a - prev < 1.5, "tumbles less than 1.5 rad a tick (" + ticks + " ticks): " + (a - prev));
                prev = a;
            }
        }
        assertEquals(0, GolemProjectile.boulderSpin(0), 1e-9);
    }

    @Test void warningRingsCloseInOnTheSpot() {
        assertEquals(2.5, GolemProjectile.ringRadius(0, 2.5, 0.8), 1e-9);
        assertEquals(0.8, GolemProjectile.ringRadius(1, 2.5, 0.8), 1e-9);
        double prev = 99, lastDrop = 0;
        for (int k = 0; k <= 20; k++) {
            double r = GolemProjectile.ringRadius(k / 20.0, 2.5, 0.8);
            assertTrue(r <= prev, "only closes");
            if (k > 0) lastDrop = prev - r;
            prev = r;
        }
        assertTrue(lastDrop > GolemProjectile.ringRadius(0, 2.5, 0.8) - GolemProjectile.ringRadius(0.05, 2.5, 0.8), "it speeds up as it lands");
    }

    @Test void shardsCrawlThenSpeedUpQuickly() {
        assertTrue(GolemMath.shardSpeed(0) < 0.15 && GolemMath.shardSpeed(3) < 0.2, "they crawl off");
        assertTrue(GolemMath.shardSpeed(10) > 1.5, "then they're quick: " + GolemMath.shardSpeed(10));
        assertEquals(2.4, GolemMath.shardSpeed(GolemMath.SHARD_RAMP + 3), 1e-9);
        for (int i = 1; i < 40; i++) {
            double d = GolemMath.shardSpeed(i) - GolemMath.shardSpeed(i - 1);
            assertTrue(d >= 0 && d < 0.6, "smooth acceleration, no lurch at tick " + i + ": " + d);
        }
    }

    @Test void aimingLeadsByTheRealFlightTime() {
        double prev = 0;
        for (double dist = 2; dist <= 30; dist += 2) {
            double eff = GolemMath.shardEffectiveSpeed(dist);
            assertTrue(eff > prev && eff > 0.1 && eff <= 2.4, "longer shots average faster: " + dist + " -> " + eff);
            prev = eff;
            double covered = 0;
            for (int i = 0; i < (int) Math.floor(GolemMath.shardTravelTicks(dist)); i++) covered += GolemMath.shardSpeed(i);
            assertTrue(covered <= dist + 1e-6 && dist - covered < 2.5, "travel time matches the ramp at " + dist);
        }
    }

    @Test void shardsSpreadOutInAWideFanAboveAndBehind() {
        for (int n : new int[]{6, 9, 10, 12}) {
            double lo = 99, hi = -99, widest = 0;
            for (int i = 0; i < n; i++) {
                double[] h = GolemProjectile.hoverSlot(i, n);
                assertTrue(h[1] > 6.5 && h[1] < 11, "above its head: " + h[1]);
                assertTrue(h[2] < -1.5, "behind it: " + h[2]);
                lo = Math.min(lo, h[1]); hi = Math.max(hi, h[1]); widest = Math.max(widest, Math.abs(h[0]));
                for (int j = 0; j < i; j++) {
                    double[] o = GolemProjectile.hoverSlot(j, n);
                    assertTrue(Math.sqrt(Math.pow(h[0] - o[0], 2) + Math.pow(h[1] - o[1], 2) + Math.pow(h[2] - o[2], 2)) > 1.4, "shards " + i + " and " + j + " bunch up (n=" + n + ")");
                }
            }
            assertTrue(widest >= 4.5, "a wide fan, not a bunch on its back: " + widest);
            assertTrue(hi - lo >= 1.5, "at different heights: " + (hi - lo));
            assertEquals(-GolemProjectile.hoverSlot(n - 1, n)[0], GolemProjectile.hoverSlot(0, n)[0], 1e-9, "symmetric");
        }
    }

    @Test void pacedMovesAreQuickerButNeverUnderTheFloor() {
        assertEquals(24, GolemMath.paced(24, 1, 8));
        assertEquals(16, GolemMath.paced(24, 1.5, 8));
        assertEquals(8, GolemMath.paced(10, 3, 8), "a wind-up never gets shorter than the minimum");
        assertTrue(GolemMath.paced(80, 0.55, 20) > 80, "the cold still slows it");
        assertEquals(20, GolemMath.paced(10, 1, 20));
    }

    @Test void turnsTheShortWayRoundAtALimitedRate() {
        assertEquals(10f, GolemMath.turnToward(0, 90, 10), 1e-4);
        assertEquals(-10f, GolemMath.turnToward(0, -90, 10), 1e-4);
        assertEquals(-170f, GolemMath.turnToward(-160, 170, 10), 1e-3, "across the wrap it goes the short way");
        assertEquals(90f, GolemMath.turnToward(85, 90, 10), 1e-4, "arrives without overshooting");
        float y = 0;
        for (int i = 0; i < 40; i++) y = GolemMath.turnToward(y, 135, 14);
        assertEquals(135f, y, 1e-3);
    }

    @Test void swoopChargeAndDrawBackAreEased() {
        assertEquals(0, GolemProjectile.peel(0), 1e-9);
        assertEquals(1, GolemProjectile.peel(1), 1e-9);
        double peak = 0;
        for (int k = 0; k <= 20; k++) peak = Math.max(peak, GolemProjectile.peel(k / 20.0));
        assertTrue(peak > 1.0 && peak < 1.12, "a little overshoot, then it settles: " + peak);
        assertTrue(GolemProjectile.charge(0.3) < 0.05 && GolemProjectile.charge(1) == 1, "the charge builds slowly, then quickly");
        assertTrue(GolemProjectile.pullBack(0.5) < 0.3 && GolemProjectile.pullBack(1) == 1, "the draw-back eases in");
    }

    @Test void rocksHangThenDropSavagely() {
        assertEquals(0, GolemProjectile.rockDrop(0), 1e-9);
        assertEquals(1, GolemProjectile.rockDrop(1), 1e-9);
        assertTrue(GolemProjectile.rockDrop(0.2) < 0.03, "a slow start");
        double prev = 0;
        for (int k = 1; k <= 10; k++) { double d = GolemProjectile.rockDrop(k / 10.0); assertTrue(d > prev, "keeps falling"); prev = d; }
    }
}
