package net.blackforge.core.survival;

import net.blackforge.core.survival.AllayJobs.Job;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for how a Forge Allay picks work (no server needed). */
class AllayJobsTest {
    private static final double[] HERE = {0, 64, 0};
    private final Job near = new Job(new UUID(0, 1), 3, 64, 0), far = new Job(new UUID(0, 2), 9, 64, 0);

    @Test void takesTheNearest() {
        assertEquals(Optional.of(near), AllayJobs.nearest(HERE, List.of(far, near), Set.of()));
    }

    @Test void skipsWhatTheFlockHasTaken() {
        assertEquals(Optional.of(far), AllayJobs.nearest(HERE, List.of(far, near), Set.of(near.id())));
        assertEquals(Optional.empty(), AllayJobs.nearest(HERE, List.of(far, near), Set.of(near.id(), far.id())));
    }

    @Test void nothingToDo() {
        assertEquals(Optional.empty(), AllayJobs.nearest(HERE, List.of(), Set.of()));
    }

    @Test void nearestPlace() {
        double[] a = {3, 64, 0}, b = {9, 64, 0};
        assertSame(a, AllayJobs.nearestPlace(HERE, List.of(b, a)).get());
        assertTrue(AllayJobs.nearestPlace(HERE, List.of()).isEmpty());
    }

    @Test void scanTiming() {
        assertTrue(AllayJobs.due(0, 100, 100));
        assertFalse(AllayJobs.due(0, 99, 100));
        assertTrue(AllayJobs.due(100, 200, 100));
    }
}
