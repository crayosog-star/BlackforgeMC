package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.text.event.ClickEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;

/**
 * /daily: one random reward per day. Claiming on consecutive days builds a streak that improves
 * the odds; every 7th day is a guaranteed Molten Key on top.
 */
public final class DailyRewards implements Listener {
    private record Reward(String label, double weight, Consumer<Player> give) {}

    private final AlloyCore plugin;
    private final Crates crates;
    private final CustomEnchants enchants;
    private final NameTags tags;
    private final File file;
    private final YamlConfiguration data;
    private final Random rnd = new Random();
    private final List<Reward> pool = new ArrayList<>();
    private Economy eco;
    private Scrap scrap;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    DailyRewards(AlloyCore plugin, Crates crates, CustomEnchants enchants, NameTags tags) {
        this.plugin = plugin;
        this.crates = crates;
        this.enchants = enchants;
        this.tags = tags;
        this.file = new File(plugin.getDataFolder(), "daily.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        define();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void setEconomy(Economy eco) { this.eco = eco; }
    void setScrap(Scrap s) { this.scrap = s; }

    private void define() {
        pool.add(new Reward("<green>$300", 20, p -> money(p, 300)));
        pool.add(new Reward("<green>$750", 12, p -> money(p, 750)));
        pool.add(new Reward("<green>$2,000", 4, p -> money(p, 2000)));
        pool.add(new Reward("<#c6a15b>⚙ 40 Scrap", 16, p -> scrap(p, 40)));
        pool.add(new Reward("<#c6a15b>⚙ 120 Scrap", 7, p -> scrap(p, 120)));
        pool.add(new Reward("<white>4 Golden Apples", 10, p -> item(p, new ItemStack(Material.GOLDEN_APPLE, 4))));
        pool.add(new Reward("<white>24 Bottles o' Enchanting", 9, p -> item(p, new ItemStack(Material.EXPERIENCE_BOTTLE, 24))));
        pool.add(new Reward("<white>16 Iron Ingots + 4 Diamonds", 6, p -> { item(p, new ItemStack(Material.IRON_INGOT, 16)); item(p, new ItemStack(Material.DIAMOND, 4)); }));
        pool.add(new Reward("<#e0895a>Cinder Key", 10, p -> crates.give(p, "iron", 1)));
        pool.add(new Reward("<#ffe45c>Spark Key", 6, p -> crates.give(p, "vote", 1)));
        pool.add(new Reward("<#ffb52e>Molten Key", 2.5, p -> crates.give(p, "gold", 1)));
        pool.add(new Reward("<aqua>Random Enchant Book", 3, p -> item(p, enchants.randomBook())));
        pool.add(new Reward("<gradient:#ffd27a:#ff4d00>Molten Name Tag", 1, p -> tags.give(p, "molten")));
        pool.add(new Reward("<#ff4422>Forgeheart Key", 0.4, p -> crates.give(p, "netherite", 1)));
    }

    private void money(Player p, int n) { if (eco != null) eco.depositPlayer(p, n); }
    private void scrap(Player p, int n) { if (scrap != null) scrap.add(p, n, null); }
    private static void item(Player p, ItemStack it) { p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l)); }

    private LocalDate today() { return LocalDate.now(ZONE); }
    private LocalDate last(Player p) { String s = data.getString(p.getUniqueId() + ".last"); return s == null ? null : LocalDate.parse(s); }
    public boolean ready(Player p) { LocalDate l = last(p); return l == null || l.isBefore(today()); }

    public void claim(Player p) {
        LocalDate l = last(p), now = today();
        if (l != null && !l.isBefore(now)) {
            long mins = java.time.Duration.between(java.time.ZonedDateTime.now(ZONE), now.plusDays(1).atStartOfDay(ZONE)).toMinutes();
            p.sendMessage(Ui.mm("<yellow>You've already claimed today's reward. Next one in <white>" + mins / 60 + "h " + mins % 60 + "m<yellow>. <gray>Streak: <white>" + data.getInt(p.getUniqueId() + ".streak") + " days"));
            return;
        }
        int streak = (l != null && l.plusDays(1).equals(now)) ? data.getInt(p.getUniqueId() + ".streak") + 1 : 1;
        // longer streaks roll more than once and keep the best (rarest) reward
        int rolls = 1 + Math.min(2, (streak - 1) / 3);
        Reward best = null;
        for (int i = 0; i < rolls; i++) { Reward r = roll(); if (best == null || r.weight() < best.weight()) best = r; }
        best.give().accept(p);
        data.set(p.getUniqueId() + ".last", now.toString());
        data.set(p.getUniqueId() + ".streak", streak);
        data.set(p.getUniqueId() + ".name", p.getName());
        try { data.save(file); } catch (Exception ignored) { }
        p.sendMessage(Ui.mm("<gold>☀ <bold>Daily reward:</bold> " + best.label() + " <gray>| Streak: <white>" + streak + " day" + (streak == 1 ? "" : "s")
                + (rolls > 1 ? " <gray>(best of " + rolls + " rolls)" : "")));
        // the streak itself pays too: 10 Scrap a day of streak, up to 150
        if (streak > 1) {
            int bonus = Math.min(150, 10 * streak);
            scrap(p, bonus);
            p.sendMessage(Ui.mm("<gold>☀ <gray>Streak bonus: <#c6a15b>+" + bonus + " Scrap <dark_gray>(keep it going tomorrow for more)"));
        }
        if (streak % 7 == 0) {
            crates.give(p, "gold", 1);
            p.sendMessage(Ui.mm("<gold>☀ <bold>" + streak + "-day streak bonus!</bold> <gray>+1 <#ffb52e>Molten Key"));
            Bukkit.broadcast(Ui.mm("<gold>☀ <white>" + p.getName() + " <gray>hit a <white>" + streak + "-day</white> daily streak! <yellow>/daily"));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.5f);
    }

    private Reward roll() {
        double total = pool.stream().mapToDouble(Reward::weight).sum(), r = rnd.nextDouble() * total;
        for (Reward rw : pool) { r -= rw.weight(); if (r < 0) return rw; }
        return pool.getFirst();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!ready(p)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && ready(p))
                p.sendMessage(Ui.mm("<gold>☀ <gray>Your daily reward is ready! <yellow><u>Click here</u> <gray>or type <white>/daily").clickEvent(ClickEvent.runCommand("/daily")));
        }, 80L);
    }
}
