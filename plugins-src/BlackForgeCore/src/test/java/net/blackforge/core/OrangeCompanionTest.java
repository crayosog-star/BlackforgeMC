package net.blackforge.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void linesComeThirtyToAHundredSecondsApart() {
        OrangeCompanion pet = new OrangeCompanion(new Random(1));
        pet.startVoiceClock(0);
        long previous = 10 * SECOND;
        assertEquals(OrangeCompanion.INTRO, pet.voiceDue(previous));
        int spoken = 0;
        for (long t = previous + SECOND; spoken < 200; t += SECOND) {
            if (pet.voiceDue(t) == null) continue;
            long gap = t - previous;
            assertTrue(gap >= 30 * SECOND && gap <= 100 * SECOND, "gap of " + gap / SECOND + "s");
            previous = t;
            spoken++;
        }
    }

    @Test
    void saysEveryLineBeforeRepeatingAndNeverTwiceInARow() {
        OrangeCompanion pet = new OrangeCompanion(new Random(2));
        pet.startVoiceClock(0);
        long t = 10 * SECOND;
        String last = pet.voiceDue(t);
        for (int round = 0; round < 50; round++) {
            Set<String> heard = new HashSet<>();
            for (int i = 0; i < OrangeCompanion.LINES.size(); i++) {
                t += 100 * SECOND;
                String line = pet.voiceDue(t);
                assertNotNull(line);
                assertNotEquals(last, line);
                assertTrue(heard.add(line), line + " repeated early");
                last = line;
            }
        }
    }

    @Test
    void resumesAfterAQuietPeriodWithJustOneLine() {
        OrangeCompanion pet = new OrangeCompanion(new Random(3));
        pet.startVoiceClock(0);
        assertEquals(OrangeCompanion.INTRO, pet.voiceDue(10 * SECOND));
        long returned = 1000 * SECOND;
        pet.startVoiceClock(returned);
        assertNotNull(pet.voiceDue(returned));
        assertNull(pet.voiceDue(returned + SECOND));
    }

    @Test
    void everyLineIsInTheResourcePack() throws Exception {
        Path pack = Path.of("../../resourcepack/assets/blackforge");
        assumeTrue(Files.isDirectory(pack), "pack not next to this build");
        String sounds = Files.readString(pack.resolve("sounds.json"));
        for (String line : OrangeCompanion.LINES) {
            String name = line.substring("blackforge:".length());
            assertTrue(sounds.contains("\"" + name + "\""), name);
            assertTrue(Files.exists(pack.resolve("sounds/pet/" + name.substring("pet.".length()) + ".ogg")), name);
        }
    }
}
