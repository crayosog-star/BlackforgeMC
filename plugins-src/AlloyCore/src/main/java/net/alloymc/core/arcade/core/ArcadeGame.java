package net.alloymc.core.arcade.core;

import org.bukkit.Material;

import java.util.*;

/**
 * A game mode: its identity, variants, what its maps must contain, its built-in maps and its default
 * settings. Each mode lives in its own package under arcade.games and is registered in Arcade.
 * Adding a mode = subclass this, write a Match subclass, and register it.
 */
public abstract class ArcadeGame {
    public enum Category {
        COMBAT("Combat", Material.IRON_SWORD), SURVIVAL("Survival", Material.GRASS_BLOCK),
        PARTY("Party Games", Material.FIREWORK_ROCKET), CASUAL("Casual", Material.FEATHER);
        public final String label; public final Material icon;
        Category(String label, Material icon) { this.label = label; this.icon = icon; }
    }

    public final String id, name, color;
    public final Material icon;
    public final Category category;
    public final String description;
    protected final List<Variant> variants = new ArrayList<>();

    protected ArcadeGame(String id, String name, String color, Material icon, Category category, String description) {
        this.id = id; this.name = name; this.color = color; this.icon = icon; this.category = category; this.description = description;
    }

    public List<Variant> variants() { return variants; }

    public Variant variant(String id) {
        for (Variant v : variants) if (v.id().equalsIgnoreCase(id)) return v;
        return null;
    }

    /** What every map of this mode must define. */
    public abstract MapRequirements requirements();

    /** The maps that ship with BlackForgeMC (written to arcade/maps/&lt;id&gt;/ on first start). */
    public abstract List<BuiltMap> builtInMaps();

    /** Create the match object for a new game on an already-loaded map instance. */
    public abstract Match newMatch(MatchContext ctx);

    /** Game settings written to arcade/games/&lt;id&gt;.yml the first time (maps may override any key). */
    public Map<String, Object> defaultSettings() { return new LinkedHashMap<>(); }

    /** Short how-to-play lines for the menu. */
    public List<String> howToPlay() { return List.of(description); }

    /** May players join a match that is already running (e.g. free-for-all arenas)? */
    public boolean lateJoin() { return false; }

    /** Seconds the queue counts down once enough players are waiting. */
    public int queueCountdown() { return 15; }
}
