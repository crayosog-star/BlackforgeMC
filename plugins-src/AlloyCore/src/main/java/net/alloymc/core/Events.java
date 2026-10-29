package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.time.*;
import java.util.*;

/**
 * The network's event calendar, on every server. /events lists what's on and what's next with countdowns (click to go
 * there); every server warns its own players 10 minutes before an event and again when it starts; the hub sidebar shows
 * the next one. Double Scrap windows multiply every Scrap reward ({@link #scrapMultiplier}).
 *
 * The weekly times are fixed here (server local time); KOTH runs on its own timer, so Survival writes its next start to
 * shared/live.yml and everyone else reads it. Seasonal events (Hallowforge, Frostforge) show while they run.
 */
public final class Events implements Listener {

    /** One kind of weekly event: starts on {@code day} at {@code hour}:00 and lasts {@code minutes}. */
    public record Weekly(String id, String name, String color, Material icon, String server, DayOfWeek day, int hour, int minutes, String what) {}

    public static final List<Weekly> WEEKLY = List.of(
            new Weekly("boss", "World Boss", "<#e5484d>", Material.WITHER_SKELETON_SKULL, "survival", DayOfWeek.SATURDAY, 20, 20,
                    "A random world boss attacks the KOTH arena.|Everyone who hits it shares the loot."),
            new Weekly("cup", "Weekend Cup", "<#ffc53d>", Material.GOLD_INGOT, "arcade", DayOfWeek.SATURDAY, 0, 48 * 60,
                    "Every Arcade game scores 1 point, every win 3.|Top 3 paid on Monday."),
            new Weekly("scrap_fri", "Double Scrap", "<#c6a15b>", Material.IRON_NUGGET, "all", DayOfWeek.FRIDAY, 18, 180,
                    "Every Scrap reward is doubled,|on every server."),
            new Weekly("scrap_sun", "Double Scrap", "<#c6a15b>", Material.IRON_NUGGET, "all", DayOfWeek.SUNDAY, 14, 180,
                    "Every Scrap reward is doubled,|on every server."),
            new Weekly("arcade_wed", "Arcade Rush", "<#ffc53d>", Material.TNT, "arcade", DayOfWeek.WEDNESDAY, 19, 120,
                    "Double Scrap in the Arcade and the|busiest queues of the week."));

    /** What's on or coming: one occurrence of an event. */
    public record Slot(String id, String name, String color, Material icon, String server, long start, long end, String what) {
        public boolean live(long now) { return now >= start && now < end; }
    }

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static File liveFile;

    private final AlloyCore plugin;
    private final String server;
    private final Set<String> warned = new HashSet<>();

