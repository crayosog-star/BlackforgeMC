package net.alloymc.core.arcade.core;

import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * /arcade - players: menu, join, leave, games, maps, top, stats, spectate.
 * Staff (arcade.admin or the specific node): map creation/editing/validation, reload, forcestart,
 * forcemap, stop, status. Map positions are taken from where the staff member stands, relative to the
 * map's template (or, if they're inside a running instance, that instance).
 */
public final class ArcadeCommand {
    private final Arcade arcade;
    private static final String P = MatchManager.TAG;

    ArcadeCommand(Arcade arcade) { this.arcade = arcade; }

    private static boolean has(CommandSender s, String perm) { return s.hasPermission(perm) || s.hasPermission("arcade.admin"); }
    private static void msg(CommandSender s, String mm) { s.sendMessage(Ui.mm(mm)); }

    public void run(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        Player p = s instanceof Player pl ? pl : null;
        try {
            switch (sub) {
                case "" -> { if (p != null) arcade.menu().openMain(p, null, 0); else help(s); }
                case "help" -> help(s);
                case "join", "play" -> join(s, p, a);
                case "leave", "quit" -> { if (p != null) arcade.matches().leave(p); }
                case "rematch" -> { if (p != null) arcade.matches().acceptRematch(p); }
                case "games" -> games(s);
                case "maps" -> maps(s, a);
                case "top", "leaderboard", "lb" -> { ArcadeGame g = a.length > 1 ? arcade.game(a[1]) : null; top(s, g, a.length > 2 ? a[2] : null); }
                case "stats" -> stats(s, p, a);
                case "spectate", "spec" -> spectate(p, a);
                case "map", "mapinfo" -> mapInfo(s, a);
                case "status" -> { if (need(s, "arcade.match.manage")) status(s); }
                case "reload" -> { if (need(s, "arcade.map.reload")) { arcade.reload(); msg(s, P + "<green>Arcade config, game settings and maps reloaded."); } }
                case "forcestart" -> { if (need(s, "arcade.match.manage")) forceStart(s, a); }
                case "forcemap" -> { if (need(s, "arcade.match.manage")) forceMap(s, a); }
                case "stop" -> { if (need(s, "arcade.match.manage")) stop(s, a); }
                case "create-map", "createmap" -> { if (need(s, "arcade.map.create")) createMap(s, p, a); }
                case "validate-map", "validate" -> { if (need(s, "arcade.map.edit")) validate(s, a); }
                case "edit", "tp" -> { if (need(s, "arcade.map.edit")) edit(p, a); }
                case "enable", "disable" -> { if (need(s, "arcade.map.edit")) toggle(s, a, sub.equals("enable")); }
                case "setspawn", "setteamspawn", "setlobby", "setspectator", "setboundary", "setpoint", "addpoint", "clearpoints", "setregion", "setvoid", "set", "refresh-template" -> {
                    if (need(s, "arcade.map.edit")) edit(s, p, sub, a);
                }
                default -> help(s);
            }
        } catch (NumberFormatException e) {
            msg(s, P + "<red>That's not a number: " + e.getMessage());
        }
    }

    private boolean need(CommandSender s, String perm) {
        if (has(s, perm)) return true;
        msg(s, P + "<red>You don't have permission (" + perm + ").");
        return false;
    }

    private void help(CommandSender s) {
        msg(s, "<aqua><bold>BlackForge Arcade</bold> <gray>commands");
        msg(s, "<white>/arcade <gray>menu • <white>/arcade join <game> [mode] <gray>• <white>/arcade leave <gray>• <white>/arcade games");
        msg(s, "<white>/arcade maps <game> <gray>• <white>/arcade top <game> [stat] <gray>• <white>/arcade stats [player] <gray>• <white>/arcade spectate <player>");
        if (has(s, "arcade.map.edit") || has(s, "arcade.match.manage")) {
            msg(s, "<gold>Staff: <white>status, reload, forcestart <game> [mode], forcemap <game> <map>, stop <match>");
            msg(s, "<gold>Maps: <white>create-map <game> <map>, edit <game> <map>, mapinfo <game> <map>, validate-map <game> <map>, enable|disable <game> <map>");
            msg(s, "<gold>Points: <white>setspawn <g> <m> [n|add], setteamspawn <g> <m> <team> [key], setlobby, setspectator, setboundary <g> <m> pos1|pos2,");
            msg(s, "<white>  setpoint <g> <m> <key>, addpoint <g> <m> <list>, clearpoints <g> <m> <list>, setregion <g> <m> <key> pos1|pos2, setvoid <g> <m>,");
            msg(s, "<white>  set <g> <m> <display-name|description|enabled|weight|min-players|max-players|teams|author> <value>, refresh-template <g> <m>");
        }
    }

