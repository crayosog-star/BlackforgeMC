package net.blackforge.core.survival;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Which job a Forge Allay takes next, and when a base looks for more. No Bukkit types, so it can be unit-tested. */
final class AllayJobs {
    private AllayJobs() {}

    /** Something to fetch, and where it was when last seen. */
    record Job(UUID id, double x, double y, double z) {}

    /** The nearest job no other allay of the flock has taken. */
    static Optional<Job> nearest(double[] from, Collection<Job> jobs, Set<UUID> taken) {
        Job best = null;
        double bestDist = Double.MAX_VALUE;
        for (Job j : jobs) {
            if (taken.contains(j.id())) continue;
            double dx = j.x() - from[0], dy = j.y() - from[1], dz = j.z() - from[2], d = dx * dx + dy * dy + dz * dz;
            if (d < bestDist) { bestDist = d; best = j; }
        }
        return Optional.ofNullable(best);
    }

    /** Has it been long enough since this base last looked? */
    static boolean due(long lastScan, long now, long intervalTicks) { return now - lastScan >= intervalTicks; }
}
