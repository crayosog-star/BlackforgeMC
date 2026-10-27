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
        if (p != null) reward(p, 1);
        else data.set("pending." + key, data.getInt("pending." + key) + 1);
        Bukkit.broadcast(Ui.mm("<#5fd35f>✔ <white>" + user + " <gray>voted on <white>" + (service == null ? "a server list" : service)
                + " <gray>and got a <#ffe45c>Spark Key<gray>! <yellow><u>/vote</u> <dark_gray>(" + count + "/" + partyEvery + " to a vote party)")
                .clickEvent(ClickEvent.runCommand("/vote")));
        if (count >= partyEvery) { data.set("party", 0); party(); } else data.set("party", count);
        save();
    }

    private void reward(Player p, int votes) {
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
        int party = data.getInt("party");
        p.sendMessage(Ui.mm("<gray>Your votes: <white>" + total + " <dark_gray>| <gray>Vote party: <white>" + party + "/" + partyEvery));
    }

    public String partyStatus() { return data.getInt("party") + "/" + partyEvery; }
}