    // ------------------------------------------------------------------ players

    private void join(CommandSender s, Player p, String[] a) {
        if (p == null) return;
        if (a.length < 2) { arcade.menu().openMain(p, null, 0); return; }
        ArcadeGame g = arcade.game(a[1]);
        if (g == null) { msg(s, P + "<red>Unknown game. <white>/arcade games"); return; }
        Variant v = a.length > 2 ? g.variant(a[2]) : g.variants().getFirst();
        if (v == null) { msg(s, P + "<red>" + g.name + " modes: " + String.join(", ", g.variants().stream().map(Variant::id).toList())); return; }
        arcade.matches().join(p, g, v);
    }

    private void games(CommandSender s) {
        msg(s, "<aqua><bold>Arcade games");
        for (ArcadeGame g : arcade.games()) {
            long ok = arcade.maps().pool(g.id).stream().filter(m -> m.enabled && arcade.isValid(m)).count();
            msg(s, g.color + "■ <white>" + g.name + " <dark_gray>(" + g.id + ") <gray>" + g.category.label + " • modes: "
                    + String.join(", ", g.variants().stream().map(Variant::id).toList()) + " • maps: " + ok + "/" + arcade.maps().pool(g.id).size()
                    + " • playing: " + arcade.matches().playing(g));
        }
    }

    private void maps(CommandSender s, String[] a) {
        ArcadeGame g = a.length > 1 ? arcade.game(a[1]) : null;
        if (g == null) { msg(s, P + "<gray>/arcade maps <game>"); return; }
        msg(s, g.color + "<bold>" + g.name + " maps <gray>(picked at random each match; recent: " + arcade.matches().selector().recent(g.id) + ")");
        for (MapDef m : arcade.maps().pool(g.id)) {
            List<String> err = MapDef.errors(arcade.problems(m));
            String st = !m.enabled ? "<dark_gray>disabled" : !arcade.templateReady(m) ? "<yellow>template building" : err.isEmpty() ? "<green>ready" : "<red>invalid (" + err.size() + " problems)";
            msg(s, "<white>" + m.id + " <gray>\"" + m.display + "\" " + m.minPlayers + "-" + m.maxPlayers + "p" + (m.teams > 0 ? ", " + m.teams + " teams" : "")
                    + (m.variants.isEmpty() ? "" : ", modes " + m.variants) + ", weight " + m.weight + " - " + st);
        }
    }

    public void top(CommandSender s, ArcadeGame g, String stat) {
        String st = stat == null ? "wins" : stat.toLowerCase();
        boolean asc = st.equals("best_time");
        List<StatsService.Row> rows = arcade.stats().top(g == null ? null : g.id, st, 10, asc);
        msg(s, "<aqua><bold>Top " + st.replace('_', ' ') + "</bold> <gray>- " + (g == null ? "all games" : g.color + g.name));
        if (rows.isEmpty()) msg(s, "<gray>No records yet - go set one!");
        for (int i = 0; i < rows.size(); i++) {
            StatsService.Row r = rows.get(i);
            String v = st.equals("best_time") ? String.format("%.2fs", r.value() / 1000.0) : String.valueOf(r.value());
            msg(s, (i < 3 ? new String[]{"<#ffd700>", "<#c0c0c0>", "<#cd7f32>"}[i] : "<yellow>") + "#" + (i + 1) + " <white>" + r.name() + " <dark_gray>- <aqua>" + v);
        }
    }

