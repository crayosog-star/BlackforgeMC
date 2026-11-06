package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.io.File;
import java.time.Duration;
import java.util.*;

/**
 * Kindle Draw (Survival 2 experiment "kindle-draw"): a lottery. Tickets cost a fixed price and go into the pot; the
 * house keeps a cut, so the draw also takes money out of the economy. Every few hours one ticket wins the pot; more
 * tickets means better odds. Staff can force a draw with /draw now.
 */
final class KindleDraw {
    static final String NAME = "<gradient:#ffd76a:#ff8a3d><bold>Kindle Draw</bold></gradient>";
    private static final String ID = "kindle-draw";

    private final LabExperiments.Ctx c;
    private final NetEconomy eco;
    private final File file;
    private final YamlConfiguration data;
    private final double price, cut;
    private final int maxTickets;
    private final long everyMillis;
    private final Random random = new Random();

    KindleDraw(LabExperiments.Ctx c) {
        this.c = c;
        this.eco = c.plugin().economy();
        this.file = new File(c.plugin().getDataFolder(), "lab/draw.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        var cfg = c.plugin().getConfig();
        price = cfg.getDouble("lab-experiments.kindle-draw.ticket-price", 100);
        cut = cfg.getDouble("lab-experiments.kindle-draw.house-cut", 0.10);
        maxTickets = cfg.getInt("lab-experiments.kindle-draw.max-tickets", 100);
        everyMillis = cfg.getLong("lab-experiments.kindle-draw.every-hours", 6) * 3_600_000L;
        if (data.getLong("next") <= 0) { data.set("next", System.currentTimeMillis() + everyMillis); save(); }
        LabExperiments.command(c.plugin(), ID, "draw", "Kindle Draw lottery", List.of("lottery", "kindle"), this::command);
        Bukkit.getScheduler().runTaskTimer(c.plugin(), this::tick, 200L, 20L * 30);
    }

    private void command(CommandSender s, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("now") && s.hasPermission("blackforge.staff")) { draw(); return; }
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        if (a.length > 0 && a[0].equalsIgnoreCase("buy")) {
            int n = a.length > 1 ? parse(a[1]) : 1;
            int have = mine(p.getUniqueId());
            if (n < 1) { p.sendMessage(Ui.mm("<red>/draw buy <tickets>")); return; }
            if (have + n > maxTickets) { p.sendMessage(Ui.mm("<red>You can hold up to " + maxTickets + " tickets per draw (you have " + have + ").")); return; }
            double cost = LabMath.round2(n * price);
            if (!eco.withdraw(p, cost)) { p.sendMessage(Ui.mm("<red>" + n + " tickets cost " + NetEconomy.money(cost) + ".")); return; }
            data.set("tickets." + p.getUniqueId(), have + n);
            data.set("names." + p.getUniqueId(), p.getName());
            save();
            p.sendMessage(Ui.mm(NAME + " <gray>Bought <white>" + n + "<gray> ticket" + (n == 1 ? "" : "s") + ". You hold <white>" + (have + n)
                    + "<gray> of " + total() + ". Pot: <green>" + NetEconomy.money(pot())));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.6f);
            return;
        }
        int have = mine(p.getUniqueId());
        long t = total();
        p.sendMessage(Ui.mm(NAME));
        p.sendMessage(Ui.mm("<gray>Pot: <green>" + NetEconomy.money(pot()) + " <dark_gray>(" + t + " tickets, " + Math.round(cut * 100) + "% house cut)"));
        p.sendMessage(Ui.mm("<gray>Your tickets: <white>" + have + (t > 0 && have > 0 ? " <dark_gray>(" + Math.round(100.0 * have / t) + "% chance)" : "")));
        p.sendMessage(Ui.mm("<gray>Draw in <white>" + LabMath.clock(Math.max(0, (data.getLong("next") - System.currentTimeMillis()) / 1000) / 60) + " <gray>(h:mm)"));
        p.sendMessage(Ui.mm("<u><click:run_command:'/draw buy 1'><#ffd76a>[Buy 1 · " + NetEconomy.money(price) + "]</click></u> "
                + "<u><click:run_command:'/draw buy 10'><#ffd76a>[Buy 10 · " + NetEconomy.money(price * 10) + "]</click></u>"));
    }

    private void tick() {
        if (!Lab.enabled(ID)) return;
        long left = data.getLong("next") - System.currentTimeMillis();
        if (left <= 0) draw();
        else if (left <= 5 * 60_000L && !data.getBoolean("warned") && total() > 0) {
            data.set("warned", true); save();
            for (Player p : Bukkit.getOnlinePlayers())
                p.sendMessage(Ui.mm(NAME + " <gray>draws in 5 minutes. Pot: <green>" + NetEconomy.money(pot()) + " <u><click:run_command:'/draw'><#ffd76a>[Tickets]</click></u>"));
        }
    }

    private void draw() {
        ConfigurationSection sec = data.getConfigurationSection("tickets");
        List<UUID> who = new ArrayList<>();
        List<Integer> tickets = new ArrayList<>();
        if (sec != null) for (String k : sec.getKeys(false)) { who.add(UUID.fromString(k)); tickets.add(sec.getInt(k)); }
        double pot = pot();
        int w = LabMath.drawWinner(tickets, random.nextDouble());
        UUID winner = w < 0 ? null : who.get(w);
        OfflinePlayer op = winner == null ? null : Bukkit.getOfflinePlayer(winner);
        String name = winner == null ? "" : data.getString("names." + winner, op.getName() == null ? "?" : op.getName());
        if (winner != null && !eco.deposit(op, pot)) {
            // keep the tickets so the pot isn't lost; try again in 5 minutes
            c.plugin().getLogger().severe("kindle draw payout failed, retrying in 5 min: " + winner + " " + pot);
            data.set("next", System.currentTimeMillis() + 5 * 60_000L);
            save();
            return;
        }
        data.set("tickets", null); data.set("names", null); data.set("warned", false);
        data.set("next", System.currentTimeMillis() + everyMillis);
        if (winner == null) { save(); return; }
        data.set("last", name + " won " + NetEconomy.money(pot));
        save();
        c.plugin().getLogger().info("kindle draw: " + name + " won " + pot + " with " + tickets.get(w) + "/" + tickets.stream().mapToInt(i -> i).sum() + " tickets");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(Ui.mm(NAME), Ui.mm("<white>" + name + " <gray>wins <green>" + NetEconomy.money(pot)),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        }
        Player online = op.getPlayer();
        if (online != null) online.getWorld().spawn(online.getLocation(), Firework.class, f -> {
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.fromRGB(0xffd76a), Color.fromRGB(0xff8a3d)).withFlicker().build());
            m.setPower(1);
            f.setFireworkMeta(m);
        });
    }

    private int mine(UUID id) { return data.getInt("tickets." + id); }

    private long total() {
        ConfigurationSection s = data.getConfigurationSection("tickets");
        long n = 0;
        if (s != null) for (String k : s.getKeys(false)) n += s.getInt(k);
        return n;
    }

    private double pot() { return LabMath.drawPot(total(), price, cut); }

    private void save() {
        try { file.getParentFile().mkdirs(); data.save(file); }
        catch (Exception ex) { c.plugin().getLogger().warning("draw.yml save failed: " + ex.getMessage()); }
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return -1; } }
}
