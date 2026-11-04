package net.blackforge.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class OrangeCompanionTest {
    private static final long SECOND = Duration.ofSeconds(1).toNanos();

    @Test
    void acceptsOnlyOnOrOff() {
        assertEquals(OrangeCompanion.Toggle.ON, OrangeCompanion.Toggle.parse(new String[]{"on"}));
        assertEquals(OrangeCompanion.Toggle.OFF, OrangeCompanion.Toggle.parse(new String[]{"OFF"}));
        assertNull(OrangeCompanion.Toggle.parse(new String[]{}));
        assertNull(OrangeCompanion.Toggle.parse(new String[]{"unknown"}));
        assertNull(OrangeCompanion.Toggle.parse(new String[]{"on", "off"}));
    }

    @Test
    void playsTheIntroTenSecondsAfterFirstSpawn() {
        OrangeCompanion pet = new OrangeCompanion();
        long firstSpawn = -20 * SECOND;
        assertNull(pet.voiceDue(firstSpawn));
        pet.startVoiceClock(firstSpawn);
        assertNull(pet.voiceDue(firstSpawn + 10 * SECOND - 1));
        assertEquals(OrangeCompanion.INTRO, pet.voiceDue(firstSpawn + 10 * SECOND));
        assertNull(pet.voiceDue(firstSpawn + 11 * SECOND));
    }

    @Test
    void rebuildingTheDisplayDoesNotRestartOrReplayTheVoice() {
        OrangeCompanion pet = new OrangeCompanion();
        pet.startVoiceClock(0);
        pet.startVoiceClock(4 * SECOND);
        assertEquals(OrangeCompanion.INTRO, pet.voiceDue(10 * SECOND));
        pet.startVoiceClock(12 * SECOND);
        assertNull(pet.voiceDue(22 * SECOND));
    }

    @Test
    void eachActivationHasItsOwnClock() {
        OrangeCompanion first = new OrangeCompanion();
        OrangeCompanion second = new OrangeCompanion();
        first.startVoiceClock(0);
        second.startVoiceClock(5 * SECOND);
        assertEquals(OrangeCompanion.INTRO, first.voiceDue(10 * SECOND));
        assertNull(second.voiceDue(10 * SECOND));
        assertEquals(OrangeCompanion.INTRO, second.voiceDue(15 * SECOND));
    }

    @Test
    void quipsPlayOnceAtThirtyAndFortySecondsFromSpawn() {
        OrangeCompanion pet = new OrangeCompanion();
        pet.startVoiceClock(0);
        assertEquals(OrangeCompanion.INTRO, pet.voiceDue(10 * SECOND));
        assertNull(pet.voiceDue(30 * SECOND - 1));
        assertEquals("blackforge:pet.vickie", pet.voiceDue(30 * SECOND));
        assertNull(pet.voiceDue(30 * SECOND));
        assertNull(pet.voiceDue(40 * SECOND - 1));
        assertEquals("blackforge:pet.hesitant_gulp", pet.voiceDue(40 * SECOND));
        assertNull(pet.voiceDue(40 * SECOND));
        assertNull(pet.voiceDue(3600 * SECOND));
    }

    @Test
    void resumesAfterAQuietPeriodWithJustOneQuip() {
        OrangeCompanion pet = new OrangeCompanion();
        pet.startVoiceClock(0);
        assertEquals(OrangeCompanion.INTRO, pet.voiceDue(10 * SECOND));
        long returned = 50 * SECOND;
        pet.startVoiceClock(returned);
        assertEquals("blackforge:pet.hesitant_gulp", pet.voiceDue(returned));
        assertNull(pet.voiceDue(returned + SECOND));
    }
}
