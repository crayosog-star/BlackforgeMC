package net.blackforge.core.survival;

/**
 * Pure Academy rules. No Bukkit types here so it can be unit-tested without a server. An instance holds the level curve
 * from config (academy.level-base, level-exponent, max-level); rules that don't depend on the curve are static.
 */
final class AcademyRules {
    /** Matches the defaults in config.yml. */
    static final AcademyRules DEFAULTS = new AcademyRules(20, 2.0, 30);

    private static final int SPEC_LEVEL = 10;
    private static final int CAPSTONE_LEVEL = 30;
    private static final int[] PERK_LEVELS = {5, 15, 25};

    private final double levelBase;
    private final double levelExponent;
    private final int maxLevel;

    AcademyRules(double levelBase, double levelExponent, int maxLevel) {
        this.levelBase = levelBase;
        this.levelExponent = levelExponent;
        this.maxLevel = Math.max(0, maxLevel);
    }

    /** Total points needed to reach a level: base x level^exponent. Levels are limited to 0..max. */
    long pointsFor(int level) {
        int l = Math.clamp(level, 0, maxLevel);
        return l == 0 ? 0 : Math.round(levelBase * Math.pow(l, levelExponent));
    }

    /** The level these total points have reached, 0..max. Negative points count as 0. */
    int levelFor(long points) {
        int level = maxLevel;
        while (level > 0 && pointsFor(level) > points) level--;
        return level;
    }

    boolean isMaxLevel(int level) { return level >= maxLevel; }

    /** How far these points are from this level to the next, 0..1. At max level it's a full bar. */
    double progressToNext(long points) {
        int level = levelFor(points);
        if (isMaxLevel(level)) return 1;
        long from = pointsFor(level), to = pointsFor(level + 1);
        return (double) (Math.max(0, points) - from) / (to - from);
    }

    /** Apprentice (0-9), Journeyman (10-19), Artisan (20-29), Master (30 and up). */
    static String rankFor(int level) {
        return level >= 30 ? "Master" : level >= 20 ? "Artisan" : level >= 10 ? "Journeyman" : "Apprentice";
    }

    /** How many perks a job at this level may have picked. */
    static int perkPicksAt(int level) {
        int picks = 0;
        for (int at : PERK_LEVELS) if (level >= at) picks++;
        return picks;
    }

    static boolean specUnlocked(int level) { return level >= SPEC_LEVEL; }

    static boolean capstoneUnlocked(int level) { return level >= CAPSTONE_LEVEL; }

    /**
     * Points actually awarded for a gain. Full value up to softCap per day, then overCapRate of it. todaySoFar is the
     * day's running total of raw gains (before this rate), not of awarded points. Each award is the difference between
     * two totals, so small gains add up exactly like one large one and a fraction is never lost over the cap.
     */
    static long dailyGain(long todaySoFar, long gain, long softCap, double overCapRate) {
        long before = Math.max(0, todaySoFar);
        long raw = Math.max(0, gain);
        return awardedTotal(before + raw, softCap, overCapRate) - awardedTotal(before, softCap, overCapRate);
    }

    /** Points one cycle of work is worth, before the daily limit. May be a fraction; the caller carries it over. */
    static double workPoints(int done, int pointsPerWork, double coachMult) {
        return Math.max(0, done) * (double) Math.max(0, pointsPerWork) * Math.max(0, coachMult);
    }

    /**
     * Adds a gain to one job for a UTC date and returns the points awarded. A new date restarts the daily count. Points
     * stop at the max level's total, so a limit raised later doesn't hand out banked levels.
     */
    long earn(AgentRecord.JobTrack track, long gain, String date, long softCap, double overCapRate) {
        if (!date.equals(track.todayDate)) {
            track.todayDate = date;
            track.todayPoints = 0;
        }
        long raw = Math.max(0, gain);
        long awarded = dailyGain(track.todayPoints, raw, softCap, overCapRate);
        track.todayPoints += raw;
        long before = track.points;
        track.points = Math.max(before, Math.min(before + awarded, pointsFor(maxLevel)));
        return track.points - before;
    }

    private static long awardedTotal(long rawTotal, long softCap, double overCapRate) {
        long cap = Math.max(0, softCap);
        if (rawTotal <= cap) return rawTotal;
        return cap + (long) Math.floor((rawTotal - cap) * Math.clamp(overCapRate, 0.0, 1.0));
    }
}
