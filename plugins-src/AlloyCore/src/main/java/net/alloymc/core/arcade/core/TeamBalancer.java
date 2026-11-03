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

    /**
     * Splits groups of players who want to play together (a party, the players named in /arcade test) into teams: a
     * group shares a team whenever it fits, and only one bigger than a team spills over. Singles even the teams out.
     *
     * @param groups       in order; a player on their own is a group of one
     * @param teams        number of teams available
     * @param teamSize     maximum per team
     * @param allowOneTeam may one group share a single team with no opponents? Only for matches staff started on
     *                     purpose: a public match always gets two sides, even if that means splitting a party.
     * @return one list per team actually used
     */
    public static <T> List<List<T>> together(List<List<T>> groups, int teams, int teamSize, boolean allowOneTeam) {
        if (teams <= 0) throw new IllegalArgumentException("teams must be > 0");
        int total = groups.stream().mapToInt(List::size).sum();
        if (total > teams * teamSize) throw new IllegalArgumentException(total + " players don't fit in " + teams + "x" + teamSize);
        int least = allowOneTeam && groups.size() == 1 ? 1 : 2;
        int used = Math.min(teams, Math.max(least, (int) Math.ceil(total / (double) teamSize)));
        used = Math.min(used, Math.max(1, total));
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < used; i++) out.add(new ArrayList<>());
        List<List<T>> biggestFirst = new ArrayList<>(groups);
        biggestFirst.sort(Comparator.comparingInt((List<T> g) -> g.size()).reversed());
        for (List<T> group : biggestFirst) {
            List<T> rest = new ArrayList<>(group);
            while (!rest.isEmpty()) {
                List<T> team = Collections.min(out, Comparator.comparingInt(List::size));
                if (!team.isEmpty() && teamSize - team.size() < rest.size() && out.size() < teams) { team = new ArrayList<>(); out.add(team); }
                int take = Math.min(teamSize - team.size(), rest.size());
                team.addAll(rest.subList(0, take));
                rest.subList(0, take).clear();
            }
        }
        // one party took every seat it could: give the empty side somebody to play
        for (List<T> team : out) if (team.isEmpty()) {
            List<T> biggest = Collections.max(out, Comparator.comparingInt(List::size));
            team.add(biggest.removeLast());
        }
        return out;
    }
}
