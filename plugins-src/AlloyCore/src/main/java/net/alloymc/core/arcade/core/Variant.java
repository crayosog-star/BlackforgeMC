package net.alloymc.core.arcade.core;

/**
 * One way to play a game (Solo, Doubles, 4v4v4v4, Ranked Sumo...). {@code teams <= 0} means free-for-all:
 * every player is on their own. {@code key} is a free-form tag a game can use (e.g. the duel kit).
 */
public record Variant(String id, String label, int teams, int teamSize, int minPlayers, int maxPlayers, boolean ranked, String key) {
    public static Variant ffa(String id, String label, int min, int max) { return new Variant(id, label, 0, 1, min, max, false, ""); }
    public static Variant teams(String id, String label, int teams, int size, int min) { return new Variant(id, label, teams, size, min, teams * size, false, ""); }

    public boolean isTeams() { return teams > 0; }

    public Variant withKey(String k) { return new Variant(id, label, teams, teamSize, minPlayers, maxPlayers, ranked, k); }
    public Variant asRanked() { return new Variant(id, label, teams, teamSize, minPlayers, maxPlayers, true, key); }
}
