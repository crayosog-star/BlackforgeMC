package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.*;

/**
 * Friends and parties across the whole network, on every server.
 * <ul>
 *   <li><b>Presence</b>: shared/presence/&lt;uuid&gt;.yml says which server each player is on (refreshed every minute).
 *       A quit followed by a join elsewhere within a few seconds is a server switch, not a network leave.</li>
 *   <li><b>/friend</b> add | accept | deny | remove | list: friends are told when you join or leave the network.</li>
 *   <li><b>/party</b> invite | accept | leave | kick | disband | warp | list, and /pc to chat. Members follow the
 *       leader from server to server, and when the leader queues for an Arcade game the party queues with them.</li>
 * </ul>
 * Lists live in shared/friends/&lt;uuid&gt;.yml and shared/parties.yml, always changed under {@link Shared#update}.
 * Players on other servers are reached through the proxy ({@link Net#messageRaw}, {@link Net#connectOther}).
 */
public final class Social implements Listener {
    static final int MAX_FRIENDS = 50, MAX_PARTY = 8;
    static final long SWITCH_MS = 15_000, STALE_MS = 150_000, INVITE_MS = 120_000;

    private final AlloyCore plugin;
    private final String server;
    private final File presence, friends, parties;

    public Social(AlloyCore plugin, String mode, File shared) {
        this.plugin = plugin;
        this.server = mode;
        this.presence = new File(shared, "presence");
        this.friends = new File(shared, "friends");
        this.parties = new File(shared, "parties.yml");
        presence.mkdirs();
        friends.mkdirs();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { for (Player p : Bukkit.getOnlinePlayers()) here(p); }, 1200L, 1200L);
        cmd("friend", List.of("friends", "fr"), this::friend);
        cmd("party", List.of("p"), this::party);
        cmd("go", List.of("goto"), (s, a) -> {   // clickable [Join]/[Go] links everywhere: /go <server>
            if (!(s instanceof Player p) || a.length == 0) return;
            String to = a[0].toLowerCase(Locale.ROOT);
            if (!Arrays.asList(Net.SERVERS).contains(to)) { p.sendMessage(Ui.mm("<red>Unknown server. Try: " + String.join(", ", Net.SERVERS))); return; }
            if (to.equals(server)) { p.sendMessage(Ui.mm("<gray>You're already on " + pretty(to) + ".")); return; }
            p.sendActionBar(Ui.mm("<gold>Sending you to <white>" + pretty(to) + "<gold>..."));
            plugin.net().send(p, to);
        });
        cmd("pc", List.of("partychat"), (s, a) -> { if (s instanceof Player p) partyChat(p, String.join(" ", a)); });
        // the party queues together: whoever leads queues everyone else on this server into the same game
        Hooks.onQueued((p, sameQueue) -> {
            String leader = partyOf(p.getUniqueId());
            if (leader == null || !leader.equals(p.getUniqueId().toString())) return;
            for (UUID m : members(leader)) {
                Player mp = Bukkit.getPlayer(m);
                if (mp != null && mp != p) sameQueue.accept(mp);
            }
        });
    }

    // ------------------------------------------------------------------ presence

    private File pfile(UUID id) { return new File(presence, id + ".yml"); }

    private void here(Player p) {
        YamlConfiguration y = Shared.read(pfile(p.getUniqueId()));
        y.set("name", p.getName());
        y.set("server", server);
        y.set("at", System.currentTimeMillis());
        try { y.save(pfile(p.getUniqueId())); } catch (Exception ignored) { }
    }

    /** The server a player is on right now, or null if they're offline. */
    public String serverOf(UUID id) {
        YamlConfiguration y = Shared.read(pfile(id));
        long at = y.getLong("at"), left = y.getLong("left-at");
        if (at == 0 || left >= at || System.currentTimeMillis() - at > STALE_MS) return null;
        return y.getString("server");
    }

    public String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Shared.read(pfile(id)).getString("name");
        if (n != null) return n;
        var off = Bukkit.getOfflinePlayer(id);
        return off.getName() == null ? "?" : off.getName();
    }

    /** Anyone who has joined since presence existed, by name (any case). */
    public UUID idOf(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p.getUniqueId();
        File[] fs = presence.listFiles((d, n) -> n.endsWith(".yml"));
        if (fs != null) for (File f : fs)
            if (name.equalsIgnoreCase(Shared.read(f).getString("name", ""))) return UUID.fromString(f.getName().substring(0, 36));
        return null;
    }

    /** Tell a player something wherever they are (dropped if they're offline). */
    public void tell(UUID to, String mm) {
        Player online = Bukkit.getPlayer(to);
        if (online != null) { online.sendMessage(Ui.mm(mm)); return; }
        Player via = Net.carrier();
        if (via != null && serverOf(to) != null) plugin.net().messageRaw(via, nameOf(to), Ui.mm(mm));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        YamlConfiguration y = Shared.read(pfile(p.getUniqueId()));
        long now = System.currentTimeMillis(), left = y.getLong("left-at"), at = y.getLong("at");
        // switching servers: the old one wrote left-at moments ago, or hasn't noticed the quit yet
        boolean switching = (left > 0 && now - left < SWITCH_MS) || (left < at && now - at < STALE_MS && !server.equals(y.getString("server")));
        here(p);
        if (!switching) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            for (UUID f : friendList(p.getUniqueId()))
                tell(f, "<#7bd88f>● <white>" + Nicks.of(p) + " <gray>joined <white>" + pretty(server) + " <dark_gray>(friend)");
            List<String> reqs = data(p.getUniqueId()).getStringList("requests");
            if (!reqs.isEmpty()) p.sendMessage(Ui.mm("<#7bd88f>✦ <gray>You have <white>" + reqs.size() + " <gray>friend request" + (reqs.size() == 1 ? "" : "s")
                    + ". <u><click:run_command:'/friend list'><#7bd88f>[View]</click></u>"));
        }, 40L);
        // the leader arrived somewhere new: bring the party along
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) pullParty(p, false); }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        String name = Nicks.of(p);
        long now = System.currentTimeMillis();
        YamlConfiguration y = Shared.read(pfile(id));
        if (!server.equals(y.getString("server"))) return;   // already on another server
        y.set("left-at", now);
        try { y.save(pfile(id)); } catch (Exception ignored) { }
        // still gone in a few seconds (not a switch): tell friends
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            YamlConfiguration z = Shared.read(pfile(id));
            if (z.getLong("at") > now || !server.equals(z.getString("server"))) return;
            for (UUID f : friendList(id)) tell(f, "<#e5484d>● <white>" + name + " <gray>left the network <dark_gray>(friend)");
        }, SWITCH_MS / 50);
    }

    static String pretty(String s) { return s == null ? "?" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    // ------------------------------------------------------------------ friends

    private File ffile(UUID id) { return new File(friends, id + ".yml"); }
    private YamlConfiguration data(UUID id) { return Shared.read(ffile(id)); }

    public List<UUID> friendList(UUID id) {
        List<UUID> out = new ArrayList<>();
        for (String s : data(id).getStringList("friends")) try { out.add(UUID.fromString(s)); } catch (Exception ignored) { }
        return out;
    }

    private static void addTo(YamlConfiguration y, String key, String v) {
        List<String> l = new ArrayList<>(y.getStringList(key));
        if (!l.contains(v)) l.add(v);
        y.set(key, l);
    }

    private static void removeFrom(YamlConfiguration y, String key, String v) {
        List<String> l = new ArrayList<>(y.getStringList(key));
        l.remove(v);
        y.set(key, l);
    }

    private void friend(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) { listFriends(p); return; }
        if (a.length < 2) {
            if (!List.of("add", "accept", "deny", "remove").contains(sub) && a.length == 1) { friend(s, new String[]{"add", a[0]}); return; }
            p.sendMessage(Ui.mm("<gray>Usage: <white>/friend add|accept|deny|remove <name><gray>, <white>/friend list"));
            return;
        }
        UUID me = p.getUniqueId(), them = idOf(a[1]);
        if (them == null) { p.sendMessage(Ui.mm("<red>No player called " + a[1] + " has been on the network.")); return; }
        if (them.equals(me)) { p.sendMessage(Ui.mm("<red>You're already your own best friend.")); return; }
        String tn = nameOf(them), mine = me.toString(), theirs = them.toString();
        switch (sub) {
            case "add" -> {
                if (friendList(me).contains(them)) { p.sendMessage(Ui.mm("<gray>" + tn + " is already your friend.")); return; }
                if (friendList(me).size() >= MAX_FRIENDS) { p.sendMessage(Ui.mm("<red>Your friend list is full (" + MAX_FRIENDS + ").")); return; }
                if (data(me).getStringList("requests").contains(theirs)) { friend(s, new String[]{"accept", a[1]}); return; }
                Shared.update(ffile(them), y -> addTo(y, "requests", mine));
                p.sendMessage(Ui.mm("<#7bd88f>✔ <gray>Friend request sent to <white>" + tn + "<gray>."));
                tell(them, "<#7bd88f>✦ <white>" + Nicks.of(p) + " <gray>wants to be friends. <u><click:run_command:'/friend accept " + p.getName()
                        + "'><#7bd88f>[Accept]</click></u> <u><click:run_command:'/friend deny " + p.getName() + "'><red>[Deny]</click></u>");
            }
            case "accept" -> {
                if (!data(me).getStringList("requests").contains(theirs)) { p.sendMessage(Ui.mm("<red>" + tn + " hasn't sent you a request.")); return; }
                Shared.update(ffile(me), y -> { removeFrom(y, "requests", theirs); addTo(y, "friends", theirs); });
                Shared.update(ffile(them), y -> { removeFrom(y, "requests", mine); addTo(y, "friends", mine); });
                p.sendMessage(Ui.mm("<#7bd88f>✔ <gray>You and <white>" + tn + " <gray>are now friends."));
                tell(them, "<#7bd88f>✔ <white>" + Nicks.of(p) + " <gray>accepted your friend request.");
                Hooks.statMax(p, "friends", friendList(me).size());
                Player tp = Bukkit.getPlayer(them);
                if (tp != null) Hooks.statMax(tp, "friends", friendList(them).size());
            }
            case "deny" -> {
                Shared.update(ffile(me), y -> removeFrom(y, "requests", theirs));
                p.sendMessage(Ui.mm("<gray>Request from " + tn + " removed."));
            }
            case "remove" -> {
                Shared.update(ffile(me), y -> removeFrom(y, "friends", theirs));
                Shared.update(ffile(them), y -> removeFrom(y, "friends", mine));
                p.sendMessage(Ui.mm("<gray>" + tn + " is no longer on your friend list."));
            }
            default -> p.sendMessage(Ui.mm("<gray>Usage: <white>/friend add|accept|deny|remove <name><gray>, <white>/friend list"));
        }
    }

    private void listFriends(Player p) {
        List<UUID> list = friendList(p.getUniqueId());
        p.sendMessage(Ui.mm("<#7bd88f>✦ <white>Friends <gray>(" + list.size() + "/" + MAX_FRIENDS + ")"));
        if (list.isEmpty()) p.sendMessage(Ui.mm("<gray>  None yet - <white>/friend add <name>"));
        list.sort(Comparator.comparing((UUID u) -> serverOf(u) == null).thenComparing(this::nameOf, String.CASE_INSENSITIVE_ORDER));
        for (UUID f : list) {
            String where = serverOf(f), n = nameOf(f);
            p.sendMessage(Ui.mm(where == null ? "<dark_gray>  ● <gray>" + n + " <dark_gray>offline"
                    : "<#7bd88f>  ● <white>" + n + " <gray>on " + pretty(where)
                    + (where.equals(server) ? "" : " <u><click:run_command:'/go " + where + "'><#4fd1c5>[Join]</click></u>")
                    + " <u><click:run_command:'/party invite " + n + "'><#ffb347>[Party]</click></u>"));
        }
        for (String r : data(p.getUniqueId()).getStringList("requests")) {
            String n = nameOf(UUID.fromString(r));
            p.sendMessage(Ui.mm("<yellow>  ? <white>" + n + " <gray>wants to be friends <u><click:run_command:'/friend accept " + n
                    + "'><#7bd88f>[Accept]</click></u> <u><click:run_command:'/friend deny " + n + "'><red>[Deny]</click></u>"));
        }
    }

    // ------------------------------------------------------------------ parties

    /** The leader uuid of the party {@code id} is in, or null. */
    public String partyOf(UUID id) { return Shared.read(parties).getString("of." + id); }

    public List<UUID> members(String leader) {
        List<UUID> out = new ArrayList<>();
        for (String s : Shared.read(parties).getStringList("party." + leader)) out.add(UUID.fromString(s));
        return out;
    }

    private void tellParty(String leader, String mm) { for (UUID m : members(leader)) tell(m, mm); }

    private void party(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        UUID me = p.getUniqueId();
        String mine = me.toString(), leader = partyOf(me);
        switch (sub) {
            case "invite", "add" -> {
                if (a.length < 2) { p.sendMessage(Ui.mm("<gray>Usage: <white>/party invite <name>")); return; }
                UUID them = idOf(a[1]);
                if (them == null || serverOf(them) == null) { p.sendMessage(Ui.mm("<red>" + a[1] + " isn't online.")); return; }
                if (them.equals(me)) return;
                if (leader != null && !leader.equals(mine)) { p.sendMessage(Ui.mm("<red>Only the party leader can invite.")); return; }
                if (partyOf(them) != null) { p.sendMessage(Ui.mm("<red>" + nameOf(them) + " is already in a party.")); return; }
                if (leader != null && members(leader).size() >= MAX_PARTY) { p.sendMessage(Ui.mm("<red>Your party is full (" + MAX_PARTY + ").")); return; }
                Shared.update(parties, y -> {
                    if (y.getString("of." + mine) == null) { y.set("of." + mine, mine); y.set("party." + mine, List.of(mine)); }
                    y.set("invite." + them, mine + "|" + System.currentTimeMillis());
                });
                p.sendMessage(Ui.mm("<#ffb347>✦ <gray>Invited <white>" + nameOf(them) + " <gray>to your party."));
                tell(them, "<#ffb347>✦ <white>" + Nicks.of(p) + " <gray>invited you to their party. <u><click:run_command:'/party accept'><#7bd88f>[Join party]</click></u>");
            }
            case "accept", "join" -> {
                String inv = Shared.read(parties).getString("invite." + me);
                if (inv == null || System.currentTimeMillis() - Long.parseLong(inv.split("\\|")[1]) > INVITE_MS) {
                    p.sendMessage(Ui.mm("<red>You don't have a party invite (they last 2 minutes).")); return;
                }
                if (leader != null) { p.sendMessage(Ui.mm("<red>Leave your party first: <white>/party leave")); return; }
                String lead = inv.split("\\|")[0];
                boolean[] ok = {false};
                Shared.update(parties, y -> {
                    y.set("invite." + me, null);
                    List<String> m = new ArrayList<>(y.getStringList("party." + lead));
                    if (m.isEmpty() || m.size() >= MAX_PARTY) return;
                    m.add(mine);
                    y.set("party." + lead, m);
                    y.set("of." + mine, lead);
                    ok[0] = true;
                });
                if (!ok[0]) { p.sendMessage(Ui.mm("<red>That party is gone or full.")); return; }
                tellParty(lead, "<#ffb347>✦ <white>" + Nicks.of(p) + " <gray>joined the party.");
                Hooks.stat(p, "party", 1);
                String where = serverOf(UUID.fromString(lead));
                if (where != null && !where.equals(server)) { p.sendMessage(Ui.mm("<gray>Taking you to your leader on " + pretty(where) + "...")); plugin.net().send(p, where); }
            }
            case "leave" -> {
                if (leader == null) { p.sendMessage(Ui.mm("<gray>You're not in a party.")); return; }
                if (leader.equals(mine)) { party(s, new String[]{"disband"}); return; }
                Shared.update(parties, y -> { y.set("of." + mine, null); List<String> m = new ArrayList<>(y.getStringList("party." + leader)); m.remove(mine); y.set("party." + leader, m); });
                p.sendMessage(Ui.mm("<gray>You left the party."));
                tellParty(leader, "<#ffb347>✦ <white>" + Nicks.of(p) + " <gray>left the party.");
            }
            case "kick" -> {
                if (leader == null || !leader.equals(mine)) { p.sendMessage(Ui.mm("<red>Only the party leader can kick.")); return; }
                UUID them = a.length > 1 ? idOf(a[1]) : null;
                if (them == null || !members(leader).contains(them) || them.equals(me)) { p.sendMessage(Ui.mm("<red>They're not in your party.")); return; }
                Shared.update(parties, y -> { y.set("of." + them, null); List<String> m = new ArrayList<>(y.getStringList("party." + mine)); m.remove(them.toString()); y.set("party." + mine, m); });
                tell(them, "<gray>You were removed from the party.");
                tellParty(mine, "<#ffb347>✦ <white>" + nameOf(them) + " <gray>was removed from the party.");
            }
            case "disband" -> {
                if (leader == null || !leader.equals(mine)) { p.sendMessage(Ui.mm("<red>Only the party leader can disband it.")); return; }
                List<UUID> was = members(mine);
                Shared.update(parties, y -> { for (UUID m : was) y.set("of." + m, null); y.set("party." + mine, null); });
                for (UUID m : was) tell(m, "<#ffb347>✦ <gray>The party was disbanded.");
            }
            case "warp" -> {
                if (leader == null || !leader.equals(mine)) { p.sendMessage(Ui.mm("<red>Only the party leader can warp the party.")); return; }
                pullParty(p, true);
            }
            case "chat" -> partyChat(p, String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
            case "list", "info" -> {
                if (leader == null) {
                    p.sendMessage(Ui.mm("<#ffb347>✦ <white>Parties <gray>- play together across every server"));
                    p.sendMessage(Ui.mm("<gray>  <white>/party invite <name> <gray>- up to " + MAX_PARTY + " players"));
                    p.sendMessage(Ui.mm("<gray>  Members follow the leader between servers and queue for Arcade games together."));
                    p.sendMessage(Ui.mm("<gray>  <white>/pc <message> <gray>talks to just your party."));
                    return;
                }
                p.sendMessage(Ui.mm("<#ffb347>✦ <white>Your party"));
                for (UUID m : members(leader)) {
                    String where = serverOf(m);
                    p.sendMessage(Ui.mm((m.toString().equals(leader) ? "<gold>  ★ " : "<gray>  • ") + "<white>" + nameOf(m)
                            + (where == null ? " <dark_gray>offline" : " <gray>on " + pretty(where))));
                }
                p.sendMessage(Ui.mm("<dark_gray>  /party leave · /pc <message>" + (leader.equals(mine) ? " · /party kick|warp|disband" : "")));
            }
            default -> p.sendMessage(Ui.mm("<gray>Usage: <white>/party invite|accept|leave|kick|warp|disband|list"));
        }
    }

    private void partyChat(Player p, String msg) {
        String leader = partyOf(p.getUniqueId());
        if (leader == null) { p.sendMessage(Ui.mm("<gray>You're not in a party.")); return; }
        if (msg.isBlank()) { p.sendMessage(Ui.mm("<gray>Usage: <white>/pc <message>")); return; }
        String safe = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(msg);
        tellParty(leader, "<#ffb347>[Party] <white>" + Nicks.of(p) + "<gray>: " + safe);
    }

    /** The leader is here: bring every online member who's elsewhere ({@code always}: even if they didn't just move). */
    private void pullParty(Player leaderP, boolean always) {
        String mine = leaderP.getUniqueId().toString();
        if (!mine.equals(partyOf(leaderP.getUniqueId()))) return;
        int moved = 0;
        for (UUID m : members(mine)) {
            if (m.equals(leaderP.getUniqueId())) continue;
            String where = serverOf(m);
            if (where == null || where.equals(server)) continue;
            tell(m, "<#ffb347>✦ <gray>Following your party leader to <white>" + pretty(server) + "<gray>...");
            plugin.net().connectOther(leaderP, nameOf(m), server);
            moved++;
        }
        if (always) leaderP.sendMessage(Ui.mm("<#ffb347>✦ <gray>" + (moved == 0 ? "Everyone's already here." : "Bringing " + moved + " party member" + (moved == 1 ? "" : "s") + " here.")));
        if (moved > 0) leaderP.playSound(leaderP.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f);
    }

    // ------------------------------------------------------------------ commands

    private interface Cmd { void run(CommandSender s, String[] args); }

    private static void cmd(String name, List<String> aliases, Cmd c) {
        Bukkit.getCommandMap().register("alloy", new Command(name, "BlackForgeMC " + name, "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) { c.run(s, args); return true; }
        });
    }
}
