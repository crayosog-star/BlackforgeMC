package net.alloymc.core.arcade.core;

import net.alloymc.core.AlloyCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

/**
 * The arcade service: game registry, per-game map pools, template/instance worlds, queues and matches,
 * stats, rewards, menus and commands. ArcadeModule (the lobby) creates it and supplies the lobby hooks.
 *
 * Files (plugins/AlloyCore/arcade/):
 *   config.yml            limits, map rotation, rewards
 *   games/&lt;game&gt;.yml     settings for one game mode
 *   maps/&lt;game&gt;/&lt;map&gt;.yml one file per map (spawns, bounds, structures, template location)
 *   stats/&lt;uuid&gt;.yml     per-player statistics
 */
public final class Arcade {
    public interface Lobby {
        void toLobby(Player p);
        void giveQueueItems(Player p);
    }

    private final AlloyCore plugin;
    private final File dir;
    private final Lobby lobby;
    private final Map<String, ArcadeGame> games = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> gameSettings = new HashMap<>();
    private final Map<String, List<String>> problems = new HashMap<>();   // map key -> validation problems
    private final Set<String> templatesReady = new HashSet<>();
    private final MapStore maps;
    private final Worlds worlds;
    private final MatchManager matches;
    private final StatsService stats;
    private final RewardsService rewards;
    private final ArcadeMenu menu;
    private final ArcadeCommand command;
    private YamlConfiguration config;

    public Arcade(AlloyCore plugin, Lobby lobby) {
        this.plugin = plugin;
        this.lobby = lobby;
        this.dir = new File(plugin.getDataFolder(), "arcade");
        dir.mkdirs();
        config = loadConfig();
        maps = new MapStore(new File(dir, "maps"), plugin.getLogger());
        worlds = new Worlds(plugin, config.getInt("blocks-per-tick", 20000));
        worlds.load();
        matches = new MatchManager(this, config.getInt("max-active-matches", 12), config.getInt("avoid-recent-maps", 2), config.getInt("max-queue-size", 64));
        stats = new StatsService(plugin, new File(dir, "stats"));
        rewards = new RewardsService(plugin, config.getConfigurationSection("rewards"));
        menu = new ArcadeMenu(this);
        command = new ArcadeCommand(this);
        Bukkit.getPluginManager().registerEvents(new ArcadeListener(this), plugin);
        Bukkit.getPluginManager().registerEvents(menu, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, matches::tickSecond, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, matches::tickFast, 1L, 1L);
    }

