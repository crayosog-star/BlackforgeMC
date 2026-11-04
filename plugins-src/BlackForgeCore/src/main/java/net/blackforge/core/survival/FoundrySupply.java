package net.blackforge.core.survival;

import net.blackforge.core.Ui;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The Foundry's infinite chests: a menu that never empties. Click anything for a full stack of it. */
final class FoundrySupply implements Listener {
    static final String[] NAMES = {"Blocks", "Resources", "Agents", "Fuel & Essence", "Hoppers"};

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    private final Predicate<Location> isChest;
    private final java.util.function.ToIntFunction<Location> chestIndex;
    private final List<List<ItemStack>> stock = new ArrayList<>();

    FoundrySupply(java.util.function.ToIntFunction<Location> chestIndex, HopperUpgrades hoppers) {
        this.chestIndex = chestIndex;
        this.isChest = l -> chestIndex.applyAsInt(l) >= 0;
        stock.add(stacks(Material.STONE, Material.COBBLESTONE, Material.DIRT, Material.GRASS_BLOCK, Material.SAND, Material.GLASS,
                Material.OAK_PLANKS, Material.OAK_LOG, Material.STONE_BRICKS, Material.DEEPSLATE_BRICKS, Material.BLACKSTONE, Material.OBSIDIAN,
                Material.CHEST, Material.BARREL, Material.FURNACE, Material.CRAFTING_TABLE, Material.DROPPER, Material.DISPENSER,
                Material.REDSTONE, Material.REPEATER, Material.COMPARATOR, Material.LEVER, Material.TORCH, Material.LANTERN,
                Material.OAK_FENCE, Material.OAK_DOOR, Material.LADDER, Material.WHITE_WOOL));
        stock.add(stacks(Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE, Material.WATER_BUCKET,
                Material.COAL, Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD, Material.NETHERITE_INGOT, Material.LAPIS_LAZULI,
                Material.COPPER_INGOT, Material.WHEAT_SEEDS, Material.CARROT, Material.POTATO, Material.BEETROOT_SEEDS, Material.NETHER_WART,
                Material.SUGAR_CANE, Material.BONE_MEAL, Material.ROTTEN_FLESH, Material.STRING, Material.BONE, Material.COD));
        List<ItemStack> agents = new ArrayList<>();
        for (Robots.Job job : Robots.Job.values())
            for (int tier = 1; tier <= Robots.MAX_TIER; tier++) agents.add(Robots.robotItem(job, tier, 0, 0, 1));
        stock.add(agents);
        List<ItemStack> power = new ArrayList<>();
        for (int tier = 1; tier <= 5; tier++) power.add(Robots.fuelCell(tier, 64));
        power.add(Spawners.essence(64));
        stock.add(power);
        List<ItemStack> hopperStock = new ArrayList<>(List.of(new ItemStack(Material.HOPPER, 64)));
        for (int tier = 2; tier <= HopperTiers.MAX_TIER; tier++) { ItemStack it = hoppers.hopperItem(tier); it.setAmount(64); hopperStock.add(it); }
        ItemStack up = HopperUpgrades.upgradeItem();
        up.setAmount(64);
        hopperStock.add(up);
        hopperStock.add(BaseStation.item());
        hopperStock.add(new ItemStack(Material.CHEST, 64));
        stock.add(hopperStock);
    }

    private static List<ItemStack> stacks(Material... all) {
        List<ItemStack> out = new ArrayList<>();
        for (Material m : all) out.add(new ItemStack(m, m.getMaxStackSize()));
        return out;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onOpen(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !isChest.test(e.getClickedBlock().getLocation())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        int i = chestIndex.applyAsInt(e.getClickedBlock().getLocation());
        List<ItemStack> items = stock.get(i);
        Menu m = new Menu();
        m.inv = org.bukkit.Bukkit.createInventory(m, Math.max(9, (items.size() + 8) / 9 * 9), Ui.mm("<dark_gray>Foundry: " + NAMES[i]));
        for (int s = 0; s < items.size(); s++) m.inv.setItem(s, items.get(s).clone());
        e.getPlayer().openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory() || e.getCurrentItem() == null) return;
        var left = p.getInventory().addItem(e.getCurrentItem().clone());
        if (!left.isEmpty()) p.sendActionBar(Ui.mm("<red>Your inventory is full."));
        else p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.3f);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }
}
