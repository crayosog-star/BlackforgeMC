package net.alloymc.core.arcade.core;

import java.util.*;

/** A game's settings with the map's overrides on top. */
public final class Settings {
    private final Map<String, Object> game, map;

    public Settings(Map<String, Object> game, Map<String, Object> map) { this.game = game; this.map = map; }

    public Object raw(String key) { return map.containsKey(key) ? map.get(key) : game.get(key); }

    public int i(String key, int def) { Object o = raw(key); return o instanceof Number n ? n.intValue() : o == null ? def : parseInt(o, def); }
    public double d(String key, double def) { Object o = raw(key); return o instanceof Number n ? n.doubleValue() : def; }
    public boolean b(String key, boolean def) { Object o = raw(key); return o instanceof Boolean x ? x : o == null ? def : Boolean.parseBoolean(o.toString()); }
    public String s(String key, String def) { Object o = raw(key); return o == null ? def : o.toString(); }

    @SuppressWarnings("unchecked")
    public List<String> list(String key, List<String> def) {
        Object o = raw(key);
        if (o instanceof List<?> l) { List<String> out = new ArrayList<>(); for (Object x : l) out.add(String.valueOf(x)); return out; }
        return def;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> section(String key) {
        Object o = raw(key);
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        if (o instanceof org.bukkit.configuration.ConfigurationSection cs) return cs.getValues(false);
        return Map.of();
    }

    private static int parseInt(Object o, int def) { try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return def; } }
}
