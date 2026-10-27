package net.alloymc.core.moderation;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * BlackForgeMC AutoMod: blocks slurs (with escalating, network-wide auto-mutes), spam, shouting and
 * advertising, and gives staff /mute, /unmute and /mutes. Runs on every server.
 */
public final class AutoMod implements Listener {
    private final AlloyCore plugin;
    private final ModStore store;
    private final List<Pattern> severeStream = new ArrayList<>();
    private final Set<String> severeWords = new HashSet<>(), mildWords = new HashSet<>();
    private final List<String> mildPhrases = new ArrayList<>();
    private final Set<String> allowedDomains = new HashSet<>();
    private final Map<UUID, Deque<Long>> recentTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> recentTexts = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> warnings = new ConcurrentHashMap<>();
    private final Map<UUID, Long> slowedUntil = new ConcurrentHashMap<>();

    /** Escalation for slurs: 1st 30m, 2nd 6h, 3rd 3d, then 30d. */
    private static final long[] SLUR_MUTES = {30 * 60_000L, 6 * 3_600_000L, 3 * 86_400_000L, 30 * 86_400_000L};

    private static final Pattern URL = Pattern.compile("(?i)\\b((?:[a-z0-9-]+\\.)+(?:com|net|org|gg|xyz|me|io|us|co|uk|tv|club|fun|pro|online|site|store|lol|ru|de|eu))\\b");
    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}(?:[.,\\s]\\d{1,3}){3}(?::\\d{2,5})?\\b");
    private static final Set<String> COMMANDS_THAT_CHAT = Set.of("msg", "tell", "w", "whisper", "r", "reply", "me", "mail", "say", "m", "pm", "t", "ac", "helpop");

    public AutoMod(AlloyCore plugin) {
        this.plugin = plugin;
        this.store = new ModStore(plugin);
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommands();
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, store::reloadIfChanged, 100L, 100L);
    }

    public ModStore store() { return store; }

    private void load() {
        File f = new File(plugin.getDataFolder(), "chatfilter.yml");
        if (!f.exists()) plugin.saveResource("chatfilter.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        if (y.getStringList("severe-stream").isEmpty())   // fall back to the bundled list if the file is broken
            y = YamlConfiguration.loadConfiguration(new InputStreamReader(plugin.getResource("chatfilter.yml")));
        severeStream.clear(); severeWords.clear(); mildWords.clear(); mildPhrases.clear(); allowedDomains.clear();
        for (String s : y.getStringList("severe-stream")) severeStream.add(Pattern.compile(s));
        severeWords.addAll(y.getStringList("severe-words"));
        for (String s : y.getStringList("mild-words")) { if (s.contains(" ")) mildPhrases.add(s); else mildWords.add(s); }
        allowedDomains.addAll(y.getStringList("allowed-domains"));
        allowedDomains.add("alloymc.net");
    }

    // ------------------------------------------------------------------ normalising

    private static final Map<Character, Character> LOOKALIKE = Map.ofEntries(
        Map.entry('0', 'o'), Map.entry('1', 'i'), Map.entry('!', 'i'), Map.entry('|', 'i'), Map.entry('3', 'e'), Map.entry('4', 'a'),
        Map.entry('@', 'a'), Map.entry('5', 's'), Map.entry('$', 's'), Map.entry('7', 't'), Map.entry('+', 't'), Map.entry('9', 'g'),
        Map.entry('6', 'g'), Map.entry('8', 'b'), Map.entry('ı', 'i'), Map.entry('í', 'i'), Map.entry('ì', 'i'), Map.entry('ł', 'l'),
        Map.entry('ø', 'o'), Map.entry('ß', 's'), Map.entry('¡', 'i'), Map.entry('€', 'e'), Map.entry('£', 'l'), Map.entry('¢', 'c'));

    /** Lower-case, strip accents, map look-alikes to letters. Keeps spaces between words. */
    static String normalise(String raw) {
        String s = Normalizer.normalize(raw.toLowerCase(Locale.ROOT), Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            Character m = LOOKALIKE.get(c);
            char ch = m != null ? m : c;
            b.append(Character.isLetter(ch) ? ch : (Character.isWhitespace(ch) ? ' ' : ch == '.' || ch == '_' || ch == '-' || ch == '*' ? ' ' : ' '));
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /** Which rule a message breaks, or null. */
    String severeHit(String raw) {
        String norm = normalise(raw);
        String stream = norm.replace(" ", "");
        for (Pattern p : severeStream) if (p.matcher(stream).find()) return p.pattern();
        for (String w : norm.split(" ")) if (severeWords.contains(w)) return w;
        // spaced-out single letters ("s p i c") rejoined
        String joined = joinSingles(norm);
        if (!joined.equals(norm))
            for (String w : joined.split(" ")) {
                if (severeWords.contains(w)) return w;
                // runs of spaced-out letters can carry extra letters around the word ("a f a g")
                if (w.length() > 3 && !norm.contains(w)) for (String bad : severeWords) if (bad.length() >= 3 && w.contains(bad)) return bad;
            }
        return null;
    }

    String mildHit(String raw) {
        String norm = normalise(raw);
        for (String w : norm.split(" ")) if (mildWords.contains(w)) return w;
        String padded = " " + norm + " ";
        for (String ph : mildPhrases) if (padded.contains(" " + ph + " ")) return ph;
        return null;
    }

    /** Rejoin runs of single letters: "s p i c is bad" -> "spic is bad". */
    static String joinSingles(String norm) {
        List<String> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (String w : norm.split(" ")) {
            if (w.length() == 1) { run.append(w); continue; }
            if (!run.isEmpty()) { out.add(run.toString()); run.setLength(0); }
            out.add(w);
        }
        if (!run.isEmpty()) out.add(run.toString());
        return String.join(" ", out);
    }

    String advert(String raw) {
        var m = URL.matcher(raw.toLowerCase(Locale.ROOT).replace("(dot)", ".").replace("[dot]", ".").replace(" dot ", "."));
        while (m.find()) {
            String dom = m.group(1);
            boolean ok = allowedDomains.stream().anyMatch(a -> dom.equals(a) || dom.endsWith("." + a));
            if (!ok) return dom;
        }
        var ip = IP.matcher(raw);
        if (ip.find()) return ip.group();
        return null;
    }

    // ------------------------------------------------------------------ chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        String verdict = check(p, msg, true);
        if (verdict == null) {
            // shouting: tone down messages that are mostly capitals
            long letters = msg.chars().filter(Character::isLetter).count();
            long caps = msg.chars().filter(Character::isUpperCase).count();
            if (letters >= 8 && caps > letters * 0.7 && !p.hasPermission("alloy.staff")) {
                e.message(Component.text(msg.toLowerCase(Locale.ROOT)));
                plugin.getLogger().info("[automod] toned down caps from " + p.getName());
            }
            return;
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChatCommand(PlayerCommandPreprocessEvent e) {
        String[] parts = e.getMessage().substring(1).split(" ", 2);
        String root = parts[0].toLowerCase(Locale.ROOT);
        if (root.contains(":")) root = root.substring(root.indexOf(':') + 1);
        if (!COMMANDS_THAT_CHAT.contains(root)) return;
        String text = parts.length > 1 ? parts[1] : "";
        if (check(e.getPlayer(), text, false) != null) e.setCancelled(true);
    }

    /** Applies every rule; returns why the message was blocked, or null if it may go through. Thread-safe. */
    private String check(Player p, String msg, boolean publicChat) {
        ModStore.Mute mute = store.muteOf(p.getUniqueId());
        if (mute != null) {
            p.sendMessage(Ui.mm("<red>You are muted for <white>" + ModStore.fmt(mute.until() - System.currentTimeMillis()) + "<red>. Reason: <white>" + mute.reason()));
            return "muted";
        }
        if (p.hasPermission("alloy.staff")) return null;
        String hit = severeHit(msg);
        if (hit != null) {
            int n = store.addOffence(p.getUniqueId());
            long len = SLUR_MUTES[Math.min(n, SLUR_MUTES.length) - 1];
            store.mute(p.getUniqueId(), p.getName(), len, "Hateful language (auto, offence #" + n + ")", "AutoMod");
            store.log("BLOCKED slur from " + p.getName() + ": " + msg);
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.sendMessage(Ui.mm("<red><bold>Muted for " + ModStore.fmt(len) + "</bold> <gray>- slurs and hate speech aren't allowed on BlackForgeMC. Offence #" + n + "."));
                staff("<red>⚑ <white>" + p.getName() + " <gray>auto-muted " + ModStore.fmt(len) + " for a slur: <dark_gray>\"" + censor(msg) + "\"");
            });
            return "slur";
        }
        String mild = mildHit(msg);
        if (mild != null) { warn(p, "That language isn't allowed here.", "Toxic language", msg); return "mild"; }
        String ad = advert(msg);
        if (ad != null) { warn(p, "Advertising other servers or sites isn't allowed.", "Advertising (" + ad + ")", msg); return "advert"; }
        if (publicChat) {
            long now = System.currentTimeMillis();
            if (slowedUntil.getOrDefault(p.getUniqueId(), 0L) > now) {
                p.sendMessage(Ui.mm("<red>Slow down! You can chat again in " + ((slowedUntil.get(p.getUniqueId()) - now) / 1000 + 1) + "s."));
                return "slowed";
            }
            Deque<Long> times = recentTimes.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            synchronized (times) {
                times.addLast(now);
                while (!times.isEmpty() && times.peekFirst() < now - 5000) times.pollFirst();
                if (times.size() > 5) { slowedUntil.put(p.getUniqueId(), now + 15_000); warn(p, "You're sending messages too fast.", "Chat flooding", msg); return "flood"; }
            }
            String key = normalise(msg).replace(" ", "");
            Deque<String> texts = recentTexts.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
            synchronized (texts) {
                long same = texts.stream().filter(t -> t.equals(key)).count();
                texts.addLast(key);
                if (texts.size() > 6) texts.pollFirst();
                if (same >= 2 && key.length() > 0) { warn(p, "Please don't repeat the same message.", "Spam", msg); return "spam"; }
            }
        }
        return null;
    }

    /** Three warnings within an hour earn a 15 minute mute. */
    private void warn(Player p, String why, String rule, String msg) {
        long now = System.currentTimeMillis();
        Deque<Long> w = warnings.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        int count;
        synchronized (w) {
            w.addLast(now);
            while (!w.isEmpty() && w.peekFirst() < now - 3_600_000) w.pollFirst();
            count = w.size();
        }
        store.log("WARN " + p.getName() + " [" + rule + "]: " + msg);
        if (count >= 3) {
            synchronized (w) { w.clear(); }
            store.mute(p.getUniqueId(), p.getName(), 15 * 60_000L, rule + " (auto, 3 warnings)", "AutoMod");
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.sendMessage(Ui.mm("<red><bold>Muted for 15m</bold> <gray>- " + why + " (3 warnings)"));
                staff("<gold>⚑ <white>" + p.getName() + " <gray>auto-muted 15m: " + rule);
            });
        } else {
            int c = count;
            Bukkit.getScheduler().runTask(plugin, () -> p.sendMessage(Ui.mm("<gold>⚠ <yellow>" + why + " <gray>(warning " + c + "/3)")));
        }
    }

    private static String censor(String s) {
        return s.length() <= 2 ? "**" : s.charAt(0) + "*".repeat(Math.min(12, s.length() - 2)) + s.charAt(s.length() - 1);
    }

    private void staff(String mm) {
        for (Player st : Bukkit.getOnlinePlayers()) if (st.hasPermission("alloy.staff")) st.sendMessage(Ui.mm(mm));
        plugin.getLogger().info(PlainTextComponentSerializer.plainText().serialize(Ui.mm(mm)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        recentTimes.remove(id); recentTexts.remove(id); slowedUntil.remove(id);
    }

    // ------------------------------------------------------------------ staff commands

    private void registerCommands() {
        reg("mute", List.of("tempmute"), (s, a) -> {
            if (a.length < 2) { s.sendMessage(Ui.mm("<gray>/mute <player> <10m|2h|3d|perm> [reason]")); return; }
            OfflinePlayer t = target(a[0]);
            if (t == null) { s.sendMessage(Ui.mm("<red>Unknown player " + a[0])); return; }
            long ms = a[1].equalsIgnoreCase("perm") ? 3650L * 86_400_000L : ModStore.parse(a[1]);
            if (ms <= 0) { s.sendMessage(Ui.mm("<red>Bad duration. Use 10m, 2h, 3d or perm.")); return; }
            String reason = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "Muted by staff";
            store.mute(t.getUniqueId(), t.getName(), ms, reason, s.getName());
            Player online = t.getPlayer();
            if (online != null) online.sendMessage(Ui.mm("<red><bold>You've been muted for " + ModStore.fmt(ms) + "</bold> <gray>- " + reason));
            staff("<gold>⚑ <white>" + t.getName() + " <gray>muted " + ModStore.fmt(ms) + " by <white>" + s.getName() + "<gray>: " + reason);
        });
        reg("unmute", List.of(), (s, a) -> {
            if (a.length < 1) { s.sendMessage(Ui.mm("<gray>/unmute <player>")); return; }
            OfflinePlayer t = target(a[0]);
            if (t == null || !store.unmute(t.getUniqueId(), s.getName())) { s.sendMessage(Ui.mm("<gray>" + a[0] + " isn't muted.")); return; }
            Player online = t.getPlayer();
            if (online != null) online.sendMessage(Ui.mm("<green>You've been unmuted."));
            staff("<green>⚑ <white>" + t.getName() + " <gray>unmuted by <white>" + s.getName());
        });
        reg("mutes", List.of("mutelist"), (s, a) -> {
            var list = store.activeMutes();
            s.sendMessage(Ui.mm("<gold>Active mutes (" + list.size() + "):"));
            long now = System.currentTimeMillis();
            for (var m : list) s.sendMessage(Ui.mm("<white>" + m.name() + " <gray>" + ModStore.fmt(m.until() - now) + " left - " + m.reason() + " <dark_gray>(" + m.by() + ")"));
        });
        reg("automod", List.of(), (s, a) -> { load(); s.sendMessage(Ui.mm("<green>AutoMod word lists reloaded.")); });
    }

    private OfflinePlayer target(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op;
    }

    private interface Cmd { void run(CommandSender s, String[] args); }

    private void reg(String name, List<String> aliases, Cmd cmd) {
        Command c = new Command(name, "BlackForgeMC moderation", "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) {
                if (!s.hasPermission("alloy.staff")) { s.sendMessage(Ui.mm("<red>Staff only.")); return true; }
                cmd.run(s, args);
                return true;
            }
        };
        Bukkit.getCommandMap().register("alloy", c);
    }
}
