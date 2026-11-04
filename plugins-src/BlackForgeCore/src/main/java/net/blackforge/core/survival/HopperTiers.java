package net.blackforge.core.survival;

/**
 * Pure hopper tier rules, with no Bukkit types so they can be unit-tested without a server. Tier I is a plain vanilla
 * hopper. A tier's delay is the longest wait, in ticks, a hopper may have between moves: vanilla waits 8.
 */
final class HopperTiers {
    private HopperTiers() {}

    static final int MAX_TIER = 3;
    /** Ticks between moves for tier I, II, III. */
    static final int[] DELAYS = {8, 4, 1};
    /** Upgrades needed to go from tier I to II, then II to III. */
    static final int[] UPGRADES_NEEDED = {1, 10};

    static int clampTier(int tier) { return Math.max(1, Math.min(MAX_TIER, tier)); }

    /** Upgrades needed to leave this tier, or 0 at the top. */
    static int upgradesToLeave(int tier, int[] needed) {
        return tier < 1 || tier >= MAX_TIER ? 0 : needed[tier - 1];
    }

    static double itemsPerSecond(int delayTicks) { return 20.0 / Math.max(1, delayTicks); }

    /** A hopper's position inside its chunk as one int, for the chunk's saved list. */
    static int pack(int x, int y, int z) { return (x & 15) | (z & 15) << 4 | (y + 2048) << 8; }
    static int localX(int packed) { return packed & 15; }
    static int localZ(int packed) { return packed >> 4 & 15; }
    static int y(int packed) { return (packed >>> 8) - 2048; }
}
