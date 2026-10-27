package net.alloymc.core.arcade.core;

import java.util.*;

/** Splits a list of players into teams as evenly as possible (sizes never differ by more than one). */
public final class TeamBalancer {
    private TeamBalancer() {}

    /**
     * @param players  in queue order (parties/friends queued together stay next to each other)
     * @param teams    number of teams available
     * @param teamSize maximum per team
     * @return one list per team actually used (empty teams dropped from the end)
     */
    public static <T> List<List<T>> split(List<T> players, int teams, int teamSize) {
        if (teams <= 0) throw new IllegalArgumentException("teams must be > 0");
        if (players.size() > teams * teamSize) throw new IllegalArgumentException(players.size() + " players don't fit in " + teams + "x" + teamSize);
        // use as many teams as needed so that every team gets at least one player and sizes are even
        int used = Math.min(teams, Math.max(2, (int) Math.ceil(players.size() / (double) teamSize)));
        used = Math.min(used, Math.max(1, players.size()));
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < used; i++) out.add(new ArrayList<>());
        for (int i = 0; i < players.size(); i++) out.get(i % used).add(players.get(i));
        return out;
    }
}
