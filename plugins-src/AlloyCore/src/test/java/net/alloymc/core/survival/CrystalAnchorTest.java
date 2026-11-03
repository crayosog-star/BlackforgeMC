package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Rimelock crystal escape (no server needed). Cage centred on 0,64,0. */
class CrystalAnchorTest {
    private static final double Y = 64;

    @Test void crystalsSitOnTheRimOverThePosts() {
        assertEquals(8, CrystalAnchor.COUNT);
        for (int k = 0; k < CrystalAnchor.COUNT; k++) {
            double x = CrystalAnchor.x(0, k), z = CrystalAnchor.z(0, k);
            assertEquals(CageShape.RADIUS, Math.sqrt(x * x + z * z), 1e-9);
            assertEquals(CageShape.edgeAngle(k * 4), CrystalAnchor.angle(k), 1e-12);
        }
        assertEquals(Y + CageShape.WALL_HEIGHT + 1, CrystalAnchor.y(Y), 1e-12);
    }

    @Test void crystalsAreEvenlySpaced() {
        double step = 2 * Math.PI / CrystalAnchor.COUNT;
        for (int k = 1; k < CrystalAnchor.COUNT; k++)
            assertEquals(step, CrystalAnchor.angle(k) - CrystalAnchor.angle(k - 1), 1e-9);
    }

    @Test void aimingStraightAtItHitsTheNearSideOfTheSphere() {
        // from the middle of the floor, straight at the crystal over the +X post
        double sx = CrystalAnchor.x(0, 0), sy = CrystalAnchor.y(Y), sz = CrystalAnchor.z(0, 0);
        double d = Math.sqrt(sx * sx + (sy - Y) * (sy - Y) + sz * sz);
        double hit = CrystalAnchor.rayHit(0, Y, 0, sx / d, (sy - Y) / d, sz / d, sx, sy, sz, 1.5, 24);
        assertEquals(d - 1.5, hit, 1e-9);
    }

    @Test void aimingJustInsideTheRadiusHitsAndJustOutsideMisses() {
        // ray along +X at the crystal's height; the sphere is 10 blocks away
        assertTrue(CrystalAnchor.rayHit(0, 0, 0, 1, 0, 0, 10, 1.4, 0, 1.5, 24) > 0);
        assertEquals(-1, CrystalAnchor.rayHit(0, 0, 0, 1, 0, 0, 10, 1.6, 0, 1.5, 24), 0);
    }

    @Test void aWideAimRadiusIsAboutFiveDegreesAtTheCrystalHeight() {
        double d = 17;   // roughly how far a crystal is from a player standing in the cage
        double angle = Math.toDegrees(Math.atan(1.5 / d));
        assertTrue(angle > 4.5 && angle < 5.5, "aim cone was " + angle);
    }

    @Test void aSphereBehindYouIsAMiss() {
        assertEquals(-1, CrystalAnchor.rayHit(0, 0, 0, 1, 0, 0, -10, 0, 0, 1.5, 24), 0);
    }

    @Test void startingInsideTheSphereHitsAtOnce() {
        assertEquals(0, CrystalAnchor.rayHit(9.5, 0, 0, 1, 0, 0, 10, 0, 0, 1.5, 24), 0);
    }

    @Test void tooFarOrBehindABlockIsAMiss() {
        assertEquals(-1, CrystalAnchor.rayHit(0, 0, 0, 1, 0, 0, 30, 0, 0, 1.5, 24), 0);    // out of chain range
        assertEquals(-1, CrystalAnchor.rayHit(0, 0, 0, 1, 0, 0, 10, 0, 0, 1.5, 5), 0);     // a block is 5 away
    }

    @Test void vaultStartsAtTheGivenSpeedAndEndsAtItsShare() {
        int n = 6;
        double start = 0.4;
        assertEquals(start, CrystalAnchor.vaultSpeed(0, n, start), 1e-12);
        assertEquals(start * CrystalAnchor.VAULT_END_SHARE, CrystalAnchor.vaultSpeed(n, n, start), 1e-12);
    }

    @Test void vaultSpeedNeverRisesAndHasNoJumps() {
        int n = 6;
        double start = 0.4, prev = CrystalAnchor.vaultSpeed(0, n, start);
        for (int k = 1; k <= n + 3; k++) {   // ticks past the end stay put
            double v = CrystalAnchor.vaultSpeed(k, n, start);
            assertTrue(v <= prev + 1e-12, "speed rose at tick " + k);
            assertTrue(prev - v <= 0.25 * start, "speed dropped too sharply at tick " + k);
            assertTrue(v >= start * CrystalAnchor.VAULT_END_SHARE - 1e-12, "undershot at tick " + k);
            prev = v;
        }
    }

    @Test void vaultLiftEasesFromTheLiftDownToNothing() {
        int n = 6;
        assertEquals(CrystalAnchor.VAULT_LIFT, CrystalAnchor.vaultLift(0, n), 1e-12);
        assertEquals(0, CrystalAnchor.vaultLift(n, n), 1e-12);
        double prev = CrystalAnchor.vaultLift(0, n);
        for (int k = 1; k <= n; k++) {
            double v = CrystalAnchor.vaultLift(k, n);
            assertTrue(v <= prev + 1e-12 && v >= -1e-12, "lift misbehaved at tick " + k);
            assertTrue(prev - v <= 0.5 * CrystalAnchor.VAULT_LIFT, "lift dropped too sharply at tick " + k);
            prev = v;
        }
    }

    @Test void zeroLengthVaultDoesNothing() {
        assertEquals(0, CrystalAnchor.vaultSpeed(0, 0, 0.4), 0);
        assertEquals(0, CrystalAnchor.vaultLift(0, 0), 0);
    }

    @Test void theReelReachesAnyCrystalInTime() {
        // from the far side of the floor the crystal is the longest way off; the reel must arrive within its time limit
        double horizontal = 2 * CageShape.RADIUS, vertical = CrystalAnchor.HEIGHT - 1.0;   // across the floor, up to the crystal
        double far = Math.sqrt(horizontal * horizontal + vertical * vertical);
        assertTrue(far <= ChainPull.GRAPPLE_RANGE, "a crystal is out of chain range from the far wall: " + far);
        double covered = 0;
        int ticks = 0;
        while (!ChainPull.arrived(far - covered) && ticks < 1000) covered += ChainPull.reelSpeed(ticks++);
        assertTrue(ticks < ChainPull.REEL_MAX_TICKS, "reel took " + ticks + " ticks");
    }
}
