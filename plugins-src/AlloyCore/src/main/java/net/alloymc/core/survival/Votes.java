package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.FireworkMeta;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Vote rewards. Votifier (from server-list sites) fires a vote; the voter gets a Spark Key and
 * some money, offline voters get theirs when they next join, and every N votes the whole
 * server gets a vote party.
 */
public final class Votes implements Listener {
    private final AlloyCore plugin;
    private final Crates crates;
    private final File file;
    private final YamlConfiguration data;
    private final int partyEvery, money;
    private net.milkbowl.vault.economy.Economy eco;
    private Scrap scrap;

    void setScrap(Scrap s) { this.scrap = s; }

    Votes(AlloyCore plugin, Crates crates) {
        this.plugin = plugin;
        this.crates = crates;
        this.file = new File(plugin.getDataFolder(), "votes.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.partyEvery = plugin.getConfig().getInt("votes.party-every", 50);
        this.money = plugin.getConfig().getInt("votes.money", 250);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        hookVotifier();
    }

    void setEconomy(net.milkbowl.vault.economy.Economy eco) { this.eco = eco; }

    /** Listen for Votifier's event by name so AlloyCore still loads on servers without Votifier. */
    @SuppressWarnings("unchecked")
    private void hookVotifier() {
        try {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName("com.vexsoftware.votifier.model.VotifierEvent");
            var getVote = type.getMethod("getVote");
            Bukkit.getPluginManager().registerEvent(type, this, EventPriority.NORMAL, (l, ev) -> {
                if (!type.isInstance(ev)) return;
                try {
                    Object vote = getVote.invoke(ev);
                    String user = (String) vote.getClass().getMethod("getUsername").invoke(vote);
                    String service = (String) vote.getClass().getMethod("getServiceName").invoke(vote);
                    // Votifier may call us off the main thread
                    Bukkit.getScheduler().runTask(plugin, () -> vote(user, service));
                } catch (Exception ex) { plugin.getLogger().warning("bad vote event: " + ex); }
            }, plugin);
            plugin.getLogger().info("Hooked into Votifier - vote rewards are live");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("Votifier not installed - votes can only be simulated with /alloy vote test");
        } catch (Exception e) {
            plugin.getLogger().warning("Could not hook Votifier: " + e);
        }
    }

    private void save() { try { data.save(file); } catch (Exception e) { plugin.getLogger().warning("votes save failed: " + e.getMessage()); } }

    public void vote(String user, String service) {
        if (user == null || user.isBlank() || !user.matches("[A-Za-z0-9_]{1,16}")) return;
        String key = user.toLowerCase(Locale.ROOT);
        data.set("total." + key, data.getInt("total." + key) + 1);
        int count = data.getInt("party") + 1;
        Player p = Bukkit.getPlayerExact(user);
        var who = p != null ? p : Bukkit.getOfflinePlayerIfCached(user);
        if (who != null) streak(who.getUniqueId());
        if (p != null) reward(p, 1);
        else data.set("pending." + key, data.getInt("pending." + key) + 1);
        Bukkit.broadcast(Ui.mm("<#5fd35f>✔ <white>" + user + " <gray>voted on <white>" + (service == null ? "a server list" : service)
                + " <gray>and got a <#ffe45c>Spark Key<gray>! <yellow><u>/vote</u> <dark_gray>(" + count + "/" + partyEvery + " to a vote party)")
                .clickEvent(ClickEvent.runCommand("/vote")));
        if (count >= partyEvery) { data.set("party", 0); party(); } else data.set("party", count);
        save();
    }

    static final int[] STREAK_DAYS = {3, 7, 14, 30};
    static final int[] STREAK_MONEY = {500, 1500, 3000, 7500}, STREAK_SCRAP = {50, 150, 300, 750};

    private java.io.File streakFile(java.util.UUID id) { return new java.io.File(plugin.shared(), "votes/" + id + ".yml"); }

    /** One more vote: counts towards today's streak (once a day); a missed day starts it over. */
    private void streak(java.util.UUID id) {
        String today = java.time.LocalDate.now().toString(), yesterday = java.time.LocalDate.now().minusDays(1).toString();
        net.alloymc.core.Shared.update(streakFile(id), y -> {
            y.set("total", y.getInt("total") + 1);
            String last = y.getString("last");
            if (today.equals(last)) return;
            int s = yesterday.equals(last) ? y.getInt("streak") + 1 : 1;
            y.set("streak", s);
            y.set("best-streak", Math.max(s, y.getInt("best-streak")));
            y.set("last", today);
        });
    }

    /** Streak bonuses (day 3, 7, 14, 30), paid once each time the streak reaches them. */
    private void streakBonus(Player p) {
        var f = streakFile(p.getUniqueId());
        var y = net.alloymc.core.Shared.read(f);
        int s = y.getInt("streak");
        net.alloymc.core.Hooks.statMax(p, "vote", y.getInt("total"));
        net.alloymc.core.Hooks.statMax(p, "vote_streak", s);
        for (int i = 0; i < STREAK_DAYS.length; i++) {
            if (s != STREAK_DAYS[i] || (y.getString("last") + "@" + s).equals(y.getString("paid"))) continue;
            int k = i;
            net.alloymc.core.Shared.update(f, z -> z.set("paid", z.getString("last") + "@" + s));
            plugin.economy().deposit(p, STREAK_MONEY[k]);
            plugin.economy().addScrap(p, STREAK_SCRAP[k]);
            p.sendMessage(Ui.mm("<#5fd35f>🔥 <white>" + s + "-day vote streak! <green>+$" + String.format("%,d", STREAK_MONEY[k]) + " <#c6a15b>+" + STREAK_SCRAP[k] + " Scrap"));
        }
        if (s > 0 && s < 30) {
            int next = java.util.Arrays.stream(STREAK_DAYS).filter(d -> d > s).findFirst().orElse(30);
            p.sendMessage(Ui.mm("<gray>Vote streak: <white>" + s + " day" + (s == 1 ? "" : "s") + " <dark_gray>- <gray>bonus at " + next + " days. Vote every day to keep it!"));
        }
    }

    private void reward(Player p, int votes) {
        streakBonus(p);
        crates.give(p, "vote", votes);
        if (eco != null) eco.depositPlayer(p, (double) money * votes);
        if (scrap != null) scrap.add(p, 20L * votes, "voted");
        p.sendMessage(Ui.mm("<#5fd35f>Thanks for voting! <gray>+" + votes + " Spark Key" + (votes > 1 ? "s" : "") + " and <green>$" + money * votes + "<gray>."));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    private void party() {
        Bukkit.broadcast(Ui.mm("<#5fd35f><bold>🎉 VOTE PARTY!</bold> <gray>" + partyEvery + " votes reached - everyone online gets a <#ffe45c>Spark Key<gray> and a <#ffb52e>Molten Key<gray>!"));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(Ui.mm("<#5fd35f><bold>VOTE PARTY!"), Ui.mm("<gray>Thanks for voting for BlackForgeMC")));
            crates.give(p, "vote", 1);
            crates.give(p, "gold", 1);
            p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class, fw -> {
                FireworkMeta m = fw.getFireworkMeta();
                m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.LIME, Color.YELLOW).trail(true).build());
                m.setPower(1);
                fw.setFireworkMeta(m);
            });
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String key = "pending." + p.getName().toLowerCase(Locale.ROOT);
        int owed = data.getInt(key);
        if (owed <= 0) return;
        data.set(key, null);
        save();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) reward(p, owed); }, 60L);
    }

    /** /vote: the vote links, your total and the vote party progress. */
    public void info(Player p) {
        p.sendMessage(Ui.mm("<#5fd35f><bold>Vote for BlackForgeMC</bold> <gray>- every vote gives you a <#ffe45c>Spark Key <gray>and <green>$" + money));
        List<String> sites = plugin.getConfig().getStringList("votes.sites");
        if (sites.isEmpty()) p.sendMessage(Ui.mm("<gray>Vote links are coming soon!"));
        int i = 1;
        for (String url : sites) {
            p.sendMessage(Ui.mm("<yellow>" + i++ + ". <white><u>" + url + "</u>").clickEvent(ClickEvent.openUrl(url)));
        }
        int total = data.getInt("total." + p.getName().toLowerCase(Locale.ROOT));
        int streak = net.alloymc.core.Shared.read(streakFile(p.getUniqueId())).getInt("streak");
        String last = net.alloymc.core.Shared.read(streakFile(p.getUniqueId())).getString("last");
        boolean alive = java.time.LocalDate.now().toString().equals(last) || java.time.LocalDate.now().minusDays(1).toString().equals(last);
        p.sendMessage(Ui.mm("<gray>Vote streak: <white>" + (alive ? streak : 0) + " day" + ((alive ? streak : 0) == 1 ? "" : "s")
                + " <dark_gray>| <gray>bonuses at 3, 7, 14 and 30 days in a row"));
        int party = data.getInt("party");
        p.sendMessage(Ui.mm("<gray>Your votes: <white>" + total + " <dark_gray>| <gray>Vote party: <white>" + party + "/" + partyEvery));
    }

    public String partyStatus() { return data.getInt("party") + "/" + partyEvery; }
}
