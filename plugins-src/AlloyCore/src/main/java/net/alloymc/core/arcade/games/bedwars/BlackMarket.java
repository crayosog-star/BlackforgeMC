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
 * The Black Market: a villager in the cave under the Forge Wars Core. Emeralds only. It shows a few deals from a
 * bigger pool and restocks every few minutes (arcade/games/forgewars.yml: black-market-slots, black-market-restock-seconds),
 * so it's worth a trip down more than once.
 */
final class BlackMarket {
    /** One deal. {@code give} returns false when the player can't take it (nothing is charged then). */
    record Deal(String id, String name, Material icon, int amount, int emeralds, String note, Give give) {}

    @FunctionalInterface interface Give { boolean give(Player p, BedWarsMatch m); }

    static final List<Deal> POOL = List.of(
        new Deal("totem", "Totem of Undying", Material.TOTEM_OF_UNDYING, 1, 8, "Cheats death once",
                (p, m) -> { BedWarsShop.give(p, new ItemStack(Material.TOTEM_OF_UNDYING)); return true; }),
        new Deal("notch_apple", "Enchanted Golden Apple", Material.ENCHANTED_GOLDEN_APPLE, 1, 6, "The good one",
                (p, m) -> { BedWarsShop.give(p, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE)); return true; }),
        new Deal("kb_stick", "Knockback II Stick", Material.STICK, 1, 4, "Into the void they go",
                (p, m) -> { ItemStack s = Ui.item(Material.STICK, "<dark_purple>Black Market Stick"); s.addUnsafeEnchantment(Enchantment.KNOCKBACK, 2); BedWarsShop.give(p, s); return true; }),
        new Deal("power_bow", "Power II Punch I Bow", Material.BOW, 1, 5, "Comes with 16 arrows",
                (p, m) -> { ItemStack b = new ItemStack(Material.BOW); b.addEnchantment(Enchantment.POWER, 2); b.addEnchantment(Enchantment.PUNCH, 1); BedWarsShop.give(p, b); BedWarsShop.give(p, new ItemStack(Material.ARROW, 16)); return true; }),
        new Deal("sharp_sword", "Sharpness II Iron Sword", Material.IRON_SWORD, 1, 5, "Replaces your sword",
                (p, m) -> { m.giveSword(p, Material.IRON_SWORD, 2); return true; }),
        new Deal("pearls", "2x Ender Pearl", Material.ENDER_PEARL, 2, 5, "Cheaper than upstairs",
                (p, m) -> { BedWarsShop.give(p, new ItemStack(Material.ENDER_PEARL, 2)); return true; }),
        new Deal("diamonds", "6x Diamond", Material.DIAMOND, 6, 2, "For team upgrades",
                (p, m) -> { BedWarsShop.give(p, new ItemStack(Material.DIAMOND, 6)); return true; }),
        new Deal("obsidian", "8x Obsidian", Material.OBSIDIAN, 8, 5, "Bury your bed",
                (p, m) -> { BedWarsShop.give(p, new ItemStack(Material.OBSIDIAN, 8)); return true; }),
        new Deal("invis", "Invisibility (45s)", Material.POTION, 1, 2, "Longer than the shop's",
                (p, m) -> { BedWarsShop.give(p, BedWarsShop.potion(PotionEffectType.INVISIBILITY, 45, 0, "Black Market Invisibility")); return true; }),
        new Deal("fireballs", "4x Fireball", Material.FIRE_CHARGE, 4, 3, "Right-click to throw",
                (p, m) -> { ItemStack f = Ui.item(Material.FIRE_CHARGE, "<gold>Fireball <gray>(right-click)"); f.setAmount(4); BedWarsShop.give(p, f); return true; }),
        new Deal("mystery", "Mystery Ability", Material.ENDER_EYE, 1, 2, "A random Arsenal item",
                (p, m) -> {
                    ForgeArsenal a = ForgeArsenal.get();
                    if (a == null) { p.sendMessage(Ui.mm("<red>The Arsenal isn't open on this server.")); return false; }
                    int limit = m.setting("arsenal-carry-limit", 2);
                    if (a.carried(p) >= limit) { p.sendMessage(Ui.mm("<red>You can carry " + limit + " abilities at once. Use one first.")); return false; }
                    BedWarsShop.give(p, a.random());
                    return true;
                }));

    /** Pick {@code n} distinct deals from the pool. */
    static List<Deal> roll(Random rnd, int n) {
        List<Deal> l = new ArrayList<>(POOL);
        Collections.shuffle(l, rnd);
        return new ArrayList<>(l.subList(0, Math.max(1, Math.min(n, l.size()))));
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
            boolean afford = have >= d.emeralds();
            ItemStack icon = Ui.item(d.icon(), (afford ? "<light_purple>" : "<red>") + d.name(), "<gray>" + d.note(), "",
                    "<gray>Cost: <green>Emeralds x" + d.emeralds(), afford ? "<yellow>Click to buy" : "<red>You can't afford this");
            icon.setAmount(Math.max(1, Math.min(64, d.amount())));
            h.inv.setItem(slots[i], icon);
            h.actions.put(slots[i], () -> {
                if (!BedWarsShop.pay(p, Material.EMERALD, d.emeralds())) return;
                if (!d.give().give(p, m)) { BedWarsShop.give(p, new ItemStack(Material.EMERALD, d.emeralds())); return; }   // refund
                open(m, p, stock, restockIn);
            });
        }
        h.inv.setItem(31, Ui.item(Material.EMERALD, "<green><bold>Emeralds: " + have, "<gray>New stock in <white>" + Math.max(0, restockIn) + "s"));
        p.openInventory(h.inv);
    }
}
