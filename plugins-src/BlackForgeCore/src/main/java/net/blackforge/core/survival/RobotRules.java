package net.blackforge.core.survival;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure robot rules (fishing pace, fuel meter, fetch/store triggers, charge blending). No Bukkit types here so it can be
 * unit-tested without a server; Robots.java converts items and inventories into these plain values.
 */
final class RobotRules {
    private RobotRules() {}

    /** Charge multiplier per Fuel Cell tier I..V (moved from Robots.FUEL_CHARGE, values unchanged). */
    static final double[] FUEL_CHARGE = {1.0, 1.25, 1.5, 2.0, 3.0};
    /** Milliseconds per fish, per robot tier I..V. */
    static final long[] FISH_INTERVAL_MS = {180_000, 150_000, 120_000, 90_000, 60_000};
    /** A Fisherman burns fuel (and builds Charge) at this fraction of the normal rate. */
    static final double FISHER_BURN = 0.25;
    /** Fuel meter thresholds: 5m, 15m, 30m, 1h, 3h, 12h, 24h. */
    static final long[] SEGMENT_MS = {5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 60 * 60_000L,
            3 * 60 * 60_000L, 12 * 60 * 60_000L, 24 * 60 * 60_000L};

    enum SegmentColor { RED, YELLOW, GREEN }

    /** One Fuel Cell (or a quantity of fuel) of a given tier, worth chargeMs of burn time. */
    record FuelCell(int tier, long chargeMs) {}

    private static int checkTier(int tier) {
        if (tier < 1 || tier > 5) throw new IllegalArgumentException("tier must be 1-5: " + tier);
        return tier;
    }

    // ---------------------------------------------------------------- fishing

    static long fishIntervalMs(int tier) { return FISH_INTERVAL_MS[checkTier(tier) - 1]; }

    static long fishPerHour(int tier) { return 3_600_000L / fishIntervalMs(tier); }

    // ---------------------------------------------------------------- fuel meter

    /** How many of the seven meter segments are lit: segments whose threshold is at or below the fuel left. */
    static int litSegments(long fuelMs) {
        int n = 0;
        for (long t : SEGMENT_MS) if (t <= fuelMs) n++;
        return n;
    }

    /** Colour of meter segment 1..7: 1-2 red, 3-4 yellow, 5-7 green. */
    static SegmentColor segmentColor(int segment) {
        if (segment < 1 || segment > SEGMENT_MS.length) throw new IllegalArgumentException("segment must be 1-7: " + segment);
        return segment <= 2 ? SegmentColor.RED : segment <= 4 ? SegmentColor.YELLOW : SegmentColor.GREEN;
    }

    // ---------------------------------------------------------------- triggers

    /** Fetch more fuel when two minutes or less of burn is left (burnRateMs = fuel ms burned per second), or the tank is empty. */
    static boolean shouldFetchFuel(long fuelMs, long burnRateMs) {
        return fuelMs == 0 || fuelMs <= 2L * 60 * burnRateMs;
    }

    /** Go store loot when any item count has reached the threshold, or storage is full. */
    static boolean shouldStore(Collection<Integer> itemCounts, int threshold, boolean storageFull) {
        if (storageFull) return true;
        for (Integer c : itemCounts) if (c != null && c >= threshold) return true;
        return false;
    }

    // ---------------------------------------------------------------- charge

    static double rateForTier(int tier) { return FUEL_CHARGE[checkTier(tier) - 1]; }

    /** Fuel-weighted average charge rate of the given cells; 1.0 when there is no fuel. */
    static double blendedChargeRate(List<FuelCell> cells) {
        double weighted = 0;
        long total = 0;
        for (FuelCell c : cells) {
            if (c.chargeMs() <= 0) continue;
            weighted += c.chargeMs() * rateForTier(c.tier());
            total += c.chargeMs();
        }
        return total == 0 ? 1.0 : weighted / total;
    }

    /**
     * Rate after adding acceptedMs of fuel at cellRate to a tank holding existingMs at existingRate. The tank keeps one
     * aggregate rate as it drains, so old saves (which never stored individual cells) blend correctly too.
     */
    static double blendRate(long existingMs, double existingRate, long acceptedMs, double cellRate) {
        long e = Math.max(0, existingMs), a = Math.max(0, acceptedMs);
        if (e + a == 0) return existingRate > 0 ? existingRate : 1.0;
        if (e == 0) return cellRate;
        return (e * existingRate + a * cellRate) / (e + a);
    }

    /** The lowest-tier cell (first one on ties), so cheap fuel is used first. */
    static Optional<FuelCell> lowestTierCell(List<FuelCell> cells) {
        return cells.stream().min(Comparator.comparingInt(FuelCell::tier));
    }

    /** An agent's name: 1-16 letters or digits, so it's safe inside MiniMessage text and saved item data. */
    static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9]{1,16}"); }
}
