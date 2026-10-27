package net.alloymc.core.arcade.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * The map registry: one YAML file per map under arcade/maps/&lt;game&gt;/&lt;map&gt;.yml, grouped into a
 * separate pool per game. Never shares maps between games.
 */
public final class MapStore {
    private final File root;
    private final Logger log;
    private final Map<String, Map<String, MapDef>> pools = new LinkedHashMap<>();

    public MapStore(File root, Logger log) { this.root = root; this.log = log; root.mkdirs(); }

    public File file(String game, String map) { return new File(new File(root, game), map + ".yml"); }

    public Collection<MapDef> pool(String game) { return pools.getOrDefault(game, Map.of()).values(); }
    public MapDef get(String game, String map) { return pools.getOrDefault(game, Map.of()).get(map); }
    public Set<String> games() { return pools.keySet(); }

    public void put(MapDef m) { pools.computeIfAbsent(m.game, g -> new LinkedHashMap<>()).put(m.id, m); }

    /** (Re)load every map file of a game. Broken files are logged and skipped, never half-loaded. */
    public int loadGame(String game) {
        Map<String, MapDef> pool = new LinkedHashMap<>();
        File dir = new File(root, game);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                try {
                    MapDef m = read(game, id, YamlConfiguration.loadConfiguration(f));
                    pool.put(id, m);
                } catch (Exception e) {
                    log.warning("[Arcade] map " + game + "/" + id + " could not be read and was skipped: " + e.getMessage());
                }
            }
        }
        pools.put(game, pool);
        return pool.size();
    }

    // ------------------------------------------------------------------ YAML

    public static MapDef read(String game, String id, ConfigurationSection y) {
        MapDef m = new MapDef(game, id);
        m.display = y.getString("display-name", id);
        m.description = y.getString("description", "");
        m.author = y.getString("author", "BlackForgeMC");
        m.enabled = y.getBoolean("enabled", true);
        m.weight = y.getInt("weight", 1);
        m.minPlayers = y.getInt("min-players", 2);
        m.maxPlayers = y.getInt("max-players", 16);
        m.variants.addAll(y.getStringList("variants"));
        m.teams = y.getInt("teams", 0);
        if (y.isString("bounds")) m.bounds = Box.parse(y.getString("bounds"));
        m.voidY = y.getInt("void-y", -10);
        if (y.isString("spectator-spawn")) m.spectator = Pt.parse(y.getString("spectator-spawn"));
        if (y.isString("waiting-spawn")) m.waiting = Pt.parse(y.getString("waiting-spawn"));
        ConfigurationSection pts = y.getConfigurationSection("points");
        if (pts != null) for (String k : pts.getKeys(true)) if (pts.isString(k)) m.points.put(k, Pt.parse(pts.getString(k)));
        ConfigurationSection lists = y.getConfigurationSection("lists");
        if (lists != null) for (String k : lists.getKeys(false)) {
            List<Pt> l = new ArrayList<>();
            for (String s : lists.getStringList(k)) l.add(Pt.parse(s));
            m.lists.put(k, l);
        }
        ConfigurationSection regs = y.getConfigurationSection("regions");
        if (regs != null) for (String k : regs.getKeys(true)) if (regs.isString(k)) m.regions.put(k, Box.parse(regs.getString(k)));
        ConfigurationSection set = y.getConfigurationSection("settings");
        if (set != null) for (String k : set.getKeys(false)) m.settings.put(k, set.get(k));
        ConfigurationSection t = y.getConfigurationSection("template");
        if (t != null) {
            m.templateX = t.getInt("x"); m.templateY = t.getInt("y", 100); m.templateZ = t.getInt("z");
            if (t.isString("copy-region")) m.copy = Box.parse(t.getString("copy-region"));
            m.builder = t.getString("builder", "custom");
            m.builderVersion = t.getInt("builder-version", 1);
        }
        m.reset = y.getString("reset-strategy", "fresh-instance");
        m.requires = y.getString("requires", m.requires);
        return m;
    }

    public void save(MapDef m) throws java.io.IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
            m.game + " map '" + m.id + "'. Positions are relative to the map origin: \"x, y, z[, yaw, pitch]\".",
            "Boxes are \"x1, y1, z1 ; x2, y2, z2\". Edit, then /arcade reload and /arcade validate-map " + m.game + " " + m.id + "."));
        y.set("display-name", m.display);
        y.set("description", m.description);
        y.set("author", m.author);
        y.set("enabled", m.enabled);
        y.set("weight", m.weight);
        y.set("min-players", m.minPlayers);
        y.set("max-players", m.maxPlayers);
        if (!m.variants.isEmpty()) y.set("variants", m.variants);
        if (m.teams > 0) y.set("teams", m.teams);
        if (m.bounds != null) y.set("bounds", m.bounds.toString());
        y.set("void-y", m.voidY);
        if (m.spectator != null) y.set("spectator-spawn", m.spectator.toString());
        if (m.waiting != null) y.set("waiting-spawn", m.waiting.toString());
        for (var e : m.points.entrySet()) y.set("points." + e.getKey(), e.getValue().toString());
        for (var e : m.lists.entrySet()) y.set("lists." + e.getKey(), e.getValue().stream().map(Pt::toString).toList());
        for (var e : m.regions.entrySet()) y.set("regions." + e.getKey(), e.getValue().toString());
        for (var e : m.settings.entrySet()) y.set("settings." + e.getKey(), e.getValue());
        y.set("template.x", m.templateX);
        y.set("template.y", m.templateY);
        y.set("template.z", m.templateZ);
        if (m.copy != null) y.set("template.copy-region", m.copy.toString());
        y.set("template.builder", m.builder);
        y.set("template.builder-version", m.builderVersion);
        y.set("reset-strategy", m.reset);
        y.set("requires", m.requires);
        File f = file(m.game, m.id);
        f.getParentFile().mkdirs();
        // write to a temp file first so a crash can never leave a half-written map
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        y.save(tmp);
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
