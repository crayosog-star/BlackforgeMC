package net.alloymc.core.arcade;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Hooks;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.Arcade;
import net.alloymc.core.arcade.core.ArcadeGame;
import net.alloymc.core.arcade.core.Ratings;
import net.alloymc.core.arcade.core.StatsService;
import net.alloymc.core.arcade.core.Variant;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Arcade competition on a schedule, run by the server itself:
 * <ul>
 *   <li><b>Ranked seasons</b> - one per calendar month. When the month turns, the top three of every ranked queue are
 *       paid and announced, the results go in the season history, and everyone's rating goes back to 1000. /ranked.</li>
 *   <li><b>The Weekend Cup</b> - every Saturday and Sunday, each Arcade game played scores 1 point and each win 3.
 *       On Monday the top three are paid and announced. /cup.</li>
 * </ul>
 * State lives in arcade/seasons.yml.
 */
public final class Seasons {
    private static final int[] SEASON_MONEY = {10000, 5000, 2500}, SEASON_SCRAP = {750, 400, 200};
    private static final int[] CUP_MONEY = {5000, 2500, 1000}, CUP_SCRAP = {400, 200, 100};

    private final AlloyCore plugin;
    private final Arcade arcade;
    private final File file;
    private final YamlConfiguration data;

    public Seasons(AlloyCore plugin, Arcade arcade) {
        this.plugin = plugin;
        this.arcade = arcade;
        this.file = new File(plugin.getDataFolder(), "arcade/seasons.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        if (!data.contains("season")) { data.set("season", YearMonth.now().toString()); save(); }
        Hooks.onArcadeEnd((p, game, won) -> {
            if (!weekend()) return;
            String key = "cup." + cupWeek() + "." + p.getUniqueId();
            data.set(key + ".name", p.getName());
            data.set(key + ".points", data.getInt(key + ".points") + (won ? 3 : 1));
            save();
            p.sendMessage(Ui.mm("<gold>🏆 <gray>Weekend Cup: <white>+" + (won ? 3 : 1) + " <gray>point" + (won ? "s" : "") + " (" + data.getInt(key + ".points") + " total) <dark_gray>/cup"));
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::check, 200L, 20L * 60 * 10);
        reg("ranked", List.of("elo", "season"), this::ranked);
        reg("cup", List.of("weekendcup", "tournament"), this::cup);
    }

    private void save() { try { data.save(file); } catch (Exception ignored) { } }

    // ------------------------------------------------------------------ schedule

    private static boolean weekend() {
        DayOfWeek d = LocalDate.now().getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    /** The weekend a date belongs to: its ISO week. */
    private static String cupWeek() {
        LocalDate d = LocalDate.now();
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-w" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    private void check() {
        String now = YearMonth.now().toString();
        if (!now.equals(data.getString("season"))) endSeason(data.getString("season"), now);
        // pay out finished cups: an ISO week runs Monday to Sunday, so any week but this one is over
        var cups = data.getConfigurationSection("cup");
        if (cups != null) for (String week : new ArrayList<>(cups.getKeys(false))) if (!week.equals(cupWeek())) endCup(week);
        if (weekend() && !data.getBoolean("cup-announced." + cupWeek())) {
            data.set("cup-announced." + cupWeek(), true);
            save();
            Bukkit.broadcast(Ui.mm("<gold>🏆 <bold>The Weekend Cup is on!</bold> <gray>Every Arcade game scores <white>1<gray>, every win <white>3<gray>. Top 3 on Monday win big. <white>/cup"));
        }
    }

    private List<Map.Entry<String, int[]>> cupTable(String week) {
        var sec = data.getConfigurationSection("cup." + week);
        List<Map.Entry<String, int[]>> rows = new ArrayList<>();
        if (sec != null) for (String u : sec.getKeys(false)) rows.add(Map.entry(u, new int[]{sec.getInt(u + ".points")}));
        rows.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
        return rows;
    }

    private void endCup(String week) {
        List<Map.Entry<String, int[]>> rows = cupTable(week);
        if (!rows.isEmpty()) {
            Bukkit.broadcast(Ui.mm("<gold>🏆 <bold>Weekend Cup results</bold>"));
            for (int i = 0; i < Math.min(3, rows.size()); i++) {
                UUID u = UUID.fromString(rows.get(i).getKey());
                String name = data.getString("cup." + week + "." + u + ".name", "?");
                pay(u, CUP_MONEY[i], CUP_SCRAP[i]);
                Bukkit.broadcast(Ui.mm("<gold>  " + (i + 1) + ". <white>" + name + " <gray>" + rows.get(i).getValue()[0] + " pts <dark_gray>» <green>"
                        + NetEconomy.money(CUP_MONEY[i]) + " <#c6a15b>+" + CUP_SCRAP[i] + " Scrap"));
                data.set("cup-history." + week + "." + (i + 1), name);
            }
        }
        data.set("cup." + week, null);
        save();
    }

    private void endSeason(String season, String next) {
        StatsService stats = arcade.stats();
        Bukkit.broadcast(Ui.mm("<gold>⚔ <bold>Ranked season " + season + " is over!</bold>"));
        for (ArcadeGame g : arcade.games())
            for (Variant v : g.variants()) {
                if (!v.ranked()) continue;
                String key = "rating_" + v.id();
                List<StatsService.Row> top = stats.top(g.id, key, 1000, false);
                for (int i = 0; i < Math.min(3, top.size()); i++) {
                    StatsService.Row r = top.get(i);
                    pay(r.id(), SEASON_MONEY[i], SEASON_SCRAP[i]);
                    data.set("history." + season + "." + v.id() + "." + (i + 1), r.name() + " (" + r.value() + ")");
                    if (i == 0) Bukkit.broadcast(Ui.mm("<gold>  " + v.label() + " champion: <white>" + r.name() + " <gray>(" + r.value() + ")"));
                }
                for (StatsService.Row r : top) stats.set(r.id(), g.id, key, Ratings.START);
            }
        Bukkit.broadcast(Ui.mm("<gray>Ratings are back to <white>" + Ratings.START + "<gray>. Season <white>" + next + " <gray>starts now - <white>/ranked"));
        data.set("season", next);
        save();
    }

    private void pay(UUID u, int money, int scrap) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(u);
        plugin.economy().deposit(p, money);
        plugin.economy().addScrap(p, scrap);
    }

    // ------------------------------------------------------------------ commands

    private void ranked(CommandSender s, String[] a) {
        YearMonth ym = YearMonth.now();
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), ym.plusMonths(1).atDay(1));
        s.sendMessage(Ui.mm("<gold>⚔ <bold>Ranked season " + ym + "</bold> <gray>- ends in <white>" + days + " day" + (days == 1 ? "" : "s")
                + " <gray>| Top 3 of each queue win up to <green>" + NetEconomy.money(SEASON_MONEY[0])));
        for (ArcadeGame g : arcade.games())
            for (Variant v : g.variants()) {
                if (!v.ranked()) continue;
                String key = "rating_" + v.id();
                List<StatsService.Row> top = arcade.stats().top(g.id, key, 3, false);
                StringBuilder sb = new StringBuilder("<white>" + v.label() + "<gray>: ");
                if (top.isEmpty()) sb.append("<dark_gray>nobody yet");
                for (int i = 0; i < top.size(); i++) sb.append(i > 0 ? "<dark_gray>, " : "").append("<gold>").append(i + 1).append(". <white>").append(top.get(i).name()).append(" <gray>").append(top.get(i).value());
                if (s instanceof Player p) sb.append(" <dark_gray>| you: <aqua>").append(Ratings.rating(arcade.stats(), p.getUniqueId(), g.id, key));
                s.sendMessage(Ui.mm(sb.toString()));
            }
    }

