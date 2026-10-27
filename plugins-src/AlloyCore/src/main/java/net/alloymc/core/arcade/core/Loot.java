package net.alloymc.core.arcade.core;

import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Weighted loot tables from settings strings: "MATERIAL amount weight [enchant:level ...] [heal|speed|strength|regen]".
 * Used to fill chests (SkyWars) and build kits ("MATERIAL amount" lists).
 */
public final class Loot {
    public record Entry(ItemStack item, int weight) {}
    private final List<Entry> entries = new ArrayList<>();
    private int total;

    public Loot(List<String> lines) {
        for (String l : lines) {
            try { Entry e = parse(l); entries.add(e); total += e.weight(); }
            catch (Exception ex) { throw new IllegalArgumentException("bad loot line '" + l + "': " + ex.getMessage()); }
        }
    }

    public boolean isEmpty() { return entries.isEmpty(); }

    public static Entry parse(String line) {
        String[] p = line.trim().split("\\s+");
        Material m = Material.matchMaterial(p[0]);
        if (m == null) throw new IllegalArgumentException("unknown item " + p[0]);
        int amount = p.length > 1 ? Integer.parseInt(p[1]) : 1, weight = p.length > 2 ? Integer.parseInt(p[2]) : 1;
        ItemStack it = new ItemStack(m, Math.max(1, amount));
        for (int i = 3; i < p.length; i++) extra(it, p[i]);
        return new Entry(it, Math.max(1, weight));
    }

    /** "STONE_SWORD 1" style kit item (no weight). */
    public static ItemStack item(String line) {
        String[] p = line.trim().split("\\s+");
        Material m = Material.matchMaterial(p[0]);
        if (m == null) throw new IllegalArgumentException("unknown item " + p[0]);
        ItemStack it = new ItemStack(m, p.length > 1 ? Integer.parseInt(p[1]) : 1);
        for (int i = 2; i < p.length; i++) extra(it, p[i]);
        return it;
    }

    private static void extra(ItemStack it, String x) {
        String s = x.toLowerCase();
        PotionEffectType pot = switch (s) {
            case "heal" -> PotionEffectType.INSTANT_HEALTH; case "speed" -> PotionEffectType.SPEED; case "strength" -> PotionEffectType.STRENGTH;
            case "regen" -> PotionEffectType.REGENERATION; case "fire" -> PotionEffectType.FIRE_RESISTANCE; default -> null;
        };
        if (pot != null && it.getItemMeta() instanceof PotionMeta) {
            it.editMeta(PotionMeta.class, m -> m.addCustomEffect(new PotionEffect(pot, pot.isInstant() ? 1 : 20 * 30, pot == PotionEffectType.INSTANT_HEALTH ? 1 : 0), true));
            return;
        }
        String[] e = s.split(":");
        Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(e[0]));
        if (en == null) throw new IllegalArgumentException("unknown enchant/effect " + x);
        int lv = e.length > 1 ? Integer.parseInt(e[1]) : 1;
        if (it.getType() == Material.ENCHANTED_BOOK) it.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(en, lv, true));
        else it.addUnsafeEnchantment(en, lv);
    }

    public ItemStack roll(Random r) {
        int x = r.nextInt(total);
        for (Entry e : entries) { x -= e.weight(); if (x < 0) return e.item().clone(); }
        return entries.getLast().item().clone();
    }

    /** Put n random items into random empty slots. */
    public void fill(Inventory inv, int n, Random r) {
        inv.clear();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, r);
        for (int i = 0; i < n && i < slots.size(); i++) inv.setItem(slots.get(i), roll(r));
    }
}
