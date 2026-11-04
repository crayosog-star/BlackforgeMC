package net.blackforge.core.survival;

/**
 * Pure Academy rules. No Bukkit types here so it can be unit-tested without a server. An instance holds the level curve
 * from config (academy.level-base, level-exponent, max-level); rules that don't depend on the curve are static.
 */
final class AcademyRules {
    /** Matches the defaults in config.yml. */
    static final AcademyRules DEFAULTS = new AcademyRules(20, 2.0, 30);

    static final String EFFICIENT = "efficient", QUICK = "quick", REACH = "reach", KEEN = "keen", STEADY = "steady";
    static final java.util.List<String> PERKS = java.util.List.of(EFFICIENT, QUICK, REACH, KEEN, STEADY);

    static final String PROSPECTOR = "prospector", EXCAVATOR = "excavator", HARVESTER = "harvester", BOTANIST = "botanist",
            REAPER = "reaper", BUTCHER = "butcher", ANGLER = "angler", DIVER = "diver";

    static final String THRIFTY = "thrifty", SWIFT = "swift", CHARGED = "charged", LUCKY = "lucky", STUDIOUS = "studious",
            PRODIGY = "prodigy", ANCIENT = "ancient", GILDED = "gilded";
    /** Roll weights, which also give each trait's rarity. */
    static final java.util.Map<String, Integer> TRAIT_WEIGHTS = traitWeights();

    private static java.util.Map<String, Integer> traitWeights() {
        java.util.Map<String, Integer> w = new java.util.LinkedHashMap<>();
        w.put(THRIFTY, 35); w.put(SWIFT, 35); w.put(CHARGED, 30);
        w.put(LUCKY, 15); w.put(STUDIOUS, 15);
        w.put(PRODIGY, 4); w.put(ANCIENT, 3); w.put(GILDED, 3);
        return java.util.Collections.unmodifiableMap(w);
    }

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

    /** Whether going between these levels passed a graduation (10 or 30), even if several levels were gained at once. */
    static boolean graduated(int before, int after) {
        return (before < 10 && after >= 10) || (before < 30 && after >= 30);
    }

    /** The Service Record's item lore in MiniMessage text. Empty until the Agent has earned something, so untrained items keep today's lore. */
    java.util.List<String> serviceRecord(AgentRecord r) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        int best = 0;
        for (AgentRecord.JobTrack t : r.jobs.values()) best = Math.max(best, levelFor(t.points));
        if (r.jobs.values().stream().noneMatch(t -> t.points > 0)) return lines;
        lines.add("");
        lines.add("<#C9A227><bold>Service Record");
        lines.add("<gray>Rank: <white>" + rankFor(best));
        r.jobs.forEach((job, t) -> {
            if (t.points <= 0) return;
            lines.add("<gray>" + Character.toUpperCase(job.charAt(0)) + job.substring(1) + ": <white>Level " + levelFor(t.points)
                    + (t.spec != null ? " <gray>(" + t.spec + ")" : ""));
            if (!t.perks.isEmpty()) lines.add("<dark_gray>  Perks: " + String.join(", ", t.perks));
        });
        if (!r.traits.isEmpty()) lines.add("<gray>Traits: <white>" + String.join(", ", r.traits));
        lines.add("<gray>Work done: <white>" + r.workDone);
        return lines;
    }

    record Price(long money, long essence) {}

    /** The price after this many uses: base x mult^uses. Essence stops rising when money reaches its limit, so the two stay in step. */
    static Price escalatingPrice(int uses, long baseMoney, long baseEssence, double mult, long maxMoney) {
        double factor = Math.pow(Math.max(1, mult), Math.max(0, uses));
        if (baseMoney > 0) factor = Math.min(factor, Math.max(1, (double) maxMoney / baseMoney));
        return new Price(Math.round(baseMoney * factor), Math.round(baseEssence * factor));
    }

    /** One weighted trait, never one in exclude. Null if nothing is left to roll. */
    static String rollTrait(java.util.Collection<String> exclude, java.util.Map<String, Integer> weights, java.util.Random random) {
        int total = 0;
        for (var e : weights.entrySet()) if (!exclude.contains(e.getKey())) total += Math.max(0, e.getValue());
        if (total <= 0) return null;
        int roll = random.nextInt(total);
        for (var e : weights.entrySet()) {
            if (exclude.contains(e.getKey())) continue;
            roll -= Math.max(0, e.getValue());
            if (roll < 0) return e.getKey();
        }
        return null;
    }

    /** How many trait slots this record has earned: the first when any job reaches 10, the second at 30. */
    int traitSlots(AgentRecord r) {
        int best = 0;
        for (AgentRecord.JobTrack t : r.jobs.values()) best = Math.max(best, levelFor(t.points));
        return best >= CAPSTONE_LEVEL ? 2 : best >= SPEC_LEVEL ? 1 : 0;
    }

    /** A job's two specialization paths. */
    static java.util.List<String> specsFor(String job) {
        return switch (job) {
            case "miner" -> java.util.List.of(PROSPECTOR, EXCAVATOR);
            case "farmer" -> java.util.List.of(HARVESTER, BOTANIST);
            case "grinder" -> java.util.List.of(REAPER, BUTCHER);
            case "fisherman" -> java.util.List.of(ANGLER, DIVER);
            default -> java.util.List.of();
        };
    }

    static boolean specFits(String spec, String job) { return specsFor(job).contains(spec); }

    /** A job picks one path, once, at level 10. Checked again when the player confirms. */
    static boolean canPickSpec(AgentRecord.JobTrack track, String spec, String job, int level) {
        return track.spec == null && specUnlocked(level) && specFits(spec, job);
    }

    /** Long Reach only helps jobs that work a radius. */
    static boolean perkFits(String perk, String job) {
        return !REACH.equals(perk) || "farmer".equals(job) || "grinder".equals(job);
    }

    /** Whether this job can take this perk now. Checked again when the player confirms, so a double-click can't add two. */
    static boolean canPickPerk(AgentRecord.JobTrack track, String perk, String job, int level) {
        return PERKS.contains(perk) && perkFits(perk, job) && !track.perks.contains(perk) && track.perks.size() < perkPicksAt(level);
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