    private void cup(CommandSender s, String[] a) {
        s.sendMessage(Ui.mm(weekend() ? "<gold>🏆 <bold>The Weekend Cup is live!</bold> <gray>1 point per game, 3 per win."
                : "<gold>🏆 <bold>Weekend Cup</bold> <gray>- starts Saturday. 1 point per Arcade game, 3 per win; top 3 paid Monday."));
        List<Map.Entry<String, int[]>> rows = cupTable(cupWeek());
        for (int i = 0; i < Math.min(5, rows.size()); i++)
            s.sendMessage(Ui.mm("<gold>  " + (i + 1) + ". <white>" + data.getString("cup." + cupWeek() + "." + rows.get(i).getKey() + ".name", "?")
                    + " <gray>" + rows.get(i).getValue()[0] + " pts" + (i < 3 ? " <dark_gray>» <green>" + NetEconomy.money(CUP_MONEY[i]) : "")));
        if (rows.isEmpty() && weekend()) s.sendMessage(Ui.mm("<gray>  Nobody has scored yet - go play!"));
    }

    private interface Cmd { void run(CommandSender s, String[] args); }

    private void reg(String name, List<String> aliases, Cmd cmd) {
        Bukkit.getCommandMap().register("alloy", new Command(name, "BlackForgeMC " + name, "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) { cmd.run(s, args); return true; }
        });
    }
}
