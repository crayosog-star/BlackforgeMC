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
import java.util.function.Function;

/**
 * Money and Scrap, one separate balance per game mode: Survival, Skyblock, OneBlock, Factions and Arcade each have
 * their own wallet ({@link #walletOf}). The hub has none: anything earned there (milestones, the tutorial, votes...)
 * waits as "pending" and is paid into the next game mode the player joins ({@link #flushPending}).
 *
 * Every server runs on this machine, so each player is one small file, MinecraftNetwork/shared/economy/&lt;uuid&gt;.yml,
 * with a section per wallet (w.&lt;wallet&gt;.money / .scrap) and the pending pot. The money/scrap keys at the top level
 * are the old network-wide balance from before the split, kept but no longer used. Exposed to other plugins through Vault.
 */
public final class NetEconomy implements org.bukkit.event.Listener {
    public static final double STARTING_MONEY = 500;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");
    private final AlloyCore plugin;
    private final File dir;
    private final String mode;
    /** The wallet a player uses on this server right now (null = none here, earnings wait as pending). */
    private Function<UUID, String> wallet;

    NetEconomy(AlloyCore plugin) {
        this.plugin = plugin;
        this.mode = plugin.getConfig().getString("mode", "backend");
        this.wallet = id -> walletForMode(mode);
        File serverDir;
        try { serverDir = plugin.getServer().getWorldContainer().getCanonicalFile(); }
        catch (Exception e) { serverDir = plugin.getServer().getWorldContainer().getAbsoluteFile(); }
        dir = new File(new File(serverDir.getParentFile(), "shared"), "economy");
        dir.mkdirs();
        registerCommands();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            try { VaultHook.register(plugin, this); plugin.getLogger().info("Economy registered with Vault (" + mode + " balances)"); }
            catch (Throwable t) { plugin.getLogger().warning("Vault hook failed: " + t); }
        }
    }

    static String walletForMode(String mode) {
        return switch (mode) {
            case "survival", "skyblock", "factions", "arcade" -> mode;
            default -> null;   // hub and anything else: no money here
        };
    }

    /** Skyblock splits its server: Skyblock and OneBlock wallets by where the player is (its lobby has none). */
    public void setWallet(Function<UUID, String> wallet) { this.wallet = wallet; }

    public String walletOf(UUID id) { return wallet.apply(id); }

    public static String walletName(String w) {
        return w == null ? "the hub" : switch (w) {
            case "oneblock" -> "OneBlock";
            default -> Character.toUpperCase(w.charAt(0)) + w.substring(1);
        };
    }

    public static String money(double d) { return "$" + MONEY.format(d); }

    /** A player's name is kept in their file (for /baltop and /pay to offline players); pending rewards are paid out. */
    @org.bukkit.event.EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        YamlConfiguration y = load(p.getUniqueId());
        if (!p.getName().equals(y.getString("name"))) { y.set("name", p.getName()); save(p.getUniqueId(), y); }
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) flushPending(p); }, 50L);
    }

    // ------------------------------------------------------------------ storage

    private File file(UUID id) { return new File(dir, id + ".yml"); }

    private synchronized YamlConfiguration load(UUID id) { return YamlConfiguration.loadConfiguration(file(id)); }

    private synchronized void save(UUID id, YamlConfiguration y) {
        try { y.save(file(id)); } catch (Exception e) { plugin.getLogger().warning("economy save failed for " + id + ": " + e.getMessage()); }
    }

    public synchronized boolean hasAccount(UUID id) {
        String w = walletOf(id);
        return w != null && load(id).contains("w." + w);
    }

    /** Opens the player's wallet for this mode with the starting money the first time they use it. */
    public synchronized void ensureAccount(OfflinePlayer p) {
        String w = walletOf(p.getUniqueId());
        if (w == null) return;
        YamlConfiguration y = load(p.getUniqueId());
        if (y.contains("w." + w)) return;
        y.set("w." + w + ".money", STARTING_MONEY);
        y.set("w." + w + ".scrap", 0L);
        if (p.getName() != null) y.set("name", p.getName());
        save(p.getUniqueId(), y);
    }

    public synchronized double balance(UUID id) {
        String w = walletOf(id);
        if (w == null) return 0;
        YamlConfiguration y = load(id);
        return y.contains("w." + w) ? y.getDouble("w." + w + ".money") : STARTING_MONEY;
    }

    public synchronized boolean setBalance(OfflinePlayer p, double v) {
        String w = walletOf(p.getUniqueId());
        if (w == null || v < 0 || Double.isNaN(v)) return false;
        ensureAccount(p);
        YamlConfiguration y = load(p.getUniqueId());
        y.set("w." + w + ".money", Math.round(v * 100) / 100.0);
        if (p.getName() != null) y.set("name", p.getName());
        save(p.getUniqueId(), y);
        return true;
    }

    /** Pays into this mode's wallet; in the hub it waits as pending for the next game mode. */
    public synchronized boolean deposit(OfflinePlayer p, double amount) {
        if (amount < 0) return false;
        if (walletOf(p.getUniqueId()) == null) return addPending(p, amount, 0);
        ensureAccount(p);
        return setBalance(p, balance(p.getUniqueId()) + amount);
    }

    public synchronized boolean withdraw(OfflinePlayer p, double amount) {
        if (amount < 0 || walletOf(p.getUniqueId()) == null) return false;
        ensureAccount(p);
        double b = balance(p.getUniqueId());
        if (b + 1e-9 < amount) return false;
        return setBalance(p, b - amount);
    }

    public synchronized long scrap(UUID id) {
        String w = walletOf(id);
        return w == null ? 0 : load(id).getLong("w." + w + ".scrap");
    }

    public synchronized void addScrap(OfflinePlayer p, long n) {
        String w = walletOf(p.getUniqueId());
        if (w == null) { if (n > 0) addPending(p, 0, n); return; }
        ensureAccount(p);
        YamlConfiguration y = load(p.getUniqueId());
        y.set("w." + w + ".scrap", Math.max(0, y.getLong("w." + w + ".scrap") + n));
        if (p.getName() != null) y.set("name", p.getName());
        save(p.getUniqueId(), y);
    }

    public synchronized boolean takeScrap(OfflinePlayer p, long n) {
        String w = walletOf(p.getUniqueId());
        if (w == null) return false;
        YamlConfiguration y = load(p.getUniqueId());
        long have = y.getLong("w." + w + ".scrap");
        if (have < n) return false;
        y.set("w." + w + ".scrap", have - n);
        save(p.getUniqueId(), y);
        return true;
    }

    // ------------------------------------------------------------------ the hub: earnings wait for a game mode

    private synchronized boolean addPending(OfflinePlayer p, double money, long scrap) {
        YamlConfiguration y = load(p.getUniqueId());
        y.set("pending.money", Math.round((y.getDouble("pending.money") + money) * 100) / 100.0);
        y.set("pending.scrap", y.getLong("pending.scrap") + scrap);
        if (p.getName() != null) y.set("name", p.getName());
        save(p.getUniqueId(), y);
        return true;
    }

    public synchronized double pendingMoney(UUID id) { return load(id).getDouble("pending.money"); }
    public synchronized long pendingScrap(UUID id) { return load(id).getLong("pending.scrap"); }

    /** In a game mode now: pay anything earned in the hub into this mode's wallet. */
    public void flushPending(Player p) {
        String w = walletOf(p.getUniqueId());
        if (w == null) return;
        double m;
        long s;
        synchronized (this) {
            YamlConfiguration y = load(p.getUniqueId());
            m = y.getDouble("pending.money");
            s = y.getLong("pending.scrap");
            if (m <= 0 && s <= 0) return;
            y.set("pending", null);
            save(p.getUniqueId(), y);
        }
        if (m > 0) deposit(p, m);
        if (s > 0) addScrap(p, s);
        p.sendMessage(Ui.mm("<gold>✦ <gray>Rewards you earned in the hub went into your <white>" + walletName(w) + " <gray>balance: "
                + (m > 0 ? "<green>+" + money(m) + " " : "") + (s > 0 ? "<#c6a15b>+" + s + " Scrap" : "")));
    }

    /** name -> money in one wallet, richest first. */
    public synchronized List<Map.Entry<String, Double>> top(String w, int n) {
        List<Map.Entry<String, Double>> rows = new ArrayList<>();
        File[] files = dir.listFiles((d, f) -> f.endsWith(".yml"));
        if (files != null) for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            String name = y.getString("name");
            if (name == null || name.startsWith("AlloyTester") || !y.contains("w." + w)) continue;
            rows.add(Map.entry(name, y.getDouble("w." + w + ".money")));
        }
        rows.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return rows.size() > n ? rows.subList(0, n) : rows;
    }

    /** name -> money in this server's wallet (the first one on the hub), richest first. */
    public List<Map.Entry<String, Double>> top(int n) {
        String w = walletForMode(mode);
        return top(w == null ? "survival" : w, n);
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

    private static final String NO_MONEY_HERE = "<gray>There's no money in the hub: every game mode has its own balance. Anything you earn here is paid into the next game mode you join.";

    private void registerCommands() {
        reg("balance", List.of("bal", "money"), (s, a) -> {
            if (a.length > 0) {
                OfflinePlayer t = find(a[0]);
                if (t == null) { s.sendMessage(Ui.mm("<red>Unknown player " + a[0])); return; }
                String w = walletOf(t.getUniqueId());
                if (w == null) { s.sendMessage(Ui.mm(NO_MONEY_HERE)); return; }
                s.sendMessage(Ui.mm("<gray>" + t.getName() + "'s " + walletName(w) + " balance: <green>" + money(balance(t.getUniqueId())) + " <dark_gray>| <#c6a15b>⚙ " + scrap(t.getUniqueId()) + " Scrap"));
            } else if (s instanceof Player p) {
                String w = walletOf(p.getUniqueId());
                if (w == null) {
                    s.sendMessage(Ui.mm(NO_MONEY_HERE));
                    double pm = pendingMoney(p.getUniqueId());
                    long ps = pendingScrap(p.getUniqueId());
                    if (pm > 0 || ps > 0) s.sendMessage(Ui.mm("<gray>Waiting for you: <green>" + money(pm) + " <gray>and <#c6a15b>" + ps + " Scrap"));
                    return;
                }
                ensureAccount(p);
                s.sendMessage(Ui.mm("<gray>" + walletName(w) + " balance: <green>" + money(balance(p.getUniqueId())) + " <dark_gray>| <#c6a15b>⚙ " + scrap(p.getUniqueId()) + " Scrap"));
            }
        });
        reg("pay", List.of(), (s, a) -> {
            if (!(s instanceof Player p)) return;
            String w = walletOf(p.getUniqueId());
            if (w == null) { p.sendMessage(Ui.mm(NO_MONEY_HERE)); return; }
            if (a.length < 2) { p.sendMessage(Ui.mm("<gray>/pay <player> <amount>")); return; }
            OfflinePlayer t = find(a[0]);
            double amt = parseAmount(a[1]);
            if (t == null || t.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>Can't pay that player.")); return; }
            if (!w.equals(walletOf(t.getUniqueId()))) { p.sendMessage(Ui.mm("<red>" + t.getName() + " is playing a different game mode: money only moves within " + walletName(w) + ".")); return; }
            if (amt < 0.01) { p.sendMessage(Ui.mm("<red>Bad amount.")); return; }
            if (!withdraw(p, amt)) { p.sendMessage(Ui.mm("<red>You don't have " + money(amt) + ".")); return; }
            deposit(t, amt);
            String tName = t.getName() != null ? t.getName() : a[0];
            p.sendMessage(Ui.mm("<green>Sent " + money(amt) + " to " + tName + "."));
            Player tp = t.getPlayer();
            if (tp != null) tp.sendMessage(Ui.mm("<green>" + p.getName() + " sent you " + money(amt) + "."));
            plugin.getLogger().info("[pay] " + w + " " + p.getName() + " -> " + tName + " " + money(amt));
        });
        reg("baltop", List.of("balancetop", "richest"), (s, a) -> {
            String w = s instanceof Player p ? walletOf(p.getUniqueId()) : walletForMode(mode);
            if (w == null) { s.sendMessage(Ui.mm(NO_MONEY_HERE)); return; }
            s.sendMessage(Ui.mm("<gold><bold>Richest players in " + walletName(w)));
            int i = 1;
            for (var e : top(w, 10)) s.sendMessage(Ui.mm("<yellow>#" + i++ + " <white>" + e.getKey() + " <dark_gray>- <green>" + money(e.getValue())));
        });
        reg("eco", List.of("economy"), (s, a) -> {
            if (!s.hasPermission("alloy.admin")) { s.sendMessage(Ui.mm("<red>Admins only.")); return; }
            if (a.length < 3) { s.sendMessage(Ui.mm("<gray>/eco <give|take|set> <player> <amount> <dark_gray>(this game mode's balance)")); return; }
            OfflinePlayer t = find(a[1]);
            if (t == null) t = Bukkit.getOfflinePlayer(a[1]);
            double amt = parseAmount(a[2]);
            if (amt < 0) { s.sendMessage(Ui.mm("<red>Bad amount.")); return; }
            boolean ok = switch (a[0].toLowerCase()) {
                case "give" -> deposit(t, amt);
                case "take" -> withdraw(t, amt);
                case "set" -> setBalance(t, amt);
                default -> false;
            };
            String w = walletOf(t.getUniqueId());
            s.sendMessage(Ui.mm(!ok ? "<red>Failed (not enough money, or no balances on this server?)"
                    : w == null ? "<green>Held for " + t.getName() + " until they join a game mode." : "<green>" + t.getName() + " now has " + money(balance(t.getUniqueId())) + " in " + walletName(w)));
            Player tp = t.getPlayer();
            if (ok && tp != null && a[0].equalsIgnoreCase("give")) tp.sendMessage(Ui.mm("<green>" + money(amt) + " has been added to your account."));
        });
    }
}
