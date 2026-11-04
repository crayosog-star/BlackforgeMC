package net.blackforge.core.survival;

/**
 * Everything the job code reads about how an Agent works. No Bukkit types here so it can be unit-tested without a server.
 * With an empty Academy record every value is the Agent's plain Tier value; perks, specializations and traits add to this
 * in later phases.
 */
record AgentStats(int intervalSeconds, double speedMult, int radius, double damage, int hits, int harvests, int fortune,
                  double fuelMult, double chargeMult, double doubleDropChance, double treasureMult) {
    static final int[] INTERVAL = {8, 6, 4, 3, 2};          // seconds between jobs, per tier
    static final int[] RADIUS = {2, 3, 4, 5, 6};
    static final double[] DAMAGE = {8, 12, 16, 22, 30};
    /** Below this tier, Miners have no Fortune. */
    private static final int FORTUNE_FROM_TIER = 4;

    /** A speed of zero would stop an Agent for good while it still burned fuel, and an interval under 1 would run every tick. */
    AgentStats {
        intervalSeconds = Math.max(1, intervalSeconds);
        if (!(speedMult > 0)) speedMult = 1;
    }

    /** The stats for a job name as saved in the record ("miner"), at tier 1-5. */
    static AgentStats of(String job, int tier, AgentRecord record) {
        int i = tier - 1;
        int interval = "fisherman".equals(job) ? (int) (RobotRules.fishIntervalMs(tier) / 1000) : INTERVAL[i];
        return new AgentStats(interval, 1, RADIUS[i], DAMAGE[i], RobotRules.grindHitsPerSwing(tier), tier,
                tier >= FORTUNE_FROM_TIER ? tier - 2 : 0, 1, 1, 0, 1);
    }

    /** Fuel taken for this much burn. Rounds up, so the last few milliseconds of fuel always run out. */
    long fuelSpent(long burn) { return Math.min(burn, (long) Math.ceil(burn * fuelMult)); }
}
