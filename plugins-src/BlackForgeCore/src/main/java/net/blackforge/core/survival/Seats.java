package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.*;

/**
 * Sit (Survival 2 experiment "sit"): right-click the top of a stair or a bottom slab with an empty hand to sit on it.
 * The seat is an invisible display entity that is never saved; sneaking stands you up (vanilla dismount), and the seat
 * is cleared when you stand, leave, or the block is broken.
 */
final class Seats implements Listener {
    static final String NAME = "<gradient:#c9a86a:#8b5a2b><bold>Sit</bold></gradient>";
    private static final String ID = "sit";

    private final LabExperiments.Ctx c;
    private final double height;
    private final Map<UUID, ItemDisplay> seats = new HashMap<>();   // sitter -> seat
    private final Map<UUID, Block> seatBlock = new HashMap<>();

    Seats(LabExperiments.Ctx c) {
        this.c = c;
        this.height = c.plugin().getConfig().getDouble("lab-experiments.sit.height", 0.5);   // tune if players sit too high or low
        Bukkit.getPluginManager().registerEvents(this, c.plugin());
        Bukkit.getScheduler().runTaskTimer(c.plugin(), this::sweep, 5L, 5L);
    }

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (p.isSneaking() || !p.getInventory().getItemInMainHand().getType().isAir() || p.isInsideVehicle() || !Lab.enabled(ID)) return;
        Block b = e.getClickedBlock();
        boolean seat = b.getBlockData() instanceof Stairs s && s.getHalf() == Bisected.Half.BOTTOM
                || b.getBlockData() instanceof Slab sl && sl.getType() == Slab.Type.BOTTOM;
        if (!seat || !b.getRelative(0, 1, 0).isPassable() || seatBlock.containsValue(b)) return;
        if (p.getLocation().distanceSquared(b.getLocation().add(0.5, 0.5, 0.5)) > 9) return;
        e.setCancelled(true);
        Location at = b.getLocation().add(0.5, height, 0.5);
        at.setYaw(p.getLocation().getYaw());
        ItemDisplay d = b.getWorld().spawn(at, ItemDisplay.class, x -> x.setPersistent(false));
        d.addPassenger(p);
        seats.put(p.getUniqueId(), d);
        seatBlock.put(p.getUniqueId(), b);
        LabExperiments.bar(p, "<gray>Sneak to stand up.");
    }

    /** Seats nobody is sitting on any more go away; the sitter is lifted clear of the block. */
    private void sweep() {
        for (Iterator<Map.Entry<UUID, ItemDisplay>> it = seats.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            ItemDisplay d = en.getValue();
            if (d.isValid() && !d.getPassengers().isEmpty()) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            Block b = seatBlock.remove(en.getKey());
            if (p != null && b != null && !p.isInsideVehicle() && p.getLocation().distanceSquared(b.getLocation()) < 4) {
                Location up = b.getLocation().add(0.5, 1, 0.5);
                up.setYaw(p.getLocation().getYaw()); up.setPitch(p.getLocation().getPitch());
                p.teleport(up);
            }
            d.remove();
            it.remove();
        }
    }

    private void stand(UUID id) {
        ItemDisplay d = seats.remove(id);
        seatBlock.remove(id);
        if (d != null) { for (Entity x : d.getPassengers()) d.removePassenger(x); d.remove(); }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { stand(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        for (var en : new ArrayList<>(seatBlock.entrySet())) if (en.getValue().equals(e.getBlock())) stand(en.getKey());
    }
}
