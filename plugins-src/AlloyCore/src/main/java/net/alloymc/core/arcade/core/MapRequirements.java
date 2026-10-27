package net.alloymc.core.arcade.core;

import java.util.*;
import java.util.function.Function;

/** What a game mode needs every one of its maps to define. Used by validation before a map can be enabled. */
public final class MapRequirements {
    public static final int PER_PLAYER = -1, PER_TEAM = -2;

    final List<String> points = new ArrayList<>();
    final Map<String, Integer> lists = new LinkedHashMap<>();
    final List<String> regions = new ArrayList<>();
    final List<String> teamPoints = new ArrayList<>();
    Function<MapDef, List<String>> extra;

    public MapRequirements point(String... keys) { points.addAll(List.of(keys)); return this; }
    /** A list of points with at least {@code min} entries (or PER_PLAYER / PER_TEAM). */
    public MapRequirements list(String key, int min) { lists.put(key, min); return this; }
    public MapRequirements region(String... keys) { regions.addAll(List.of(keys)); return this; }
    /** Points every team needs, stored as team.&lt;n&gt;.&lt;key&gt;. */
    public MapRequirements teamPoint(String... keys) { teamPoints.addAll(List.of(keys)); return this; }
    public MapRequirements check(Function<MapDef, List<String>> f) { this.extra = f; return this; }

    public List<String> describe() {
        List<String> d = new ArrayList<>();
        for (String p : points) d.add("point " + p);
        for (var e : lists.entrySet())
            d.add("list " + e.getKey() + " (" + (e.getValue() == PER_PLAYER ? "one per player" : e.getValue() == PER_TEAM ? "one per team" : "at least " + e.getValue()) + ")");
        for (String r : regions) d.add("region " + r);
        for (String t : teamPoints) d.add("team point team.<n>." + t);
        return d;
    }
}
