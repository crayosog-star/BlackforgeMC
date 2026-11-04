package net.blackforge.core.survival;

/**
 * The Night Thieves' maths, kept apart from Bukkit so it can be tested without a server: when an ambush can happen, how
 * many thieves come and how much stronger they are, how many are mages, what beating them pays, and the timeline of a
 * mage's Hex Bolt.
 *
 * <p>Gear score: each armour piece scores its material tier ({@link #tier}), the weapon scores 1.5x its tier, and every
 * enchantment level adds {@link #PER_ENCHANT_LEVEL}. Bare hands and no armour is 0; full netherite with a netherite sword
 * and no enchants is 27.5; a fully enchanted netherite kit lands around 35 to 40.
 */
public final class ThiefGang {
    private ThiefGang() {}

    /** Night runs from dusk (13000) to just before dawn (23000) in world time. */
    public static final long NIGHT_START = 13000, NIGHT_END = 23000;
    public static final double PER_ENCHANT_LEVEL = 0.25, WEAPON_WEIGHT = 1.5;

    public static boolean isNight(long worldTime) {
        long t = Math.floorMod(worldTime, 24000L);
        return t >= NIGHT_START && t < NIGHT_END;
    }

    /** Does this minute's roll start an ambush? {@code roll} is a uniform 0..1. */
    public static boolean ambush(double roll, double chance) { return roll < chance; }

    /** Material tier of an armour piece or weapon, from its Bukkit material name (e.g. "DIAMOND_SWORD"). 0 for anything else. */
    public static double tier(String material) {
        if (material == null) return 0;
        String m = material.toUpperCase();
        if (m.equals("MACE")) return 4.5;
        if (m.equals("TRIDENT")) return 4;
        if (m.startsWith("NETHERITE_")) return 5;
        if (m.startsWith("DIAMOND_")) return 4;
        if (m.startsWith("IRON_") || m.equals("TURTLE_HELMET")) return 3;
        if (m.startsWith("CHAINMAIL_")) return 2.5;
        if (m.startsWith("COPPER_")) return 2;
        if (m.startsWith("GOLDEN_")) return 1.5;
        if (m.startsWith("LEATHER_")) return 1;
        if (m.startsWith("STONE_")) return 1.5;
        if (m.startsWith("WOODEN_")) return 1;
        return 0;
    }

    /** Is this material something a thief can turn on you: a sword, axe, spear, mace or trident? */
    public static boolean isWeapon(String material) {
        if (material == null) return false;
        String m = material.toUpperCase();
        return m.endsWith("_SWORD") || m.endsWith("_AXE") || m.endsWith("_SPEAR") || m.equals("MACE") || m.equals("TRIDENT");
    }

    /** One player's gear score: four armour material names (nulls for empty), the held weapon (or null) and total enchant levels. */
    public static double gearScore(String[] armour, String weapon, int enchantLevels) {
        double s = 0;
        if (armour != null) for (String a : armour) s += tier(a);
        if (isWeapon(weapon)) s += WEAPON_WEIGHT * tier(weapon);
        return s + PER_ENCHANT_LEVEL * Math.max(0, enchantLevels);
    }

    /**
     * How many thieves come. Solo is always one. A group gets one per player, plus one for every {@code perGear} points of
     * the group's average gear score, never more than {@code max}.
     */
    public static int gangSize(int players, double avgGear, double perGear, int max) {
        if (players <= 1) return 1;
        int extra = perGear <= 0 ? 0 : (int) Math.floor(Math.max(0, avgGear) / perGear);
        return Math.max(1, Math.min(max, players + extra));
    }

    /**
     * How much stronger than the gear they copy the thieves are. Solo is exactly {@code solo} (1.5). A group adds
     * {@code perPlayer} for each extra player and {@code perGear} for each point of average gear score above 10, capped at
     * {@code max}.
     */
    public static double multiplier(int players, double avgGear, double solo, double perPlayer, double perGear, double max) {
        if (players <= 1) return solo;
        double m = solo + perPlayer * (players - 1) + perGear * Math.max(0, avgGear - 10);
        return Math.max(solo, Math.min(max, m));
    }

