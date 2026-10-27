package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.text.DecimalFormat;
import java.util.*;

/**
 * Network-wide money and Scrap. Every server runs on this machine, so each player's balance is a
 * small file in MinecraftNetwork/shared/economy/&lt;uuid&gt;.yml. A player is only ever on one
 * server, so per-player files never get conflicting writes. Exposed to other plugins through Vault.
 */
public final class NetEconomy implements org.bukkit.event.Listener {
    public static final double STARTING_MONEY = 500;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");
    private final AlloyCore plugin;
    private final File dir;

    NetEconomy(AlloyCore plugin) {
        this.plugin = plugin;
        File serverDir;
        try { serverDir = plugin.getServer().getWorldContainer().getCanonicalFile(); }
        catch (Exception e) { serverDir = plugin.getServer().getWorldContainer().getAbsoluteFile(); }
        dir = new File(new File(serverDir.getParentFile(), "shared"), "economy");
        dir.mkdirs();
        registerCommands();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            try { VaultHook.register(plugin, this); plugin.getLogger().info("Network economy registered with Vault"); }
            catch (Throwable t) { plugin.getLogger().warning("Vault hook failed: " + t); }
        }
    }

    public static String money(double d) { return "$" + MONEY.format(d); }

    /** Every player gets their account (and starting money) the first time they join any server. */
    @org.bukkit.event.EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        ensureAccount(p);
        YamlConfiguration y = load(p.getUniqueId());
        if (!p.getName().equals(y.getString("name"))) { y.set("name", p.getName()); save(p.getUniqueId(), y); }
    }

    // ------------------------------------------------------------------ storage

    private File file(UUID id) { return new File(dir, id + ".yml"); }

    private synchronized YamlConfiguration load(UUID id) { return YamlConfiguration.loadConfiguration(file(id)); }

    private synchronized void save(UUID id, YamlConfiguration y) {
        try { y.save(file(id)); } catch (Exception e) { plugin.getLogger().warning("economy save failed for " + id + ": " + e.getMessage()); }
    }

    public synchronized boolean hasAccount(UUID id) { return file(id).exists(); }

    /** Creates the account with the starting balance the first time a player is seen anywhere. */
    public synchronized void ensureAccount(OfflinePlayer p) {
        if (hasAccount(p.getUniqueId())) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", p.getName());
        y.set("money", STARTING_MONEY);
        y.set("scrap", 0L);
        save(p.getUniqueId(), y);
    }

    public synchronized double balance(UUID id) { return load(id).getDouble("money", hasAccount(id) ? 0 : STARTING_MONEY); }

    public synchronized boolean setBalance(OfflinePlayer p, double v) {
        if (v < 0 || Double.isNaN(v)) return false;
        YamlConfiguration y = load(p.getUniqueId());
        if (!y.contains("money")) y.set("scrap", y.getLong("scrap"));
        y.set("money", Math.round(v * 100) / 100.0);
        if (p.getName() != null) y.set("name", p.getName());
        save(p.getUniqueId(), y);
        return true;
    }

    public synchronized boolean deposit(OfflinePlayer p, double amount) {
        if (amount < 0) return false;
        ensureAccount(p);
        return setBalance(p, balance(p.getUniqueId()) + amount);
    }

    public synchronized boolean withdraw(OfflinePlayer p, double amount) {
        if (amount < 0) return false;
        ensureAccount(p);
        double b = balance(p.getUniqueId());
        if (b + 1e-9 < amount) return false;
        return setBalance(p, b - amount);
    }

    public synchronized long scrap(UUID id) { return load(id).getLong("scrap"); }

    public synchronized void addScrap(OfflinePlayer p, long n) {
        ensureAccount(p);
        YamlConfiguration y = load(p.getUniqueId());
        y.set("scrap", Math.max(0, y.getLong("scrap") + n));
        if (p.getName() != null) y.set("name", p.getName());
        save(p.getUniqueId(), y);
    }

    public synchronized boolean takeScrap(OfflinePlayer p, long n) {
        YamlConfiguration y = load(p.getUniqueId());
        long have = y.getLong("scrap");
        if (have < n) return false;
        y.set("scrap", have - n);
        save(p.getUniqueId(), y);
        return true;
    }

    /** name -> money for everyone with an account, richest first. */
    public synchronized List<Map.Entry<String, Double>> top(int n) {
        List<Map.Entry<String, Double>> rows = new ArrayList<>();
        File[] files = dir.listFiles((d, f) -> f.endsWith(".yml"));
        if (files != null) for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            String name = y.getString("name");
            if (name == null || name.startsWith("AlloyTester")) continue;
            rows.add(Map.entry(name, y.getDouble("money")));
        }
        rows.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return rows.size() > n ? rows.subList(0, n) : rows;
    }

    // ------------------------------------------------------------------ commands

    private OfflinePlayer find(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        if (op != null) return op;
        // players who have only been on other servers: look them up by the name in their file
        File[] files = dir.listFiles((d, f) -> f.endsWith(".yml"));
        if (files != null) for (File f : files)
            if (name.equalsIgnoreCase(YamlConfiguration.loadConfiguration(f).getString("name")))
                return Bukkit.getOfflinePlayer(UUID.fromString(f.getName().replace(".yml", "")));
        return null;
    }

    private interface Cmd { void run(CommandSender s, String[] a); }

    private void reg(String name, List<String> aliases, Cmd cmd) {
        Bukkit.getCommandMap().register("alloy", new Command(name, "BlackForgeMC economy", "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] a) { cmd.run(s, a); return true; }
        });
    }

    private double parseAmount(String s) {
        try {
            String t = s.toLowerCase().replace(",", "").replace("$", "");
            double mul = t.endsWith("k") ? 1_000 : t.endsWith("m") ? 1_000_000 : 1;
            if (mul > 1) t = t.substring(0, t.length() - 1);
            return Double.parseDouble(t) * mul;
        } catch (Exception e) { return -1; }
    }

    private void registerCommands() {
        reg("balance", List.of("bal", "money"), (s, a) -> {
            if (a.length > 0) {
                OfflinePlayer t = find(a[0]);
                if (t == null) { s.sendMessage(Ui.mm("<red>Unknown player " + a[0])); return; }
                s.sendMessage(Ui.mm("<gray>" + t.getName() + "'s balance: <green>" + money(balance(t.getUniqueId())) + " <dark_gray>| <#c6a15b>⚙ " + scrap(t.getUniqueId()) + " Scrap"));
            } else if (s instanceof Player p) {
                ensureAccount(p);
                s.sendMessage(Ui.mm("<gray>Balance: <green>" + money(balance(p.getUniqueId())) + " <dark_gray>| <#c6a15b>⚙ " + scrap(p.getUniqueId()) + " Scrap <dark_gray>(shared across BlackForgeMC)"));
            }
        });
        reg("pay", List.of(), (s, a) -> {
            if (!(s instanceof Player p)) return;
            if (a.length < 2) { p.sendMessage(Ui.mm("<gray>/pay <player> <amount>")); return; }
            OfflinePlayer t = find(a[0]);
            double amt = parseAmount(a[1]);
            if (t == null || t.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>Can't pay that player.")); return; }
            if (amt < 0.01) { p.sendMessage(Ui.mm("<red>Bad amount.")); return; }
            if (!withdraw(p, amt)) { p.sendMessage(Ui.mm("<red>You don't have " + money(amt) + ".")); return; }
            deposit(t, amt);
            String tName = t.getName() != null ? t.getName() : a[0];
            p.sendMessage(Ui.mm("<green>Sent " + money(amt) + " to " + tName + "."));
            Player tp = t.getPlayer();
            if (tp != null) tp.sendMessage(Ui.mm("<green>" + p.getName() + " sent you " + money(amt) + "."));
            plugin.getLogger().info("[pay] " + p.getName() + " -> " + tName + " " + money(amt));
        });
        reg("baltop", List.of("balancetop", "richest"), (s, a) -> {
            s.sendMessage(Ui.mm("<gold><bold>Richest players on BlackForgeMC"));
            int i = 1;
            for (var e : top(10)) s.sendMessage(Ui.mm("<yellow>#" + i++ + " <white>" + e.getKey() + " <dark_gray>- <green>" + money(e.getValue())));
        });
        reg("eco", List.of("economy"), (s, a) -> {
            if (!s.hasPermission("alloy.admin")) { s.sendMessage(Ui.mm("<red>Admins only.")); return; }
            if (a.length < 3) { s.sendMessage(Ui.mm("<gray>/eco <give|take|set> <player> <amount>")); return; }
            OfflinePlayer t = find(a[1]);
            if (t == null) t = Bukkit.getOfflinePlayer(a[1]);
            double amt = parseAmount(a[2]);
            if (amt < 0) { s.sendMessage(Ui.mm("<red>Bad amount.")); return; }
            boolean ok = switch (a[0].toLowerCase()) {
                case "give" -> deposit(t, amt);
                case "take" -> withdraw(t, amt);
                case "set" -> { ensureAccount(t); yield setBalance(t, amt); }
                default -> false;
            };
            s.sendMessage(Ui.mm(ok ? "<green>" + t.getName() + " now has " + money(balance(t.getUniqueId())) : "<red>Failed (not enough money?)"));
            Player tp = t.getPlayer();
            if (ok && tp != null && a[0].equalsIgnoreCase("give")) tp.sendMessage(Ui.mm("<green>" + money(amt) + " has been added to your account."));
        });
    }
}
