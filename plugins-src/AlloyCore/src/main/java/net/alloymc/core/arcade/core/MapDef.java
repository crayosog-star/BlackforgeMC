package net.alloymc.core.arcade.core;

import java.util.*;

/**
 * Everything about one map of one game mode, as read from arcade/maps/&lt;game&gt;/&lt;map&gt;.yml.
 * All positions are relative to the map origin, so the same map can be loaded into any instance slot.
 * Plain Java (no Bukkit) so it can be unit-tested.
 */
public final class MapDef {
    public final String game, id;
    public String display, description = "", author = "BlackForgeMC";
    public boolean enabled = true;
    public int weight = 1;
    public int minPlayers = 2, maxPlayers = 16;
    /** Variant ids this map supports; empty = every variant of the game. */
    public final List<String> variants = new ArrayList<>();
    /** Team islands/bases on this map (team games), 0 for free-for-all maps. */
    public int teams = 0;
    public Box bounds;
    /** Below this (relative) Y a player has fallen into the void. */
    public int voidY = -10;
    public Pt spectator, waiting;
    public final Map<String, Pt> points = new LinkedHashMap<>();
    public final Map<String, List<Pt>> lists = new LinkedHashMap<>();
    public final Map<String, Box> regions = new LinkedHashMap<>();
    /** Per-map overrides of the game's settings (round length, generator speeds, palettes...). */
    public final Map<String, Object> settings = new LinkedHashMap<>();
    /** Where the immutable template lives in the template world, and the region copied for each match. */
    public int templateX, templateY = 100, templateZ;
    public Box copy;
    /** Built-in generator that (re)creates the template, or "custom" for maps built by staff. */
    public String builder = "custom";
    public int builderVersion = 1;
    public String reset = "fresh-instance";
    public String requires = "Paper 26.3, AlloyCore 1.0";

    public MapDef(String game, String id) {
        this.game = game; this.id = id; this.display = id;
    }

    public Pt point(String key) { return points.get(key); }
    public List<Pt> list(String key) { return lists.getOrDefault(key, List.of()); }
    public Box region(String key) { return regions.get(key); }

    public MapDef point(String key, Pt p) { points.put(key, p); return this; }
    public MapDef add(String key, Pt p) { lists.computeIfAbsent(key, k -> new ArrayList<>()).add(p); return this; }
    public MapDef region(String key, Box b) { regions.put(key, b); return this; }
    public MapDef set(String key, Object v) { settings.put(key, v); return this; }

    public boolean supports(Variant v) {
        if (!variants.isEmpty() && !variants.contains(v.id())) return false;
        return true;
    }

    public boolean fits(int players) { return players >= minPlayers && players <= maxPlayers; }

    /** Can this many players in this variant fit on the map's team bases? */
    public boolean fits(Variant v, int players) {
        if (!fits(players)) return false;
        return !v.isTeams() || teams <= 0 || players <= teams * v.teamSize();
    }

    public Box copyRegion() { return copy != null ? copy : bounds; }

    /** Problems that stop this map from being used, plus warnings (prefixed "warning:"). Empty = valid. */
    public List<String> validate(MapRequirements req) {
        List<String> out = new ArrayList<>();
        if (bounds == null) { out.add("missing bounds (arena boundary)"); return out; }
        if (bounds.sizeX() > 900 || bounds.sizeZ() > 900 || bounds.sizeY() > 300) out.add("bounds too big for an instance slot (max 900 x 300 x 900)");
        if (spectator == null) out.add("missing spectator spawn");
        else if (!bounds.contains(spectator)) out.add("spectator spawn is outside the bounds");
        if (minPlayers < 1 || maxPlayers < minPlayers) out.add("bad player limits (min " + minPlayers + ", max " + maxPlayers + ")");
        if (voidY >= bounds.y2()) out.add("void level is above the whole arena");
        if (req != null) {
            for (String k : req.points) {
                Pt p = points.get(k);
                if (p == null) out.add("missing point '" + k + "'");
                else if (!bounds.contains(p)) out.add("point '" + k + "' is outside the bounds");
            }
            for (var e : req.lists.entrySet()) {
                int need = e.getValue() == MapRequirements.PER_PLAYER ? maxPlayers : e.getValue() == MapRequirements.PER_TEAM ? teams : e.getValue();
                List<Pt> l = list(e.getKey());
                if (l.size() < need) out.add("needs at least " + need + " '" + e.getKey() + "' points (has " + l.size() + ")");
                for (Pt p : l) if (!bounds.contains(p)) { out.add("a '" + e.getKey() + "' point is outside the bounds: " + p); break; }
            }
            for (String k : req.regions) {
                Box b = regions.get(k);
                if (b == null) out.add("missing region '" + k + "'");
                else if (!bounds.intersects(b)) out.add("region '" + k + "' is outside the bounds");
            }
            if (req.teamPoints.size() > 0) {
                if (teams < 2) out.add("team map needs 'teams: 2' or more");
                for (int t = 0; t < teams; t++)
                    for (String k : req.teamPoints) {
                        Pt p = points.get("team." + t + "." + k);
                        if (p == null) out.add("missing point 'team." + t + "." + k + "'");
                        else if (!bounds.contains(p)) out.add("point 'team." + t + "." + k + "' is outside the bounds");
                    }
            }
            if (req.extra != null) out.addAll(req.extra.apply(this));
        }
        // overlapping spawns: two players would land on each other
        List<Pt> spawns = new ArrayList<>(list("spawns"));
        for (int t = 0; t < teams; t++) { Pt p = points.get("team." + t + ".spawn"); if (p != null) spawns.add(p); }
        outer:
        for (int i = 0; i < spawns.size(); i++)
            for (int j = i + 1; j < spawns.size(); j++)
                if (spawns.get(i).dist(spawns.get(j)) < 0.9) { out.add("warning: spawns " + i + " and " + j + " overlap"); break outer; }
        return out;
    }

    /** Validation errors only (warnings removed). */
    public static List<String> errors(List<String> problems) {
        return problems.stream().filter(s -> !s.startsWith("warning:")).toList();
    }

    @Override public String toString() { return game + "/" + id; }
}
