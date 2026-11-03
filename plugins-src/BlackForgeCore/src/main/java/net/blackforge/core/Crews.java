package net.blackforge.core;

import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crews: a named group of up to 20 players, on every server. Members who aren't wearing a cape wear their crew's cape
 * in the crew's colour (turn it off in /settings), and the crew leaderboard adds up the members' Season Points.
 * <p>
 * Everything lives in shared/crews/crews.yml, changed under a lock, so servers never disagree about who is in what.
 */
final class Crews implements Listener {
    static final int MAX_MEMBERS = 20;

    private final BlackForgeCore plugin;
    private final Cosmetics cos;
    private final File file;

    /** Online players -> the cape id their crew gives them ("crew_RED"). Filled every few seconds so the cape code never reads a file. */
    private static final Map<UUID, String> CAPE = new ConcurrentHashMap<>();

    /** The crew cape this player wears when they have no cape of their own on, or null. */
    static String capeFor(Player p) { return CAPE.get(p.getUniqueId()); }

    static CosmeticFx.CapeDef capeDef(String color) {
        try {
            DyeColor c = DyeColor.valueOf(color);
            return new CosmeticFx.CapeDef(Material.valueOf(c.name() + "_WOOL"), Material.valueOf(c.name() + "_CONCRETE"), Material.valueOf(c.name() + "_GLAZED_TERRACOTTA"), false, null);
        } catch (IllegalArgumentException ex) { return null; }
    }

