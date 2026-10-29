package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Hooks;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Nicks;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

/**
 * Bounties (Survival): /bounty &lt;player&gt; &lt;amount&gt; puts money on someone's head (it's taken from you straight away);
 * bounties on the same player add up. Whoever kills them in PvP collects the lot. /bounty list shows the most wanted.
 * Saved in bounties.yml.
 */
public final class Bounties {
    public static final int MIN = 500;

    private final AlloyCore plugin;
    private final File file;
    private final YamlConfiguration data;

    Bounties(AlloyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        Hooks.onKill((killer, victim, where) -> {
            if (where.equals("survival")) collect(killer, victim);
        });
    }

    private void save() { try { data.save(file); } catch (Exception ignored) { } }

    public double on(UUID id) { return data.getDouble(id + ".amount"); }

    public void command(Player p, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("list") || a[0].equalsIgnoreCase("top")) { list(p); return; }
        if (a.length < 2) { p.sendMessage(Ui.mm("<red>Usage: /bounty <player> <amount> <gray>or <white>/bounty list")); return; }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null) { p.sendMessage(Ui.mm("<red>Never seen a player called " + a[0].replaceAll("[^A-Za-z0-9_]", "") + ".")); return; }
        if (t.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>You can't put a bounty on yourself.")); return; }
        double amount;
        try { amount = Math.floor(Double.parseDouble(a[1].replace("$", "").replace(",", ""))); } catch (NumberFormatException e) { p.sendMessage(Ui.mm("<red>That's not an amount.")); return; }
        if (amount < MIN) { p.sendMessage(Ui.mm("<red>Bounties start at " + NetEconomy.money(MIN) + ".")); return; }
        if (!plugin.economy().withdraw(p, amount)) { p.sendMessage(Ui.mm("<red>You don't have " + NetEconomy.money(amount) + ".")); return; }
        String id = t.getUniqueId().toString();
        double total = data.getDouble(id + ".amount") + amount;
        data.set(id + ".amount", total);
        data.set(id + ".name", t.getName());
        save();
        Bukkit.broadcast(Ui.mm("<red>☠ <bold>BOUNTY</bold> <white>" + p.getName() + " <gray>put <green>" + NetEconomy.money(amount) + " <gray>on <white>"
                + t.getName() + "<gray>'s head! <dark_gray>(total " + NetEconomy.money(total) + ")"));
        for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.6f, 0.6f);
    }

    private void list(Player p) {
        List<String> ids = new ArrayList<>(data.getKeys(false));
        ids.sort((x, y) -> Double.compare(data.getDouble(y + ".amount"), data.getDouble(x + ".amount")));
        p.sendMessage(Ui.mm("<red>☠ <bold>Most Wanted</bold> <gray>- kill them in PvP to collect"));
        if (ids.isEmpty()) p.sendMessage(Ui.mm("<gray>  No bounties right now. <white>/bounty <player> <amount>"));
        for (int i = 0; i < Math.min(10, ids.size()); i++)
            p.sendMessage(Ui.mm("<gray>  " + (i + 1) + ". <white>" + data.getString(ids.get(i) + ".name") + " <dark_gray>» <green>" + NetEconomy.money(data.getDouble(ids.get(i) + ".amount"))));
    }

    private void collect(Player killer, Player victim) {
        String id = victim.getUniqueId().toString();
        double amount = data.getDouble(id + ".amount");
        if (amount <= 0) return;
        data.set(id, null);
        save();
        plugin.economy().deposit(killer, amount);
        net.alloymc.core.Hooks.stat(killer, "bounty", 1);
        Bukkit.broadcast(Ui.mm("<red>☠ <white>" + Nicks.of(killer) + " <gray>collected the <green>" + NetEconomy.money(amount) + " <gray>bounty on <white>" + Nicks.of(victim) + "<gray>!"));
        killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
    }
}
