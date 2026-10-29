package net.alloymc.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Everything that ties the servers to the BlackForgeMC community, on every server:
 * <ul>
 *   <li>/discord - the invite link, clickable.</li>
 *   <li>Rotating tips in chat every few minutes (config "tips"; arcade, hub and the open worlds each add their own).</li>
 *   <li>/report &lt;player&gt; &lt;reason&gt; - straight to online staff, the shared moderation log and the Discord staff channel.</li>
 *   <li>A one-way Discord feed through webhooks (shared/discord.yml, never committed): chat from every server,
 *       kill-feed deaths, first-ever joins, and reports to the staff channel. Replies from Discord would need a bot.</li>
 * </ul>
 */
public final class Community implements Listener {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final net.kyori.adventure.text.minimessage.MiniMessage MM = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage();
    public static final String DEFAULT_INVITE = "https://discord.gg/CChc5MfbD5";

    private final AlloyCore plugin;
    private final String mode, label, invite;
    private final File shared;
    private final List<String> tips = new ArrayList<>();
    private final Map<UUID, Long> reportCooldown = new HashMap<>();
    private final LinkedBlockingQueue<String[]> outbox = new LinkedBlockingQueue<>(500);   // {webhook url, json}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private String chatHook = "", staffHook = "";
    private int tipIndex;

