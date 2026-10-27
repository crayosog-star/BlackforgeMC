package net.alloymc.core.arcade.core;

import java.util.*;

/** Elo ratings for ranked variants (stored as the "rating_<variant>" stat, starting at 1000). */
public final class Ratings {
    public static final int START = 1000, K = 32;
    private Ratings() {}

    /** Expected score of a against b. */
    public static double expected(double a, double b) { return 1.0 / (1.0 + Math.pow(10, (b - a) / 400.0)); }

    /** New ratings after a game: result 1 = a won, 0 = a lost, 0.5 = draw. */
    public static int[] after(int a, int b, double result) {
        double ea = expected(a, b);
        int na = (int) Math.round(a + K * (result - ea));
        int nb = (int) Math.round(b + K * ((1 - result) - (1 - ea)));
        return new int[]{na, nb};
    }

    static void update(StatsService stats, Match m) {
        String key = "rating_" + m.variant.id();
        List<UUID> win = new ArrayList<>(), lose = new ArrayList<>();
        for (UUID u : m.players()) (m.winners.contains(u) ? win : lose).add(u);
        if (win.isEmpty() || lose.isEmpty()) return;
        double wa = win.stream().mapToLong(u -> rating(stats, u, m.game.id, key)).average().orElse(START);
        double la = lose.stream().mapToLong(u -> rating(stats, u, m.game.id, key)).average().orElse(START);
        int[] n = after((int) wa, (int) la, 1);
        int dw = n[0] - (int) wa, dl = n[1] - (int) la;
        for (UUID u : win) stats.set(u, m.game.id, key, rating(stats, u, m.game.id, key) + dw);
        for (UUID u : lose) stats.set(u, m.game.id, key, Math.max(0, rating(stats, u, m.game.id, key) + dl));
        m.broadcast(MatchManager.TAG + "<gray>Rating: <green>+" + dw + " <gray>winners, <red>" + dl + " <gray>losers");
    }

    public static long rating(StatsService s, UUID u, String game, String key) { return s.has(u, game, key) ? s.get(u, game, key) : START; }
}