    public Events(AlloyCore plugin, String mode, File shared) {
        this.plugin = plugin;
        this.server = mode;
        liveFile = new File(shared, "live.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 400L);
        Bukkit.getCommandMap().register("alloy", new Command("events", "BlackForgeMC event calendar", "/events", List.of("event", "schedule")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) open(p); else upcoming(System.currentTimeMillis(), 7).forEach(x -> s.sendMessage(x.name() + " " + Instant.ofEpochMilli(x.start())));
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ the calendar

    /** Every Scrap reward is multiplied by this (2 during Double Scrap; the Arcade Rush only counts in the Arcade). */
    public static double scrapMultiplier(String mode) {
        long now = System.currentTimeMillis();
        for (Slot s : upcoming(now, 1))
            if (s.live(now) && (s.id().startsWith("scrap") || (s.id().equals("arcade_wed") && "arcade".equals(mode)))) return 2;
        return 1;
    }

    /** Events that are live now or start within {@code days} days, soonest first. */
    public static List<Slot> upcoming(long now, int days) {
        List<Slot> out = new ArrayList<>();
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(ZONE);
        for (Weekly w : WEEKLY) {
            // the latest start at or before now (it may still be running), then the following weeks
            ZonedDateTime start = t.with(java.time.temporal.TemporalAdjusters.previousOrSame(w.day())).toLocalDate().atTime(w.hour(), 0).atZone(ZONE);
            if (start.isAfter(t)) start = start.minusWeeks(1);
            for (int i = 0; i <= days / 7 + 1; i++, start = start.plusWeeks(1)) {
                long s = start.toInstant().toEpochMilli(), e = s + w.minutes() * 60_000L;
                if (e <= now || s > now + days * 86_400_000L) continue;
                out.add(new Slot(w.id(), w.name(), w.color(), w.icon(), w.server(), s, e, w.what()));
            }
        }
        // KOTH: Survival publishes its own timer
        if (liveFile != null) {
            YamlConfiguration live = YamlConfiguration.loadConfiguration(liveFile);
            long next = live.getLong("koth-next"), seen = live.getLong("updated");
            if (now - seen < 10 * 60_000L) {
                if (live.getBoolean("koth-active")) out.add(new Slot("koth", "KOTH", "<#e5484d>", Material.GOLDEN_HELMET, "survival", now - 1, now + 20 * 60_000L, "Hold the hill in the KOTH arena|alone to win a crate key."));
                else if (next > now) out.add(new Slot("koth", "KOTH", "<#e5484d>", Material.GOLDEN_HELMET, "survival", next, next + 20 * 60_000L, "Hold the hill in the KOTH arena|alone to win a crate key."));
                if (live.getBoolean("boss-active")) out.removeIf(x -> x.id().equals("boss"));
                if (live.getBoolean("boss-active")) out.add(new Slot("boss", "World Boss", "<#e5484d>", Material.WITHER_SKELETON_SKULL, "survival", now - 1, now + 20 * 60_000L, "A world boss is fighting in the KOTH arena!|Hit it to share the loot."));
            }
        }
        seasonal(out, now, "hallowforge", "Hallowforge", "<gradient:#ff9a1f:#9b4dff>", Material.JACK_O_LANTERN, "2026-10-28", "2026-11-10", "Soul Candy drops in every open world.|Trade it at /hallow.");
        seasonal(out, now, "frostforge", "Frostforge", "<gradient:#bfefff:#4fa3ff>", Material.SNOWBALL, "2026-12-01", "2027-01-06", "Frost Shards drop in every open world.|Trade them at /frost.");
        out.sort(Comparator.comparingLong(Slot::start));
        return out;
    }

    private static void seasonal(List<Slot> out, long now, String id, String name, String color, Material icon, String from, String to, String what) {
        long s = LocalDate.parse(from).atStartOfDay(ZONE).toInstant().toEpochMilli(), e = LocalDate.parse(to).plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        if (e > now && s < now + 14 * 86_400_000L) out.add(new Slot(id, name, color, icon, "all", s, e, what));
    }

    /** Survival: publish KOTH and boss state for the other servers' calendars. */
    public static void publishLive(long kothNext, boolean kothActive, boolean bossActive) {
        if (liveFile == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("koth-next", kothNext);
        y.set("koth-active", kothActive);
        y.set("boss-active", bossActive);
        y.set("updated", System.currentTimeMillis());
        try { y.save(liveFile); } catch (Exception ignored) { }
    }

    public static String in(long ms) {
        long m = Math.max(0, ms / 60_000);
        if (m >= 1440) return m / 1440 + "d " + (m % 1440) / 60 + "h";
        if (m >= 60) return m / 60 + "h " + m % 60 + "m";
        return Math.max(1, m) + "m";
    }

    private static String where(String s) { return s.equals("all") ? "every server" : Social.pretty(s); }

    /** One line for sidebars: the live event, else the next one. */
    public static String sidebarLine() {
        long now = System.currentTimeMillis();
        for (Slot s : upcoming(now, 7)) {
            if (s.id().equals("hallowforge") || s.id().equals("frostforge")) continue;
            return s.live(now) ? "<green>LIVE: " + s.color() + s.name() : "<gray>Next: " + s.color() + s.name() + " <gray>" + in(s.start() - now);
        }
        return "<gray>/events";
    }

    /** For the hub selector: what's on for that server. */
    public static String statusFor(String server) {
        long now = System.currentTimeMillis();
        for (Slot s : upcoming(now, 7))
            if (s.server().equals(server) || (s.server().equals("all") && !s.id().endsWith("forge")))
                return s.live(now) ? "<green>● LIVE: " + s.color() + s.name() : "<gray>Next: " + s.color() + s.name() + " <gray>in " + in(s.start() - now);
        return null;
    }

    // ------------------------------------------------------------------ warnings

    private void tick() {
        long now = System.currentTimeMillis();
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        for (Slot s : upcoming(now, 1)) {
            if (s.id().endsWith("forge")) continue;
            if (s.id().equals("koth") && server.equals("survival")) continue;   // Survival's KOTH warns its own players
            long until = s.start() - now;
            String key10 = s.id() + "@" + s.start() + ":10", key0 = s.id() + "@" + s.start() + ":0";
            String go = s.server().equals("all") || s.server().equals(server) ? ""
                    : " <u><click:run_command:'/go " + s.server() + "'><hover:show_text:'<gray>Go to " + where(s.server()) + "'><#4fd1c5>[Go]</hover></click></u>";
            if (until > 0 && until <= 10 * 60_000L && warned.add(key10))
                broadcast("<gold>⏰ " + s.color() + "<bold>" + s.name() + "</bold> <gray>starts in <white>" + in(until) + " <gray>on " + where(s.server()) + "!" + go);
            else if (until <= 0 && now - s.start() < 2 * 60_000L && warned.add(key0)) {
                broadcast("<gold>⚑ " + s.color() + "<bold>" + s.name() + "</bold> <gray>has started on " + where(s.server()) + "!" + go);
                for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 0.7f, 1.2f);
            }
        }
        if (warned.size() > 200) warned.clear();
    }

    private void broadcast(String mm) { for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Ui.mm(mm)); }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        final List<Slot> slots = new ArrayList<>();
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        long now = System.currentTimeMillis();
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Events <gray>- what's on"));
        int[] at = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (Slot s : upcoming(now, 7)) {
            if (i >= at.length) break;
            List<String> lore = new ArrayList<>();
            for (String line : s.what().split("\\|")) lore.add("<gray>" + line);
            lore.add("");
            lore.add("<gray>Where: <white>" + where(s.server()));
            lore.add(s.live(now) ? "<green>● Live now <gray>- ends in " + in(s.end() - now)
                    : "<gray>Starts in <white>" + in(s.start() - now) + " <dark_gray>(" + Instant.ofEpochMilli(s.start()).atZone(ZONE).getDayOfWeek().toString().substring(0, 3)
                    + " " + String.format("%02d:%02d", Instant.ofEpochMilli(s.start()).atZone(ZONE).getHour(), Instant.ofEpochMilli(s.start()).atZone(ZONE).getMinute()) + ")");
            if (!s.server().equals("all") && !s.server().equals(server)) lore.add("<yellow>Click to go there");
            ItemStack it = Ui.item(s.icon(), s.color() + "<bold>" + s.name(), lore.toArray(String[]::new));
            if (s.live(now)) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(at[i++], it);
            m.slots.add(s);
        }
        m.inv.setItem(4, Ui.item(Material.CLOCK, "<gold><bold>Event Calendar", "<gray>Times are server time.", "<gray>You'll get a heads-up 10 minutes before each one."));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        int[] at = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < m.slots.size(); i++)
            if (at[i] == e.getRawSlot()) {
                Slot s = m.slots.get(i);
                if (s.server().equals("all") || s.server().equals(server)) return;
                p.closeInventory();
                plugin.net().send(p, s.server());
            }
    }
}
