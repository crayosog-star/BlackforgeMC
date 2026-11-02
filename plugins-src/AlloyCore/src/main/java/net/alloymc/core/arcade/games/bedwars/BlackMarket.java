package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * The Black Market: a villager on the floor of the Undercroft, the cave under the Forge Wars Core, and the only
 * place to get the best weapons and gear. Emeralds only. It shows a few deals from a bigger pool (always at least two
 * premium ones) and restocks every few minutes (arcade/games/forgewars.yml: black-market-slots,
 * black-market-restock-seconds), so it's worth fighting your way down more than once.
 */
final class BlackMarket {
    /**
     * One deal. {@code premium} deals are the best weapons and gear in the game; every restock shows at least two.
     * {@code give} returns false when the player can't take it (they're refunded then).
     */
    record Deal(String id, String name, Material icon, int amount, int emeralds, boolean premium, String note, Give give) {}

    @FunctionalInterface interface Give { boolean give(Player p, BedWarsMatch m); }

    private static boolean item(Player p, ItemStack it) { BedWarsShop.give(p, it); return true; }

    private static ItemStack named(Material m, String name, Object... enchants) {
        ItemStack it = Ui.item(m, name);
        for (int i = 0; i + 1 < enchants.length; i += 2) it.addUnsafeEnchantment((Enchantment) enchants[i], (Integer) enchants[i + 1]);
        return it;
    }

