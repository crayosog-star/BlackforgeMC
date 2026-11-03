package net.blackforge.core;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.LocalDate;
import java.util.*;

/**
 * Season points and the event champions. While a limited-time season is on, everything a player does on any server
 * earns Season Points (KOTH wins, world boss hits, Colosseum fights, arcade wins, kills, event trades...). When the
 * season ends, the top players get that season's Champion cape for good.
 * <p>
 * Points live in shared/cosmetics/seasons/&lt;season&gt;-&lt;year&gt;.yml. Every server adds only what it saw since its last
 * flush, so servers running side by side never overwrite each other.
 */
final class SeasonBoard {
    record Entry(UUID id, String name, int points) {}

    private final BlackForgeCore plugin;
    private final Cosmetics cos;
    private final File dir;
    /** file key -> (player -> points not yet written). */
    private final Map<String, Map<UUID, Integer>> pending = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final int champions;

    SeasonBoard(BlackForgeCore plugin, Cosmetics cos, File shared) {
        this.plugin = plugin;
        this.cos = cos;
        this.dir = new File(shared, "cosmetics/seasons");
        dir.mkdirs();
        this.champions = Math.max(1, cos.conf().getInt("cosmetics.champions", 3));
        Hooks.onStat((p, key, n, max) -> { if (!max) add(p, points(key) * (int) Math.max(1, Math.min(n, 50))); });
        Hooks.onKill((killer, victim, where) -> add(killer, 1));
        Hooks.onArcadeEnd((p, game, won) -> { if (won) add(p, 2); });
        Bukkit.getScheduler().runTaskTimer(plugin, this::flush, 600L, 600L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::awardEnded, 1200L, 12_000L);
    }

    /** Season Points for one count of a network stat (0 = doesn't count). */
    static int points(String key) {
        return switch (key) {
            case "koth", "boss" -> 5;
            case "event_trade" -> 3;
            case "arena" -> 2;
            case "event_candy", "event_frost", "crate", "cosmetic_crate", "parkour", "vote" -> 1;
            default -> 0;
        };
    }

    private String fileKey(Cosmetics.Season s) { return s.id() + "-" + s.start().getYear(); }

    private File file(String key) { return new File(dir, key + ".yml"); }

    /** Points from quests and other bonuses. */
    void bonus(Player p, int pts) { add(p, pts); }

    private void add(Player p, int pts) {
        if (p == null || pts <= 0) return;
        List<Cosmetics.Season> live = cos.liveSeasons();
        if (live.isEmpty()) return;
        names.put(p.getUniqueId(), p.getName());
        for (Cosmetics.Season s : live) pending.computeIfAbsent(fileKey(s), k -> new HashMap<>()).merge(p.getUniqueId(), pts, Integer::sum);
    }

    void flush() {
        if (pending.isEmpty()) return;
        Map<String, Map<UUID, Integer>> work = new HashMap<>(pending);
        pending.clear();
        for (Map.Entry<String, Map<UUID, Integer>> e : work.entrySet()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file(e.getKey()));
            for (Map.Entry<UUID, Integer> d : e.getValue().entrySet()) {
                String id = d.getKey().toString();
                y.set("scores." + id, y.getInt("scores." + id, 0) + d.getValue());
                if (names.containsKey(d.getKey())) y.set("names." + id, names.get(d.getKey()));
            }
            try { y.save(file(e.getKey())); } catch (Exception ex) { plugin.getLogger().warning("season scores save failed: " + ex.getMessage()); }
        }
    }

    /** Best first. Includes points this server hasn't written yet. */
    List<Entry> top(Cosmetics.Season s, int n) {
        flush();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file(fileKey(s)));
        ConfigurationSection sc = y.getConfigurationSection("scores");
        if (sc == null) return List.of();
        List<Entry> out = new ArrayList<>();
        for (String k : sc.getKeys(false)) {
            try { out.add(new Entry(UUID.fromString(k), y.getString("names." + k, "?"), sc.getInt(k))); } catch (IllegalArgumentException ignored) { }
        }
        out.sort(Comparator.comparingInt(Entry::points).reversed());
        return n >= out.size() ? out : out.subList(0, n);
    }

    /** 1-based place of this player, or 0 with no points. */
    int placeOf(Cosmetics.Season s, UUID u) {
        List<Entry> all = top(s, Integer.MAX_VALUE);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id().equals(u)) return i + 1;
        return 0;
    }

    int pointsOf(Cosmetics.Season s, UUID u) {
        for (Entry e : top(s, Integer.MAX_VALUE)) if (e.id().equals(u)) return e.points();
        return 0;
    }

    /** Ended seasons that haven't been settled yet: the top players get the champion cape. */
    void awardEnded() {
        LocalDate today = LocalDate.now();
        for (Cosmetics.Season s : cos.allSeasons()) {
            if (s.grantOnly() || !today.isAfter(s.end())) continue;
            File f = file(fileKey(s));
            if (!f.exists()) continue;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (y.getBoolean("awarded")) continue;
            y.set("awarded", true);   // set first: a second server that checks a moment later will see it
            try { y.save(f); } catch (Exception ex) { continue; }
            String cape = "c_champ_" + s.id();
            if (Cosmetics.byId(cape) == null) continue;
            List<Entry> top = top(s, champions);
            if (top.isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            int place = 0;
            for (Entry e : top) {
                place++;
                cos.unlockOriginal(e.id(), cape);
                if (Cosmetics.byId("i_champ_" + s.id()) != null) cos.unlockOriginal(e.id(), "i_champ_" + s.id());
                cos.addNotice(e.id(), "<gold>🏆 <gray>You finished <white>#" + place + "<gray> in <white>" + s.label() + "<gray> with <white>" + e.points() + " <gray>points and earned the Champion's cape and icon! <white>/cosmetics");
                if (sb.length() > 0) sb.append("<gray>, ");
                sb.append("<white>#").append(place).append(' ').append(e.name()).append(" <dark_gray>(").append(e.points()).append(")");
                Player online = Bukkit.getPlayer(e.id());
                if (online != null) cos.deliverNotices(online);
            }
            Bukkit.broadcast(Ui.mm("<gold>🏆 <gray>The champions of " + s.label() + "<gray>: " + sb));
            plugin.getLogger().info("season " + s.id() + " settled: " + top.size() + " champion(s)");
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
        }
    }
}
