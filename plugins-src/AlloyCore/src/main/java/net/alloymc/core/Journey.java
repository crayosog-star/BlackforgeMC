package net.alloymc.core;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * A player's journey across the whole network, on every server:
 * <ul>
 *   <li><b>Milestones</b>: active playtime (AFK minutes don't count) is added up network-wide; reaching 30 min, 2 h,
 *       5 h ... pays money + Scrap straight away. /milestones shows the road, /playtime the total.</li>
 *   <li><b>Join digest</b>: back after a break, players get a short "here's where you are" with clickable next steps;
 *       away a week or more, a welcome-back gift (once a month at most).</li>
 *   <li><b>/guide</b> (also plain /help): every server, the shared economy, rules, and every custom item with its
 *       texture and where to get it.</li>
 *   <li><b>Referrals</b>: a new player names who brought them (/referral &lt;name&gt;) within their first 2 hours;
 *       once they've played an hour, both get paid. Same connection (IP) can't refer itself.</li>
 * </ul>
 * Data: shared/journey/&lt;uuid&gt;.yml, read and written per change (a player is only on one server at a time,
 * and nothing is cached, so a server switch can't write back stale numbers).
 */
public final class Journey implements Listener {

    public record Milestone(long minutes, String name, String color, double money, long scrap) {}

    public static final List<Milestone> MILESTONES = List.of(
            new Milestone(30, "Spark", "<#ffd27a>", 500, 25),
            new Milestone(120, "Kindled", "<#ffb347>", 1500, 75),
            new Milestone(300, "Tempered", "<#ff9a3c>", 3000, 150),
            new Milestone(720, "Forged", "<#ff7a1a>", 6000, 300),
            new Milestone(1440, "Hardened", "<#ff5a1a>", 10000, 500),
            new Milestone(2880, "Steelborn", "<#d9dde3>", 17500, 800),
            new Milestone(6000, "Blackforged", "<#b9a7ff>", 30000, 1500),
            new Milestone(15000, "Legend of the Forge", "<gradient:#ffd27a:#ff4d00>", 60000, 3000));

    static final long AFK_MINUTES = 5, DIGEST_GAP_MS = 20 * 60_000L, RETURN_GAP_MS = 7 * 86_400_000L;
    static final long REFERRAL_WINDOW = 120, REFERRAL_PAYOUT_AT = 60;
    static final double RETURN_MONEY = 2500, REFERRER_MONEY = 2500, NEWBIE_MONEY = 1000;
    static final long RETURN_SCRAP = 150, REFERRER_SCRAP = 200, NEWBIE_SCRAP = 100;

    private final AlloyCore plugin;
    private final String server;
    private final File dir;
    private final boolean textures;
    private final Map<UUID, Location> lastSpot = new HashMap<>();
    private final Map<UUID, Integer> stillMinutes = new HashMap<>();

    public Journey(AlloyCore plugin, String mode, File shared) {
        this.plugin = plugin;
        this.server = mode;
        this.dir = new File(shared, "journey");
        dir.mkdirs();
        this.textures = plugin.getConfig().getBoolean("resource-pack-live", false);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1200L, 1200L);
        cmd("milestones", List.of("milestone", "journey"), (s, a) -> { if (s instanceof Player p) openMilestones(p); });
        cmd("playtime", List.of("pt"), (s, a) -> { if (s instanceof Player p) playtime(p); });
        cmd("guide", List.of("menu", "info", "howto"), (s, a) -> { if (s instanceof Player p) openGuide(p); });
        cmd("referral", List.of("refer", "referrals"), this::referral);
        cmd("calendar", List.of("login", "rewards", "dailycalendar"), (s, a) -> { if (s instanceof Player p) openCalendar(p); });
    }

    // ------------------------------------------------------------------ data

    private File file(UUID id) { return new File(dir, id + ".yml"); }

    private YamlConfiguration load(UUID id) { return YamlConfiguration.loadConfiguration(file(id)); }

    private void save(UUID id, YamlConfiguration y) { try { y.save(file(id)); } catch (Exception ignored) { } }

    public long minutes(UUID id) { return load(id).getLong("minutes"); }

    static String time(long minutes) {
        if (minutes < 60) return minutes + "m";
        return minutes / 60 + "h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "m");
    }

    static Milestone next(long minutes) {
        for (Milestone m : MILESTONES) if (minutes < m.minutes) return m;
        return null;
    }

    private static String ipHash(Player p) {
        try {
            if (p.getAddress() == null) return "";
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("bfmc:" + p.getAddress().getAddress().getHostAddress()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------ playtime + milestones

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Location now = p.getLocation(), was = lastSpot.put(id, now.clone());
            boolean still = was != null && was.getWorld() == now.getWorld() && was.getBlockX() == now.getBlockX()
                    && was.getBlockY() == now.getBlockY() && was.getBlockZ() == now.getBlockZ() && was.getYaw() == now.getYaw();
            int idle = still ? stillMinutes.merge(id, 1, Integer::sum) : 0;
            if (!still) stillMinutes.put(id, 0);
            YamlConfiguration y = load(id);
            y.set("name", p.getName());
            y.set("last-seen", System.currentTimeMillis());
            if (idle >= AFK_MINUTES) { save(id, y); continue; }   // AFK: seen, but no playtime
            long minutes = y.getLong("minutes") + 1;
            y.set("minutes", minutes);
            Hooks.statMax(p, "playtime", minutes);
            List<Integer> claimed = new ArrayList<>(y.getIntegerList("claimed"));
            List<Milestone> reached = new ArrayList<>();
            for (int i = 0; i < MILESTONES.size(); i++) {
                if (minutes < MILESTONES.get(i).minutes || claimed.contains(i)) continue;
                claimed.add(i);
                reached.add(MILESTONES.get(i));
            }
            // several at once (a veteran's first minute): pay them all, show and announce only the highest
            for (int i = 0; i < reached.size(); i++) reward(p, reached.get(i), i == reached.size() - 1);
            y.set("claimed", claimed);
            if (minutes >= REFERRAL_PAYOUT_AT && y.contains("referred-by") && !y.getBoolean("referral-paid")) {
                y.set("referral-paid", true);
                payReferral(p, UUID.fromString(y.getString("referred-by")));
            }
            save(id, y);
        }
    }

    private void reward(Player p, Milestone m, boolean last) {
        plugin.economy().deposit(p, m.money);
        plugin.economy().addScrap(p, m.scrap);
        p.sendMessage(Ui.mm("<gold>✦ <gray>Milestone <white>" + m.color + m.name + "</white> <gray>reached! <green>+"
                + NetEconomy.money(m.money) + " <gray>and <#c6a15b>+" + m.scrap + " Scrap"));
        if (!last) return;
        p.showTitle(Title.title(Ui.mm(m.color + "<bold>" + m.name.toUpperCase()), Ui.mm("<gray>Milestone reached: <white>" + time(m.minutes) + " played"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        Milestone n = next(m.minutes);
        if (n != null) p.sendMessage(Ui.mm("<gray>  Next: " + n.color + n.name + " <gray>at " + time(n.minutes)
                + " <dark_gray>- <u><click:run_command:'/milestones'><gray>see the road</click></u>"));
        if (m.minutes >= 720)   // the big ones are worth telling everyone
            plugin.net().broadcastAll(p, Ui.mm("<gold>✦ <white>" + Nicks.of(p) + " <gray>reached the " + m.color + m.name
                    + " <gray>milestone (" + time(m.minutes) + " played)!"));
    }

    private void playtime(Player p) {
        long min = minutes(p.getUniqueId());
        Milestone n = next(min);
        p.sendMessage(Ui.mm("<gold>✦ <gray>Network playtime: <white>" + time(min)
                + (n == null ? " <gray>- every milestone reached!" : " <gray>- " + n.color + n.name + " <gray>in <white>" + time(n.minutes - min))));
    }

    private static final class Menu implements InventoryHolder {
        final String page;
        Inventory inv;
        Menu(String page) { this.page = page; }
        @Override public Inventory getInventory() { return inv; }
    }

    public void openMilestones(Player p) {
        long min = minutes(p.getUniqueId());
        List<Integer> claimed = load(p.getUniqueId()).getIntegerList("claimed");
        Menu m = new Menu("milestones");
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Milestones <gray>- " + time(min) + " played"));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        for (int i = 0; i < MILESTONES.size(); i++) {
            Milestone ms = MILESTONES.get(i);
            boolean done = min >= ms.minutes || claimed.contains(i);
            long prev = i == 0 ? 0 : MILESTONES.get(i - 1).minutes;
            int bars = done ? 10 : (int) Math.max(0, Math.min(10, Math.floor(10.0 * (min - prev) / (ms.minutes - prev))));
            ItemStack it = Ui.item(done ? Material.LIME_STAINED_GLASS_PANE : (min >= prev ? Material.CLOCK : Material.GRAY_STAINED_GLASS_PANE),
                    (done ? "<green>✔ " : "") + ms.color + "<bold>" + ms.name,
                    "<gray>Play " + time(ms.minutes) + " across the network",
                    done ? "<green>Reached" : "<gray>Progress: <white>" + time(Math.max(0, min - prev)) + "<gray>/" + time(ms.minutes - prev),
                    "<green>" + "■".repeat(bars) + "<dark_gray>" + "■".repeat(10 - bars),
                    "",
                    "<gray>Reward: <green>" + NetEconomy.money(ms.money) + " <gray>+ <#c6a15b>" + ms.scrap + " Scrap");
            if (done) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(slots[i], it);
        }
        m.inv.setItem(18, Ui.item(Material.PAPER, "<gold>How it works",
                "<gray>Every minute you play on any server", "<gray>counts. AFK time doesn't.", "<gray>Rewards are paid automatically."));
        m.inv.setItem(26, Ui.item(Material.ARROW, "<gray>Back to the guide"));
        p.openInventory(m.inv);
    }

    // ------------------------------------------------------------------ join digest

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        YamlConfiguration y = load(id);
        long now = System.currentTimeMillis(), last = y.getLong("last-seen", 0);
        boolean fresh = !file(id).exists();
        y.set("name", p.getName());
        y.set("last-seen", now);
        y.set("ip", ipHash(p));
        if (fresh) {
            y.set("first-seen", now);
            // here before milestones existed: start from their playtime on this server (paid out on the next minute)
            if (p.hasPlayedBefore()) y.set("minutes", (long) p.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 1200);
        }
        boolean gift = !fresh && last > 0 && now - last >= RETURN_GAP_MS && now - y.getLong("last-return-gift", 0) >= 30 * 86_400_000L;
        if (gift) y.set("last-return-gift", now);
        List<String> notes = y.getStringList("notes");
        y.set("notes", null);
        save(id, y);
        lastSpot.remove(id);
        stillMinutes.remove(id);
        if (gift) { plugin.economy().deposit(p, RETURN_MONEY); plugin.economy().addScrap(p, RETURN_SCRAP); }
        if (fresh) return;   // brand new: the hub's welcome and tutorial look after them
        boolean digest = now - last >= DIGEST_GAP_MS;
        if (!digest && notes.isEmpty()) return;
        long days = (now - last) / 86_400_000L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (digest) digest(p, days, gift);
            for (String n : notes) p.sendMessage(Ui.mm(n));
        }, server.equals("hub") ? 80L : 40L);
    }

    private void digest(Player p, long daysAway, boolean gift) {
        long min = minutes(p.getUniqueId());
        Milestone n = next(min);
        p.sendMessage(Ui.mm(" "));
        p.sendMessage(Ui.mm("  <gold>✦ <gray>Welcome back, <white>" + Nicks.of(p) + "<gray>!"
                + (daysAway >= 1 ? " <dark_gray>(" + daysAway + (daysAway == 1 ? " day" : " days") + " away)" : "")));
        if (gift) p.sendMessage(Ui.mm("  <gold>» <gray>Welcome-back gift: <green>+" + NetEconomy.money(RETURN_MONEY)
                + " <gray>and <#c6a15b>+" + RETURN_SCRAP + " Scrap <gray>- we missed you!"));
        p.sendMessage(Ui.mm("  <gold>» <gray>Played <white>" + time(min) + (n == null ? " <gray>- every milestone done!"
                : " <gray>- " + n.color + n.name + " <gray>in <white>" + time(n.minutes - min))
                + " <u><click:run_command:'/milestones'><hover:show_text:'<gray>Open milestones'><#ffb347>[Milestones]</hover></click></u>"));
        String ev = Events.sidebarLine();
        if (!ev.equals("<gray>/events")) p.sendMessage(Ui.mm("  <gold>» " + ev
                + " <u><click:run_command:'/events'><hover:show_text:'<gray>The event calendar'><#ffb347>[Events]</hover></click></u>"));
        int unseen = News.unseen(p.getUniqueId(), dir.getParentFile());
        News.Item latest = News.all().isEmpty() ? null : News.all().getFirst();
        if (unseen > 0 && latest != null) p.sendMessage(Ui.mm("  <gold>» <gray>New: <white>" + (latest.text().length() > 70 ? latest.text().substring(0, 67) + "..." : latest.text())
                + " <u><click:run_command:'/news'><hover:show_text:'<gray>" + unseen + " update" + (unseen == 1 ? "" : "s") + " you haven\'t seen'><#ffb347>[News]</hover></click></u>"));
        if (calendarReady(load(p.getUniqueId()))) p.sendMessage(Ui.mm("  <gold>» <gray>Your daily calendar reward is ready"
                + " <u><click:run_command:'/calendar'><hover:show_text:'<gray>Claim it'><#7bd88f>[Claim]</hover></click></u>"));
        String extra = switch (server) {
            case "survival", "skyblock" -> " <u><click:run_command:'/vote'><hover:show_text:'<gray>Vote for a Spark Key'><#7bd88f>[Vote]</hover></click></u>"
                    + " <u><click:run_command:'/daily'><hover:show_text:'<gray>Claim your daily reward'><#7bd88f>[Daily]</hover></click></u>"
                    + " <u><click:run_command:'/challenges'><hover:show_text:'<gray>This week\\'s challenges'><#7bd88f>[Challenges]</hover></click></u>";
            case "factions", "arcade" -> " <u><click:run_command:'/challenges'><hover:show_text:'<gray>This week\\'s challenges'><#7bd88f>[Challenges]</hover></click></u>";
            default -> "";
        };
        p.sendMessage(Ui.mm("  <gold>» <u><click:run_command:'/guide'><hover:show_text:'<gray>Servers, items, economy, rules'><#4fd1c5>[Guide]</hover></click></u>"
                + extra + " <u><click:run_command:'/discord'><hover:show_text:'<gray>Join the Discord'><#8c9eff>[Discord]</hover></click></u>"));
        p.sendMessage(Ui.mm(" "));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastSpot.remove(id);
        stillMinutes.remove(id);
        YamlConfiguration y = load(id);
        y.set("last-seen", System.currentTimeMillis());
        save(id, y);
    }

    // ------------------------------------------------------------------ daily calendar

    static final double[] CAL_MONEY = {250, 500, 750, 1000, 1500, 2000, 4000};
    static final long[] CAL_SCRAP = {15, 25, 40, 50, 75, 100, 250};

    private static String today() { return java.time.LocalDate.now().toString(); }
    private static String yesterday() { return java.time.LocalDate.now().minusDays(1).toString(); }

    static boolean calendarReady(YamlConfiguration y) { return !today().equals(y.getString("calendar-last")); }

    /** The calendar day (1-7) that's up today: the streak continues from yesterday, otherwise it starts over. */
    static int calendarDay(YamlConfiguration y) {
        String last = y.getString("calendar-last");
        if (today().equals(last)) return Math.floorMod(y.getInt("calendar-next", 1) - 2, 7) + 1;   // already claimed today's
        return yesterday().equals(last) ? y.getInt("calendar-next", 1) : 1;
    }

    public void openCalendar(Player p) {
        YamlConfiguration y = load(p.getUniqueId());
        boolean ready = calendarReady(y);
        int day = calendarDay(y);
        Menu m = new Menu("calendar");
        m.inv = Bukkit.createInventory(m, 9, Ui.mm("<dark_gray>Daily Calendar <gray>- day " + day + "/7"));
        for (int d = 1; d <= 7; d++) {
            boolean past = d < day || (d == day && !ready), now = d == day && ready;
            ItemStack it = Ui.item(past ? Material.LIME_STAINED_GLASS_PANE : now ? (d == 7 ? Material.ENDER_CHEST : Material.CHEST) : (d == 7 ? Material.ENDER_CHEST : Material.GRAY_STAINED_GLASS_PANE),
                    (now ? "<green><bold>Day " + d + " - claim!" : past ? "<green>✔ Day " + d : "<gray>Day " + d) + (d == 7 ? " <gold>(big one)" : ""),
                    "<gray>Reward: <green>" + NetEconomy.money(CAL_MONEY[d - 1]) + " <gray>+ <#c6a15b>" + CAL_SCRAP[d - 1] + " Scrap",
                    now ? "<yellow>Click to claim" : past ? "<green>Claimed" : "<gray>Come back on day " + d);
            if (now) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(d - 1, it);
        }
        m.inv.setItem(8, Ui.item(Material.ARROW, "<gray>Back to the guide", "", "<gray>Claim once a day on any server.", "<gray>Miss a day and it starts over.",
                "<gray>Streak: <white>" + y.getInt("calendar-streak") + " day" + (y.getInt("calendar-streak") == 1 ? "" : "s")));
        p.openInventory(m.inv);
    }

    private void claimCalendar(Player p) {
        UUID id = p.getUniqueId();
        YamlConfiguration y = load(id);
        if (!calendarReady(y)) { p.sendMessage(Ui.mm("<gray>You've already claimed today's reward - come back tomorrow!")); return; }
        int day = calendarDay(y);
        int streak = yesterday().equals(y.getString("calendar-last")) ? y.getInt("calendar-streak") + 1 : 1;
        y.set("calendar-last", today());
        y.set("calendar-next", day % 7 + 1);
        y.set("calendar-streak", streak);
        save(id, y);
        plugin.economy().deposit(p, CAL_MONEY[day - 1]);
        plugin.economy().addScrap(p, CAL_SCRAP[day - 1]);
        Hooks.statMax(p, "calendar_streak", streak);
        p.playSound(p.getLocation(), day == 7 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        p.sendMessage(Ui.mm("<#7bd88f>✔ <gray>Day " + day + " reward: <green>+" + NetEconomy.money(CAL_MONEY[day - 1]) + " <gray>and <#c6a15b>+"
                + CAL_SCRAP[day - 1] + " Scrap <dark_gray>(streak " + streak + ")"));
        openCalendar(p);
    }

    // ------------------------------------------------------------------ referrals

    private void referral(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        YamlConfiguration y = load(p.getUniqueId());
        if (a.length == 0) {
            p.sendMessage(Ui.mm("<gold>✦ <white>Referrals"));
            p.sendMessage(Ui.mm("<gray>  Bring a friend! New players type <white>/referral <your name></white> in their first "
                    + time(REFERRAL_WINDOW) + "."));
            p.sendMessage(Ui.mm("<gray>  Once they've played " + time(REFERRAL_PAYOUT_AT) + ": you get <green>" + NetEconomy.money(REFERRER_MONEY)
                    + " <gray>+ <#c6a15b>" + REFERRER_SCRAP + " Scrap<gray>, they get <green>" + NetEconomy.money(NEWBIE_MONEY)
                    + " <gray>+ <#c6a15b>" + NEWBIE_SCRAP + " Scrap<gray>."));
            p.sendMessage(Ui.mm("<gray>  Friends you've brought: <white>" + y.getInt("referrals")));
            if (y.contains("referred-by")) p.sendMessage(Ui.mm("<gray>  You were brought by <white>" + y.getString("referred-by-name", "?")));
            return;
        }
        if (y.contains("referred-by")) { p.sendMessage(Ui.mm("<red>You've already named who brought you.")); return; }
        if (y.getLong("minutes") >= REFERRAL_WINDOW) {
            p.sendMessage(Ui.mm("<red>Referrals are for new players (first " + time(REFERRAL_WINDOW) + " of play).")); return;
        }
        UUID target = null;
        String targetName = a[0];
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) for (File f : files) {
            YamlConfiguration o = YamlConfiguration.loadConfiguration(f);
            if (targetName.equalsIgnoreCase(o.getString("name", ""))) {
                target = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                targetName = o.getString("name");
                break;
            }
        }
        if (target == null) { p.sendMessage(Ui.mm("<red>No player called " + a[0] + " has played here.")); return; }
        if (target.equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>Nice try.")); return; }
        String mine = ipHash(p);
        if (!mine.isEmpty() && mine.equals(load(target).getString("ip"))) {
            p.sendMessage(Ui.mm("<red>That player connects from the same place as you, so they can't refer you.")); return;
        }
        y.set("referred-by", target.toString());
        y.set("referred-by-name", targetName);
        save(p.getUniqueId(), y);
        p.sendMessage(Ui.mm("<green>✔ <gray>Thanks! Once you've played <white>" + time(REFERRAL_PAYOUT_AT) + "<gray>, you and <white>"
                + targetName + " <gray>both get a reward."));
        note(target, "<gold>✦ <white>" + Nicks.of(p) + " <gray>says you brought them to the server! You'll be paid once they've played "
                + time(REFERRAL_PAYOUT_AT) + ".");
    }

    private void payReferral(Player newbie, UUID referrer) {
        OfflinePlayer r = Bukkit.getOfflinePlayer(referrer);
        plugin.economy().deposit(r, REFERRER_MONEY);
        plugin.economy().addScrap(r, REFERRER_SCRAP);
        plugin.economy().deposit(newbie, NEWBIE_MONEY);
        plugin.economy().addScrap(newbie, NEWBIE_SCRAP);
        YamlConfiguration ry = load(referrer);
        ry.set("referrals", ry.getInt("referrals") + 1);
        save(referrer, ry);
        if (r.getPlayer() != null) Hooks.statMax(r.getPlayer(), "referrals", ry.getInt("referrals"));
        newbie.sendMessage(Ui.mm("<gold>✦ <gray>Referral reward: <green>+" + NetEconomy.money(NEWBIE_MONEY) + " <gray>and <#c6a15b>+"
                + NEWBIE_SCRAP + " Scrap<gray>. Welcome to the forge!"));
        note(referrer, "<gold>✦ <gray>Referral reward for bringing <white>" + Nicks.of(newbie) + "<gray>: <green>+"
                + NetEconomy.money(REFERRER_MONEY) + " <gray>and <#c6a15b>+" + REFERRER_SCRAP + " Scrap<gray>. Thanks!");
    }

    /** Tell a player something now if they're on this server, otherwise the next time they join anywhere. */
    private void note(UUID to, String msg) {
        Player online = Bukkit.getPlayer(to);
        if (online != null) { online.sendMessage(Ui.mm(msg)); return; }
        YamlConfiguration y = load(to);
        List<String> notes = new ArrayList<>(y.getStringList("notes"));
        notes.add(msg);
        y.set("notes", notes);
        save(to, y);
    }

    // ------------------------------------------------------------------ guide

    /** id (texture), base item, name, what it does, where to get it */
    record Guided(String id, Material base, String name, String what, String where) {}

    static final List<Guided> ITEMS = List.of(
            new Guided("gale_bellows", Material.BREEZE_ROD, "<gradient:#EAF6FF:#4FC3D9><bold>Gale Bellows", "A wave of wind that throws enemies back;|aim at your feet to launch yourself up. 3 uses.", "Crates, and rarely from Scrapyard supply crates"),
            new Guided(null, Material.SLIME_BALL, "<#7ddc4a><bold>Jelly Legs", "No fall damage while it's switched on.|/jl toggles it. Unlocked for good.", "Scrap Shop (/scrap), or a rare crate prize"),
            new Guided("surge", Material.WITHER_ROSE, "<#e5484d><bold>Surge", "6 seconds of ghost speed and big jumps,|paid for with a health drain. One use.", "Survival crates · try it in the hub Arena"),
            new Guided("darkness_potion", Material.SPLASH_POTION, "<#a77fe0><bold>Potion of Darkness", "Blinds players it hits and plays fake|footsteps on the wrong side. Confuses mobs.", "Survival crates · try it in the hub Arena"),
            new Guided("gaia_potion", Material.SPLASH_POTION, "<#ff9ad5><bold>Potion of Gaia", "Grows a blooming mist that lifts everyone|on it 8 blocks into the air.", "Survival crates · try it in the hub Arena"),
            new Guided("rimelock", Material.HEART_OF_THE_SEA, "<#6cc8ef><bold>Rimelock", "Leap and slam: a glass cage traps everyone|where you land for 10 seconds.", "Rare Survival crate prize (PvP zones)"),
            new Guided("ashen_chain", Material.IRON_CHAIN, "<#ff7a1a><bold>Ashen Chain", "Right-click to grapple to a block,|left-click to strike and set enemies alight.", "Rare Survival crate prize (PvP zones)"),
            new Guided("ashen_bonfire", Material.CAMPFIRE, "<#9a9aa6><bold>Ashen Bonfire", "A sword falls from the sky and lights a|fire that heals everyone standing near it.", "Rare Survival crate prize (PvP zones)"),
            new Guided("gods_eye", Material.ENDER_EYE, "<#ffd23f><bold>God's Eye", "Marks the nearest player for 10 seconds|with a halo and a beam everyone can see.", "Rare Survival crate prize (PvP zones)"),
            new Guided("last_rite", Material.PAPER, "<#e03a3a><bold>Last Rite", "Charge it with 3 kills, then catch an enemy|in a wave and duel them in a sealed chamber.", "Rare Survival crate prize · charge it with kills"),
            new Guided(null, Material.EMERALD, "<#c6ff3d><bold>EMP", "Shuts down every ability around you|and strips your enemies' potion effects.", "Rare Survival crate prize (PvP zones)"),
            new Guided("frost_shard", Material.PRISMARINE_SHARD, "<#9fe6ff><bold>Frost Shard", "Frostforge event currency: trade it at|/frost for Scrap, keys and a crown.", "Dropped by mobs during Frostforge (Dec 1 - Jan 6)"),
            new Guided("soul_candy", Material.POPPED_CHORUS_FRUIT, "<#ff9a1f><bold>Soul Candy", "Hallowforge event currency: trade it at|/hallow for Scrap, keys and a hat.", "Dropped by mobs during the Hallowforge event"));

    static final String[][] SERVERS = {
            {"skyblock", "GRASS_BLOCK", "<#4fd1c5><bold>Skyblock", "Grow your own island, or try OneBlock.|Each has its own inventory.", "/is, /skyhub, /ah, /daily, /scrap"},
            {"survival", "IRON_PICKAXE", "<#d9dde3><bold>Survival", "Claims, crates, jobs, KOTH, world bosses|and the Scrapyard.", "/rtp, /claim, /jobs, /bounty, /ah, /daily"},
            {"factions", "NETHERITE_SWORD", "<#e5484d><bold>Factions", "Build a faction, claim land,|raid your rivals.", "/f, /rtp, /season"},
            {"arcade", "TNT", "<#ffc53d><bold>Arcade", "BedWars, SkyWars, Duels, Murder|Mystery and more minigames.", "/ranked, /cup, /challenges"},
            {"hub", "COMPASS", "<gold><bold>Hub", "Server selector, parkour, the Arena|and the leaderboards.", "/hub, /parkour, /tutorial"}};

    public void openGuide(Player p) {
        Menu m = new Menu("guide");
        m.inv = Bukkit.createInventory(m, 36, Ui.mm("<dark_gray>BlackForgeMC Guide"));
        int[] serverSlots = {11, 12, 13, 14, 15};
        for (int i = 0; i < SERVERS.length; i++) {
            String[] s = SERVERS[i];
            List<String> lore = new ArrayList<>();
            for (String line : s[3].split("\\|")) lore.add("<gray>" + line);
            lore.add("");
            lore.add("<dark_gray>Commands: <white>" + s[4]);
            lore.add("");
            lore.add(s[0].equals(server) ? "<green>You're here" : "<yellow>Click to go");
            m.inv.setItem(serverSlots[i], Ui.item(Material.valueOf(s[1]), s[2], lore.toArray(String[]::new)));
        }
        m.inv.setItem(4, Ui.item(Material.KNOWLEDGE_BOOK, "<gold><bold>Welcome to " + Ui.TITLE,
                "<gray>Pick a server below to jump in.", "<gray>Money and Scrap are shared by every server."));
        m.inv.setItem(19, Ui.item(Material.NETHER_STAR, "<#ffb347><bold>Custom Items", "<gray>Every BlackForgeMC item,", "<gray>what it does and where to get it.", "", "<yellow>Click to open"));
        m.inv.setItem(20, Ui.item(Material.CLOCK, "<#ffb347><bold>Milestones", "<gray>Rewards for playing, on any server.", "<gray>You've played <white>" + time(minutes(p.getUniqueId())), "", "<yellow>Click to open"));
        m.inv.setItem(21, Ui.item(Material.SUNFLOWER, "<green><bold>Money & Scrap",
                "<gray>One balance across every server.", "<gray>Money: <white>/bal /pay /baltop",
                "<gray>Scrap: earned from kills, playtime,", "<gray>challenges and voting. Spend in <white>/scrap", "<gray>on Survival and Skyblock."));
        m.inv.setItem(22, Ui.item(Material.EMERALD, "<green><bold>Bring a Friend",
                "<gray>New players type <white>/referral <name>", "<gray>and you both get paid after", "<gray>they've played an hour.", "", "<yellow>Click for details"));
        m.inv.setItem(23, Ui.item(Material.GOLD_INGOT, "<gold><bold>Ranks & Supporting",
                "<gray>Ranks are earned in game: <white>/ranks", "<gray>Supporter perks are cosmetic only.", "<gray>Store: <white>/store"));
        m.inv.setItem(24, Ui.item(Material.WRITABLE_BOOK, "<red><bold>Rules",
                "<gray>Be respectful, no cheats or exploits,", "<gray>no spam or advertising.", "<gray>See something? <white>/report <name> <why>", "", "<yellow>Click for the full rules"));
        m.inv.setItem(25, Ui.item(Material.AMETHYST_SHARD, "<#8c9eff><bold>Discord", "<gray>Events, updates and support.", "<gray>Link your account with <white>/link", "<gray>for your rank roles and a reward.", "", "<yellow>Click for the invite"));
        if (server.equals("hub")) m.inv.setItem(31, Ui.item(Material.MAP, "<#4fd1c5><bold>Tutorial", "<gray>A quick tour of the hub.", "", "<yellow>Click to start"));
        m.inv.setItem(28, Ui.item(Material.BELL, "<#ffb347><bold>Events", "<gray>World bosses, KOTH, Double Scrap,", "<gray>the Weekend Cup and more.", "", Events.sidebarLine(), "", "<yellow>Click for the calendar"));
        m.inv.setItem(29, Ui.item(Material.EXPERIENCE_BOTTLE, "<gold><bold>Achievements", "<gray>" + Achievements.ALL.size() + " goals, each pays once.", "", "<yellow>Click to open"));
        m.inv.setItem(30, Ui.item(Material.CAKE, "<#7bd88f><bold>Daily Calendar", "<gray>Log in every day for bigger", "<gray>rewards, up to day 7.",
                "", calendarReady(load(p.getUniqueId())) ? "<green>Today's reward is ready!" : "<gray>Come back tomorrow", "<yellow>Click to open"));
        m.inv.setItem(32, Ui.item(Material.PLAYER_HEAD, "<#7bd88f><bold>Friends", "<gray>See who's online and where.", "<white>/friend add <name>", "", "<yellow>Click for your list"));
        m.inv.setItem(33, Ui.item(Material.FIREWORK_ROCKET, "<#ffb347><bold>Party", "<gray>Play together: follow the leader", "<gray>between servers, queue together.", "<white>/party invite <name>", "", "<yellow>Click for details"));
        m.inv.setItem(34, Ui.item(Material.PAPER, "<white><bold>What's New", "<gray>The latest updates.", "", "<yellow>Click to read"));
        p.openInventory(m.inv);
    }

    public void openItems(Player p) {
        Menu m = new Menu("items");
        m.inv = Bukkit.createInventory(m, 36, Ui.mm("<dark_gray>Custom Items"));
        int slot = 10;
        for (Guided g : ITEMS) {
            List<String> lore = new ArrayList<>();
            for (String line : g.what.split("\\|")) lore.add("<gray>" + line);
            lore.add("");
            lore.add("<gold>Get it: <white>" + g.where);
            ItemStack it = Ui.item(g.base, g.name, lore.toArray(String[]::new));
            if (textures && g.id != null) it.editMeta(meta -> meta.setItemModel(new NamespacedKey("blackforge", g.id)));
            m.inv.setItem(slot, it);
            slot += slot % 9 == 7 ? 3 : 1;
        }
        m.inv.setItem(31, Ui.item(Material.ARROW, "<gray>Back to the guide"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        if (m.page.equals("calendar")) {
            if (slot == 8) openGuide(p);
            else if (slot == calendarDay(load(p.getUniqueId())) - 1) claimCalendar(p);
            return;
        }
        if (m.page.equals("milestones") || m.page.equals("items")) {
            if (e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) openGuide(p);
            return;
        }
        switch (slot) {
            case 11, 12, 13, 14, 15 -> {
                String target = SERVERS[slot - 11][0];
                if (target.equals(server)) return;
                p.closeInventory();
                plugin.net().send(p, target);
            }
            case 19 -> openItems(p);
            case 20 -> openMilestones(p);
            case 22 -> { p.closeInventory(); referral(p, new String[0]); }
            case 23 -> { p.closeInventory(); p.performCommand("store"); }
            case 24 -> { p.closeInventory(); p.sendMessage(Ui.mm("<gold>✦ <gray>Full rules: <u><click:open_url:'https://blackforgemc.com/rules.html'><#4fd1c5>blackforgemc.com/rules</click></u>")); }
            case 25 -> { p.closeInventory(); p.performCommand("discord"); }
            case 31 -> { if (server.equals("hub")) { p.closeInventory(); p.performCommand("tutorial"); } }
            case 28 -> p.performCommand("events");
            case 29 -> p.performCommand("achievements");
            case 30 -> openCalendar(p);
            case 32 -> { p.closeInventory(); p.performCommand("friend list"); }
            case 33 -> { p.closeInventory(); p.performCommand("party list"); }
            case 34 -> { p.closeInventory(); p.performCommand("news"); }
            default -> { }
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    /** Plain /help (and /?) opens the guide; /help with arguments still goes to the server's normal help. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHelp(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim().toLowerCase(Locale.ROOT);
        if (msg.equals("/help") || msg.equals("/?") || msg.equals("/essentials:help") || msg.equals("/minecraft:help")) {
            e.setCancelled(true);
            openGuide(e.getPlayer());
        }
    }

    // ------------------------------------------------------------------ commands

    private interface Cmd { void run(CommandSender s, String[] args); }

    private static void cmd(String name, List<String> aliases, Cmd c) {
        Bukkit.getCommandMap().register("alloy", new Command(name, "BlackForgeMC " + name, "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) { c.run(s, args); return true; }
        });
    }
}
