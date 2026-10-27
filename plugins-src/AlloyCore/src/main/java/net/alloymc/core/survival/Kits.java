package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.util.*;

/**
 * /kits: god-themed gear kits. They are sold on the web store (real money), which delivers them
 * with the console command {@code alloy kit give <player> <kit>}; in game you can browse and preview them.
 */
public final class Kits implements Listener {
    record Kit(String id, String god, String color, String title, Material icon, int price, int cooldownHours, List<ItemStack> items) {}

    private final AlloyCore plugin;
    private final File file;
    private final YamlConfiguration data;
    private final List<Kit> kits = new ArrayList<>();
    private Economy eco;

    Kits(AlloyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "kit-cooldowns.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        define();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void setEconomy(Economy eco) { this.eco = eco; }

    // ------------------------------------------------------------------ kit contents

    private static final class B {
        final List<ItemStack> items = new ArrayList<>();
        final String god, color;
        B(String god, String color) { this.god = god; this.color = color; }

        B gear(Material m, String name, Object... enchants) {
            ItemStack it = new ItemStack(m);
            it.editMeta(meta -> {
                meta.displayName(Ui.mm(color + "<bold>" + name));
                meta.lore(List.of(Ui.mm("<gray>Blessed by " + color + god), Ui.mm("<dark_gray>BlackForgeMC god kit")));
                for (int i = 0; i + 1 < enchants.length; i += 2) meta.addEnchant((Enchantment) enchants[i], (Integer) enchants[i + 1], true);
            });
            items.add(it);
            return this;
        }

        B add(Material m, int n) { items.add(new ItemStack(m, n)); return this; }

        B potion(PotionType t, int n, boolean splash) {
            for (int i = 0; i < n; i++) {
                ItemStack it = new ItemStack(splash ? Material.SPLASH_POTION : Material.POTION);
                it.editMeta(PotionMeta.class, m -> m.setBasePotionType(t));
                items.add(it);
            }
            return this;
        }
    }

    private void define() {
        var P = Enchantment.PROTECTION; var U = Enchantment.UNBREAKING; var S = Enchantment.SHARPNESS; var M = Enchantment.MENDING;

        B hermes = new B("Hermes", "<#7ff0ff>")
            .gear(Material.IRON_HELMET, "Hermes' Winged Helm", P, 2, U, 2)
            .gear(Material.IRON_CHESTPLATE, "Hermes' Chestplate", P, 2, U, 2)
            .gear(Material.IRON_LEGGINGS, "Hermes' Leggings", P, 2, U, 2)
            .gear(Material.IRON_BOOTS, "Hermes' Winged Sandals", P, 2, Enchantment.FEATHER_FALLING, 4, U, 2)
            .gear(Material.IRON_SWORD, "Caduceus Blade", S, 2, U, 2)
            .potion(PotionType.STRONG_SWIFTNESS, 3, false).add(Material.ENDER_PEARL, 8).add(Material.GOLDEN_APPLE, 4).add(Material.COOKED_BEEF, 32);
        kits.add(new Kit("hermes", "Hermes", "<#7ff0ff>", "God of Speed", Material.FEATHER, 2500, 12, hermes.items));

        B apollo = new B("Apollo", "<#ffd34d>")
            .gear(Material.GOLDEN_HELMET, "Apollo's Sun Crown", P, 3, U, 3)
            .gear(Material.IRON_CHESTPLATE, "Apollo's Chestplate", P, 3, U, 2)
            .gear(Material.IRON_LEGGINGS, "Apollo's Leggings", P, 3, U, 2)
            .gear(Material.IRON_BOOTS, "Apollo's Boots", P, 3, U, 2)
            .gear(Material.BOW, "Bow of the Sun", Enchantment.POWER, 4, Enchantment.FLAME, 1, Enchantment.INFINITY, 1, U, 3)
            .gear(Material.IRON_SWORD, "Apollo's Dagger", S, 3, U, 2)
            .add(Material.ARROW, 1).potion(PotionType.STRONG_REGENERATION, 2, false).add(Material.GOLDEN_APPLE, 4).add(Material.GOLDEN_CARROT, 32);
        kits.add(new Kit("apollo", "Apollo", "<#ffd34d>", "God of the Sun", Material.BOW, 5000, 12, apollo.items));

        B athena = new B("Athena", "<#c9d6ff>")
            .gear(Material.DIAMOND_HELMET, "Athena's War Helm", P, 2, U, 3)
            .gear(Material.DIAMOND_CHESTPLATE, "Athena's Aegis Plate", P, 2, U, 3)
            .gear(Material.IRON_LEGGINGS, "Athena's Greaves", P, 3, U, 3)
            .gear(Material.IRON_BOOTS, "Athena's Boots", P, 3, U, 3)
            .gear(Material.DIAMOND_SWORD, "Spear of Wisdom", S, 3, U, 3)
            .gear(Material.SHIELD, "Aegis", U, 3)
            .add(Material.GOLDEN_APPLE, 6).potion(PotionType.STRONG_HEALING, 2, true).add(Material.COOKED_BEEF, 32);
        kits.add(new Kit("athena", "Athena", "<#c9d6ff>", "Goddess of Wisdom & War", Material.SHIELD, 10000, 24, athena.items));

        B ares = new B("Ares", "<#ff4d4d>")
            .gear(Material.DIAMOND_HELMET, "Ares' Bloodied Helm", P, 3, U, 3)
            .gear(Material.DIAMOND_CHESTPLATE, "Ares' Battleplate", P, 3, U, 3)
            .gear(Material.DIAMOND_LEGGINGS, "Ares' Legplates", P, 3, U, 3)
            .gear(Material.DIAMOND_BOOTS, "Ares' Warboots", P, 3, U, 3)
            .gear(Material.DIAMOND_SWORD, "Blade of War", S, 4, Enchantment.FIRE_ASPECT, 1, U, 3)
            .gear(Material.DIAMOND_AXE, "Ares' Cleaver", S, 3, U, 3)
            .potion(PotionType.STRONG_STRENGTH, 2, false).add(Material.GOLDEN_APPLE, 8).potion(PotionType.STRONG_HEALING, 2, true);
        kits.add(new Kit("ares", "Ares", "<#ff4d4d>", "God of War", Material.DIAMOND_SWORD, 20000, 24, ares.items));

        B poseidon = new B("Poseidon", "<#2fb7ff>")
            .gear(Material.TURTLE_HELMET, "Poseidon's Shell Crown", P, 4, Enchantment.RESPIRATION, 3, Enchantment.AQUA_AFFINITY, 1, U, 3)
            .gear(Material.DIAMOND_CHESTPLATE, "Poseidon's Tidal Plate", P, 4, U, 3)
            .gear(Material.DIAMOND_LEGGINGS, "Poseidon's Leggings", P, 4, U, 3)
            .gear(Material.DIAMOND_BOOTS, "Poseidon's Wavewalkers", P, 4, Enchantment.DEPTH_STRIDER, 3, U, 3)
            .gear(Material.TRIDENT, "Trident of the Seas", Enchantment.LOYALTY, 3, Enchantment.IMPALING, 5, Enchantment.CHANNELING, 1, U, 3)
            .gear(Material.DIAMOND_SWORD, "Tidecaller", S, 4, Enchantment.KNOCKBACK, 2, U, 3)
            .potion(PotionType.LONG_WATER_BREATHING, 2, false).potion(PotionType.STRONG_STRENGTH, 2, false).add(Material.GOLDEN_APPLE, 10);
        kits.add(new Kit("poseidon", "Poseidon", "<#2fb7ff>", "God of the Sea", Material.TRIDENT, 35000, 48, poseidon.items));

        B hades = new B("Hades", "<#9b5cff>")
            .gear(Material.DIAMOND_HELMET, "Helm of Darkness", P, 4, Enchantment.FIRE_PROTECTION, 0, U, 3)
            .gear(Material.NETHERITE_CHESTPLATE, "Hades' Soulplate", P, 3, U, 3)
            .gear(Material.NETHERITE_LEGGINGS, "Hades' Underworld Greaves", P, 3, U, 3)
            .gear(Material.DIAMOND_BOOTS, "Hades' Ashwalkers", P, 4, Enchantment.FEATHER_FALLING, 4, U, 3)
            .gear(Material.NETHERITE_SWORD, "Bident of the Dead", S, 5, Enchantment.FIRE_ASPECT, 2, U, 3)
            .potion(PotionType.LONG_FIRE_RESISTANCE, 3, false).potion(PotionType.STRONG_STRENGTH, 2, false)
            .add(Material.ENCHANTED_GOLDEN_APPLE, 1).add(Material.GOLDEN_APPLE, 10);
        kits.add(new Kit("hades", "Hades", "<#9b5cff>", "God of the Underworld", Material.WITHER_SKELETON_SKULL, 50000, 48, hades.items));

        B zeus = new B("Zeus", "<#ffe066>")
            .gear(Material.NETHERITE_HELMET, "Crown of Olympus", P, 4, U, 3, M, 1)
            .gear(Material.NETHERITE_CHESTPLATE, "Zeus' Stormplate", P, 4, U, 3, M, 1)
            .gear(Material.NETHERITE_LEGGINGS, "Zeus' Thunder Greaves", P, 4, U, 3, M, 1)
            .gear(Material.NETHERITE_BOOTS, "Zeus' Skystriders", P, 4, Enchantment.FEATHER_FALLING, 4, U, 3, M, 1)
            .gear(Material.NETHERITE_SWORD, "Thunderbolt", S, 5, Enchantment.FIRE_ASPECT, 2, Enchantment.LOOTING, 3, U, 3, M, 1)
            .gear(Material.MACE, "Hammer of the Heavens", Enchantment.DENSITY, 4, Enchantment.WIND_BURST, 1, U, 3)
            .gear(Material.TRIDENT, "Storm Caller", Enchantment.CHANNELING, 1, Enchantment.LOYALTY, 3, Enchantment.IMPALING, 3)
            .add(Material.WIND_CHARGE, 16).add(Material.ENCHANTED_GOLDEN_APPLE, 2).add(Material.GOLDEN_APPLE, 12)
            .potion(PotionType.STRONG_STRENGTH, 2, false).potion(PotionType.STRONG_SWIFTNESS, 2, false);
        kits.add(new Kit("zeus", "Zeus", "<#ffe066>", "King of the Gods", Material.LIGHTNING_ROD, 100000, 72, zeus.items));
    }

    // ------------------------------------------------------------------ cooldowns

    private long readyAt(Player p, Kit k) { return data.getLong(p.getUniqueId() + "." + k.id(), 0); }

    private static String fmt(long ms) {
        long h = ms / 3600_000, m = ms / 60_000 % 60;
        return h > 0 ? h + "h " + m + "m" : Math.max(1, m) + "m";
    }

    // ------------------------------------------------------------------ menus

    private static final class Menu implements InventoryHolder {
        final boolean preview; final String kitId; Inventory inv;
        Menu(boolean preview, String kitId) { this.preview = preview; this.kitId = kitId; }
        public Inventory getInventory() { return inv; }
    }

    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16};

