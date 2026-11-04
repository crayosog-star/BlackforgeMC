package net.blackforge.core.survival;

import net.blackforge.core.Titles;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
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
import org.bukkit.plugin.Plugin;

import java.util.function.BiPredicate;

/**
 * OWNER title holders can look inside containers on other players' land. They get a copy of the contents, not the
 * real inventory, so nothing can be taken or added whatever the client sends. The copy doesn't update while it's open.
 */
public final class OwnerView implements Listener {
    private static BiPredicate<Player, Location> foreign = (p, l) -> false;

    private static final class View implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    private OwnerView() {}

    /** foreign: is this spot on someone else's land, ignoring staff permissions? */
    static void install(Plugin plugin, BiPredicate<Player, Location> rule) {
        foreign = rule;
        Bukkit.getPluginManager().registerEvents(new OwnerView(), plugin);
    }

    /** An OWNER on land they don't belong to: containers here open as a read-only copy. */
    static boolean viewing(Player p, Location l) { return Titles.isOwner(p) && foreign.test(p, l); }

    /** Menus come in rows of 9: the smallest that fits a container of this size. */
    static int viewSize(int slots) { return Math.min(54, Math.max(1, (slots + 8) / 9) * 9); }

    // Runs after the protection and Base Station handlers, and decides even when they already denied the click.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOpen(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || b == null || e.getPlayer().isSneaking() && e.getItem() != null) return;
        if (!viewing(e.getPlayer(), b.getLocation()) || !(b.getState() instanceof Container c)) return;
        e.setCancelled(true);   // both hands, or the off-hand click would open the real container
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack[] items = c.getInventory().getContents();
        View v = new View();
        v.inv = Bukkit.createInventory(v, viewSize(items.length), Ui.mm("<dark_red>Read-only <dark_gray>· " + name(b)));
        for (int i = 0; i < items.length && i < v.inv.getSize(); i++) if (items[i] != null) v.inv.setItem(i, items[i].clone());
        e.getPlayer().openInventory(v.inv);
        e.getPlayer().playSound(e.getPlayer().getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f);
    }

    private static String name(Block b) {
        return b.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) { if (e.getInventory().getHolder() instanceof View) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof View) e.setCancelled(true); }
}
