package net.blackforge.core.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentStatsTest {
    private static final String[] JOBS = {"miner", "farmer", "grinder", "fisherman"};
    // today's values, written out so a change to the shared arrays fails here
    private static final int[] WORK_INTERVAL = {8, 6, 4, 3, 2};
    private static final int[] FISH_INTERVAL = {180, 150, 120, 90, 60};
    private static final int[] RADIUS = {2, 3, 4, 5, 6};
    private static final double[] DAMAGE = {8, 12, 16, 22, 30};
    private static final int[] HITS = {2, 3, 4, 5, 6};
    private static final int[] FORTUNE = {0, 0, 0, 2, 3};

    private static AgentStats withSpeed(double speed) { return new AgentStats(8, speed, 2, 8, 2, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0, 0); }

    private static AgentStats withFuel(double fuelMult) { return new AgentStats(8, 1, 2, 8, 2, 1, 0, fuelMult, 1, 0, 1, 0, 0, 0, 0, 0); }

    @Test
    void emptyRecordMatchesTodaysValuesForEveryJobAndTier() {
        for (String job : JOBS) {
            for (int tier = 1; tier <= 5; tier++) {
                int i = tier - 1;
                AgentStats s = AgentStats.of(job, tier);
                String at = job + " tier " + tier;
                assertEquals(job.equals("fisherman") ? FISH_INTERVAL[i] : WORK_INTERVAL[i], s.intervalSeconds(), at);
                assertEquals(RADIUS[i], s.radius(), at);
                assertEquals(DAMAGE[i], s.damage(), at);
                assertEquals(HITS[i], s.hits(), at);
                assertEquals(tier, s.harvests(), at);
                assertEquals(FORTUNE[i], s.fortune(), at);
                assertEquals(1.0, s.speedMult(), at);
                assertEquals(1.0, s.fuelMult(), at);
                assertEquals(1.0, s.chargeMult(), at);
                assertEquals(0.0, s.doubleDropChance(), at);
                assertEquals(1.0, s.treasureMult(), at);
            }
        }
    }

    @Test
    void speedThatWouldStopAnAgentFallsBackToNormal() {
        assertEquals(1.0, withSpeed(0).speedMult());
        assertEquals(1.0, withSpeed(-2).speedMult());
        assertEquals(1.0, withSpeed(Double.NaN).speedMult());
        assertEquals(1.1, withSpeed(1.1).speedMult());
    }

    @Test
    void intervalIsNeverBelowOneSecond() {
        assertEquals(1, new AgentStats(0, 1, 2, 8, 2, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0, 0).intervalSeconds());
    }

    @Test
    void fuelSpentMatchesTodaysBurnWithNoBonus() {
        AgentStats s = AgentStats.of("miner", 1);
        assertEquals(1000, s.fuelSpent(1000));
        assertEquals(250, s.fuelSpent(250));   // the Fisherman's quarter burn
        assertEquals(1, s.fuelSpent(1));
        assertEquals(0, s.fuelSpent(0));
    }

    @Test
    void lastOfTheFuelAlwaysRunsOut() {
        assertEquals(1, withFuel(0.85).fuelSpent(1));
        assertEquals(850, withFuel(0.85).fuelSpent(1000));
    }

    // ---------------------------------------------------------------- perks

    private static final AgentStats.Tuning TUNE = AgentStats.Tuning.DEFAULTS;

    /** A record with this job at this many points, and these perks. */
    private static AgentRecord trained(String job, long points, String... perks) {
        AgentRecord r = new AgentRecord();
        AgentRecord.JobTrack t = new AgentRecord.JobTrack();
        t.points = points;
        t.perks.addAll(java.util.List.of(perks));
        r.jobs.put(job, t);
        return r;
    }

    private static AgentStats stats(String job, AgentRecord r) { return AgentStats.of(job, 1, r, AcademyRules.DEFAULTS, TUNE); }

    @Test
    void eachPerkChangesOnlyItsOwnValue() {
        assertEquals(0.85, stats("miner", trained("miner", 500, "efficient")).fuelMult());
        assertEquals(1.10, stats("miner", trained("miner", 500, "quick")).speedMult());
        assertEquals(0.05, stats("miner", trained("miner", 500, "keen")).doubleDropChance());
        assertEquals(1.15, stats("miner", trained("miner", 500, "steady")).chargeMult());
        assertEquals(3, stats("farmer", trained("farmer", 500, "reach")).radius());
        AgentStats keen = stats("miner", trained("miner", 500, "keen"));
        assertEquals(1.0, keen.fuelMult());
        assertEquals(1.0, keen.speedMult());
    }

    @Test
    void longReachDoesNothingForJobsWithoutARadius() {
        assertEquals(2, stats("miner", trained("miner", 500, "reach")).radius());
        assertEquals(2, stats("fisherman", trained("fisherman", 500, "reach")).radius());
        assertEquals(3, stats("grinder", trained("grinder", 500, "reach")).radius());
    }

    @Test
    void perksBelongToTheirJob() {
        assertEquals(1.0, stats("farmer", trained("miner", 500, "quick")).speedMult());
    }

    @Test
    void unknownPerksAreIgnored() {
        assertEquals(1.0, stats("miner", trained("miner", 500, "retired-perk")).speedMult());
    }

    @Test
    void tuningDefaultsMatchThePlan() {
        assertEquals(0.85, TUNE.efficientFuel());
        assertEquals(1, TUNE.reachRadius());
    }
}