    Crews(BlackForgeCore plugin, Cosmetics cos, File shared) {
        this.plugin = plugin;
        this.cos = cos;
        this.file = new File(shared, "crews/crews.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 60L, 200L);
        Bukkit.getCommandMap().register("blackforge", new Command("crew", "Your crew", "/crew [create|invite|accept|leave|kick|color|disband|top|cape]", List.of("crews", "guild")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) run(p, a); else s.sendMessage("Players only.");
                return true;
            }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) {
                if (a.length == 1) return List.of("info", "create", "invite", "accept", "leave", "kick", "color", "disband", "top", "cape");
                if (a.length == 2 && a[0].equalsIgnoreCase("color")) return Arrays.stream(DyeColor.values()).map(c -> c.name().toLowerCase()).toList();
                if (a.length == 2 && (a[0].equalsIgnoreCase("invite") || a[0].equalsIgnoreCase("kick"))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                return List.of();
            }
        });
    }

    // ------------------------------------------------------------------ data

    private YamlConfiguration read() { return Shared.read(file); }

    private String crewOf(YamlConfiguration y, UUID u) { return y.getString("of." + u); }

    private static String keyOf(String name) { return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""); }

    /** Keep the cape cache current for the people here. */
    private void refresh() {
        YamlConfiguration y = read();
        Set<UUID> on = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            String k = crewOf(y, p.getUniqueId());
            if (k == null || !cos.setting(p.getUniqueId(), "crew-cape", true)) continue;
            String color = y.getString("crews." + k + ".color", "");
            if (capeDef(color) == null) continue;
            CAPE.put(p.getUniqueId(), "crew_" + color);
            on.add(p.getUniqueId());
        }
        CAPE.keySet().removeIf(u -> !on.contains(u));
    }

    private String nameOf(UUID u) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() == null ? "?" : op.getName();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            YamlConfiguration y = read();
            String inv = y.getString("invites." + p.getUniqueId());
            if (inv != null && y.contains("crews." + inv)) p.sendMessage(Ui.mm("<aqua>✦ <gray>You've been invited to the crew <white>" + y.getString("crews." + inv + ".name") + "<gray>. <yellow>/crew accept"));
            refresh();
        }, 100L);
    }

    // ------------------------------------------------------------------ commands

    void run(Player p, String[] a) {
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(p, a);
            case "invite" -> invite(p, a);
            case "accept" -> accept(p);
            case "leave" -> leave(p);
            case "kick" -> kick(p, a);
            case "color", "colour" -> color(p, a);
            case "disband" -> disband(p);
            case "top" -> top(p);
            case "cape" -> {
                boolean now = !cos.setting(p.getUniqueId(), "crew-cape", true);
                cos.setSetting(p.getUniqueId(), "crew-cape", now);
                refresh();
                p.sendMessage(Ui.mm("<gray>Crew cape: " + (now ? "<green>on" : "<red>off")));
            }
            default -> info(p);
        }
    }

    private void info(Player p) {
        YamlConfiguration y = read();
        String k = crewOf(y, p.getUniqueId());
        if (k == null) {
            p.sendMessage(Ui.mm("<gray>You're not in a crew. <white>/crew create <name><gray> starts one, or ask a leader for an invite. <white>/crew top<gray> shows the best crews."));
            return;
        }
        String base = "crews." + k;
        List<String> members = y.getStringList(base + ".members");
        String leader = y.getString(base + ".leader", "");
        StringBuilder sb = new StringBuilder();
        for (String m : members) {
            String n = nameOf(UUID.fromString(m));
            boolean on = Bukkit.getPlayer(UUID.fromString(m)) != null;
            if (sb.length() > 0) sb.append("<gray>, ");
            sb.append(on ? "<green>" : "<gray>").append(n).append(m.equals(leader) ? "<gold>★" : "");
        }
        p.sendMessage(Ui.mm("<aqua>━ Crew <white><bold>" + y.getString(base + ".name") + "</bold> <aqua>━"));
        p.sendMessage(Ui.mm("<gray>Colour: <white>" + y.getString(base + ".color", "?").toLowerCase() + " <dark_gray>| <gray>Members: <white>" + members.size() + "/" + MAX_MEMBERS));
        p.sendMessage(Ui.mm(sb.toString()));
        p.sendMessage(Ui.mm("<gray>/crew invite <player>, color, kick, leave, cape, top"));
    }

    private void create(Player p, String[] a) {
        if (a.length < 2) { p.sendMessage(Ui.mm("<red>/crew create <name>")); return; }
        String name = String.join(" ", Arrays.copyOfRange(a, 1, a.length)).trim();
        String key = keyOf(name);
        if (!name.matches("[A-Za-z0-9 ]{3,14}") || key.length() < 3) { p.sendMessage(Ui.mm("<red>A crew name is 3-14 letters, numbers and spaces.")); return; }
        boolean[] ok = {false};
        String[] why = {""};
        Shared.update(file, y -> {
            if (y.contains("of." + p.getUniqueId())) { why[0] = "You're already in a crew. /crew leave first."; return; }
            if (y.contains("crews." + key)) { why[0] = "A crew with that name exists."; return; }
            y.set("crews." + key + ".name", name);
            y.set("crews." + key + ".color", DyeColor.values()[Math.abs(key.hashCode()) % DyeColor.values().length].name());
            y.set("crews." + key + ".leader", p.getUniqueId().toString());
            y.set("crews." + key + ".members", new ArrayList<>(List.of(p.getUniqueId().toString())));
            y.set("of." + p.getUniqueId(), key);
            ok[0] = true;
        });
        if (!ok[0]) { p.sendMessage(Ui.mm("<red>" + why[0])); return; }
        refresh();
        p.sendMessage(Ui.mm("<green>✔ <gray>Crew <white>" + name + "<gray> created. <white>/crew invite <player><gray> to add people, <white>/crew color<gray> to pick your cape colour."));
        Bukkit.broadcast(Ui.mm("<aqua>✦ <white>" + p.getName() + " <gray>started the crew <white>" + name + "<gray>."));
    }

    private void invite(Player p, String[] a) {
        if (a.length < 2) { p.sendMessage(Ui.mm("<red>/crew invite <player>")); return; }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[1]);
        if (t == null) { p.sendMessage(Ui.mm("<red>Never seen " + a[1] + ".")); return; }
        String[] msg = {null};
        Shared.update(file, y -> {
            String k = crewOf(y, p.getUniqueId());
            if (k == null) { msg[0] = "You're not in a crew."; return; }
            if (!p.getUniqueId().toString().equals(y.getString("crews." + k + ".leader"))) { msg[0] = "Only the leader invites."; return; }
            if (y.getStringList("crews." + k + ".members").size() >= MAX_MEMBERS) { msg[0] = "Your crew is full."; return; }
            if (y.contains("of." + t.getUniqueId())) { msg[0] = a[1] + " is already in a crew."; return; }
            y.set("invites." + t.getUniqueId(), k);
        });
        if (msg[0] != null) { p.sendMessage(Ui.mm("<red>" + msg[0])); return; }
        p.sendMessage(Ui.mm("<green>✔ <gray>Invited <white>" + t.getName() + "<gray>. They can join with <white>/crew accept<gray>."));
        Player online = t.getPlayer();
        if (online != null) online.sendMessage(Ui.mm("<aqua>✦ <white>" + p.getName() + " <gray>invited you to their crew. <yellow>/crew accept"));
        else cos.addNotice(t.getUniqueId(), "<aqua>✦ <gray>You've been invited to a crew. <yellow>/crew accept");
    }

    private void accept(Player p) {
        String[] result = {null, null};
        Shared.update(file, y -> {
            String k = y.getString("invites." + p.getUniqueId());
            if (k == null || !y.contains("crews." + k)) { result[0] = "You have no invite."; return; }
            if (y.contains("of." + p.getUniqueId())) { result[0] = "Leave your crew first."; return; }
            List<String> m = new ArrayList<>(y.getStringList("crews." + k + ".members"));
            if (m.size() >= MAX_MEMBERS) { result[0] = "That crew is full."; return; }
            m.add(p.getUniqueId().toString());
            y.set("crews." + k + ".members", m);
            y.set("of." + p.getUniqueId(), k);
            y.set("invites." + p.getUniqueId(), null);
            result[1] = y.getString("crews." + k + ".name");
        });
        if (result[0] != null) { p.sendMessage(Ui.mm("<red>" + result[0])); return; }
        refresh();
        p.sendMessage(Ui.mm("<green>✔ <gray>You joined <white>" + result[1] + "<gray>!"));
    }

    private void leave(Player p) {
        String[] r = {null};
        Shared.update(file, y -> {
            String k = crewOf(y, p.getUniqueId());
            if (k == null) { r[0] = "You're not in a crew."; return; }
            removeMember(y, k, p.getUniqueId());
        });
        if (r[0] != null) { p.sendMessage(Ui.mm("<red>" + r[0])); return; }
        refresh();
        p.sendMessage(Ui.mm("<gray>You left your crew."));
    }

    /** Inside a lock: take a member out, pass leadership on, or close an empty crew. */
    private void removeMember(YamlConfiguration y, String k, UUID u) {
        List<String> m = new ArrayList<>(y.getStringList("crews." + k + ".members"));
        m.remove(u.toString());
        y.set("of." + u, null);
        if (m.isEmpty()) { y.set("crews." + k, null); return; }
        y.set("crews." + k + ".members", m);
        if (u.toString().equals(y.getString("crews." + k + ".leader"))) y.set("crews." + k + ".leader", m.get(0));
    }

    private void kick(Player p, String[] a) {
        if (a.length < 2) { p.sendMessage(Ui.mm("<red>/crew kick <player>")); return; }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[1]);
        if (t == null) { p.sendMessage(Ui.mm("<red>Never seen " + a[1] + ".")); return; }
        String[] r = {null};
        Shared.update(file, y -> {
            String k = crewOf(y, p.getUniqueId());
            if (k == null || !p.getUniqueId().toString().equals(y.getString("crews." + k + ".leader"))) { r[0] = "Only a crew leader can kick."; return; }
            if (!k.equals(crewOf(y, t.getUniqueId())) || t.getUniqueId().equals(p.getUniqueId())) { r[0] = "That's not one of your members."; return; }
            removeMember(y, k, t.getUniqueId());
        });
        if (r[0] != null) { p.sendMessage(Ui.mm("<red>" + r[0])); return; }
        refresh();
        p.sendMessage(Ui.mm("<gray>Removed <white>" + t.getName() + "<gray> from the crew."));
    }

    private void color(Player p, String[] a) {
        if (a.length < 2) { p.sendMessage(Ui.mm("<gray>Colours: <white>" + String.join(", ", Arrays.stream(DyeColor.values()).map(c -> c.name().toLowerCase()).toList()))); return; }
        DyeColor c;
        try { c = DyeColor.valueOf(a[1].toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { p.sendMessage(Ui.mm("<red>Not a colour. Try red, blue, lime, purple...")); return; }
        String[] r = {null};
        Shared.update(file, y -> {
            String k = crewOf(y, p.getUniqueId());
            if (k == null || !p.getUniqueId().toString().equals(y.getString("crews." + k + ".leader"))) { r[0] = "Only a crew leader picks the colour."; return; }
            y.set("crews." + k + ".color", c.name());
        });
        if (r[0] != null) { p.sendMessage(Ui.mm("<red>" + r[0])); return; }
        refresh();
        p.sendMessage(Ui.mm("<green>✔ <gray>Crew colour is now <white>" + c.name().toLowerCase() + "<gray>. It shows on everybody's cape within a few seconds."));
    }

    private void disband(Player p) {
        String[] r = {null};
        Shared.update(file, y -> {
            String k = crewOf(y, p.getUniqueId());
            if (k == null || !p.getUniqueId().toString().equals(y.getString("crews." + k + ".leader"))) { r[0] = "Only a crew leader can disband it."; return; }
            for (String m : y.getStringList("crews." + k + ".members")) y.set("of." + m, null);
            y.set("crews." + k, null);
        });
        if (r[0] != null) { p.sendMessage(Ui.mm("<red>" + r[0])); return; }
        refresh();
        p.sendMessage(Ui.mm("<gray>Your crew is disbanded."));
    }

    /** Crews ranked by the Season Points their members have earned in the running season. */
    private void top(Player p) {
        List<Cosmetics.Season> live = cos.liveSeasons().stream().filter(s -> !s.grantOnly()).toList();
        if (live.isEmpty()) { p.sendMessage(Ui.mm("<gray>The crew leaderboard runs with each season - none is on right now.")); return; }
        Cosmetics.Season s = live.get(0);
        Map<UUID, Integer> pts = new HashMap<>();
        for (SeasonBoard.Entry e : cos.board().top(s, Integer.MAX_VALUE)) pts.put(e.id(), e.points());
        YamlConfiguration y = read();
        ConfigurationSection sec = y.getConfigurationSection("crews");
        record Row(String name, int points, int size) {}
        List<Row> rows = new ArrayList<>();
        if (sec != null) for (String k : sec.getKeys(false)) {
            int sum = 0;
            List<String> m = y.getStringList("crews." + k + ".members");
            for (String id : m) try { sum += pts.getOrDefault(UUID.fromString(id), 0); } catch (IllegalArgumentException ignored) { }
            rows.add(new Row(y.getString("crews." + k + ".name", k), sum, m.size()));
        }
        rows.sort(Comparator.comparingInt(Row::points).reversed());
        p.sendMessage(Ui.mm("<aqua>━ Top crews - " + s.label() + " <aqua>━"));
        if (rows.isEmpty()) p.sendMessage(Ui.mm("<gray>No crews yet. <white>/crew create <name>"));
        for (int i = 0; i < Math.min(10, rows.size()); i++) p.sendMessage(Ui.mm("<gold>#" + (i + 1) + " <white>" + rows.get(i).name + " <gray>- " + rows.get(i).points + " points <dark_gray>(" + rows.get(i).size + " members)"));
    }
}
