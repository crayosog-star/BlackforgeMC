package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.Team;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** The Item Shop and Team Upgrades menus for one BedWars match. Prices are paid in iron/gold/diamonds/emeralds. */
final class BedWarsShop {
    record Offer(String name, Material icon, int amount, Material currency, int price, BiConsumer<Player, BedWarsMatch> give) {}

    static final class Holder implements InventoryHolder {
        final boolean upgrades;
        final Map<Integer, Runnable> actions = new HashMap<>();
        Inventory inv;
        Holder(boolean upgrades) { this.upgrades = upgrades; }
        public Inventory getInventory() { return inv; }
    }

    private static final Material IRON = Material.IRON_INGOT, GOLD = Material.GOLD_INGOT, DIA = Material.DIAMOND, EM = Material.EMERALD;

    static String cur(Material m) {
        return switch (m) { case IRON_INGOT -> "<white>Iron"; case GOLD_INGOT -> "<gold>Gold"; case DIAMOND -> "<aqua>Diamonds"; default -> "<green>Emeralds"; };
    }

    static int count(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == m) n += it.getAmount();
        return n;
    }

    static boolean pay(Player p, Material m, int price) {
        if (count(p, m) < price) {
            p.sendMessage(Ui.mm("<red>You need " + price + " " + cur(m) + "<red> (you have " + count(p, m) + ")."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return false;
        }
        p.getInventory().removeItem(new ItemStack(m, price));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.6f);
        return true;
    }

    static List<Offer> offers(BedWarsMatch m, Player p) {
        Team t = m.team(p);
        Material wool = t.color.wool(), clay = t.color.terracotta(), glass = t.color.glass();
        List<Offer> o = new ArrayList<>();
        o.add(new Offer("16x Wool", wool, 16, IRON, 4, (pl, mm) -> give(pl, new ItemStack(wool, 16))));
        o.add(new Offer("16x Hardened Clay", clay, 16, IRON, 12, (pl, mm) -> give(pl, new ItemStack(clay, 16))));
        o.add(new Offer("4x Blast-Proof Glass", glass, 4, IRON, 12, (pl, mm) -> give(pl, new ItemStack(glass, 4))));
        o.add(new Offer("12x End Stone", Material.END_STONE, 12, IRON, 24, (pl, mm) -> give(pl, new ItemStack(Material.END_STONE, 12))));
        o.add(new Offer("16x Oak Planks", Material.OAK_PLANKS, 16, GOLD, 4, (pl, mm) -> give(pl, new ItemStack(Material.OAK_PLANKS, 16))));
        o.add(new Offer("4x Obsidian", Material.OBSIDIAN, 4, EM, 4, (pl, mm) -> give(pl, new ItemStack(Material.OBSIDIAN, 4))));
        o.add(new Offer("Stone Sword", Material.STONE_SWORD, 1, IRON, 10, (pl, mm) -> mm.giveSword(pl, Material.STONE_SWORD)));
        o.add(new Offer("Iron Sword", Material.IRON_SWORD, 1, GOLD, 7, (pl, mm) -> mm.giveSword(pl, Material.IRON_SWORD)));
        o.add(new Offer("Diamond Sword", Material.DIAMOND_SWORD, 1, EM, 4, (pl, mm) -> mm.giveSword(pl, Material.DIAMOND_SWORD)));
        o.add(new Offer("Knockback Stick", Material.STICK, 1, GOLD, 5, (pl, mm) -> { ItemStack s = new ItemStack(Material.STICK); s.addUnsafeEnchantment(Enchantment.KNOCKBACK, 1); give(pl, s); }));
        o.add(new Offer("Chainmail Armour (permanent)", Material.CHAINMAIL_BOOTS, 1, IRON, 24, (pl, mm) -> mm.setArmour(pl, 1)));
        o.add(new Offer("Iron Armour (permanent)", Material.IRON_BOOTS, 1, GOLD, 12, (pl, mm) -> mm.setArmour(pl, 2)));
        o.add(new Offer("Diamond Armour (permanent)", Material.DIAMOND_BOOTS, 1, EM, 6, (pl, mm) -> mm.setArmour(pl, 3)));
        o.add(new Offer("Shears (permanent)", Material.SHEARS, 1, IRON, 20, (pl, mm) -> mm.giveShears(pl)));
        o.add(new Offer("Pickaxe upgrade", Material.IRON_PICKAXE, 1, IRON, 10, (pl, mm) -> mm.upgradeTool(pl, true)));
        o.add(new Offer("Axe upgrade", Material.IRON_AXE, 1, IRON, 10, (pl, mm) -> mm.upgradeTool(pl, false)));
        o.add(new Offer("Bow", Material.BOW, 1, GOLD, 12, (pl, mm) -> give(pl, new ItemStack(Material.BOW))));
        o.add(new Offer("Bow (Power I)", Material.BOW, 1, GOLD, 24, (pl, mm) -> { ItemStack b = new ItemStack(Material.BOW); b.addEnchantment(Enchantment.POWER, 1); give(pl, b); }));
        o.add(new Offer("8x Arrows", Material.ARROW, 8, GOLD, 2, (pl, mm) -> give(pl, new ItemStack(Material.ARROW, 8))));
        o.add(new Offer("Golden Apple", Material.GOLDEN_APPLE, 1, GOLD, 3, (pl, mm) -> give(pl, new ItemStack(Material.GOLDEN_APPLE))));
        o.add(new Offer("Fireball", Material.FIRE_CHARGE, 1, IRON, 40, (pl, mm) -> give(pl, Ui.item(Material.FIRE_CHARGE, "<gold>Fireball <gray>(right-click)"))));
        o.add(new Offer("TNT", Material.TNT, 1, GOLD, 4, (pl, mm) -> give(pl, new ItemStack(Material.TNT))));
        o.add(new Offer("Ender Pearl", Material.ENDER_PEARL, 1, EM, 4, (pl, mm) -> give(pl, new ItemStack(Material.ENDER_PEARL))));
        o.add(new Offer("Water Bucket", Material.WATER_BUCKET, 1, GOLD, 3, (pl, mm) -> give(pl, new ItemStack(Material.WATER_BUCKET))));
        o.add(new Offer("Speed II Potion (45s)", Material.POTION, 1, EM, 1, (pl, mm) -> give(pl, potion(PotionEffectType.SPEED, 45, 1, "Speed II"))));
        o.add(new Offer("Jump V Potion (45s)", Material.POTION, 1, EM, 1, (pl, mm) -> give(pl, potion(PotionEffectType.JUMP_BOOST, 45, 4, "Jump V"))));
        o.add(new Offer("Invisibility Potion (30s)", Material.POTION, 1, EM, 2, (pl, mm) -> give(pl, potion(PotionEffectType.INVISIBILITY, 30, 0, "Invisibility"))));
        return o;
    }

    static ItemStack potion(PotionEffectType t, int seconds, int amp, String name) {
        ItemStack it = new ItemStack(Material.POTION);
        it.editMeta(PotionMeta.class, m -> { m.addCustomEffect(new PotionEffect(t, seconds * 20, amp), true); m.displayName(Ui.mm("<light_purple>" + name)); m.setColor(org.bukkit.Color.fromRGB(0x9a6bff)); });
        return it;
    }

    static void give(Player p, ItemStack it) { p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItem(p.getLocation(), l)); }

    static void openShop(BedWarsMatch m, Player p) {
        Holder h = new Holder(false);
        h.inv = Bukkit.createInventory(h, 54, Ui.mm("<dark_gray>Item Shop"));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) h.inv.setItem(i, pane);
        List<Offer> offers = offers(m, p);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        for (int i = 0; i < offers.size() && i < slots.length; i++) {
            Offer o = offers.get(i);
            boolean afford = count(p, o.currency()) >= o.price();
            ItemStack icon = Ui.item(o.icon(), (afford ? "<green>" : "<red>") + o.name(), "<gray>Cost: " + cur(o.currency()) + " x" + o.price(), "", afford ? "<yellow>Click to buy" : "<red>You can't afford this");
            icon.setAmount(Math.max(1, Math.min(64, o.amount())));
            h.inv.setItem(slots[i], icon);
            h.actions.put(slots[i], () -> { if (pay(p, o.currency(), o.price())) { o.give().accept(p, m); openShop(m, p); } });
        }
        h.inv.setItem(4, Ui.item(Material.EMERALD, "<green><bold>Your resources", "<white>Iron: " + count(p, IRON), "<gold>Gold: " + count(p, GOLD), "<aqua>Diamonds: " + count(p, DIA), "<green>Emeralds: " + count(p, EM)));
        p.openInventory(h.inv);
    }

    record Upgrade(String id, String name, Material icon, String desc, int max, Function<Integer, Integer> cost) {}

    static List<Upgrade> upgrades(BedWarsMatch m) {
        List<Integer> prot = m.costs("upgrade-protection-costs", List.of(2, 4, 8, 16)), haste = m.costs("upgrade-haste-costs", List.of(2, 4)), forge = m.costs("upgrade-forge-costs", List.of(2, 4, 6));
        int sharp = m.costs("upgrade-sharpness-cost", List.of(4)).getFirst(), heal = m.costs("upgrade-healpool-cost", List.of(1)).getFirst();
        return List.of(
            new Upgrade("sharpness", "Sharpened Swords", Material.IRON_SWORD, "Your team's swords get Sharpness I", 1, l -> sharp),
            new Upgrade("protection", "Reinforced Armour", Material.IRON_CHESTPLATE, "Protection on your team's armour", prot.size(), l -> prot.get(Math.min(l, prot.size() - 1))),
            new Upgrade("haste", "Maniac Miner", Material.GOLDEN_PICKAXE, "Haste for your whole team", haste.size(), l -> haste.get(Math.min(l, haste.size() - 1))),
            new Upgrade("forge", "Iron Forge", Material.FURNACE, "+50% generator speed per level; level 3 adds emeralds", forge.size(), l -> forge.get(Math.min(l, forge.size() - 1))),
            new Upgrade("healpool", "Heal Pool", Material.BEACON, "Regeneration near your island", 1, l -> heal));
    }

    static void openUpgrades(BedWarsMatch m, Player p) {
        Holder h = new Holder(true);
        h.inv = Bukkit.createInventory(h, 36, Ui.mm("<dark_gray>Team Upgrades"));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 36; i++) h.inv.setItem(i, pane);
        Team t = m.team(p);
        int[] slots = {11, 12, 13, 14, 15};
        List<Upgrade> ups = upgrades(m);
        for (int i = 0; i < ups.size(); i++) {
            Upgrade u = ups.get(i);
            int lv = t.getInt(u.id());
            boolean maxed = lv >= u.max();
            int cost = maxed ? 0 : u.cost().apply(lv);
            ItemStack icon = Ui.item(u.icon(), (maxed ? "<gold>" : "<aqua>") + u.name() + (u.max() > 1 ? " " + Math.min(lv + (maxed ? 0 : 1), u.max()) : ""),
                    "<gray>" + u.desc(), "<gray>Level: <white>" + lv + "/" + u.max(), "", maxed ? "<green>Maxed" : "<gray>Cost: <aqua>" + cost + " Diamonds", maxed ? "" : "<yellow>Click to buy for your team");
            h.inv.setItem(slots[i], icon);
            h.actions.put(slots[i], () -> {
                if (maxed || !pay(p, DIA, cost)) return;
                t.inc(u.id());
                m.applyUpgrades(t);
                m.teamMessage(t, "<aqua>" + p.getName() + " bought <white>" + u.name() + " " + t.getInt(u.id()) + "<aqua>!");
                openUpgrades(m, p);
            });
        }
        h.inv.setItem(31, Ui.item(Material.DIAMOND, "<aqua><bold>Diamonds: " + count(p, DIA)));
        p.openInventory(h.inv);
    }
}