    public void open(Player p) {
        Menu m = new Menu(false, null);
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Kits of the Gods"));
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) m.inv.setItem(i, pane);
        long now = System.currentTimeMillis();
        for (int i = 0; i < kits.size() && i < SLOTS.length; i++) {
            Kit k = kits.get(i);
            long ready = readyAt(p, k);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + k.title());
            lore.add("");
            for (ItemStack it : k.items()) {
                if (lore.size() > 12) { lore.add("<dark_gray>...and more"); break; }
                String nm = it.hasItemMeta() && it.getItemMeta().hasDisplayName()
                        ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName())
                        : (it.getAmount() > 1 ? it.getAmount() + "x " : "") + it.getType().name().toLowerCase().replace('_', ' ');
                lore.add("<dark_gray>• <white>" + nm);
            }
            lore.add("");
            lore.add("<gold>Available on the store");
            lore.add("<gray>" + plugin.getConfig().getString("store-url", "Store coming soon!"));
            lore.add("<yellow>» Click to preview");
            m.inv.setItem(SLOTS[i], Ui.item(k.icon(), k.color() + "<bold>KIT " + k.god().toUpperCase(), lore.toArray(String[]::new)));
        }
        m.inv.setItem(22, Ui.item(Material.EMERALD, "<green><bold>Get kits on the store", "<gray>" + plugin.getConfig().getString("store-url", "Store coming soon!")));
        p.openInventory(m.inv);
    }

    private void preview(Player p, Kit k) {
        Menu m = new Menu(true, k.id());
        m.inv = Bukkit.createInventory(m, 27, Ui.mm(k.color() + "Kit " + k.god() + " <dark_gray>preview"));
        for (ItemStack it : k.items()) m.inv.addItem(it.clone());
        m.inv.setItem(26, Ui.item(Material.ARROW, "<yellow>« Back"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (m.preview) { if (e.getRawSlot() == 26) open(p); return; }
        for (int i = 0; i < kits.size(); i++) {
            if (e.getRawSlot() != SLOTS[i]) continue;
            Kit k = kits.get(i);
            preview(p, k);
        }
    }

    private void deliver(Player p, Kit k) {
        for (ItemStack it : k.items()) p.getInventory().addItem(it.clone()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.5f, 1.4f);
        p.getWorld().strikeLightningEffect(p.getLocation());
        Bukkit.broadcast(Ui.mm("<gold>⚡ <white>" + p.getName() + " <gray>received the blessing of " + k.color() + "<bold>" + k.god() + "<gray>!"));
        data.set(p.getUniqueId() + "." + k.id() + "-claimed", System.currentTimeMillis());
        try { data.save(file); } catch (Exception ignored) { }
    }

    /** Store hook (console): deliver a kit. Price/cooldown in the definitions are informational for the store listing. */
    public boolean giveFree(Player p, String id) {
        for (Kit k : kits) if (k.id().equalsIgnoreCase(id)) { deliver(p, k); return true; }
        return false;
    }

    public List<String> ids() { return kits.stream().map(Kit::id).toList(); }
}
