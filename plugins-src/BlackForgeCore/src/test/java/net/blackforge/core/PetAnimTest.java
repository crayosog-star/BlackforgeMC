package net.blackforge.core;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PetAnimTest {
    @Test
    void hopCrouchesLeavesTheGroundAndLands() {
        for (boolean leap : new boolean[]{false, true}) {
            int frames = PetAnim.hopFrames(leap);
            assertEquals(0f, PetAnim.hop(frames - 1, leap).lift());
            assertTrue(PetAnim.hop(frames - 1, leap).stretch() < 1, "lands squashed");
            float peak = 0;
            for (int f = 0; f < frames; f++) {
                PetAnim.Pose pose = PetAnim.hop(f, leap);
                assertTrue(pose.lift() >= 0, "never below the ground");
                assertEquals(PetAnim.airborne(f, leap), pose.lift() > 0, "travels only while in the air");
                peak = Math.max(peak, pose.lift());
            }
            assertTrue(peak > .4f, "a visible hop, not a shuffle");
        }
        assertTrue(PetAnim.hop(0, false).stretch() < 1, "crouches before a normal hop");
    }

    @Test
    void everyGagStartsAndEndsAtRest() {
        for (PetAnim.Gag gag : PetAnim.Gag.values()) {
            assertEquals(PetAnim.Pose.REST, PetAnim.gag(gag, 0), gag.name());
            assertEquals(PetAnim.Pose.REST, PetAnim.gag(gag, gag.frames - 1), gag.name());
        }
    }

    /** A display entity takes the short way round, so a step of half a turn or more would spin it backwards. */
    @Test
    void gagRotationsNeverStepFarEnoughToReverse() {
        for (PetAnim.Gag gag : PetAnim.Gag.values()) {
            for (int f = 1; f < gag.frames; f++) {
                PetAnim.Pose a = PetAnim.gag(gag, f - 1), b = PetAnim.gag(gag, f);
                assertTrue(Math.abs(Math.IEEEremainder(b.pitch() - a.pitch(), 2 * Math.PI)) < 2.6, gag + " pitch at " + f);
                assertTrue(Math.abs(Math.IEEEremainder(b.spin() - a.spin(), 2 * Math.PI)) < 2.6, gag + " spin at " + f);
            }
        }
    }

    @Test
    void gagsNeverSinkThroughTheFloor() {
        for (PetAnim.Gag gag : PetAnim.Gag.values()) {
            for (int f = 0; f < gag.frames; f++) assertTrue(PetAnim.gag(gag, f).lift() >= 0, gag + " at " + f);
        }
    }

    @Test
    void roamSpotsCircleTheOwnerInsideTheLeash() {
        Random rng = new Random(7);
        int[] quadrant = new int[4];
        for (int i = 0; i < 4000; i++) {
            double[] offset = PetAnim.roamOffset(rng, PetAnim.ROAM_MIN, PetAnim.ROAM_MAX);
            double radius = Math.hypot(offset[0], offset[1]);
            assertTrue(radius >= PetAnim.ROAM_MIN - 1e-9 && radius <= PetAnim.ROAM_MAX + 1e-9);
            quadrant[(offset[0] >= 0 ? 1 : 0) + (offset[1] >= 0 ? 2 : 0)]++;
        }
        for (int count : quadrant) assertTrue(count > 800, "spread all around the owner, not just behind");
        assertTrue(PetAnim.ROAM_MAX < PetAnim.CHASE_START && PetAnim.CHASE_START < PetAnim.LEASH);
    }

    @Test
    void chaseStartsPastTheLimitAndHoldsUntilClose() {
        assertFalse(PetAnim.chasing(false, 7));
        assertTrue(PetAnim.chasing(false, 8));
        assertTrue(PetAnim.chasing(true, 6));
        assertFalse(PetAnim.chasing(true, 4));
    }

    @Test
    void chaseOutrunsASprintingOwner() {
        int air = 0;
        for (int f = 0; f < PetAnim.hopFrames(true); f++) if (PetAnim.airborne(f, true)) air++;
        double blocksPerSecond = air * PetAnim.LEAP_STEP / (PetAnim.hopFrames(true) * .1);
        assertTrue(blocksPerSecond > 5.7, "sprint is about 5.6 blocks a second, got " + blocksPerSecond);
    }

    @Test
    void turnsTheShortWayRound() {
        assertEquals(1f, PetAnim.turnToward(.8f, 1f, .45f));
        assertEquals(2.95f, PetAnim.turnToward(2.5f, -2.5f, .45f), 1e-4f);
    }
}
