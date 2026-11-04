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
        assertTrue(text.startsWith("3|"));
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
        String[] corrupt = {null, "", "garbage", "1|", "1|too|few", "9|u|1|1||", "1|u|x|1||", "1|u|1|1||miner:notanumber::",
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

    @Test
    void dailyFieldsRoundTrip() {
        AgentRecord in = sample();
        in.jobs.get("miner").todayPoints = 321;
        in.jobs.get("miner").todayDate = "2026-11-04";
        AgentRecord out = AgentRecord.decode(in.encode());
        assertEquals(321, out.jobs.get("miner").todayPoints);
        assertEquals("2026-11-04", out.jobs.get("miner").todayDate);
        assertEquals(0, out.jobs.get("farmer").todayPoints);
        assertNull(out.jobs.get("farmer").todayDate);
    }

    @Test
    void oldFormatStillReads() {
        AgentRecord r = AgentRecord.decode("1|u%2F1|7|8|swift|miner:2000:prospector:efficient,keen;farmer:0::");
        assertEquals("u/1", r.uid);
        assertEquals(7, r.workDone);
        assertEquals(List.of("swift"), r.traits);
        assertEquals(2000, r.jobs.get("miner").points);
        assertEquals("prospector", r.jobs.get("miner").spec);
        assertEquals(List.of("efficient", "keen"), r.jobs.get("miner").perks);
        assertEquals(0, r.jobs.get("miner").todayPoints);
        assertNull(r.jobs.get("miner").todayDate);
        assertTrue(r.encode().startsWith("3|"));
    }

    @Test
    void wrongFieldCountForItsVersionIsCorrupt() {
        assertEquals("", AgentRecord.decode("1|u|0|0||miner:1::x:y").uid);
        assertEquals("", AgentRecord.decode("2|u|0|0||miner:1::").uid);
        assertEquals("", AgentRecord.decode("3|u|0|0||miner:1:::0:").uid);
    }

    @Test
    void respecCountRoundTripsAndOlderVersionsReadAsZero() {
        AgentRecord in = sample();
        in.jobs.get("miner").respecs = 3;
        assertEquals(3, AgentRecord.decode(in.encode()).jobs.get("miner").respecs);
        AgentRecord two = AgentRecord.decode("2|u|0|0||miner:5:::9:2026-11-04");
        assertEquals(0, two.jobs.get("miner").respecs);
        assertEquals(9, two.jobs.get("miner").todayPoints);
    }

    @Test
    void pricesEscalateThenStopAtTheLimit() {
        assertEquals(new AcademyRules.Price(25000, 128), AcademyRules.escalatingPrice(0, 25000, 128, 1.5, 500000));
        assertEquals(new AcademyRules.Price(37500, 192), AcademyRules.escalatingPrice(1, 25000, 128, 1.5, 500000));
        assertEquals(new AcademyRules.Price(500000, 2560), AcademyRules.escalatingPrice(50, 25000, 128, 1.5, 500000));
        assertEquals(new AcademyRules.Price(25000, 128), AcademyRules.escalatingPrice(-1, 25000, 128, 1.5, 500000));
    }

    // ---------------------------------------------------------------- earning

    private static final String DAY1 = "2026-11-04", DAY2 = "2026-11-05";

    @Test
    void workPointsUseTheJobRateAndCoachBonus() {
        assertEquals(1.0, AcademyRules.workPoints(1, 1, 1));
        assertEquals(1.25, AcademyRules.workPoints(1, 1, 1.25));
        assertEquals(12.0, AcademyRules.workPoints(1, 12, 1));
        assertEquals(0.0, AcademyRules.workPoints(0, 2, 1.25));
        assertEquals(0.0, AcademyRules.workPoints(-3, 2, 1));
    }

    @Test
    void earningStopsBeingFullValueAtTheDailyCap() {
        AgentRecord.JobTrack t = new AgentRecord.JobTrack();
        assertEquals(1500, RULES.earn(t, 1500, DAY1, 1500, 0.25));
        assertEquals(25, RULES.earn(t, 100, DAY1, 1500, 0.25));
        assertEquals(1525, t.points);
        assertEquals(1600, t.todayPoints);
    }

    @Test
    void aNewDateRestartsTheDailyCount() {
        AgentRecord.JobTrack t = new AgentRecord.JobTrack();
        RULES.earn(t, 2000, DAY1, 1500, 0.25);
        assertEquals(100, RULES.earn(t, 100, DAY2, 1500, 0.25));
        assertEquals(DAY2, t.todayDate);
        assertEquals(100, t.todayPoints);
    }

    @Test
    void pointsStopAtTheMaxLevel() {
        AgentRecord.JobTrack t = new AgentRecord.JobTrack();
        t.points = RULES.pointsFor(30) - 5;
        assertEquals(5, RULES.earn(t, 100, DAY1, 1500, 0.25));
        assertEquals(RULES.pointsFor(30), t.points);
        assertEquals(0, RULES.earn(t, 100, DAY1, 1500, 0.25));
    }

    // ---------------------------------------------------------------- menu values

    @Test
    void rankBoundaries() {
        assertEquals("Apprentice", AcademyRules.rankFor(0));
        assertEquals("Apprentice", AcademyRules.rankFor(9));
        assertEquals("Journeyman", AcademyRules.rankFor(10));
        assertEquals("Journeyman", AcademyRules.rankFor(19));
        assertEquals("Artisan", AcademyRules.rankFor(20));
        assertEquals("Artisan", AcademyRules.rankFor(29));
        assertEquals("Master", AcademyRules.rankFor(30));
    }

    @Test
    void progressRunsFromZeroToOneWithinALevel() {
        assertEquals(0.0, RULES.progressToNext(0));
        assertEquals(0.5, RULES.progressToNext(RULES.pointsFor(1) / 2));
        assertEquals(0.0, RULES.progressToNext(RULES.pointsFor(10)));
        assertEquals(1.0, RULES.progressToNext(RULES.pointsFor(30)));
        assertEquals(1.0, RULES.progressToNext(Long.MAX_VALUE));
        assertFalse(RULES.isMaxLevel(29));
        assertTrue(RULES.isMaxLevel(30));
    }

    @Test
    void graduationsAreFoundEvenWhenLevelsAreSkipped() {
        assertFalse(AcademyRules.graduated(8, 9));
        assertTrue(AcademyRules.graduated(9, 10));
        assertFalse(AcademyRules.graduated(10, 11));
        assertTrue(AcademyRules.graduated(5, 15));
        assertTrue(AcademyRules.graduated(29, 30));
        assertTrue(AcademyRules.graduated(0, 30));
        assertFalse(AcademyRules.graduated(30, 30));
    }

    @Test
    void untrainedAgentsHaveNoServiceRecord() {
        assertTrue(RULES.serviceRecord(new AgentRecord()).isEmpty());
        AgentRecord r = new AgentRecord();
        r.jobs.put("miner", new AgentRecord.JobTrack());
        assertTrue(RULES.serviceRecord(r).isEmpty());
    }

    @Test
    void serviceRecordShowsWhatWasEarned() {
        List<String> lines = RULES.serviceRecord(sample());
        String all = String.join("\n", lines);
        assertTrue(all.contains("Service Record"));
        assertTrue(all.contains("Rank: <white>Journeyman"));
        assertTrue(all.contains("Miner: <white>Level 10 <gray>(prospector)"));
        assertTrue(all.contains("efficient, keen"));
        assertTrue(all.contains("swift, gilded"));
        assertTrue(all.contains("Work done: <white>1234"));
        assertFalse(all.contains("Farmer"));
    }

    // ---------------------------------------------------------------- perk picks

    @Test
    void perkPicksAreLimitedByLevel() {
        AgentRecord.JobTrack t = new AgentRecord.JobTrack();
        assertFalse(AcademyRules.canPickPerk(t, "quick", "miner", 4));
        assertTrue(AcademyRules.canPickPerk(t, "quick", "miner", 5));
        t.perks.add("quick");
        assertFalse(AcademyRules.canPickPerk(t, "keen", "miner", 14));
        assertTrue(AcademyRules.canPickPerk(t, "keen", "miner", 15));
    }

    @Test
    void theSamePerkCantBePickedTwice() {
        AgentRecord.JobTrack t = new AgentRecord.JobTrack();
        t.perks.add("quick");
        assertFalse(AcademyRules.canPickPerk(t, "quick", "miner", 30));
    }

    @Test
    void perksMustExistAndFitTheJob() {
        AgentRecord.JobTrack t = new AgentRecord.JobTrack();
        assertFalse(AcademyRules.canPickPerk(t, "reach", "miner", 30));
        assertTrue(AcademyRules.canPickPerk(t, "reach", "farmer", 30));
        assertFalse(AcademyRules.canPickPerk(t, "made-up", "miner", 30));
    }

    @Test
    void specsArePickedOnceFromTheJobsPathsAtLevelTen() {
        AgentRecord.JobTrack t = new AgentRecord.JobTrack();
        assertFalse(AcademyRules.canPickSpec(t, "prospector", "miner", 9));
        assertTrue(AcademyRules.canPickSpec(t, "prospector", "miner", 10));
        assertFalse(AcademyRules.canPickSpec(t, "reaper", "miner", 10));
        t.spec = "excavator";
        assertFalse(AcademyRules.canPickSpec(t, "prospector", "miner", 30));
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
