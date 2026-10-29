package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.time.LocalDate;
import java.util.*;

/**
 * What's new on the network: /news shows the latest updates, the join digest mentions anything a player hasn't seen,
 * (seen counts in shared/news-seen.yml) and the hub selector shows each server's newest line. Entries live in shared/news.yml (newest first); staff add one
 * with /news add [server] &lt;text&gt;, which also goes to the Discord chat feed when its webhook is set.
 */
public final class News {
    public record Item(String date, String server, String text) {}

    private static File file;
    private final Community community;

    public News(AlloyCore plugin, File shared, Community community) {
        file = new File(shared, "news.yml");
        this.community = community;
        if (!file.exists()) seed();
        Bukkit.getCommandMap().register("alloy", new Command("news", "What's new on BlackForgeMC", "/news", List.of("updates", "changelog", "whatsnew")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (a.length >= 2 && a[0].equalsIgnoreCase("add") && s.hasPermission("alloy.admin")) { add(s, Arrays.copyOfRange(a, 1, a.length)); return true; }
                show(s);
                if (s instanceof Player p) markSeen(p, plugin);
                return true;
            }
        });
    }

    private void seed() {
        String d = LocalDate.now().toString();
        List<Map<String, Object>> l = new ArrayList<>();
        for (String[] e : new String[][]{
                {"all", "Friends and parties: /friend and /party work across every server - parties follow their leader and queue for the Arcade together."},
                {"all", "Achievements: 41 goals across the network, each paying money and Scrap. /achievements"},
                {"all", "Event calendar: /events shows what's on. Double Scrap every Friday evening and Sunday afternoon."},
                {"hub", "Daily calendar: /calendar pays out every day you log in, with a big reward on day 7."},
                {"survival", "Rimelock, Ashen Chain, Ashen Bonfire, God's Eye and the EMP now work in the PvP zones - and drop from crates."},
                {"all", "Every custom item now has its own texture. /guide shows them all and where to get them."},
                {"all", "Milestones: every hour you play pays out. /milestones"}})
            l.add(Map.of("date", d, "server", e[0], "text", e[1]));
        YamlConfiguration y = new YamlConfiguration();
        y.set("news", l);
        try { y.save(file); } catch (Exception ignored) { }
    }

    public static List<Item> all() {
        if (file == null) return List.of();
        List<Item> out = new ArrayList<>();
        for (Map<?, ?> m : YamlConfiguration.loadConfiguration(file).getMapList("news"))
            out.add(new Item(String.valueOf(m.get("date")), String.valueOf(m.get("server")), String.valueOf(m.get("text"))));
        return out;
    }

    /** The newest line for one server (or for everyone), or null. */
    public static Item latestFor(String server) {
        for (Item i : all()) if (i.server.equals(server) || i.server.equals("all")) return i;
        return null;
    }

    /** How many entries {@code p} hasn't seen yet (by count, kept in their journey file). */
    public static int unseen(UUID id, File shared) {
        int seen = YamlConfiguration.loadConfiguration(new File(shared, "news-seen.yml")).getInt(id.toString());
        return Math.max(0, all().size() - seen);
    }

    static void markSeen(Player p, AlloyCore plugin) {
        Shared.update(new File(file.getParentFile(), "news-seen.yml"), y -> y.set(p.getUniqueId().toString(), all().size()));
    }

    private void show(CommandSender s) {
        List<Item> items = all();
        s.sendMessage(Ui.mm("<gold>✦ <white><bold>What's new</bold> <gray>on " + Ui.TITLE));
        for (int i = 0; i < Math.min(7, items.size()); i++) {
            Item it = items.get(i);
            s.sendMessage(Ui.mm("<dark_gray>" + it.date.substring(5) + " " + (it.server.equals("all") ? "" : "<gray>[" + Social.pretty(it.server) + "] ") + "<white>" + it.text));
        }
        if (items.isEmpty()) s.sendMessage(Ui.mm("<gray>Nothing yet."));
    }

    private void add(CommandSender s, String[] a) {
        String server = Arrays.asList(Net.SERVERS).contains(a[0].toLowerCase()) ? a[0].toLowerCase() : "all";
        String text = String.join(" ", server.equals("all") ? a : Arrays.copyOfRange(a, 1, a.length));
        Shared.update(file, y -> {
            List<Map<?, ?>> l = new ArrayList<>(y.getMapList("news"));
            l.addFirst(Map.of("date", LocalDate.now().toString(), "server", server, "text", text));
            y.set("news", l.subList(0, Math.min(50, l.size())));
        });
        community.postNews((server.equals("all") ? "" : "[" + Social.pretty(server) + "] ") + text);
        s.sendMessage(Ui.mm("<green>Added to /news" + (server.equals("all") ? "" : " for " + server) + "."));
    }
}