    /**
     * How many of a gang of {@code n} are mages: one in every {@code every} (3), with the remainder settled by {@code roll}
     * (0..1), so a lone thief is a mage a third of the time and a gang of three always has exactly one.
     */
    public static int mages(int n, int every, double roll) {
        if (n <= 0 || every <= 0) return 0;
        int whole = n / every, rest = n % every;
        return Math.min(n, whole + (roll < rest / (double) every ? 1 : 0));
    }

    /** Coins each player gets for wiping out a gang of {@code thieves}, split between {@code players}, scaled by difficulty. */
    public static double rewardEach(double base, int thieves, double multiplier, int players) {
        if (players <= 0 || thieves <= 0) return 0;
        return Math.round(base * thieves * multiplier / players);
    }

    /** Chance each player wins a key: the base chance, raised by how much harder than solo this gang was, at most triple. */
    public static double keyChance(double base, double multiplier, double solo) {
        double f = solo <= 0 ? 1 : multiplier / solo;
        return Math.min(base * 3, base * f * f);
    }

    // ------------------------------------------------------------------ approach and vanish

    /** Ticks a thief takes to grow in from nothing (or shrink away): no popping in or out. */
    public static final int GROW_TICKS = 6;

    /** Scale a thief appears at {@code tick} ticks after it starts to grow in: eases out, never past 1. */
    public static double growIn(int tick) {
        double u = Math.max(0, Math.min(1, tick / (double) GROW_TICKS));
        double inv = 1 - u;
        return 0.1 + 0.9 * (1 - inv * inv * inv);
    }

    /** Ticks for a fleeing thief's burst of speed to ramp up to full. */
    public static final int FLEE_RAMP = 12;

    /** Extra movement speed (0..{@code boost}) {@code tick} ticks into fleeing: smoothstep, so it builds and settles. */
    public static double fleeBoost(int tick, double boost) {
        double u = Math.max(0, Math.min(1, tick / (double) FLEE_RAMP));
        return boost * u * u * (3 - 2 * u);
    }

    // ------------------------------------------------------------------ the mage's Hex Bolt

    /**
     * The Hex Bolt's timeline from the tick it is cast: {@link #CHARGE} ticks gathering in the mage's hand (a visible
     * telegraph), {@link #LOCK} ticks frozen on where it will strike (the dodge window: it stops tracking), then it crawls
     * off and whips to top speed ({@link #boltSpeed}).
     */
    public static final int CHARGE = 16, LOCK = 4, FIRE_DELAY = CHARGE + LOCK, RAMP = 10;
    public static final double TOP_SPEED = 1.6, MAX_RANGE = 32, HIT_RADIUS = 0.9;

    /** How gathered the bolt is (0..1) {@code tick} ticks into the charge: slow, then quick. */
    public static double charge(int tick) {
        double u = Math.max(0, Math.min(1, tick / (double) CHARGE));
        return u * u * u;
    }

    /** Blocks per tick {@code ticksFlying} ticks after the lock ends: a crawl, then cubic to {@link #TOP_SPEED}. */
    public static double boltSpeed(int ticksFlying) {
        double u = Math.max(0, Math.min(1, ticksFlying / (double) RAMP));
        return Math.min(TOP_SPEED, 0.06 + TOP_SPEED * u * u * u);
    }

    /** Ticks the bolt needs to cover {@code dist} blocks. */
    public static double travelTicks(double dist) {
        double left = Math.max(0, dist);
        for (int i = 0; i < 400; i++) {
            double v = boltSpeed(i);
            if (left <= v) return i + left / v;
            left -= v;
        }
        return 400 + left / TOP_SPEED;
    }

    /**
     * Where to aim at a target moving at (vx, vz) blocks a tick, {@code dist} away: lead it by the bolt's real flight time
     * (plus the lock, during which the bolt does not move), {@code lead} of the way (0 = where it stands). Returns {dx, dz}.
     */
    public static double[] lead(double vx, double vz, double dist, double lead) {
        double t = (travelTicks(dist) + LOCK) * Math.max(0, Math.min(1, lead));
        return new double[]{vx * t, vz * t};
    }
}
