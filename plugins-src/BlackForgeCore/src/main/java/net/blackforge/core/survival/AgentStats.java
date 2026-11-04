package net.blackforge.core.survival;

import java.util.function.ToDoubleBiFunction;

/**
 * Everything the job code reads about how an Agent works. No Bukkit types here so it can be unit-tested without a server.
 * With an empty Academy record every value is the Agent's plain Tier value; perks, specializations and traits add to that.
 */
record AgentStats(int intervalSeconds, double speedMult, int radius, double damage, int hits, int harvests, int fortune,
                  double fuelMult, double chargeMult, double doubleDropChance, double treasureMult,
                  int mineHeight, int growCrops, double cycleDoubleChance, double harvestDoubleChance, double copyDropChance) {
    static final int[] INTERVAL = {8, 6, 4, 3, 2};          // seconds between jobs, per tier
    static final int[] RADIUS = {2, 3, 4, 5, 6};
    static final double[] DAMAGE = {8, 12, 16, 22, 30};
    /** Below this tier, Miners have no Fortune. */
    private static final int FORTUNE_FROM_TIER = 4;
    private static final AgentRecord EMPTY = new AgentRecord();

    /** The academy.perks and academy.specs values from config. */
    record Tuning(double efficientFuel, double quickSpeed, int reachRadius, double keenDouble, double steadyCharge,
                  int prospectorFortune, double motherlodeChance, int excavatorHeight, int clearcutHeight, int harvesterExtra, double bumperChance,
                  int botanistCrops, int verdantCrops, double reaperDamage, int reaperHits, int cleaveHits, double butcherChance, double primeChance,
                  double anglerSpeed, double quicklineSpeed, double diverMult, double troveMult) {
        static final Tuning DEFAULTS = read((key, fallback) -> fallback);

        /** get receives a key under academy, and the default to use if it's missing. */
        static Tuning read(ToDoubleBiFunction<String, Double> get) {
            return new Tuning(get.applyAsDouble("perks.efficient-fuel-mult", 0.85), get.applyAsDouble("perks.quick-speed-mult", 1.10),
                    (int) get.applyAsDouble("perks.reach-radius", 1.0), get.applyAsDouble("perks.keen-double-chance", 0.05),
                    get.applyAsDouble("perks.steady-charge-mult", 1.15),
                    (int) get.applyAsDouble("specs.prospector-fortune", 1.0), get.applyAsDouble("specs.motherlode-chance", 0.02),
                    (int) get.applyAsDouble("specs.excavator-height", 1.0), (int) get.applyAsDouble("specs.clearcut-height", 2.0),
                    (int) get.applyAsDouble("specs.harvester-extra", 2.0), get.applyAsDouble("specs.bumper-chance", 0.05),
                    (int) get.applyAsDouble("specs.botanist-crops", 1.0), (int) get.applyAsDouble("specs.verdant-crops", 3.0),
                    get.applyAsDouble("specs.reaper-damage-mult", 1.25), (int) get.applyAsDouble("specs.reaper-hits", 1.0), (int) get.applyAsDouble("specs.cleave-hits", 2.0),
                    get.applyAsDouble("specs.butcher-chance", 0.20), get.applyAsDouble("specs.prime-chance", 0.35),
                    get.applyAsDouble("specs.angler-speed-mult", 1.20), get.applyAsDouble("specs.quickline-speed-mult", 1.35),
                    get.applyAsDouble("specs.diver-mult", 1.5), get.applyAsDouble("specs.trove-mult", 2.0));
        }
    }

    /** A speed of zero would stop an Agent for good while it still burned fuel, and an interval under 1 would run every tick. */
    AgentStats {
        intervalSeconds = Math.max(1, intervalSeconds);
        if (!(speedMult > 0)) speedMult = 1;
    }

    /** Plain Tier stats, with no Academy bonuses. */
    static AgentStats of(String job, int tier) { return of(job, tier, EMPTY, AcademyRules.DEFAULTS, Tuning.DEFAULTS); }

    /** The stats for a job name as saved in the record ("miner"), at tier 1-5. */
    static AgentStats of(String job, int tier, AgentRecord record, AcademyRules rules, Tuning tune) {
        int i = tier - 1;
        int interval = "fisherman".equals(job) ? (int) (RobotRules.fishIntervalMs(tier) / 1000) : INTERVAL[i];
        double speed = 1, damage = DAMAGE[i], fuel = 1, charge = 1, doubleDrop = 0, treasure = 1;
        int radius = RADIUS[i], hits = RobotRules.grindHitsPerSwing(tier), harvests = tier, fortune = tier >= FORTUNE_FROM_TIER ? tier - 2 : 0;
        int mineHeight = 0, grow = 0;
        double cycleDouble = 0, harvestDouble = 0, copyDrop = 0;

        AgentRecord.JobTrack track = record.jobs.get(job);
        if (track != null) {
            for (String perk : track.perks) {
                switch (perk) {
                    case AcademyRules.EFFICIENT -> fuel *= tune.efficientFuel();
                    case AcademyRules.QUICK -> speed *= tune.quickSpeed();
                    case AcademyRules.REACH -> { if (AcademyRules.perkFits(perk, job)) radius += tune.reachRadius(); }
                    case AcademyRules.KEEN -> doubleDrop += tune.keenDouble();
                    case AcademyRules.STEADY -> charge *= tune.steadyCharge();
                    default -> { }   // a removed perk stays saved but does nothing
                }
            }
            int level = rules.levelFor(track.points);
            if (track.spec != null && AcademyRules.specUnlocked(level) && AcademyRules.specFits(track.spec, job)) {
                boolean capstone = AcademyRules.capstoneUnlocked(level);
                // where the capstone gives a higher value, it replaces the level 10 one rather than stacking
                switch (track.spec) {
                    case AcademyRules.PROSPECTOR -> { fortune += tune.prospectorFortune(); if (capstone) cycleDouble = tune.motherlodeChance(); }
                    case AcademyRules.EXCAVATOR -> mineHeight = capstone ? tune.clearcutHeight() : tune.excavatorHeight();
                    case AcademyRules.HARVESTER -> { harvests += tune.harvesterExtra(); if (capstone) harvestDouble = tune.bumperChance(); }
                    case AcademyRules.BOTANIST -> grow = capstone ? tune.verdantCrops() : tune.botanistCrops();
                    case AcademyRules.REAPER -> { damage *= tune.reaperDamage(); hits += tune.reaperHits() + (capstone ? tune.cleaveHits() : 0); }
                    case AcademyRules.BUTCHER -> copyDrop = capstone ? tune.primeChance() : tune.butcherChance();
                    case AcademyRules.ANGLER -> speed *= capstone ? tune.quicklineSpeed() : tune.anglerSpeed();
                    case AcademyRules.DIVER -> treasure *= capstone ? tune.troveMult() : tune.diverMult();
                    default -> { }
                }
            }
        }
        return new AgentStats(interval, speed, radius, damage, hits, harvests, fortune, fuel, charge, doubleDrop, treasure,
                mineHeight, grow, cycleDouble, harvestDouble, copyDrop);
    }

    /** Fuel taken for this much burn. Rounds up, so the last few milliseconds of fuel always run out. */
    long fuelSpent(long burn) { return Math.min(burn, (long) Math.ceil(burn * fuelMult)); }
}
