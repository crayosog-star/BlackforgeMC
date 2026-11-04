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
    void speaksOnceTenSecondsAfterFirstSpawn() {
        OrangeCompanion pet = new OrangeCompanion();
        long firstSpawn = -20 * SECOND;
        assertFalse(pet.voiceDue(firstSpawn));
        pet.startVoiceClock(firstSpawn);
        assertFalse(pet.voiceDue(firstSpawn + 10 * SECOND - 1));
        assertTrue(pet.voiceDue(firstSpawn + 10 * SECOND));
        assertFalse(pet.voiceDue(firstSpawn + 30 * SECOND));
    }

    @Test
    void rebuildingTheDisplayDoesNotRestartOrReplayTheVoice() {
        OrangeCompanion pet = new OrangeCompanion();
        pet.startVoiceClock(0);
        pet.startVoiceClock(4 * SECOND);
        assertTrue(pet.voiceDue(10 * SECOND));
        pet.startVoiceClock(12 * SECOND);
        assertFalse(pet.voiceDue(22 * SECOND));
    }

    @Test
    void eachActivationHasItsOwnClock() {
        OrangeCompanion first = new OrangeCompanion();
        OrangeCompanion second = new OrangeCompanion();
        first.startVoiceClock(0);
        second.startVoiceClock(5 * SECOND);
        assertTrue(first.voiceDue(10 * SECOND));
        assertFalse(second.voiceDue(10 * SECOND));
        assertTrue(second.voiceDue(15 * SECOND));
    }

    @Test
    void hopReturnsToTheGroundWhenThePetStops() {
        assertEquals(0f, OrangeCompanion.hopHeight(0));
        int frame = OrangeCompanion.nextHopFrame(0, true);
        assertTrue(OrangeCompanion.hopHeight(frame) > 0);
        for (int i = 0; i < 12; i++) frame = OrangeCompanion.nextHopFrame(frame, false);
        assertEquals(0, frame);
        assertEquals(0f, OrangeCompanion.hopHeight(frame));
    }
}