    private void stats(CommandSender s, Player p, String[] a) {
        UUID u;
        String name;
        if (a.length > 1) { var op = Bukkit.getOfflinePlayerIfCached(a[1]); if (op == null) { msg(s, P + "<red>Unknown player."); return; } u = op.getUniqueId(); name = op.getName(); }
        else if (p != null) { u = p.getUniqueId(); name = p.getName(); }
        else return;
        StatsService st = arcade.stats();
        msg(s, "<aqua><bold>" + name + "'s arcade stats <gray>(wins " + st.total(u, "wins") + ", played " + st.total(u, "played") + ")");
        for (ArcadeGame g : arcade.games()) {
            if (st.get(u, g.id, "played") == 0) continue;
            msg(s, g.color + g.name + " <gray>played " + st.get(u, g.id, "played") + ", wins " + st.get(u, g.id, "wins") + ", kills " + st.get(u, g.id, "kills")
                    + ", deaths " + st.get(u, g.id, "deaths") + (st.has(u, g.id, "best_time") ? String.format(", best %.2fs", st.get(u, g.id, "best_time") / 1000.0) : ""));
        }
    }

    private void spectate(Player p, String[] a) {
        if (p == null || a.length < 2) return;
        Player t = Bukkit.getPlayerExact(a[1]);
        Match m = t == null ? null : arcade.matches().matchOf(t);
        if (m == null || !m.state().live()) { msg(p, P + "<red>That player isn't in a running match."); return; }
        if (arcade.matches().matchOf(p) != null) { msg(p, P + "<red>Leave your own match first."); return; }
        arcade.matches().spectate(p, m);
    }

    // ------------------------------------------------------------------ staff: matches

    private void status(CommandSender s) {
        MatchManager mm = arcade.matches();
        msg(s, "<aqua><bold>Arcade status</bold> <gray>- " + mm.active().size() + " matches, slots " + mm.slots().used() + "/" + mm.slots().max());
        for (Match m : mm.active()) msg(s, "<white>" + m.summary() + " <dark_gray>(slot " + m.slot + ", map " + m.map.id + ")");
        for (ArcadeGame g : arcade.games()) for (Variant v : g.variants()) {
            MatchManager.Queue q = mm.queue(g, v);
            if (q.size() > 0) msg(s, "<gray>queue " + g.id + ":" + v.id() + " = " + q.size() + (q.countdown() >= 0 ? " (starting in " + q.countdown() + "s)" : ""));
        }
    }

    private void forceStart(CommandSender s, String[] a) {
        ArcadeGame g = a.length > 1 ? arcade.game(a[1]) : null;
        if (g == null) { msg(s, P + "<gray>/arcade forcestart <game> [mode]"); return; }
        Variant v = a.length > 2 ? g.variant(a[2]) : g.variants().getFirst();
        if (v == null) { msg(s, P + "<red>Unknown mode."); return; }
        MatchManager.Queue q = arcade.matches().queue(g, v);
        if (q.size() == 0) { msg(s, P + "<red>Nobody is queued for " + g.name + " " + v.label() + "."); return; }
        arcade.matches().forceStart(q);
        msg(s, P + "<green>Starting " + g.name + " " + v.label() + " with " + q.size() + " players.");
    }

    private void forceMap(CommandSender s, String[] a) {
        if (a.length < 3) { msg(s, P + "<gray>/arcade forcemap <game> <map|off>"); return; }
        ArcadeGame g = arcade.game(a[1]);
        if (g == null) { msg(s, P + "<red>Unknown game."); return; }
        if (a[2].equalsIgnoreCase("off")) { arcade.matches().force(g.id, null); msg(s, P + "<gray>Random selection restored."); return; }
        MapDef m = arcade.maps().get(g.id, a[2]);
        if (m == null || !arcade.isValid(m)) { msg(s, P + "<red>No playable map '" + a[2] + "' for " + g.name + "."); return; }
        arcade.matches().force(g.id, m.id);
        msg(s, P + "<green>The next " + g.name + " match will use " + m.display + " (staff override; normally random).");
    }

    private void stop(CommandSender s, String[] a) {
        if (a.length < 2) { msg(s, P + "<gray>/arcade stop <match id|all>"); return; }
        List<Match> targets = a[1].equalsIgnoreCase("all") ? new ArrayList<>(arcade.matches().active()) : Optional.ofNullable(arcade.matches().get(a[1].toUpperCase())).map(List::of).orElse(List.of());
        if (targets.isEmpty()) { msg(s, P + "<red>No such match. <white>/arcade status"); return; }
        for (Match m : targets) arcade.matches().abort(m, "<red>This match was stopped by staff.");
        msg(s, P + "<green>Stopped " + targets.size() + " match(es).");
    }