    private YamlConfiguration loadConfig() {
        File f = new File(dir, "config.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        y.options().setHeader(List.of("BlackForgeMC Arcade settings. Per-game settings are in games/, maps in maps/<game>/."));
        y.addDefault("max-active-matches", 12);
        y.addDefault("max-queue-size", 64);
        y.addDefault("avoid-recent-maps", 2);
        y.addDefault("blocks-per-tick", 20000);
        y.addDefault("rewards.default.win-money", 150);
        y.addDefault("rewards.default.win-scrap", 15);
        y.addDefault("rewards.default.play-money", 25);
        y.addDefault("rewards.default.play-scrap", 2);
        y.addDefault("rewards.default.kill-money", 10);
        y.addDefault("rewards.default.kill-scrap", 1);
        y.addDefault("rewards.default.min-seconds", 45);
        y.options().copyDefaults(true);
        try { y.save(f); } catch (Exception e) { plugin.getLogger().warning("[Arcade] could not write config.yml: " + e.getMessage()); }
        return y;
    }

    // ------------------------------------------------------------------ registry

    public void register(ArcadeGame g) {
        games.put(g.id, g);
        File f = new File(new File(dir, "games"), g.id + ".yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        boolean changed = false;
        for (var e : g.defaultSettings().entrySet()) if (!y.contains(e.getKey())) { y.set(e.getKey(), e.getValue()); changed = true; }
        if (changed || !f.exists()) {
            y.options().setHeader(List.of(g.name + " settings. Any key can also be overridden per map under 'settings:' in its map file."));
            try { f.getParentFile().mkdirs(); y.save(f); } catch (Exception e) { plugin.getLogger().warning("[Arcade] could not write " + f + ": " + e.getMessage()); }
        }
        gameSettings.put(g.id, y.getValues(true));
    }

    public Collection<ArcadeGame> games() { return games.values(); }
    public ArcadeGame game(String id) { return id == null ? null : games.get(id.toLowerCase()); }
    public Map<String, Object> gameSettings(ArcadeGame g) { return gameSettings.getOrDefault(g.id, Map.of()); }

    /**
     * Write any missing built-in map files, load every pool, then generate missing templates one map at a
     * time (spreading the work over ticks). Maps become playable as their template finishes.
     */
    public void loadMaps(Consumer<String> progress) {
        List<BuiltMap> toBuild = new ArrayList<>();
        int gi = 0;
        for (ArcadeGame g : games.values()) {
            int mi = 0;
            for (BuiltMap b : g.builtInMaps()) {
                MapDef d = b.def();
                d.templateX = (gi * 8 + mi) * 1100;   // stable home for each built-in template
                d.templateZ = 0;
                d.builder = g.id + ":" + d.id;
                File f = maps.file(g.id, d.id);
                MapDef existing = f.exists() ? MapStore.read(g.id, d.id, YamlConfiguration.loadConfiguration(f)) : null;
                if (existing == null || (existing.builder.equals(d.builder) && existing.builderVersion < d.builderVersion)) {
                    if (existing != null) { d.enabled = existing.enabled; d.weight = existing.weight; d.settings.putAll(existing.settings); }
                    try { maps.save(d); } catch (Exception e) { plugin.getLogger().warning("[Arcade] could not write map " + d + ": " + e.getMessage()); }
                }
                toBuild.add(b);
                mi++;
            }
            gi++;
        }
        for (ArcadeGame g : games.values()) maps.loadGame(g.id);
        revalidate();
        // custom (staff-built) maps use whatever is already in the template world
        for (String gid : maps.games()) for (MapDef m : maps.pool(gid)) if (m.builder.equals("custom")) templatesReady.add(m.toString());
        buildNext(new ArrayDeque<>(toBuild), progress);
    }

    private void buildNext(Deque<BuiltMap> q, Consumer<String> progress) {
        if (q.isEmpty()) { progress.accept("All arcade map templates are ready."); return; }
        BuiltMap b = q.poll();
        MapDef live = maps.get(b.def().game, b.def().id);
        if (live == null) { buildNext(q, progress); return; }
        // the template geometry comes from the builder; the live file supplies its location/version
        b.def().templateX = live.templateX; b.def().templateY = live.templateY; b.def().templateZ = live.templateZ;
        b.def().builderVersion = live.builderVersion;
        boolean built = worlds.ensureTemplate(b, () -> {
            templatesReady.add(live.toString());
            worlds.invalidate(live);
            Bukkit.getScheduler().runTask(plugin, () -> buildNext(q, progress));
        });
        if (built) progress.accept("Generated template " + live);
    }

    public void revalidate() {
        problems.clear();
        for (String gid : maps.games()) {
            ArcadeGame g = games.get(gid);
            for (MapDef m : maps.pool(gid)) {
                List<String> p = m.validate(g == null ? null : g.requirements());
                problems.put(m.toString(), p);
                if (!MapDef.errors(p).isEmpty()) plugin.getLogger().warning("[Arcade] map " + m + " is not playable: " + MapDef.errors(p));
            }
        }
    }

    public List<String> problems(MapDef m) { return problems.getOrDefault(m.toString(), List.of("not validated")); }

    /** Enabled maps must pass validation and have their template built before they can be picked. */
    public boolean isValid(MapDef m) { return MapDef.errors(problems(m)).isEmpty() && templatesReady.contains(m.toString()); }
    public boolean templateReady(MapDef m) { return templatesReady.contains(m.toString()); }
    public void markTemplateReady(MapDef m) { templatesReady.add(m.toString()); worlds.invalidate(m); }

    public boolean hasPlayableMap(ArcadeGame g, Variant v) {
        for (MapDef m : maps.pool(g.id)) if (m.enabled && m.supports(v) && isValid(m)) return true;
        return false;
    }

    public void reload() {
        config = loadConfig();
        rewards.reload(config.getConfigurationSection("rewards"));
        for (ArcadeGame g : games.values()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(new File(dir, "games"), g.id + ".yml"));
            gameSettings.put(g.id, y.getValues(true));
            maps.loadGame(g.id);
        }
        for (String gid : maps.games()) for (MapDef m : maps.pool(gid)) { if (m.builder.equals("custom")) templatesReady.add(m.toString()); worlds.invalidate(m); }
        revalidate();
    }

    public void shutdown() { matches.shutdown(); stats.flush(false); }

    // ------------------------------------------------------------------ accessors

    public AlloyCore plugin() { return plugin; }
    public MapStore maps() { return maps; }
    public Worlds worlds() { return worlds; }
    public MatchManager matches() { return matches; }
    public StatsService stats() { return stats; }
    public RewardsService rewards() { return rewards; }
    public ArcadeMenu menu() { return menu; }
    public ArcadeCommand command() { return command; }
    public YamlConfiguration config() { return config; }
    public void toLobby(Player p) { lobby.toLobby(p); }
    public void giveQueueItems(Player p) { lobby.giveQueueItems(p); }
}
