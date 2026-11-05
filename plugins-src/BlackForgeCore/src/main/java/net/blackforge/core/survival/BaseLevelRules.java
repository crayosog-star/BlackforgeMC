package net.blackforge.core.survival;

import java.util.Collection;
import java.util.List;

/**
 * Pure base level rules: what each level costs and unlocks. No Bukkit types here so it can be unit-tested without a server.
 * Levels run from 1 to {@link #MAX_LEVEL}. Delivery targets are lifetime totals, so levelling up never resets progress.
 */
final class BaseLevelRules {
    private BaseLevelRules() {}

    static final int MAX_LEVEL = 5;
    /** Agent jobs by the name the Academy saves them under, in the order Table.jobLevels lists them. */
    static final List<String> JOBS = List.of("farmer", "miner", "fisherman", "grinder");
    private static final int ALLAY_TIERS = 3, HOPPER_TIERS = 3;

    /** What it takes to reach a level. */
    record Cost(long deliveries, int money, int essence) {}

    /** What a claim-wide hopper tier costs. */
    record Price(int money, int essence) {}

    /**
     * The numbers from config. {@code deliveries}, {@code money} and {@code essence} have one entry per level from 2 up.
     * {@code jobLevels} follows {@link #JOBS}. {@code allayLevels} covers allay tiers I..III. {@code hopperLevels},
     * {@code hopperMoney} and {@code hopperEssence} cover hopper tiers II and III.
     */
    record Table(long[] deliveries, int[] money, int[] essence, int[] jobLevels, int[] allayLevels,
                 int[] hopperLevels, int[] hopperMoney, int[] hopperEssence) {
        static final Table DEFAULT = new Table(new long[]{2_000, 12_000, 52_000, 202_000},
                new int[]{25_000, 100_000, 400_000, 2_000_000}, new int[]{128, 512, 2_048, 8_192},
                new int[]{1, 2, 3, 4}, new int[]{3, 4, 5}, new int[]{2, 4},
                new int[]{150_000, 1_500_000}, new int[]{512, 8_192});

        /** A bad config must not make a level free or unreachable, so it's refused here and the defaults are used instead. */
        Table {
            int steps = MAX_LEVEL - 1;
            if (deliveries.length != steps || money.length != steps || essence.length != steps)
                throw new IllegalArgumentException("level costs need " + steps + " entries");
            if (jobLevels.length != JOBS.size()) throw new IllegalArgumentException("job levels need " + JOBS.size() + " entries");
            if (allayLevels.length != ALLAY_TIERS) throw new IllegalArgumentException("allay levels need " + ALLAY_TIERS + " entries");
            int upgrades = HOPPER_TIERS - 1;
            if (hopperLevels.length != upgrades || hopperMoney.length != upgrades || hopperEssence.length != upgrades)
                throw new IllegalArgumentException("hopper tiers need " + upgrades + " entries");
            for (int i = 0; i < steps; i++) {
                if (deliveries[i] <= (i == 0 ? 0 : deliveries[i - 1])) throw new IllegalArgumentException("delivery targets must rise");
                if (money[i] < 0 || essence[i] < 0) throw new IllegalArgumentException("level costs can't be negative");
            }
            if (jobLevels[0] != 1) throw new IllegalArgumentException("the farmer must be unlocked at level 1");
            for (int level : jobLevels) checkUnlock(level);
            checkRising(allayLevels);
            checkRising(hopperLevels);
            for (int i = 0; i < upgrades; i++)
                if (hopperMoney[i] < 0 || hopperEssence[i] < 0) throw new IllegalArgumentException("hopper prices can't be negative");
        }

        /** What it takes to reach this level, from 2 up. */
        Cost cost(int level) {
            if (level < 2 || level > MAX_LEVEL) throw new IllegalArgumentException("no cost for level " + level);
            return new Cost(deliveries[level - 2], money[level - 2], essence[level - 2]);
        }

        /** Enough deliveries to pay for the next level? The fee is checked when it's paid. */
        boolean canLevelUp(int level, long delivered) {
            checkLevel(level);
            return level < MAX_LEVEL && delivered >= deliveries[level - 1];
        }

        /** How far along the next level's target this base is, from 0 to 1. A base at the top is full. */
        double progress(int level, long delivered) {
            checkLevel(level);
            if (level == MAX_LEVEL) return 1;
            return Math.max(0, Math.min(1, (double) delivered / deliveries[level - 1]));
        }

        int jobLevel(String job) {
            int i = JOBS.indexOf(job);
            if (i < 0) throw new IllegalArgumentException("unknown job: " + job);
            return jobLevels[i];
        }

        boolean jobUnlocked(int level, String job) {
            checkLevel(level);
            return level >= jobLevel(job);
        }

        /** The level that lets a base buy this allay tier (1-3). */
        int allayLevel(int tier) {
            if (tier < 1 || tier > ALLAY_TIERS) throw new IllegalArgumentException("allay tier must be 1-" + ALLAY_TIERS + ": " + tier);
            return allayLevels[tier - 1];
        }

        boolean allayUnlocked(int level, int tier) {
            checkLevel(level);
            return level >= allayLevel(tier);
        }

        /** The level that lets a base buy this hopper tier (1-3). Tier I is a plain hopper, so every base has it. */
        int hopperLevel(int tier) {
            checkHopper(tier);
            return tier == 1 ? 1 : hopperLevels[tier - 2];
        }

        boolean hopperUnlocked(int level, int tier) {
            checkLevel(level);
            return level >= hopperLevel(tier);
        }

        /** What this claim-wide hopper tier (2-3) costs. */
        Price hopperCost(int tier) {
            checkHopper(tier);
            if (tier == 1) throw new IllegalArgumentException("tier 1 is a plain hopper");
            return new Price(hopperMoney[tier - 2], hopperEssence[tier - 2]);
        }

        /** The lowest level that keeps everything a base already has, so nobody loses anything when levels arrive. */
        int floor(Collection<String> jobs, int allayTier, int hopperTier) {
            int floor = 1;
            for (String job : jobs) floor = Math.max(floor, jobLevel(job));
            if (allayTier > 0) floor = Math.max(floor, allayLevel(allayTier));
            if (hopperTier > 0) floor = Math.max(floor, hopperLevel(hopperTier));
            return floor;
        }
    }

    private static void checkLevel(int level) {
        if (level < 1 || level > MAX_LEVEL) throw new IllegalArgumentException("level must be 1-" + MAX_LEVEL + ": " + level);
    }

    private static void checkHopper(int tier) {
        if (tier < 1 || tier > HOPPER_TIERS) throw new IllegalArgumentException("hopper tier must be 1-" + HOPPER_TIERS + ": " + tier);
    }

    private static void checkUnlock(int level) {
        if (level < 1 || level > MAX_LEVEL) throw new IllegalArgumentException("unlock level must be 1-" + MAX_LEVEL + ": " + level);
    }

    private static void checkRising(int[] levels) {
        for (int i = 0; i < levels.length; i++) {
            checkUnlock(levels[i]);
            if (i > 0 && levels[i] < levels[i - 1]) throw new IllegalArgumentException("a higher tier can't unlock before a lower one");
        }
    }
}
