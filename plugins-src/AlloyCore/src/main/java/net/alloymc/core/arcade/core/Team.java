package net.alloymc.core.arcade.core;

import java.util.*;

/** One team inside one match. Free-for-all matches give every player a team of one. */
public final class Team {
    public final int index;
    public final TeamColor color;
    public final List<UUID> members = new ArrayList<>();
    public boolean eliminated;
    public int score;
    /** Game-specific per-team state (bed alive, upgrades...). */
    public final Map<String, Object> data = new HashMap<>();

    public Team(int index) { this.index = index; this.color = TeamColor.of(index); }

    public String name() { return color.mm + color.label; }

    @SuppressWarnings("unchecked") public <T> T get(String k, T def) { return (T) data.getOrDefault(k, def); }
    public int getInt(String k) { return ((Number) data.getOrDefault(k, 0)).intValue(); }
    public void inc(String k) { data.put(k, getInt(k) + 1); }
}
