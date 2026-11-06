package net.blackforge.core.survival;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AcademyRulesTest {
    private static final AcademyRules RULES = AcademyRules.DEFAULTS;

    private static AgentRecord sample() {
        AgentRecord r = new AgentRecord();
        r.uid = "6f1c-uid";
        r.workDone = 1234;
        r.minutesWorked = 56;
        r.traits.addAll(List.of("swift", "gilded"));
        AgentRecord.JobTrack miner = new AgentRecord.JobTrack();
        miner.points = 2000;
        miner.spec = "prospector";
        miner.perks.addAll(List.of("efficient", "keen"));
        r.jobs.put("miner", miner);
        r.jobs.put("farmer", new AgentRecord.JobTrack());
        return r;
    }

    // ---------------------------------------------------------------- encoding

    @Test
    void roundTripKeepsEveryField() {
        AgentRecord in = sample();
        String text = in.encode();
        assertTrue(text.startsWith("1|"));
        AgentRecord out = AgentRecord.decode(text);
        assertEquals("6f1c-uid", out.uid);
        assertEquals(1234, out.workDone);
        assertEquals(56, out.minutesWorked);
        assertEquals(List.of("swift", "gilded"), out.traits);
        assertEquals(List.of("miner", "farmer"), List.copyOf(out.jobs.keySet()));
        assertEquals(2000, out.jobs.get("miner").points);
        assertEquals("prospector", out.jobs.get("miner").spec);
        assertEquals(List.of("efficient", "keen"), out.jobs.get("miner").perks);
        assertNull(out.jobs.get("farmer").spec);
        assertTrue(out.jobs.get("farmer").perks.isEmpty());
        assertEquals(text, out.encode());
    }

    @Test
    void emptyRecordRoundTrips() {
        AgentRecord out = AgentRecord.decode(new AgentRecord().encode());
        assertEquals("", out.uid);
        assertTrue(out.jobs.isEmpty());
        assertTrue(out.traits.isEmpty());
    }

    @Test
    void separatorsInsideNamesAndUnknownNamesSurvive() {
        AgentRecord in = new AgentRecord();
        in.uid = "a|b:c";
        in.traits.add("future,trait;x");
        AgentRecord.JobTrack t = new AgentRecord.JobTrack();
        t.spec = "new spec";
        t.perks.add("perk:with|marks");
        in.jobs.put("astronaut", t);
        AgentRecord out = AgentRecord.decode(in.encode());
        assertEquals("a|b:c", out.uid);
        assertEquals(List.of("future,trait;x"), out.traits);
        assertEquals("new spec", out.jobs.get("astronaut").spec);
        assertEquals(List.of("perk:with|marks"), out.jobs.get("astronaut").perks);
    }

    @Test
    void corruptInputGivesAnEmptyRecord() {
        String[] corrupt = {null, "", "garbage", "1|", "1|too|few", "2|u|1|1||", "1|u|x|1||", "1|u|1|1||miner:notanumber::",
                "1|u|1|1||miner:1", "1|u|1|1||:1::", "1|%zz|1|1||", "1|u|1|1||miner:1::|extra"};
        for (String text : corrupt) {
            AgentRecord r = AgentRecord.decode(text);
            assertEquals("", r.uid, text);
            assertEquals(0, r.workDone, text);
            assertTrue(r.jobs.isEmpty(), text);
            assertTrue(r.traits.isEmpty(), text);
        }
    }

    @Test
    void negativeNumbersAreReadAsZero() {
        AgentRecord r = AgentRecord.decode("1|u|-5|-6||miner:-7::");
        assertEquals(0, r.workDone);
        assertEquals(0, r.minutesWorked);
        assertEquals(0, r.jobs.get("miner").points);
    }

    // ---------------------------------------------------------------- levels

    @Test
    void levelBoundaries() {
        assertEquals(0, RULES.pointsFor(0));
        assertEquals(2000, RULES.pointsFor(10));
        assertEquals(18000, RULES.pointsFor(30));
        assertEquals(0, RULES.levelFor(0));
        assertEquals(0, RULES.levelFor(RULES.pointsFor(1) - 1));
        assertEquals(1, RULES.levelFor(RULES.pointsFor(1)));
        assertEquals(9, RULES.levelFor(RULES.pointsFor(10) - 1));
        assertEquals(10, RULES.levelFor(RULES.pointsFor(10)));
        assertEquals(29, RULES.levelFor(RULES.pointsFor(30) - 1));
        assertEquals(30, RULES.levelFor(RULES.pointsFor(30)));
    }

    @Test
    void levelsStayInRange() {
        assertEquals(0, RULES.levelFor(-1));
        assertEquals(30, RULES.levelFor(Long.MAX_VALUE));
        assertEquals(0, RULES.pointsFor(-3));
        assertEquals(RULES.pointsFor(30), RULES.pointsFor(31));
    }

    @Test
    void everyLevelRoundTrips() {
        for (int level = 0; level <= 30; level++) assertEquals(level, RULES.levelFor(RULES.pointsFor(level)));
    }

    @Test
    void unlocks() {
        assertEquals(0, AcademyRules.perkPicksAt(4));
        assertEquals(1, AcademyRules.perkPicksAt(5));
        assertEquals(1, AcademyRules.perkPicksAt(14));
        assertEquals(2, AcademyRules.perkPicksAt(15));
        assertEquals(3, AcademyRules.perkPicksAt(25));
        assertEquals(3, AcademyRules.perkPicksAt(31));
        assertFalse(AcademyRules.specUnlocked(9));
        assertTrue(AcademyRules.specUnlocked(10));
        assertFalse(AcademyRules.capstoneUnlocked(29));
        assertTrue(AcademyRules.capstoneUnlocked(30));
    }

    // ---------------------------------------------------------------- daily cap

    @Test
    void gainBelowCapIsFullAndAboveCapIsReduced() {
        assertEquals(100, AcademyRules.dailyGain(0, 100, 1500, 0.25));
        assertEquals(1500, AcademyRules.dailyGain(0, 1500, 1500, 0.25));
        assertEquals(25, AcademyRules.dailyGain(1500, 100, 1500, 0.25));
    }

    @Test
    void gainCrossingTheCapSplits() {
        assertEquals(100 + 25, AcademyRules.dailyGain(1400, 200, 1500, 0.25));
    }

    @Test
    void twoSmallGainsEqualOneLargeOne() {
        for (long before : new long[]{0, 1000, 1499, 1500, 1503}) {
            for (long first = 0; first <= 40; first++) {
                long split = AcademyRules.dailyGain(before, first, 1500, 0.25)
                        + AcademyRules.dailyGain(before + first, 40 - first, 1500, 0.25);
                assertEquals(AcademyRules.dailyGain(before, 40, 1500, 0.25), split);
            }
        }
    }

    @Test
    void singlePointsOverTheCapAreNotLostToRounding() {
        long total = 0;
        for (int i = 0; i < 100; i++) total += AcademyRules.dailyGain(1500 + i, 1, 1500, 0.25);
        assertEquals(25, total);
    }

    @Test
    void badInputsAreSafe() {
        assertEquals(0, AcademyRules.dailyGain(0, -5, 1500, 0.25));
        assertEquals(10, AcademyRules.dailyGain(-5, 10, 1500, 0.25));
        assertEquals(0, AcademyRules.dailyGain(1500, 10, 1500, 0));
        assertEquals(10, AcademyRules.dailyGain(1500, 10, 1500, 5));
        assertEquals(5, AcademyRules.dailyGain(0, 10, -1, 0.5));
    }
}
