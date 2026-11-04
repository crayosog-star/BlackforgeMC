package net.blackforge.core.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentStatsTest {
    private static final String[] JOBS = {"miner", "farmer", "grinder", "fisherman"};
    // today's values, written out so a change to the shared arrays fails here
    private static final int[] WORK_INTERVAL = {6, 4, 3, 2, 1};
    private static final int[] FISH_INTERVAL = {120, 100, 80, 60, 40};
    private static final int[] RADIUS = {3, 4, 5, 6, 7};
    private static final double[] DAMAGE = {10, 15, 20, 28, 40};
    private static final int[] HITS = {2, 3, 4, 5, 6};
    private static final int[] FORTUNE = {0, 0, 1, 2, 3};

    private static AgentStats withSpeed(double speed) { return new AgentStats(8, speed, 2, 8, 2, 1, 0, 1, 1, 0, 1); }

    private static AgentStats withFuel(double fuelMult) { return new AgentStats(8, 1, 2, 8, 2, 1, 0, fuelMult, 1, 0, 1); }

    @Test
    void emptyRecordMatchesTodaysValuesForEveryJobAndTier() {
        for (String job : JOBS) {
            for (int tier = 1; tier <= 5; tier++) {
                int i = tier - 1;
                AgentStats s = AgentStats.of(job, tier, new AgentRecord());
                String at = job + " tier " + tier;
                assertEquals(job.equals("fisherman") ? FISH_INTERVAL[i] : WORK_INTERVAL[i], s.intervalSeconds(), at);
                assertEquals(RADIUS[i], s.radius(), at);
                assertEquals(DAMAGE[i], s.damage(), at);
                assertEquals(HITS[i], s.hits(), at);
                assertEquals(tier * 2, s.harvests(), at);
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
        assertEquals(1, new AgentStats(0, 1, 2, 8, 2, 1, 0, 1, 1, 0, 1).intervalSeconds());
    }

    @Test
    void fuelSpentMatchesTodaysBurnWithNoBonus() {
        AgentStats s = AgentStats.of("miner", 1, new AgentRecord());
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
}
