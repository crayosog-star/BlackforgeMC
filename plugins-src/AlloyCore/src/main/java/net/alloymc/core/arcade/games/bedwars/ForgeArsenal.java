package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.Arcade;
import net.alloymc.core.arcade.core.Match;
import net.alloymc.core.arcade.core.MatchState;
import net.alloymc.core.survival.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Arsenal: the BlackForgeMC ability items, sold by a villager on every Forge Wars island.
 *
 * The items are created once per Arcade server ({@link #install}) and only work inside a running Forge Wars match.
 * Damage they deal goes through the Arcade's normal damage check, so teammates are safe. Rimelock lets the caster's
 * team walk through the cage. Last Rite is left out: it moves both players into its chamber world, which would take
 * them out of the match.
 */
public final class ForgeArsenal {
    /** One ability's default price. Overridable per server in arcade/games/forgewars.yml as arsenal-price-&lt;id&gt;. */
    record Price(String id, Material currency, int amount) {}

    private static final Material EM = Material.EMERALD, DIA = Material.DIAMOND;

    static final List<Price> PRICES = List.of(
            new Price("gaia", EM, 2),
            new Price("darkness", EM, 2),
            new Price("surge", EM, 3),
            new Price("godseye", EM, 3),
            new Price("bellows", EM, 4),
            new Price("chain", EM, 4),
            new Price("bonfire", EM, 4),
            new Price("rimelock", EM, 6),
            new Price("emp", DIA, 4));   // the counter to everything else: cheap on purpose so people actually buy it

    private record Ability(Price price, Supplier<ItemStack> item, Predicate<ItemStack> is) {}

    private static ForgeArsenal installed;
    private final Map<String, Ability> abilities = new LinkedHashMap<>();

    /** Create the ability items on this server, once. Called by the Arcade module on start-up. */
    public static void install(AlloyCore plugin, Arcade arcade) {
        if (installed == null) installed = new ForgeArsenal(plugin, arcade);
    }

    /** The installed Arsenal, or null (unit tests, or the Arcade module never installed it). */
    static ForgeArsenal get() { return installed; }

    private ForgeArsenal(AlloyCore plugin, Arcade arcade) {
        Predicate<Location> inMatch = l -> inForgeWars(arcade, l);
        GaiaPotion gaia = new GaiaPotion(plugin, (p, l) -> inMatch.test(l));
        DarknessPotion darkness = new DarknessPotion(plugin, inMatch);
        Surge surge = new Surge(plugin, inMatch);
        GaleBellows bellows = new GaleBellows(plugin);   // hits only where PvP works, so the match decides who can be hit
        GodsEye eye = new GodsEye(plugin, inMatch);
        AshenChain chain = new AshenChain(plugin, inMatch, e -> false, e -> false);
        AshenBonfire bonfire = new AshenBonfire(plugin, inMatch);
        Rimelock cage = new Rimelock(plugin, inMatch, (caster, p) -> sameTeam(arcade, caster, p));
        Emp emp = new Emp(plugin, inMatch, false);
        surge.onActivate(p -> chain.breakFree(p, "Surge"));   // same counter as everywhere else
        emp.hackable(surge, cage, eye, chain, bonfire, gaia, darkness);

        add("gaia", gaia::item, gaia::isGaia);
        add("darkness", darkness::item, darkness::isDarkness);
        add("surge", surge::item, surge::isSurge);
        add("godseye", eye::item, eye::isEye);
        add("bellows", bellows::item, bellows::isBellows);
        add("chain", chain::item, chain::isChain);
        add("bonfire", bonfire::item, bonfire::isBonfire);
        add("rimelock", cage::item, cage::isCage);
        add("emp", emp::item, emp::isEmp);
    }

    private void add(String id, Supplier<ItemStack> item, Predicate<ItemStack> is) {
        Price price = PRICES.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        abilities.put(id, new Ability(price, item, is));
    }

    /** True inside a Forge Wars match that is being played (all matches share one world, so check its bounds). */
    static boolean inForgeWars(Arcade arcade, Location l) {
        for (Match m : arcade.matches().active())
            if (m.game.id.equals(ForgeWars.ID) && m.state() == MatchState.IN_PROGRESS && m.inBounds(l)) return true;
        return false;
    }

    private static boolean sameTeam(Arcade arcade, UUID casterId, Player p) {
        Player caster = Bukkit.getPlayer(casterId);
        if (caster == null) return false;
        Match m = arcade.matches().matchOf(p);
        return m != null && m == arcade.matches().matchOf(caster) && m.team(p) != null && m.team(p) == m.team(caster);
    }

    /** A random ability item (the Black Market's Mystery Ability). */
    ItemStack random() {
        List<Ability> l = new ArrayList<>(abilities.values());
        return l.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(l.size())).item().get();
    }

    /** How many ability items the player is holding (any slot). */
    int carried(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null) continue;
            for (Ability a : abilities.values()) if (a.is().test(it)) { n += it.getAmount(); break; }
        }
        return n;
    }

    void open(BedWarsMatch m, Player p) {
        BedWarsShop.Holder h = new BedWarsShop.Holder(false);
        h.inv = Bukkit.createInventory(h, 36, Ui.mm("<dark_gray>Arsenal"));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 36; i++) h.inv.setItem(i, pane);
        int limit = m.setting("arsenal-carry-limit", 2), held = carried(p);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 21, 23};
        int i = 0;
        for (Ability a : abilities.values()) {
            if (i >= slots.length) break;
            Material cur = a.price().currency();
            int cost = m.setting("arsenal-price-" + a.price().id(), a.price().amount());
            boolean afford = BedWarsShop.count(p, cur) >= cost, room = held < limit;
            ItemStack icon = a.item().get();
            icon.editMeta(meta -> {
                List<Component> lore = new ArrayList<>(Optional.ofNullable(meta.lore()).orElse(List.of()));
                lore.add(Component.empty());
                lore.add(Ui.mm("<gray>Cost: " + BedWarsShop.cur(cur) + " x" + cost));
                lore.add(Ui.mm(!room ? "<red>You're carrying " + held + "/" + limit + " abilities" : afford ? "<yellow>Click to buy" : "<red>You can't afford this"));
                meta.lore(lore);
            });
            int slot = slots[i++];
            h.inv.setItem(slot, icon);
            h.actions.put(slot, () -> {
                if (carried(p) >= limit) { p.sendMessage(Ui.mm("<red>You can carry " + limit + " abilities at once. Use one first.")); return; }
                if (!BedWarsShop.pay(p, cur, cost)) return;
                BedWarsShop.give(p, a.item().get());
                open(m, p);
            });
        }
        h.inv.setItem(31, Ui.item(Material.EMERALD, "<green><bold>Your resources", "<green>Emeralds: " + BedWarsShop.count(p, EM),
                "<aqua>Diamonds: " + BedWarsShop.count(p, DIA), "", "<gray>Abilities carried: <white>" + held + "/" + limit,
                "<gray>Abilities are lost when you die."));
        p.openInventory(h.inv);
    }
}
