package net.blackforge.core.survival;

import java.util.List;

/**
 * Survival 2 experiments: every rule and number that doesn't need Bukkit, so the build can test it.
 * Ash Urns, Cinder Draw, Living Prices, Ember Bonds, Ashfall Nights, Forge Rifts and Hearth Contracts.
 */
public final class LabMath {
    private LabMath() {}

    static double round2(double v) { return Math.round(v * 100) / 100.0; }

    // ------------------------------------------------------------------ Ash Urns

    /** Who may open an urn: its owner always, anyone once the lock has run out. */
    static boolean urnOpenFor(boolean owner, long diedAt, long now, long lockMillis) {
        return owner || now - diedAt >= lockMillis;
    }

    /** Seconds left on an urn's owner lock (0 once it's public). */
    static long urnLockLeft(long diedAt, long now, long lockMillis) {
        return Math.max(0, (diedAt + lockMillis - now + 999) / 1000);
    }

    /** An urn crumbles (dropping what's left) after this. */
    static boolean urnExpired(long diedAt, long now, long lifeMillis) {
        return now - diedAt >= lifeMillis;
    }

    /** "4:05" for the hologram countdown. */
    static String clock(long seconds) {
        seconds = Math.max(0, seconds);
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }

    // ------------------------------------------------------------------ Cinder Draw

    /** The prize: every ticket's price minus the house cut (money the draw takes out of the economy). */
    static double drawPot(long tickets, double ticketPrice, double houseCut) {
        double cut = Math.max(0, Math.min(1, houseCut));
        return round2(tickets * ticketPrice * (1 - cut));
    }

    /**
     * Picks the winning index by tickets held: {@code roll} in [0, 1). Each player's chance is their share of tickets.
     * Returns -1 when nobody bought one.
     */
    static int drawWinner(List<Integer> tickets, double roll) {
        long total = 0;
        for (int t : tickets) total += Math.max(0, t);
        if (total <= 0) return -1;
        double target = Math.max(0, Math.min(0.999999999, roll)) * total;
        long run = 0;
        for (int i = 0; i < tickets.size(); i++) {
            run += Math.max(0, tickets.get(i));
            if (target < run) return i;
        }
        return tickets.size() - 1;
    }

    // ------------------------------------------------------------------ Living Prices

    /** Recent server-wide sales of an item fade with this half-life, so a price recovers when people stop selling it. */
    static double decay(double sold, double hours, double halfLifeHours) {
        if (halfLifeHours <= 0) return 0;
        return sold * Math.pow(0.5, Math.max(0, hours) / halfLifeHours);
    }

    /** Price multiplier: 1 with nothing sold, halving at {@code baseline} recent sales, never below {@code floor}. */
    static double priceFactor(double recentSold, double baseline, double floor) {
        if (baseline <= 0) return 1;
        double f = baseline / (baseline + Math.max(0, recentSold));
        return Math.max(floor, Math.min(1, f));
    }

    // ------------------------------------------------------------------ Ember Bonds

    /** What a bond pays back when it matures (the money put in plus its interest). */
    static double bondPayout(double principal, double ratePercent) {
        return round2(principal * (1 + ratePercent / 100.0));
    }

    static boolean bondMatured(long start, long now, int days) {
        return now - start >= days * 86_400_000L;
    }

    /** Can this player lock {@code amount} more when they already hold {@code locked} in bonds? */
    static boolean bondFits(double locked, double amount, double cap, double min) {
        return amount >= min && locked + amount <= cap + 1e-9;
    }

    // ------------------------------------------------------------------ Ashfall Nights

    /** Ashfall falls on every {@code every}-th night, counting from the world's first day. */
    static boolean ashfallNight(long day, int every) {
        return every > 0 && day > 0 && day % every == 0;
    }

    /** The night before, players are warned. */
    static boolean ashfallTomorrow(long day, int every) {
        return ashfallNight(day + 1, every);
    }

    /** Minecraft night: 13000 to 23000 ticks into the day. */
    static boolean isNight(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return t >= 13000 && t < 23000;
    }

    // ------------------------------------------------------------------ Forge Rifts

    /** Mobs in each wave (1-based): more each wave, a few extra per player who joins in. */
    static int riftWave(int wave, int players) {
        return 3 + wave * 2 + Math.max(0, players - 1);
    }

    /** Rift scale while it opens and closes, eased so it never pops: 0 to 1 over {@code ramp} ticks. */
    static double riftScale(int tick, int ramp) {
        if (ramp <= 0) return 1;
        double t = Math.max(0, Math.min(1, tick / (double) ramp));
        return 1 - Math.pow(1 - t, 3);   // ease-out cubic
    }

    // ------------------------------------------------------------------ Hearth Contracts

    /** Money held from the buyer while an order is open. */
    static double contractEscrow(int amount, double each) {
        return round2(amount * each);
    }

    /** Posting fee: a share of the order with a minimum, so posting and cancelling over and over costs something. */
    static double contractFee(double escrow, double percent, double min) {
        return round2(Math.max(min, escrow * percent / 100.0));
    }

    /** How many a seller can hand in: what's still wanted, what they carry, whichever is smaller. */
    static int contractFill(int wanted, int delivered, int carried) {
        return Math.max(0, Math.min(wanted - delivered, carried));
    }
}