    public Community(AlloyCore plugin, String mode, File shared) {
        this.plugin = plugin;
        this.mode = mode;
        this.shared = shared;
        this.label = plugin.getConfig().getString("display-name", mode);
        this.invite = plugin.getConfig().getString("discord-invite", DEFAULT_INVITE);
        loadHooks();
        buildTips();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        reg("discord", List.of("dc", "disc"), (s, a) -> discord(s));
        reg("report", List.of("rep"), this::report);
        int every = plugin.getConfig().getInt("tips-minutes", 6) * 60 * 20;
        // each server starts its tips at a different point so the network doesn't say the same thing at once
        tipIndex = Math.floorMod(mode.hashCode(), Math.max(1, tips.size()));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tip, every, every);
        Thread sender = new Thread(this::sendLoop, "BlackForge-Discord");
        sender.setDaemon(true);
        sender.start();
    }

    // ------------------------------------------------------------------ /discord

    private void discord(CommandSender s) {
        s.sendMessage(Ui.mm("<#7289da><bold>Discord</bold> <dark_gray>» <gray>Join the BlackForgeMC community: <click:open_url:'" + invite
                + "'><hover:show_text:'<gray>Open " + invite + "'><white><u>" + invite + "</u></hover></click>"));
    }

    // ------------------------------------------------------------------ tips

    private void buildTips() {
        List<String> cfg = plugin.getConfig().getStringList("tips");
        if (!cfg.isEmpty()) { tips.addAll(cfg); return; }
        tips.add("Join our Discord for news, giveaways and help: <white>/discord");
        tips.add("Vote for the server every day for free crate keys: <white>/vote");
        tips.add("Spotted a cheater or a rule-breaker? <white>/report <player> <reason>");
        tips.add("Support the server and get a supporter badge and trails: <white>/store");
        tips.add("Your Ingots and Scrap travel with you to every game mode.");
        switch (mode) {
            case "arcade" -> {
                tips.add("Every Arcade win pays Ingots and Scrap.");
            }
            case "hub" -> tips.add("Watch the sky - the forge dragons never land.");
            case "survival", "skyblock", "factions" -> {
                tips.add("Daily rewards grow with your login streak: <white>/daily");
                tips.add("Weekly challenges pay Scrap: <white>/challenges");
            }
            default -> {}
        }
    }

    private void tip() {
        if (tips.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) return;
        String t = tips.get(tipIndex++ % tips.size());
        Bukkit.broadcast(Ui.mm("<gold>⚒ <dark_gray>» <gray>" + t));
    }

    // ------------------------------------------------------------------ /report

    private void report(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        if (a.length < 2) { p.sendMessage(Ui.mm("<red>Usage: /report <player> <reason>")); return; }
        long now = System.currentTimeMillis();
        if (reportCooldown.getOrDefault(p.getUniqueId(), 0L) > now) { p.sendMessage(Ui.mm("<red>Give it a minute before reporting again.")); return; }
        reportCooldown.put(p.getUniqueId(), now + 60_000);
        // only letters, digits and _ in a name; the reason is shown with its tags escaped, so nobody can slip
        // clickable commands into staff chat
        String target = a[0].replaceAll("[^A-Za-z0-9_]", ""), reason = String.join(" ", Arrays.copyOfRange(a, 1, a.length));
        if (target.isEmpty()) { p.sendMessage(Ui.mm("<red>That's not a player name.")); return; }
        String shown = MM.escapeTags(reason), cmdReason = reason.replaceAll("[^A-Za-z0-9 .,!?-]", "");
        Player t = Bukkit.getPlayerExact(target);
        String where = t == null ? "not on " + label : "on " + label + " at " + t.getLocation().getBlockX() + ", " + t.getLocation().getBlockY() + ", " + t.getLocation().getBlockZ();
        int staff = 0;
        for (Player o : Bukkit.getOnlinePlayers())
            if (o.hasPermission("alloy.staff")) {
                staff++;
                o.sendMessage(Ui.mm("<red>⚑ REPORT <dark_gray>» <white>" + p.getName() + " <gray>reported <white>" + target + " <gray>(" + where + "): <white>" + shown
                        + " <click:run_command:'/punish " + target + " " + cmdReason + "'><hover:show_text:'<gray>Open the punish menu'><dark_red>[Punish]</hover></click>"));
                o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.6f);
            }
        logShared("REPORT " + p.getName() + " -> " + target + " [" + label + "]: " + reason);
        staffPost("⚑ Report", p.getName() + " reported **" + escape(target) + "** (" + where + ")\n> " + escape(reason), 0xe5484d);
        p.sendMessage(Ui.mm("<green>Thanks - your report on <white>" + target + " <green>was sent to " + (staff > 0 ? staff + " online staff and " : "") + "the staff team."));
    }

    private void logShared(String line) {
        try {
            Files.writeString(new File(shared, "moderation.log").toPath(), "[" + LocalDateTime.now().withNano(0) + "] " + line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ Discord feed

    private void loadHooks() {
        File f = new File(shared, "discord.yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        chatHook = y.getString("chat-webhook", "");
        staffHook = y.getString("staff-webhook", "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (chatHook.isBlank()) return;
        Player p = e.getPlayer();
        String msg = PLAIN.serialize(e.message());
        post(chatHook, Nicks.of(p) + " · " + label, p.getUniqueId(), escape(msg));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (chatHook.isBlank() || e.deathMessage() == null || !(mode.equals("survival") || mode.equals("skyblock") || mode.equals("factions"))) return;
        post(chatHook, label, null, "💀 " + escape(PLAIN.serialize(e.deathMessage()).replace("☠ ", "")));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (chatHook.isBlank() || !mode.equals("hub") || e.getPlayer().hasPlayedBefore()) return;
        post(chatHook, "BlackForgeMC", null, "👋 **" + escape(e.getPlayer().getName()) + "** joined BlackForgeMC for the first time - welcome!");
    }

    private void post(String hook, String username, UUID avatar, String content) {
        String json = "{\"username\":" + q(username) + (avatar != null ? ",\"avatar_url\":" + q("https://mc-heads.net/avatar/" + avatar + "/64") : "")
                + ",\"content\":" + q(content.length() > 1900 ? content.substring(0, 1900) + "…" : content) + ",\"allowed_mentions\":{\"parse\":[]}}";
        outbox.offer(new String[]{hook, json});
    }

    /** A /news entry, to the chat feed as an announcement. */
    public void postNews(String text) {
        if (chatHook.isBlank()) return;
        post(chatHook, "BlackForgeMC News", null, "📰 **What's new:** " + escape(text));
    }

    private void staffPost(String title, String text, int color) {
        if (staffHook.isBlank()) return;
        String json = "{\"username\":\"BlackForgeMC Staff\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":" + q(title)
                + ",\"description\":" + q(text) + ",\"color\":" + color + ",\"footer\":{\"text\":" + q(label) + "}}]}";
        outbox.offer(new String[]{staffHook, json});
    }

    /** Sends one webhook call at a time, waiting out Discord's rate limit when it asks. */
    private void sendLoop() {
        while (true) {
            try {
                String[] job = outbox.take();
                HttpRequest req = HttpRequest.newBuilder(URI.create(job[0])).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(job[1])).build();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 429) {
                    String body = r.body();
                    int i = body.indexOf("retry_after");
                    double wait = 2;
                    if (i >= 0) try { wait = Double.parseDouble(body.substring(body.indexOf(':', i) + 1).replaceAll("[^0-9.].*", "").trim()); } catch (Exception ignored) { }
                    Thread.sleep((long) (wait * 1000) + 250);
                    outbox.offer(job);
                } else Thread.sleep(400);
            } catch (InterruptedException ie) {
                return;
            } catch (Exception ex) {
                try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /** Stop Discord markdown and mentions from player text. */
    static String escape(String s) { return s.replaceAll("([*_`~|>\\\\])", "\\\\$1").replace("@", "@​"); }

    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> {}
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    // ------------------------------------------------------------------ commands

    private interface Cmd { void run(CommandSender s, String[] args); }

    private void reg(String name, List<String> aliases, Cmd cmd) {
        Bukkit.getCommandMap().register("alloy", new Command(name, "BlackForgeMC " + name, "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) { cmd.run(s, args); return true; }
        });
    }
}