    // ------------------------------------------------------------------ staff: maps

    private MapDef mapArg(CommandSender s, String[] a) {
        if (a.length < 3) { msg(s, P + "<red>Need <game> <map>."); return null; }
        MapDef m = arcade.maps().get(a[1].toLowerCase(), a[2].toLowerCase());
        if (m == null) msg(s, P + "<red>No map " + a[1] + "/" + a[2] + ". <white>/arcade maps " + a[1]);
        return m;
    }

    private void mapInfo(CommandSender s, String[] a) {
        MapDef m = mapArg(s, a);
        if (m == null) return;
        msg(s, "<aqua><bold>" + m.game + "/" + m.id + "</bold> <white>\"" + m.display + "\" <gray>by " + m.author);
        msg(s, "<gray>" + m.description);
        msg(s, "<gray>enabled " + m.enabled + ", weight " + m.weight + ", players " + m.minPlayers + "-" + m.maxPlayers + (m.teams > 0 ? ", teams " + m.teams : "") + (m.variants.isEmpty() ? "" : ", modes " + m.variants));
        msg(s, "<gray>bounds " + m.bounds + ", void y " + m.voidY + ", spectator " + m.spectator + (m.waiting != null ? ", lobby " + m.waiting : ""));
        msg(s, "<gray>template " + m.templateX + ", " + m.templateY + ", " + m.templateZ + " (" + m.builder + " v" + m.builderVersion + "), reset: " + m.reset);
        msg(s, "<gray>points: <white>" + String.join(", ", m.points.keySet()));
        for (var e : m.lists.entrySet()) msg(s, "<gray>list " + e.getKey() + ": <white>" + e.getValue().size());
        if (!m.regions.isEmpty()) msg(s, "<gray>regions: <white>" + String.join(", ", m.regions.keySet()));
        if (!m.settings.isEmpty()) msg(s, "<gray>settings: <white>" + m.settings);
        for (Match x : arcade.matches().active()) if (x.map == m) msg(s, "<gold>in use by " + x.id);
        List<String> pr = arcade.problems(m);
        msg(s, pr.isEmpty() ? "<green>Valid." : "<red>Problems: <white>" + String.join("; ", pr));
    }

    private void validate(CommandSender s, String[] a) {
        MapDef m = mapArg(s, a);
        if (m == null) return;
        ArcadeGame g = arcade.game(m.game);
        List<String> pr = m.validate(g.requirements());
        arcade.revalidate();
        if (pr.isEmpty()) { msg(s, P + "<green>" + m + " is valid" + (arcade.templateReady(m) ? "." : " (template not built yet).")); return; }
        msg(s, P + (MapDef.errors(pr).isEmpty() ? "<yellow>" + m + " is valid with warnings:" : "<red>" + m + " has problems:"));
        for (String x : pr) msg(s, (x.startsWith("warning:") ? "<yellow> • " : "<red> • ") + x);
        msg(s, "<gray>Needs: " + String.join(", ", g.requirements().describe()));
    }

    private void toggle(CommandSender s, String[] a, boolean on) {
        MapDef m = mapArg(s, a);
        if (m == null) return;
        if (on) {
            List<String> err = MapDef.errors(m.validate(arcade.game(m.game).requirements()));
            if (!err.isEmpty()) { msg(s, P + "<red>Fix these before enabling: " + String.join("; ", err)); return; }
        }
        m.enabled = on;
        save(s, m);
        msg(s, P + (on ? "<green>Enabled " : "<yellow>Disabled ") + m + ". Matches already running on it are not affected.");
    }

