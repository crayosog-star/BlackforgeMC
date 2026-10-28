package net.alloymc.core.arcade.core;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Match rewards in the network's existing money + Scrap (NetEconomy, shared across servers). Amounts are
 * configurable per game in arcade/config.yml. Every payout goes through a ledger so it can only happen once,
 * and only players who are still in the match at the end (and played long enough) are paid.
 */
public final class RewardsService {
    private final AlloyCore plugin;
    private final RewardLedger ledger = new RewardLedger(50_000);
    private ConfigurationSection cfg;

    public RewardsService(AlloyCore plugin, ConfigurationSection cfg) { this.plugin = plugin; this.cfg = cfg; }

    public void reload(ConfigurationSection c) { this.cfg = c; }

    private double num(String game, String key, double def) {
        if (cfg == null) return def;
        if (cfg.contains(game + "." + key)) return cfg.getDouble(game + "." + key);
        return cfg.getDouble("default." + key, def);
    }

    private void pay(Player p, Match m, String reason, double money, long scrap) {
        if (money <= 0 && scrap <= 0) return;
        if (!ledger.claim(m.id, p.getUniqueId(), reason)) return;
        NetEconomy eco = plugin.economy();
        if (money > 0) eco.deposit(p, money);
        if (scrap > 0) eco.addScrap(p, scrap);
        plugin.getLogger().info("[Arcade] reward " + m.id + " " + p.getName() + " " + reason + " " + NetEconomy.money(money) + " + " + scrap + " Scrap");
        p.sendMessage(Ui.mm("<dark_gray>+ <green>" + NetEconomy.money(money) + (scrap > 0 ? " <#c6a15b>+" + scrap + " Scrap" : "") + " <gray>(" + reason + ")"));
    }

    public void kill(Match m, Player killer) {
        // each kill is its own ledger entry: matchId|player|kill#n
        long n = m.kills.getOrDefault(killer.getUniqueId(), 0);
        pay(killer, m, "kill #" + n, num(m.game.id, "kill-money", 10), (long) num(m.game.id, "kill-scrap", 1));
    }

    public void finish(Match m) {
        int minSeconds = (int) num(m.game.id, "min-seconds", 45);
        for (UUID u : m.players()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) continue;                 // disconnected players get nothing
            long joined = m.joinedAt.getOrDefault(u, System.currentTimeMillis());
            boolean longEnough = (System.currentTimeMillis() - joined) / 1000 >= minSeconds;
            boolean won = m.winners.contains(u);
            net.alloymc.core.Hooks.arcadeEnd(p, m.game.id, won);
            if (won) pay(p, m, "win", num(m.game.id, "win-money", 150), (long) num(m.game.id, "win-scrap", 15));
            if (longEnough) pay(p, m, "played", num(m.game.id, "play-money", 25), (long) num(m.game.id, "play-scrap", 2));
        }
    }
}
