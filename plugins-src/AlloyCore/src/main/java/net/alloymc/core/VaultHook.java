package net.alloymc.core;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.util.List;

/**
 * Exposes {@link NetEconomy} as the Vault economy (Highest priority, so it wins over Essentials).
 * Kept in its own class so servers without Vault never load the Vault API.
 */
final class VaultHook extends AbstractEconomy {
    private final NetEconomy eco;

    private VaultHook(NetEconomy eco) { this.eco = eco; }

    static void register(AlloyCore plugin, NetEconomy eco) {
        Bukkit.getServicesManager().register(Economy.class, new VaultHook(eco), plugin, ServicePriority.Highest);
    }

    private static EconomyResponse ok(double amt, double bal) { return new EconomyResponse(amt, bal, EconomyResponse.ResponseType.SUCCESS, null); }
    private static EconomyResponse fail(double bal, String why) { return new EconomyResponse(0, bal, EconomyResponse.ResponseType.FAILURE, why); }
    @SuppressWarnings("deprecation") private static OfflinePlayer byName(String n) { return Bukkit.getOfflinePlayer(n); }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "BlackForgeMC Economy"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double v) { return NetEconomy.money(v); }
    @Override public String currencyNamePlural() { return "dollars"; }
    @Override public String currencyNameSingular() { return "dollar"; }

    // OfflinePlayer API (what modern plugins call)
    @Override public boolean hasAccount(OfflinePlayer p) { return true; }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return true; }
    @Override public double getBalance(OfflinePlayer p) { return eco.balance(p.getUniqueId()); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }
    @Override public boolean has(OfflinePlayer p, double amt) { return getBalance(p) + 1e-9 >= amt; }
    @Override public boolean has(OfflinePlayer p, String world, double amt) { return has(p, amt); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amt) {
        return eco.withdraw(p, amt) ? ok(amt, getBalance(p)) : fail(getBalance(p), "Insufficient funds");
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amt) { return withdrawPlayer(p, amt); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amt) {
        return eco.deposit(p, amt) ? ok(amt, getBalance(p)) : fail(getBalance(p), "Invalid amount");
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amt) { return depositPlayer(p, amt); }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { eco.ensureAccount(p); return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return createPlayerAccount(p); }

    // legacy name-based API
    @Override public boolean hasAccount(String n) { return true; }
    @Override public boolean hasAccount(String n, String w) { return true; }
    @Override public double getBalance(String n) { return getBalance(byName(n)); }
    @Override public double getBalance(String n, String w) { return getBalance(byName(n)); }
    @Override public boolean has(String n, double a) { return has(byName(n), a); }
    @Override public boolean has(String n, String w, double a) { return has(byName(n), a); }
    @Override public EconomyResponse withdrawPlayer(String n, double a) { return withdrawPlayer(byName(n), a); }
    @Override public EconomyResponse withdrawPlayer(String n, String w, double a) { return withdrawPlayer(byName(n), a); }
    @Override public EconomyResponse depositPlayer(String n, double a) { return depositPlayer(byName(n), a); }
    @Override public EconomyResponse depositPlayer(String n, String w, double a) { return depositPlayer(byName(n), a); }
    @Override public boolean createPlayerAccount(String n) { return createPlayerAccount(byName(n)); }
    @Override public boolean createPlayerAccount(String n, String w) { return createPlayerAccount(byName(n)); }

    // no banks
    private static final EconomyResponse NO_BANKS = new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "No banks");
    @Override public EconomyResponse createBank(String name, String player) { return NO_BANKS; }
    @Override public EconomyResponse deleteBank(String name) { return NO_BANKS; }
    @Override public EconomyResponse bankBalance(String name) { return NO_BANKS; }
    @Override public EconomyResponse bankHas(String name, double a) { return NO_BANKS; }
    @Override public EconomyResponse bankWithdraw(String name, double a) { return NO_BANKS; }
    @Override public EconomyResponse bankDeposit(String name, double a) { return NO_BANKS; }
    @Override public EconomyResponse isBankOwner(String name, String player) { return NO_BANKS; }
    @Override public EconomyResponse isBankMember(String name, String player) { return NO_BANKS; }
    @Override public List<String> getBanks() { return List.of(); }
}