    static final List<Deal> POOL = List.of(
        // ---- premium: only down here
        new Deal("forgefire", "Forgefire Blade", Material.NETHERITE_SWORD, 1, 9, true, "Netherite, Sharpness II, Fire Aspect",
                (p, m) -> {
                    m.giveSword(p, Material.NETHERITE_SWORD, 2);
                    for (ItemStack it : p.getInventory().getContents())
                        if (it != null && it.getType() == Material.NETHERITE_SWORD) { it.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 1); it.editMeta(x -> x.displayName(Ui.mm("<gold>Forgefire Blade"))); }
                    return true;
                }),
        new Deal("diamond_blade", "Sharpness II Diamond Sword", Material.DIAMOND_SWORD, 1, 6, true, "Replaces your sword",
                (p, m) -> { m.giveSword(p, Material.DIAMOND_SWORD, 2); return true; }),
        new Deal("mace", "Mace + 3 Wind Charges", Material.MACE, 1, 12, true, "Density II. Jump, then land on them",
                (p, m) -> { item(p, named(Material.MACE, "<dark_purple>Undercroft Mace", Enchantment.DENSITY, 2)); return item(p, new ItemStack(Material.WIND_CHARGE, 3)); }),
        new Deal("diamond_armour", "Diamond Armour (permanent)", Material.DIAMOND_CHESTPLATE, 1, 4, true, "Cheaper than the Item Shop",
                (p, m) -> {
                    if (m.armourTier(p) >= 3) { p.sendMessage(Ui.mm("<gray>You already have diamond armour.")); return false; }
                    m.setArmour(p, 3);
                    return true;
                }),
        new Deal("crossbow", "Multishot Crossbow + 12 Arrows", Material.CROSSBOW, 1, 5, true, "Quick Charge II",
                (p, m) -> { item(p, named(Material.CROSSBOW, "<dark_purple>Undercroft Crossbow", Enchantment.MULTISHOT, 1, Enchantment.QUICK_CHARGE, 2)); return item(p, new ItemStack(Material.ARROW, 12)); }),
        new Deal("trident", "Trident", Material.TRIDENT, 1, 6, true, "Loyalty III: always comes back",
                (p, m) -> item(p, named(Material.TRIDENT, "<dark_purple>Undercroft Trident", Enchantment.LOYALTY, 3))),
        new Deal("totem", "Totem of Undying", Material.TOTEM_OF_UNDYING, 1, 8, true, "Cheats death once",
                (p, m) -> item(p, new ItemStack(Material.TOTEM_OF_UNDYING))),
        // ---- regular
        new Deal("notch_apple", "Enchanted Golden Apple", Material.ENCHANTED_GOLDEN_APPLE, 1, 6, false, "The good one",
                (p, m) -> item(p, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE))),
        new Deal("kb_stick", "Knockback II Stick", Material.STICK, 1, 4, false, "Into the void they go",
                (p, m) -> item(p, named(Material.STICK, "<dark_purple>Black Market Stick", Enchantment.KNOCKBACK, 2))),
        new Deal("power_bow", "Power II Punch I Bow", Material.BOW, 1, 5, false, "Comes with 16 arrows",
                (p, m) -> { item(p, named(Material.BOW, "<dark_purple>Black Market Bow", Enchantment.POWER, 2, Enchantment.PUNCH, 1)); return item(p, new ItemStack(Material.ARROW, 16)); }),
        new Deal("pearls", "2x Ender Pearl", Material.ENDER_PEARL, 2, 5, false, "Cheaper than upstairs",
                (p, m) -> item(p, new ItemStack(Material.ENDER_PEARL, 2))),
        new Deal("diamonds", "6x Diamond", Material.DIAMOND, 6, 2, false, "For team upgrades",
                (p, m) -> item(p, new ItemStack(Material.DIAMOND, 6))),
        new Deal("obsidian", "8x Obsidian", Material.OBSIDIAN, 8, 5, false, "Bury your bed",
                (p, m) -> item(p, new ItemStack(Material.OBSIDIAN, 8))),
        new Deal("invis", "Invisibility (45s)", Material.POTION, 1, 2, false, "Longer than the shop's",
                (p, m) -> item(p, BedWarsShop.potion(PotionEffectType.INVISIBILITY, 45, 0, "Black Market Invisibility"))),
        new Deal("fireballs", "4x Fireball", Material.FIRE_CHARGE, 4, 3, false, "Right-click to throw",
                (p, m) -> { ItemStack f = Ui.item(Material.FIRE_CHARGE, "<gold>Fireball <gray>(right-click)"); f.setAmount(4); return item(p, f); }),
        new Deal("mystery", "Mystery Ability", Material.ENDER_EYE, 1, 2, false, "A random Arsenal item",
                (p, m) -> {
                    ForgeArsenal a = ForgeArsenal.get();
                    if (a == null) { p.sendMessage(Ui.mm("<red>The Arsenal isn't open on this server.")); return false; }
                    int limit = m.setting("arsenal-carry-limit", 2);
                    if (a.carried(p) >= limit) { p.sendMessage(Ui.mm("<red>You can carry " + limit + " abilities at once. Use one first.")); return false; }
                    return item(p, a.random());
                }));

    static final int PREMIUM_PER_RESTOCK = 2;

    /** Pick {@code n} distinct deals: at least two premium ones (fewer only if n is smaller), the rest from everything else. */
    static List<Deal> roll(Random rnd, int n) {
        n = Math.max(1, Math.min(n, POOL.size()));
        List<Deal> premium = new ArrayList<>(), rest = new ArrayList<>();
        for (Deal d : POOL) (d.premium() ? premium : rest).add(d);
        Collections.shuffle(premium, rnd);
        int np = Math.min(Math.min(PREMIUM_PER_RESTOCK, n), premium.size());
        List<Deal> out = new ArrayList<>(premium.subList(0, np));
        rest.addAll(premium.subList(np, premium.size()));   // leftover premium can still turn up in the other slots
        Collections.shuffle(rest, rnd);
        out.addAll(rest.subList(0, n - np));
        out.sort(Comparator.comparing((Deal d) -> !d.premium()));   // premium first in the menu
        return out;
    }

    static void open(BedWarsMatch m, Player p, List<Deal> stock, int restockIn) {
        BedWarsShop.Holder h = new BedWarsShop.Holder(false);
        h.inv = Bukkit.createInventory(h, 36, Ui.mm("<dark_purple>Black Market"));
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 36; i++) h.inv.setItem(i, pane);
        int have = BedWarsShop.count(p, Material.EMERALD);
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < stock.size() && i < slots.length; i++) {
            Deal d = stock.get(i);
            boolean free = d.premium() && m.hasGolemVoucher(p);   // the Golem's top damage dealer gets one premium deal free
            boolean afford = free || have >= d.emeralds();
            ItemStack icon = Ui.item(d.icon(), (afford ? d.premium() ? "<gold>★ " : "<light_purple>" : "<red>") + d.name(), "<gray>" + d.note(), "",
                    free ? "<#ff3fa4>FREE <gray>(Golem's reward)" : "<gray>Cost: <green>Emeralds x" + d.emeralds(), afford ? "<yellow>Click to buy" : "<red>You can't afford this");
            icon.setAmount(Math.max(1, Math.min(64, d.amount())));
            h.inv.setItem(slots[i], icon);
            h.actions.put(slots[i], () -> {
                if (d.premium() && m.hasGolemVoucher(p)) {
                    if (d.give().give(p, m)) { m.spendGolemVoucher(p); p.sendMessage(Ui.mm("<#ff3fa4>Claimed your Golem's reward!")); }
                    open(m, p, stock, restockIn);
                    return;
                }
                if (!BedWarsShop.pay(p, Material.EMERALD, d.emeralds())) return;
                if (!d.give().give(p, m)) { BedWarsShop.give(p, new ItemStack(Material.EMERALD, d.emeralds())); return; }   // refund
                open(m, p, stock, restockIn);
            });
        }
        h.inv.setItem(31, Ui.item(Material.EMERALD, "<green><bold>Emeralds: " + have, "<gray>New stock in <white>" + Math.max(0, restockIn) + "s"));
        p.openInventory(h.inv);
    }
}