    private void createMap(CommandSender s, Player p, String[] a) {
        if (a.length < 3) { msg(s, P + "<gray>/arcade create-map <game> <map-id>"); return; }
        ArcadeGame g = arcade.game(a[1]);
        if (g == null) { msg(s, P + "<red>Unknown game."); return; }
        String id = a[2].toLowerCase().replaceAll("[^a-z0-9_-]", "");
        if (id.isEmpty() || arcade.maps().get(g.id, id) != null) { msg(s, P + "<red>Pick a new map id (a-z, 0-9, _ -)."); return; }
        MapDef m = new MapDef(g.id, id);
        m.display = a[2];
        m.enabled = false;
        m.author = p != null ? p.getName() : "staff";
        m.builder = "custom";
        // custom maps live on their own row of the template world, away from built-in templates
        int maxX = 0;
        for (String gid : arcade.maps().games()) for (MapDef x : arcade.maps().pool(gid)) if (x.templateZ == 20000) maxX = Math.max(maxX, x.templateX + 1100);
        m.templateX = maxX; m.templateY = 100; m.templateZ = 20000;
        m.bounds = Box.of(-50, -30, -50, 50, 60, 50);
        m.spectator = Pt.of(0.5, 20, 0.5);
        Variant first = g.variants().getFirst();
        m.minPlayers = first.minPlayers(); m.maxPlayers = first.maxPlayers();
        save(s, m);
        arcade.markTemplateReady(m);
        // a small starter platform so the builder has something to stand on
        var w = arcade.worlds().templates();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) w.getBlockAt(m.templateX + x, m.templateY - 1, m.templateZ + z).setType(Material.POLISHED_DEEPSLATE);
        msg(s, P + "<green>Created " + m + " (disabled). Build it in the template world, then set its points and run <white>/arcade validate-map " + g.id + " " + id);
        msg(s, "<gray>Needs: " + String.join(", ", g.requirements().describe()));
        if (p != null) edit(p, new String[]{"edit", g.id, id});
    }

    private void edit(Player p, String[] a) {
        if (p == null) return;
        MapDef m = mapArg(p, a);
        if (m == null) return;
        p.teleport(new Location(arcade.worlds().templates(), m.templateX + 0.5, m.templateY + (m.spectator != null ? m.spectator.y() : 5), m.templateZ + 0.5));
        p.setGameMode(GameMode.CREATIVE);
        msg(p, P + "<gray>Editing the template of <white>" + m + "<gray>. Changes affect new matches after <white>/arcade refresh-template " + m.game + " " + m.id);
    }

    /** Position of the sender relative to the map origin (template or the running instance they're in). */
    private Pt rel(Player p, MapDef m) {
        Location l = p.getLocation();
        if (l.getWorld() == arcade.worlds().templates())
            return new Pt(round(l.getX() - m.templateX), round(l.getY() - m.templateY), round(l.getZ() - m.templateZ), Math.round(l.getYaw()), Math.round(l.getPitch()));
        if (l.getWorld() == arcade.worlds().matches()) {
            int slot = SlotAllocator.slotAt(l.getX());
            return new Pt(round(l.getX() - SlotAllocator.originX(slot)), round(l.getY() - m.templateY), round(l.getZ()), Math.round(l.getYaw()), Math.round(l.getPitch()));
        }
        return null;
    }

    private static double round(double d) { return Math.round(d * 2) / 2.0; }

    private void edit(CommandSender s, Player p, String sub, String[] a) {
        MapDef m = mapArg(s, a);
        if (m == null) return;
        if (sub.equals("set")) {
            if (a.length < 5) { msg(s, P + "<gray>/arcade set <game> <map> <field> <value>"); return; }
            String v = String.join(" ", Arrays.copyOfRange(a, 4, a.length));
            switch (a[3].toLowerCase()) {
                case "display-name", "name" -> m.display = v;
                case "description" -> m.description = v;
                case "author" -> m.author = v;
                case "weight" -> m.weight = Integer.parseInt(v);
                case "min-players" -> m.minPlayers = Integer.parseInt(v);
                case "max-players" -> m.maxPlayers = Integer.parseInt(v);
                case "teams" -> m.teams = Integer.parseInt(v);
                case "enabled" -> { toggle(s, a, Boolean.parseBoolean(v)); return; }
                default -> { m.settings.put(a[3], v.matches("-?\\d+") ? Integer.valueOf(v) : v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") ? Boolean.valueOf(v) : v); }
            }
            save(s, m);
            msg(s, P + "<green>" + m + " " + a[3] + " = " + v);
            return;
        }
        if (sub.equals("refresh-template")) { arcade.worlds().invalidate(m); arcade.markTemplateReady(m); msg(s, P + "<green>New matches on " + m + " will use the current template."); return; }
        if (p == null) { msg(s, P + "<red>Stand where you want the point and run this in-game."); return; }
        Pt here = rel(p, m);
        if (here == null) { msg(s, P + "<red>Stand in the map's template (/arcade edit " + m.game + " " + m.id + ")."); return; }
        String done;
        switch (sub) {
            case "setspawn" -> {
                List<Pt> l = m.lists.computeIfAbsent("spawns", k -> new ArrayList<>());
                if (a.length > 3 && !a[3].equalsIgnoreCase("add")) {
                    int i = Integer.parseInt(a[3]);
                    while (l.size() <= i) l.add(here);
                    l.set(i, here);
                    done = "spawn #" + i;
                } else { l.add(here); done = "spawn #" + (l.size() - 1); }
            }
            case "setteamspawn" -> {
                if (a.length < 4) { msg(s, P + "<gray>/arcade setteamspawn <game> <map> <team#> [key]"); return; }
                int t = Integer.parseInt(a[3]);
                String key = a.length > 4 ? a[4] : "spawn";
                m.points.put("team." + t + "." + key, here);
                m.teams = Math.max(m.teams, t + 1);
                done = "team." + t + "." + key + " (" + TeamColor.of(t).label + ")";
            }
            case "setlobby" -> { m.waiting = here; done = "waiting spawn"; }
            case "setspectator" -> { m.spectator = here; done = "spectator spawn"; }
            case "setvoid" -> { m.voidY = (int) Math.floor(here.y()); done = "void level " + m.voidY; }
            case "setboundary" -> {
                boolean first = a.length < 4 || a[3].equalsIgnoreCase("pos1");
                Box b = m.bounds == null ? Box.of((int) here.x(), (int) here.y(), (int) here.z(), (int) here.x(), (int) here.y(), (int) here.z()) : m.bounds;
                int x = (int) Math.floor(here.x()), y = (int) Math.floor(here.y()), z = (int) Math.floor(here.z());
                m.bounds = first ? new Box(x, y, z, pending(b, true)[0], pending(b, true)[1], pending(b, true)[2]) : new Box(b.x1(), b.y1(), b.z1(), x, y, z);
                done = "boundary " + (first ? "pos1" : "pos2") + " -> " + m.bounds;
            }
            case "setpoint" -> { if (a.length < 4) { msg(s, P + "<gray>/arcade setpoint <game> <map> <key>"); return; } m.points.put(a[3], here); done = "point " + a[3]; }
            case "addpoint" -> { if (a.length < 4) { msg(s, P + "<gray>/arcade addpoint <game> <map> <list>"); return; } m.lists.computeIfAbsent(a[3], k -> new ArrayList<>()).add(here); done = a[3] + " #" + (m.lists.get(a[3]).size() - 1); }
            case "clearpoints" -> { if (a.length < 4) return; m.lists.remove(a[3]); done = "cleared list " + a[3]; }
            case "setregion" -> {
                if (a.length < 5) { msg(s, P + "<gray>/arcade setregion <game> <map> <key> pos1|pos2"); return; }
                int x = (int) Math.floor(here.x()), y = (int) Math.floor(here.y()), z = (int) Math.floor(here.z());
                Box b = m.regions.get(a[3]);
                m.regions.put(a[3], a[4].equalsIgnoreCase("pos1") || b == null ? Box.of(x, y, z, b == null ? x : b.x2(), b == null ? y : b.y2(), b == null ? z : b.z2()) : Box.of(b.x1(), b.y1(), b.z1(), x, y, z));
                done = "region " + a[3] + " -> " + m.regions.get(a[3]);
            }
            default -> { return; }
        }
        save(s, m);
        arcade.revalidate();
        msg(s, P + "<green>Set " + done + " <gray>at " + here + " <dark_gray>(" + m + ")");
        List<String> err = MapDef.errors(arcade.problems(m));
        if (!err.isEmpty()) msg(s, "<yellow>Still needed: <gray>" + String.join("; ", err.subList(0, Math.min(4, err.size()))) + (err.size() > 4 ? " ..." : ""));
    }

    private static int[] pending(Box b, boolean ignored) { return new int[]{b.x2(), b.y2(), b.z2()}; }

    private void save(CommandSender s, MapDef m) {
        try { arcade.maps().save(m); arcade.maps().put(m); arcade.revalidate(); }
        catch (Exception e) { msg(s, P + "<red>Could not save " + m + ": " + e.getMessage()); }
    }
}
