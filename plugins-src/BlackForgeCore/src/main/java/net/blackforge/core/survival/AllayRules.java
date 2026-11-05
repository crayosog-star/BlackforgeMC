package net.blackforge.core.survival;

import java.util.Optional;

/**
 * Pure Forge Allay rules: what each tier unlocks, what it costs, which drops to leave alone and how sales and trips work
 * out. No Bukkit types here so it can be unit-tested without a server.
 */
final class AllayRules {
    private AllayRules() {}

    static final int MAX_TIER = 3;

    /** The numbers that vary by tier, one entry per tier I..III. */
    record Table(int[] flock, int[] leash, int[] price, int[] essence) {
        static final Table DEFAULT = new Table(new int[]{1, 2, 3}, new int[]{12, 20, 28},
                new int[]{75_000, 250_000, 750_000}, new int[]{256, 1_024, 4_096});

        Table {
            if (flock.length != MAX_TIER || leash.length != MAX_TIER || price.length != MAX_TIER || essence.length != MAX_TIER)
                throw new IllegalArgumentException("every allay table needs " + MAX_TIER + " entries");
        }

        int flock(int tier) { return flock[checkTier(tier) - 1]; }
        int leash(int tier) { return leash[checkTier(tier) - 1]; }
        Cost cost(int tier) { return new Cost(price[checkTier(tier) - 1], essence[tier - 1]); }
    }

    /** What it takes to reach a tier. */
    record Cost(int money, int essence) {}

    /** Why an allay leaves a dropped item on the ground. */
    enum DropSkip { TOO_NEW, DEATH_DROP, THROWN_BY_VISITOR }

    private static int checkTier(int tier) {
        if (tier < 1 || tier > MAX_TIER) throw new IllegalArgumentException("tier must be 1-" + MAX_TIER + ": " + tier);
        return tier;
    }

    /** An allay's name: 1-16 letters, digits or single spaces, so it's safe inside MiniMessage text. */
    static boolean validName(String name) {
        return name != null && name.length() <= 16 && name.matches("[A-Za-z0-9]+( [A-Za-z0-9]+)*");
    }

    /** The easter egg: "Smoked Sausage" flies upside down, however it's capitalised and spaced. */
    static boolean flipsUpsideDown(String name) {
        return name != null && name.replaceAll("\\s+", "").equalsIgnoreCase("smokedsausage");
    }

    /** Courier and up: sorts the Station into framed chests. */
    static boolean canSort(int tier) { return checkTier(tier) >= 2; }

    /** Courier and up: tops up Agents from Fuel Cells in the Station. */
    static boolean canRefuel(int tier) { return checkTier(tier) >= 2; }

    /** Steward: sells from the sell chest. */
    static boolean canSell(int tier) { return checkTier(tier) >= 3; }

    /** Why this drop is left alone, or empty if an allay may take it. Other people's loot comes before age, so the reason stays true as it ages. */
    static Optional<DropSkip> skipDrop(int ageTicks, int minAgeTicks, boolean deathDrop, boolean thrownByVisitor) {
        if (deathDrop) return Optional.of(DropSkip.DEATH_DROP);
        if (thrownByVisitor) return Optional.of(DropSkip.THROWN_BY_VISITOR);
        if (ageTicks < minAgeTicks) return Optional.of(DropSkip.TOO_NEW);
        return Optional.empty();
    }

    /** What the owner gets from a sale after the Steward's fee. The fee is held to 0-1, so a bad config can't charge more than the sale. */
    static double netSale(double gross, double fee) {
        return gross * (1 - Math.max(0, Math.min(1, fee)));
    }

    /** Seconds for one trip there and back, with the pause at the end. */
    static double tripSeconds(double distance, double speed, double pauseSeconds) {
        if (speed <= 0) throw new IllegalArgumentException("speed must be above 0: " + speed);
        return 2 * distance / speed + pauseSeconds;
    }

    /** Items a second for one allay making that trip over and over. */
    static double itemsPerSecond(int carry, double tripSeconds) { return carry / tripSeconds; }

    /** Where an unreadable allays.yml is moved: ".broken", or a stamped name when an older broken copy is still there, so the move doesn't fail. */
    static String brokenName(String name, long stamp, java.util.function.Predicate<String> taken) {
        String plain = name + ".broken";
        if (!taken.test(plain)) return plain;
        String stamped = plain + "-" + stamp;
        for (int n = 2; taken.test(stamped); n++) stamped = plain + "-" + stamp + "-" + n;
        return stamped;
    }
}
