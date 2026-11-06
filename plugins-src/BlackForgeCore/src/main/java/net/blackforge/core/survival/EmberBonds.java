package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Ember Bonds (Survival 2 experiment "ember-bonds"): lock money away for a short or a long term and get it back with
 * interest when it matures, paid even if you're offline. Each player can only have so much locked at once and there is
 * no early withdrawal, so it rewards saving without snowballing.
 */
final class EmberBonds {
    static final String NAME = "<gradient:#f2c14e:#a8742e><bold>Ember Bonds</bold></gradient>";
    private static final String ID = "ember-bonds";

    private final LabExperiments.Ctx c;
    private final NetEconomy eco;
    private final File file;
    private final YamlConfiguration data;
    private final int shortDays, longDays;
    private final double shortRate, longRate, cap, min;

    EmberBonds(LabExperiments.Ctx c) {
        this.c = c;
        this.eco = c.plugin().economy();
        this.file = new File(c.plugin().getDataFolder(), "lab/bonds.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        var cfg = c.plugin().getConfig();
        shortDays = cfg.getInt("lab-experiments.ember-bonds.short-days", 3);
        shortRate = cfg.getDouble("lab-experiments.ember-bonds.short-rate-percent", 3);
        longDays = cfg.getInt("lab-experiments.ember-bonds.long-days", 7);
        longRate = cfg.getDouble("lab-experiments.ember-bonds.long-rate-percent", 8);
        cap = cfg.getDouble("lab-experiments.ember-bonds.max-locked", 50_000);
        min = cfg.getDouble("lab-experiments.ember-bonds.min", 1_000);
        LabExperiments.command(c.plugin(), ID, "bonds", "Ember Bonds: lock money for interest", List.of("bond"), this::command);
        Bukkit.getScheduler().runTaskTimer(c.plugin(), this::payOut, 300L, 20L * 60);
    }

    private void command(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        if (a.length >= 3 && a[0].equalsIgnoreCase("buy")) { buy(p, a[1], a[2]); return; }
        ConfigurationSection mine = data.getConfigurationSection("bonds." + p.getUniqueId());
        p.sendMessage(Ui.mm(NAME));
        p.sendMessage(Ui.mm("<gray>Short: <white>" + shortDays + " days, +" + shortRate + "%  <gray>Long: <white>" + longDays + " days, +" + longRate + "%"));
        p.sendMessage(Ui.mm("<gray>From " + NetEconomy.money(min) + ", up to <white>" + NetEconomy.money(cap) + "<gray> locked at once. No early withdrawal."));
        long now = System.currentTimeMillis();
        if (mine != null) for (String k : mine.getKeys(false)) {
            double amt = mine.getDouble(k + ".amount");
            long due = mine.getLong(k + ".start") + mine.getInt(k + ".days") * 86_400_000L;
            p.sendMessage(Ui.mm("<dark_gray>• <white>" + NetEconomy.money(amt) + " <gray>→ <green>" + NetEconomy.money(LabMath.bondPayout(amt, mine.getDouble(k + ".rate")))
                    + " <gray>in " + Math.max(0, (due - now) / 3_600_000L) + "h"));
        }
        p.sendMessage(Ui.mm("<white>/bonds buy short|long <amount>"));
    }

    private void buy(Player p, String term, String amountText) {
        boolean shortTerm = term.equalsIgnoreCase("short") || term.equals(String.valueOf(shortDays));
        boolean longTerm = term.equalsIgnoreCase("long") || term.equals(String.valueOf(longDays));
        if (!shortTerm && !longTerm) { p.sendMessage(Ui.mm("<red>/bonds buy short|long <amount>")); return; }
        double amount = NetEconomy.parseAmount(amountText);
        if (!NetEconomy.validPrice(amount)) { p.sendMessage(Ui.mm("<red>That amount doesn't work.")); return; }
        double locked = locked(p.getUniqueId());
        if (!LabMath.bondFits(locked, amount, cap, min)) {
            p.sendMessage(Ui.mm("<red>Bonds are " + NetEconomy.money(min) + " or more, with at most " + NetEconomy.money(cap) + " locked (you have " + NetEconomy.money(locked) + ")."));
            return;
        }
        if (!eco.withdraw(p, amount)) { p.sendMessage(Ui.mm("<red>You don't have " + NetEconomy.money(amount) + ".")); return; }
        int days = shortTerm ? shortDays : longDays;
        double rate = shortTerm ? shortRate : longRate;
        String k = "bonds." + p.getUniqueId() + "." + UUID.randomUUID().toString().substring(0, 8);
        data.set(k + ".amount", amount); data.set(k + ".rate", rate); data.set(k + ".days", days); data.set(k + ".start", System.currentTimeMillis());
        save();
        p.sendMessage(Ui.mm(NAME + " <gray>Locked <white>" + NetEconomy.money(amount) + "<gray> for " + days + " days. You'll get <green>"
                + NetEconomy.money(LabMath.bondPayout(amount, rate)) + "<gray> back."));
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 0.8f);
    }

    private double locked(UUID id) {
        ConfigurationSection s = data.getConfigurationSection("bonds." + id);
        double n = 0;
        if (s != null) for (String k : s.getKeys(false)) n += s.getDouble(k + ".amount");
        return n;
    }

    private void payOut() {
        if (!Lab.enabled(ID)) return;   // switched off: bonds wait, nothing is lost
        ConfigurationSection all = data.getConfigurationSection("bonds");
        if (all == null) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (String who : all.getKeys(false)) {
            ConfigurationSection mine = all.getConfigurationSection(who);
            if (mine == null) continue;
            for (String k : mine.getKeys(false)) {
                if (!LabMath.bondMatured(mine.getLong(k + ".start"), now, mine.getInt(k + ".days"))) continue;
                double pay = LabMath.bondPayout(mine.getDouble(k + ".amount"), mine.getDouble(k + ".rate"));
                OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(who));
                if (!eco.deposit(op, pay)) { c.plugin().getLogger().severe("bond payout failed " + who + " " + pay); continue; }
                mine.set(k, null);
                changed = true;
                Player p = op.getPlayer();
                if (p != null) { p.sendMessage(Ui.mm(NAME + " <gray>A bond matured: <green>+" + NetEconomy.money(pay))); p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f); }
            }
        }
        if (changed) save();
    }

    private void save() {
        try { file.getParentFile().mkdirs(); data.save(file); }
        catch (Exception ex) { c.plugin().getLogger().warning("bonds.yml save failed: " + ex.getMessage()); }
    }
}
