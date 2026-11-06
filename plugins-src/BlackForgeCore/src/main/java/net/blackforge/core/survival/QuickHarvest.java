package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Quick Harvest (Survival 2 experiment "quick-harvest"): right-click a fully grown crop to harvest it and replant it in
 * one go. It counts as breaking the crop (a break event is fired first), so claims, Farmer job pay and quests all work;
 * one seed from the drops pays for the replant.
 */
final class QuickHarvest implements Listener {
    static final String NAME = "<gradient:#e3c85a:#8fb36a><bold>Quick Harvest</bold></gradient>";
    private static final String ID = "quick-harvest";
    private static final Map<Material, Material> SEED = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROTS, Material.CARROT, Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS, Material.NETHER_WART, Material.NETHER_WART);

    QuickHarvest(LabExperiments.Ctx c) {
        Bukkit.getPluginManager().registerEvents(this, c.plugin());
    }

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        Material seed = SEED.get(b.getType());
        Player p = e.getPlayer();
        if (seed == null || p.isSneaking() || !Lab.enabled(ID) || !(b.getBlockData() instanceof Ageable age) || age.getAge() < age.getMaximumAge()) return;
        e.setCancelled(true);
        BlockBreakEvent check = new BlockBreakEvent(b, p);   // claims, jobs and quests see a normal harvest
        check.setDropItems(false);
        if (!check.callEvent()) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        List<ItemStack> drops = new ArrayList<>(b.getDrops(tool, p));
        for (ItemStack s : drops) if (s.getType() == seed) { s.setAmount(s.getAmount() - 1); break; }   // the replant
        age.setAge(0);
        b.setBlockData(age);
        for (ItemStack s : drops) if (s.getAmount() > 0) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), s);
        b.getWorld().playSound(b.getLocation(), b.getType() == Material.NETHER_WART ? Sound.BLOCK_NETHER_WART_BREAK : Sound.BLOCK_CROP_BREAK, 0.8f, 1.1f);
        p.swingMainHand();
    }
}
