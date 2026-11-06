package net.blackforge.core.raft;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** What the chests, barrels and wrecks of the Raft ocean hold. */
public final class RaftLoot {
    private RaftLoot() {}

    /** Chests of the ocean carry this tag (the kind of loot) until someone first opens them. */
    public static final org.bukkit.NamespacedKey KEY = new org.bukkit.NamespacedKey(net.blackforge.core.BlackForgeCore.NS, "raft_loot");

    /** The loot kind a chest block still carries (and clears it), or null. */
    public static String takeTag(org.bukkit.block.Block b) {
        if (!(b.getState() instanceof org.bukkit.block.Chest chest)) return null;
        String kind = chest.getPersistentDataContainer().get(KEY, org.bukkit.persistence.PersistentDataType.STRING);
        if (kind == null) return null;
        chest.getPersistentDataContainer().remove(KEY);
        chest.update(true, false);
        return kind;
    }

    private record Entry(int weight, java.util.function.Supplier<ItemStack> item, int min, int max) {}

    private static Entry raft(int w, String id, int min, int max) { return new Entry(w, () -> RaftItems.make(id), min, max); }
    private static Entry mat(int w, Material m, int min, int max) { return new Entry(w, () -> new ItemStack(m), min, max); }

    private static final List<Entry> BARREL = List.of(
            raft(20, "scrap", 1, 3), raft(18, "plastic", 1, 3), raft(14, "thatch", 2, 5), mat(14, Material.OAK_PLANKS, 2, 5),
            mat(8, Material.BREAD, 1, 2), mat(6, Material.COOKED_SALMON, 1, 2), mat(6, Material.POTATO, 1, 3), mat(4, Material.BEETROOT_SEEDS, 2, 4),
            raft(7, "fresh", 1, 1), mat(3, Material.IRON_INGOT, 1, 2), mat(2, Material.GOLDEN_CARROT, 1, 2));
    private static final List<Entry> ISLAND = List.of(
            mat(18, Material.OAK_PLANKS, 3, 8), raft(14, "plastic", 1, 4), raft(14, "scrap", 1, 4), raft(12, "thatch", 3, 8),
            mat(8, Material.BREAD, 1, 3), mat(6, Material.COOKED_COD, 2, 4), mat(6, Material.CARROT, 2, 5), mat(5, Material.MELON_SEEDS, 2, 4),
            mat(5, Material.COAL, 2, 6), mat(6, Material.IRON_INGOT, 1, 3), raft(8, "fresh", 1, 2), mat(2, Material.GOLD_INGOT, 1, 2));
    private static final List<Entry> WRECK = List.of(
            raft(18, "scrap", 2, 6), raft(18, "plastic", 2, 5), mat(14, Material.IRON_INGOT, 2, 5), mat(10, Material.GOLD_INGOT, 1, 3),
            mat(8, Material.COOKED_BEEF, 2, 4), raft(8, "fresh", 1, 3), mat(5, Material.EMERALD, 1, 3), mat(3, Material.GOLDEN_APPLE, 1, 1),
            mat(3, Material.DIAMOND, 1, 1), mat(8, Material.COAL, 3, 8), mat(6, Material.OAK_PLANKS, 4, 8));
    private static final List<Entry> TOWER = List.of(
            mat(18, Material.IRON_INGOT, 4, 10), mat(10, Material.GOLD_INGOT, 2, 5), mat(8, Material.DIAMOND, 1, 3), mat(6, Material.GOLDEN_APPLE, 1, 2),
            raft(14, "scrap", 4, 8), raft(14, "plastic", 3, 7), mat(10, Material.COOKED_BEEF, 3, 6), raft(12, "fresh", 2, 4), mat(8, Material.COAL, 6, 12),
            mat(5, Material.EMERALD, 2, 5), mat(3, Material.SHIELD, 1, 1));
    private static final List<Entry> UTOPIA = List.of(
            mat(16, Material.DIAMOND, 3, 8), mat(14, Material.EMERALD, 4, 10), mat(10, Material.GOLDEN_APPLE, 2, 4), mat(4, Material.ENCHANTED_GOLDEN_APPLE, 1, 1),
            mat(10, Material.NETHERITE_SCRAP, 1, 3), mat(12, Material.GOLD_BLOCK, 1, 3), mat(8, Material.IRON_BLOCK, 2, 4), raft(10, "fresh", 3, 6),
            mat(8, Material.COOKED_BEEF, 6, 12), mat(4, Material.TOTEM_OF_UNDYING, 1, 1));
    private static final List<Entry> STARTER = List.of(
            mat(30, Material.OAK_PLANKS, 4, 8), raft(20, "plastic", 2, 4), raft(20, "thatch", 3, 6), raft(12, "scrap", 2, 4),
            mat(10, Material.BREAD, 2, 3), raft(8, "fresh", 1, 2));

    private static List<Entry> table(String kind) {
        return switch (kind) {
            case "barrel" -> BARREL; case "wreck" -> WRECK; case "tower" -> TOWER; case "utopia" -> UTOPIA; case "starter" -> STARTER; default -> ISLAND;
        };
    }

    /** Rolls loot for a kind of container; used when the world is generated and for hooked barrels. */
    public static List<ItemStack> roll(String kind, Random r) {
        List<Entry> t = table(kind);
        int total = t.stream().mapToInt(Entry::weight).sum();
        int rolls = switch (kind) { case "barrel" -> 3 + r.nextInt(2); case "utopia" -> 7 + r.nextInt(4); case "tower" -> 6 + r.nextInt(3); case "wreck" -> 5 + r.nextInt(3); default -> 4 + r.nextInt(2); };
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            int pick = r.nextInt(total);
            for (Entry e : t) {
                pick -= e.weight();
                if (pick < 0) { ItemStack it = e.item().get(); it.setAmount(e.min() + r.nextInt(e.max() - e.min() + 1)); out.add(it); break; }
            }
        }
        return out;
    }

    public static void fill(Inventory inv, String kind, Random r) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        java.util.Collections.shuffle(slots, r);
        int i = 0;
        for (ItemStack it : roll(kind, r)) if (i < slots.size()) inv.setItem(slots.get(i++), it);
    }
}
