package net.blackforge.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

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
    private final BlackForgeCore plugin;
    private final File dir;
    private final String mode;
    /** This server's own wallet (Survival 2's on the lab server), or null on the hub. */
    private final String homeWallet;
    /** The wallet a player uses on this server right now (null = none here, earnings wait as pending). */
    private Function<UUID, String> wallet;

    NetEconomy(BlackForgeCore plugin) {
        this.plugin = plugin;
        this.mode = plugin.getConfig().getString("mode", "backend");
        String w = walletForMode(mode, Lab.on() ? Lab.id() : null);   // Survival 2 keeps its own balances
        this.homeWallet = w;
        this.wallet = id -> w;
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

    /** {@code lab} is the Survival 2 id on the lab server (its own wallet, so test money never reaches live), else null. */
    static String walletForMode(String mode, String lab) {
        if (lab != null && walletForMode(mode) != null) return lab;
        return walletForMode(mode);
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
            case "survival2" -> "Survival 2";
            default -> Character.toUpperCase(w.charAt(0)) + w.substring(1);
        };
    }

    public static String money(double d) { return "$" + MONEY.format(d); }

    /** Player-entered prices must be exactly representable in cents before they reach either wallet. */
    public static double parsePrice(String value, long multiplier) {
        if (value.length() > 40) return -1;
        try {
            BigDecimal price = new BigDecimal(value).multiply(BigDecimal.valueOf(multiplier));
            if (price.signum() < 0 || price.stripTrailingZeros().scale() > 2
                    || price.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) > 0) return -1;
            return price.doubleValue();
        } catch (NumberFormatException | ArithmeticException ex) {
            return -1;
        }
    }

    public static boolean validPrice(double value) {
        return Double.isFinite(value) && parsePrice(Double.toString(value), 1) >= 0;
    }

    /** A player's name is kept in their file (for /baltop and /pay to offline players); pending rewards are paid out. */
    @org.bukkit.event.EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        updateAccount(p.getUniqueId(), y -> {
            if (p.getName().equals(y.getString("name"))) return false;
            y.set("name", p.getName());
            return true;
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) flushPending(p); }, 50L);
    }

    // ------------------------------------------------------------------ storage

    private File file(UUID id) { return new File(dir, id + ".yml"); }

    private YamlConfiguration load(UUID id) { return YamlConfiguration.loadConfiguration(file(id)); }

    /** All backends share these files. Lock the read and write together, then replace the YAML in one move. */
    private synchronized boolean updateAccount(UUID id, Predicate<YamlConfiguration> change) {
        Path account = file(id).toPath();
        Path lock = account.resolveSibling(account.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            YamlConfiguration y = new YamlConfiguration();
            if (Files.exists(account)) y.load(account.toFile());
            if (!change.test(y)) return false;
            Path temp = Files.createTempFile(account.getParent(), id + "-", ".tmp");
            try {
                y.save(temp.toFile());
                Files.move(temp, account, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("economy save failed for " + id + ": " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean hasAccount(UUID id) {
        String w = walletOf(id);
        return w != null && load(id).contains("w." + w);
    }

    /** Opens the player's wallet for this mode with the starting money the first time they use it. */
    public synchronized void ensureAccount(OfflinePlayer p) {
        String w = walletOf(p.getUniqueId());
        if (w == null) return;
        updateAccount(p.getUniqueId(), y -> {
            if (y.contains("w." + w)) return false;
            return writeBalance(y, p, w, STARTING_MONEY);
        });
    }

    public synchronized double balance(UUID id) {
        String w = walletOf(id);
        return w == null ? 0 : readBalance(load(id), w);
    }

    private static double readBalance(YamlConfiguration y, String wallet) {
        return y.contains("w." + wallet) ? y.getDouble("w." + wallet + ".money") : STARTING_MONEY;
    }

    private static boolean writeBalance(YamlConfiguration y, OfflinePlayer p, String w, double amount) {
        if (!Double.isFinite(amount) || amount < 0 || amount > Long.MAX_VALUE / 100.0) return false;
        y.set("w." + w + ".money", Math.round(amount * 100) / 100.0);
        if (!y.contains("w." + w + ".scrap")) y.set("w." + w + ".scrap", 0L);
        if (p.getName() != null) y.set("name", p.getName());
        return true;
    }

    public synchronized boolean setBalance(OfflinePlayer p, double v) {
        String w = walletOf(p.getUniqueId());
        return w != null && updateAccount(p.getUniqueId(), y -> writeBalance(y, p, w, v));
    }

    /** Pays into this mode's wallet; in the hub it waits as pending for the next game mode. */
    public synchronized boolean deposit(OfflinePlayer p, double amount) {
        if (!Double.isFinite(amount) || amount < 0 || amount > Long.MAX_VALUE / 100.0) return false;
        String w = walletOf(p.getUniqueId());
        if (w == null) return addPending(p, amount, 0);
        return updateAccount(p.getUniqueId(), y -> writeBalance(y, p, w, readBalance(y, w) + amount));
    }

    public synchronized boolean withdraw(OfflinePlayer p, double amount) {
        if (!Double.isFinite(amount) || amount < 0) return false;
        String w = walletOf(p.getUniqueId());
        return w != null && updateAccount(p.getUniqueId(), y -> {
            double current = readBalance(y, w);
            return current + 1e-9 >= amount && writeBalance(y, p, w, Math.max(0, current - amount));
        });
    }

    public synchronized long scrap(UUID id) {
        String w = walletOf(id);
        return w == null ? 0 : load(id).getLong("w." + w + ".scrap");
    }

    public synchronized void addScrap(OfflinePlayer p, long n) {
        String w = walletOf(p.getUniqueId());
        if (w == null) { if (n > 0) addPending(p, 0, n); return; }
        updateAccount(p.getUniqueId(), y -> {
            if (!y.contains("w." + w)) writeBalance(y, p, w, STARTING_MONEY);
            y.set("w." + w + ".scrap", Math.max(0, Math.addExact(y.getLong("w." + w + ".scrap"), n)));
            if (p.getName() != null) y.set("name", p.getName());
            return true;
        });
    }

    public synchronized boolean takeScrap(OfflinePlayer p, long n) {
        String w = walletOf(p.getUniqueId());
        return w != null && n >= 0 && updateAccount(p.getUniqueId(), y -> {
            long have = y.getLong("w." + w + ".scrap");
            if (have < n) return false;
            if (!y.contains("w." + w)) writeBalance(y, p, w, STARTING_MONEY);
            y.set("w." + w + ".scrap", have - n);
            return true;
        });
    }

    // ------------------------------------------------------------------ the hub: earnings wait for a game mode

    private synchronized boolean addPending(OfflinePlayer p, double money, long scrap) {
        return updateAccount(p.getUniqueId(), y -> {
            double total = y.getDouble("pending.money") + money;
            if (!Double.isFinite(total) || total > Long.MAX_VALUE / 100.0) return false;
            y.set("pending.money", Math.round(total * 100) / 100.0);
            y.set("pending.scrap", Math.addExact(y.getLong("pending.scrap"), scrap));
            if (p.getName() != null) y.set("name", p.getName());
            return true;
        });
    }

    public synchronized double pendingMoney(UUID id) { return load(id).getDouble("pending.money"); }
    public synchronized long pendingScrap(UUID id) { return load(id).getLong("pending.scrap"); }

    private record PendingReward(double money, long scrap) {}

    /** Move pending rewards and clear the pending pot in the same account update. */
    public void flushPending(Player p) {
        if (Lab.on()) return;   // hub rewards belong to a live wallet, never the Survival 2 test wallet
        String w = walletOf(p.getUniqueId());
        if (w == null) return;
        java.util.concurrent.atomic.AtomicReference<PendingReward> paid = new java.util.concurrent.atomic.AtomicReference<>();
        if (!updateAccount(p.getUniqueId(), y -> {
            double money = y.getDouble("pending.money");
            long scrap = y.getLong("pending.scrap");
            if (money <= 0 && scrap <= 0) return false;
            if (!writeBalance(y, p, w, readBalance(y, w) + Math.max(0, money))) return false;
            if (scrap > 0) y.set("w." + w + ".scrap", Math.addExact(y.getLong("w." + w + ".scrap"), scrap));
            y.set("pending", null);
            paid.set(new PendingReward(money, scrap));
            return true;
        })) return;
        PendingReward reward = paid.get();
        p.sendMessage(Ui.mm("<gold>✦ <gray>Rewards you earned in the hub went into your <white>" + walletName(w) + " <gray>balance: "
                + (reward.money() > 0 ? "<green>+" + money(reward.money()) + " " : "") + (reward.scrap() > 0 ? "<#c6a15b>+" + reward.scrap() + " Scrap" : "")));
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
        return top(homeWallet == null ? "survival" : homeWallet, n);
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
        Bukkit.getCommandMap().register("blackforge", new Command(name, "BlackForgeMC economy", "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] a) { cmd.run(s, a); return true; }
        });
    }

    public static double parseAmount(String s) {
        try {
            String t = s.toLowerCase(Locale.ROOT).replace(",", "").replace("$", "");
            long mul = t.endsWith("k") ? 1_000 : t.endsWith("m") ? 1_000_000 : 1;
            if (mul > 1) t = t.substring(0, t.length() - 1);
            return parsePrice(t, mul);
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
            if (!deposit(t, amt)) {
                if (!deposit(p, amt)) plugin.getLogger().severe("Could not refund failed payment from " + p.getUniqueId());
                p.sendMessage(Ui.mm("<red>Payment could not be completed."));
                return;
            }
            String tName = t.getName() != null ? t.getName() : a[0];
            p.sendMessage(Ui.mm("<green>Sent " + money(amt) + " to " + tName + "."));
            Player tp = t.getPlayer();
            if (tp != null) tp.sendMessage(Ui.mm("<green>" + p.getName() + " sent you " + money(amt) + "."));
            plugin.getLogger().info("[pay] " + w + " " + p.getName() + " -> " + tName + " " + money(amt));
        });
        reg("baltop", List.of("balancetop", "richest"), (s, a) -> {
            String w = s instanceof Player p ? walletOf(p.getUniqueId()) : homeWallet;   // the console on Survival 2 sees its wallet too
            if (w == null) { s.sendMessage(Ui.mm(NO_MONEY_HERE)); return; }
            s.sendMessage(Ui.mm("<gold><bold>Richest players in " + walletName(w)));
            int i = 1;
            for (var e : top(w, 10)) s.sendMessage(Ui.mm("<yellow>#" + i++ + " <white>" + e.getKey() + " <dark_gray>- <green>" + money(e.getValue())));
        });
        reg("eco", List.of("economy"), (s, a) -> {
            if (!s.hasPermission("blackforge.admin")) { s.sendMessage(Ui.mm("<red>Admins only.")); return; }
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
