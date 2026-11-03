package net.blackforge.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Consumer;

/**
 * Build Showcase: stand at something you built on Survival, Skyblock or Factions and nominate it; everybody votes for the
 * best one of the week (one vote each, not for yourself). On Monday the winner gets Cosmetic Keys and Forge Dust, and their
 * build becomes the Featured Build everyone can visit with {@code /showcase visit} - from any server.
 * <p>
 * Nominations are one file per week in shared/showcase, changed under a lock.
 */
final class Showcase implements Listener {
    private static final Set<String> BUILD_MODES = Set.of("survival", "skyblock", "factions");
    private static final int PRIZE_KEYS = 2, PRIZE_DUST = 100;

    private final BlackForgeCore plugin;
    private final String mode;
    private final Cosmetics cos;
    private final Net net;
    private final File dir;

    Showcase(BlackForgeCore plugin, String mode, Cosmetics cos, Net net, File shared) {
        this.plugin = plugin;
        this.mode = mode;
        this.cos = cos;
        this.net = net;
        this.dir = new File(shared, "showcase");
        dir.mkdirs();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::settle, 1200L, 12_000L);
        Bukkit.getCommandMap().register("blackforge", new Command("showcase", "Nominate and vote for the best build", "/showcase [nominate <note>|visit|vote <player>|retract]", List.of("builds", "featured")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "nominate" -> nominate(p, a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "");
                    case "retract" -> retract(p);
                    case "visit" -> visit(p);
                    case "vote" -> {
                        if (a.length < 2) { p.sendMessage(Ui.mm("<red>/showcase vote <player>")); break; }
                        var t = Bukkit.getOfflinePlayerIfCached(a[1]);
                        if (t == null) p.sendMessage(Ui.mm("<red>Never seen " + a[1] + ".")); else vote(p, t.getUniqueId());
                    }
                    default -> menu(p);
                }
                return true;
            }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) {
                return a.length == 1 ? List.of("nominate", "vote", "visit", "retract") : List.of();
            }
        });
    }

    private static String weekKey() { return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(); }

    private File weekFile(String key) { return new File(dir, "week-" + key + ".yml"); }

    private File featuredFile() { return new File(dir, "featured.yml"); }

    // ------------------------------------------------------------------ nominating and voting

    void nominate(Player p, String note) {
        if (!BUILD_MODES.contains(mode)) { p.sendMessage(Ui.mm("<red>Nominate a build while you're standing at it on Survival, Skyblock or Factions.")); return; }
        if (note.length() > 60) note = note.substring(0, 60);
        Location l = p.getLocation();
        String n = note.isBlank() ? "A build by " + p.getName() : note;
        Shared.update(weekFile(weekKey()), y -> {
            String b = "noms." + p.getUniqueId();
            boolean had = y.contains(b);
            y.set(b + ".name", p.getName());
            y.set(b + ".mode", mode);
            y.set(b + ".world", l.getWorld().getName());
            y.set(b + ".x", l.getX()); y.set(b + ".y", l.getY()); y.set(b + ".z", l.getZ());
            y.set(b + ".yaw", (double) l.getYaw()); y.set(b + ".pitch", (double) l.getPitch());
            y.set(b + ".note", n);
            if (!had) y.set(b + ".at", System.currentTimeMillis());
        });
        p.sendMessage(Ui.mm("<green>✔ <gray>Nominated where you're standing: <white>" + n + "<gray>. Others can vote for it with <white>/showcase<gray>. Run it again somewhere else to move it."));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
    }

    void retract(Player p) {
        Shared.update(weekFile(weekKey()), y -> y.set("noms." + p.getUniqueId(), null));
        p.sendMessage(Ui.mm("<gray>Your nomination is withdrawn."));
    }

    void vote(Player p, UUID target) {
        if (target.equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>You can't vote for your own build.")); return; }
        String[] r = {null};
        Shared.update(weekFile(weekKey()), y -> {
            if (!y.contains("noms." + target)) { r[0] = "They haven't nominated a build this week."; return; }
            // one vote each: take it off whatever you voted for before
            ConfigurationSection noms = y.getConfigurationSection("noms");
            for (String k : noms.getKeys(false)) {
                List<String> v = new ArrayList<>(y.getStringList("noms." + k + ".votes"));
                if (v.remove(p.getUniqueId().toString())) y.set("noms." + k + ".votes", v);
            }
            List<String> v = new ArrayList<>(y.getStringList("noms." + target + ".votes"));
            v.add(p.getUniqueId().toString());
            y.set("noms." + target + ".votes", v);
        });
        if (r[0] != null) { p.sendMessage(Ui.mm("<red>" + r[0])); return; }
        p.sendMessage(Ui.mm("<green>✔ <gray>Your vote is in. You can change it any time this week."));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
    }

    // ------------------------------------------------------------------ visiting

    void visit(Player p) {
        YamlConfiguration f = Shared.read(featuredFile());
        if (!f.contains("world")) { p.sendMessage(Ui.mm("<gray>No featured build yet - the first winner is picked on Monday. <white>/showcase<gray> to nominate or vote.")); return; }
        String where = f.getString("mode", "");
        if (where.equals(mode)) { go(p, f); return; }
        p.sendMessage(Ui.mm("<gray>The featured build is on <white>" + where + "<gray>. Taking you there..."));
        Shared.update(featuredFile(), y -> y.set("pending." + p.getUniqueId(), System.currentTimeMillis()));
        net.send(p, where);
    }

    private void go(Player p, ConfigurationSection s) {
        World w = Bukkit.getWorld(s.getString("world", ""));
        if (w == null) { p.sendMessage(Ui.mm("<red>That build's world isn't loaded right now.")); return; }
        Location l = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
        p.teleport(l);
        p.sendMessage(Ui.mm("<green>✦ <gray>Featured build by <white>" + s.getString("name") + "<gray>: <white>" + s.getString("note")));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            YamlConfiguration f = Shared.read(featuredFile());
            if (f.contains("pending." + p.getUniqueId())) {
                long at = f.getLong("pending." + p.getUniqueId());
                Shared.update(featuredFile(), y -> y.set("pending." + p.getUniqueId(), null));
                if (System.currentTimeMillis() - at < 60_000 && f.getString("mode", "").equals(mode)) { go(p, f); return; }
            }
            if (mode.equals("hub") && f.contains("world"))
                p.sendMessage(Ui.mm("<green>✦ <gray>Featured build this week: <white>" + f.getString("note") + " <gray>by <white>" + f.getString("name") + "<gray>. <yellow>/showcase visit"));
        }, 120L);
    }

    // ------------------------------------------------------------------ Monday: pick the winner

    /** The last finished week with nominations gets its winner (set first, so a second server that checks sees it's done). */
    private void settle() {
        String current = weekKey();
        File[] files = dir.listFiles((d, n) -> n.startsWith("week-") && n.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            String key = f.getName().substring(5, f.getName().length() - 4);
            if (key.compareTo(current) >= 0) continue;
            boolean[] mine = {false};
            Shared.update(f, y -> { if (!y.getBoolean("settled")) { y.set("settled", true); mine[0] = true; } });
            if (!mine[0]) continue;
            YamlConfiguration y = Shared.read(f);
            ConfigurationSection noms = y.getConfigurationSection("noms");
            if (noms == null) continue;
            String best = null;
            int bestVotes = 0;
            long bestAt = Long.MAX_VALUE;
            for (String k : noms.getKeys(false)) {
                int v = y.getStringList("noms." + k + ".votes").size();
                long at = y.getLong("noms." + k + ".at", Long.MAX_VALUE);
                if (v > bestVotes || (v == bestVotes && v > 0 && at < bestAt)) { best = k; bestVotes = v; bestAt = at; }
            }
            if (best == null) continue;
            final String winnerKey = best;
            UUID winner = UUID.fromString(best);
            ConfigurationSection w = y.getConfigurationSection("noms." + best);
            Shared.update(featuredFile(), fy -> {
                fy.set("name", w.getString("name")); fy.set("note", w.getString("note")); fy.set("mode", w.getString("mode")); fy.set("world", w.getString("world"));
                fy.set("x", w.getDouble("x")); fy.set("y", w.getDouble("y")); fy.set("z", w.getDouble("z")); fy.set("yaw", w.getDouble("yaw")); fy.set("pitch", w.getDouble("pitch"));
                fy.set("week", key); fy.set("winner", winnerKey);
            });
            cos.grantKeys(winner, PRIZE_KEYS);
            cos.addDust(winner, PRIZE_DUST);
            cos.addNotice(winner, "<green>🏆 <gray>Your build won the Build Showcase with <white>" + bestVotes + " votes<gray>! You got <gold>" + PRIZE_KEYS + " Keys<gray> and <#ffb347>" + PRIZE_DUST + " Dust<gray>, and it's now the Featured Build.");
            Player online = Bukkit.getPlayer(winner);
            if (online != null) cos.deliverNotices(online);
            Bukkit.broadcast(Ui.mm("<green>🏆 <gray>Build Showcase winner: <white>" + w.getString("name") + " <gray>(" + bestVotes + " votes) - <yellow>/showcase visit"));
        }
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    void menu(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 54, Ui.mm("<dark_gray>Build Showcase"));
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) m.inv.setItem(i, pane);
        YamlConfiguration y = Shared.read(weekFile(weekKey()));
        ConfigurationSection noms = y.getConfigurationSection("noms");
        String myVote = null;
        if (noms != null) for (String k : noms.getKeys(false)) if (y.getStringList("noms." + k + ".votes").contains(p.getUniqueId().toString())) myVote = k;
        List<String> keys = noms == null ? new ArrayList<>() : new ArrayList<>(noms.getKeys(false));
        keys.sort(Comparator.comparingInt((String k) -> y.getStringList("noms." + k + ".votes").size()).reversed());
        for (int i = 0; i < Math.min(45, keys.size()); i++) {
            String k = keys.get(i);
            int votes = y.getStringList("noms." + k + ".votes").size();
            boolean mine = k.equals(p.getUniqueId().toString()), voted = k.equals(myVote);
            ItemStack head = Ui.item(Material.PLAYER_HEAD, (voted ? "<green>" : "<white>") + y.getString("noms." + k + ".name"), "<gray>" + y.getString("noms." + k + ".note"),
                    "<gray>Server: <white>" + y.getString("noms." + k + ".mode"), "<gray>Votes: <white>" + votes, "",
                    mine ? "<gray>Your nomination" : voted ? "<green>Your vote" : "<yellow>Click to vote");
            head.editMeta(SkullMeta.class, sm -> sm.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(k))));
            if (voted) head.editMeta(x -> x.setEnchantmentGlintOverride(true));
            m.inv.setItem(i, head);
            if (!mine) m.actions.put(i, pl -> { vote(pl, UUID.fromString(k)); menu(pl); });
        }
        if (keys.isEmpty()) m.inv.setItem(22, Ui.item(Material.BARRIER, "<gray>Nothing nominated yet", "<gray>Stand at your build and use <white>/showcase nominate"));
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
        m.inv.setItem(46, Ui.item(Material.SPYGLASS, "<green><bold>Visit the featured build", "<gray>Last week's winner, from any server."));
        m.actions.put(46, pl -> { pl.closeInventory(); visit(pl); });
        m.inv.setItem(48, Ui.item(Material.OAK_SIGN, "<yellow><bold>Nominate where I'm standing", BUILD_MODES.contains(mode) ? "<gray>Put the build you're at into this week's vote." : "<red>Do this on Survival, Skyblock or Factions."));
        m.actions.put(48, pl -> { pl.closeInventory(); nominate(pl, ""); });
        m.inv.setItem(49, Ui.item(Material.CLOCK, "<white><bold>Winner in " + days + " day" + (days == 1 ? "" : "s"), "<gray>Prize: <gold>" + PRIZE_KEYS + " Keys<gray> + <#ffb347>" + PRIZE_DUST + " Dust", "<gray>and a week as the featured build."));
        m.inv.setItem(50, Ui.item(Material.BARRIER, "<red>Withdraw my nomination"));
        m.actions.put(50, pl -> { retract(pl); menu(pl); });
        m.inv.setItem(53, Ui.item(Material.OAK_DOOR, "<white>Close"));
        m.actions.put(53, Player::closeInventory);
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Consumer<Player> a = m.actions.get(e.getSlot());
        if (a == null) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        a.accept(p);
    }
}
