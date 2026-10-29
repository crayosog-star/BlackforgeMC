package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Puts the BlackForgeMC resource pack's texture on every custom item, on every server. Items are recognised by the
 * plugin tag their own module gives them (alloycore:darkness_potion, ...) and get item model blackforge:&lt;id&gt;, so
 * copies made before the pack existed are fixed too: on join, on pickup, when a chest opens, and every few seconds.
 * Only while resource-pack-live is true: without the pack a model id renders as the purple/black missing model.
 * To add an item: draw it in tools/pack_art.py, rebuild the pack, add its tag here.
 */
public final class ItemSkins implements Listener {

    /** plugin tag -> pack model (both without namespace) */
    private static final Map<String, String> SKINS = new LinkedHashMap<>();
    static {
        for (String id : new String[]{"ashen_bonfire", "ashen_chain", "darkness_potion", "gaia_potion", "gods_eye",
                "last_rite", "rimelock", "soul_candy", "gale_bellows"}) SKINS.put(id, id);
        SKINS.put("surge_item", "surge");
    }

    private final Map<NamespacedKey, NamespacedKey> keys = new LinkedHashMap<>();

    public ItemSkins(AlloyCore plugin) {
        if (!plugin.getConfig().getBoolean("resource-pack-live", false)) return;
        SKINS.forEach((tag, model) -> keys.put(new NamespacedKey(plugin, tag), new NamespacedKey("blackforge", model)));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) skin(p.getInventory());
        }, 100L, 60L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        skin(e.getPlayer().getInventory());
        skin(e.getPlayer().getEnderChest());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) { skin(e.getInventory()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        ItemStack it = e.getItem().getItemStack();
        if (skin(it)) e.getItem().setItemStack(it);
    }

    private void skin(Inventory inv) {
        for (ItemStack it : inv.getContents()) if (it != null) skin(it);
    }

    /** True if the item needed (and got) its model. */
    private boolean skin(ItemStack it) {
        if (!it.hasItemMeta()) return false;
        var pdc = it.getPersistentDataContainer();
        for (Map.Entry<NamespacedKey, NamespacedKey> k : keys.entrySet()) {
            if (!pdc.has(k.getKey())) continue;
            if (k.getValue().equals(it.getItemMeta().getItemModel())) return false;
            // the bellows only glints as a stand-in for its texture; other items keep their own glint
            boolean bellows = k.getKey().getKey().equals("gale_bellows");
            it.editMeta(m -> { m.setItemModel(k.getValue()); if (bellows) m.setEnchantmentGlintOverride(null); });
            return true;
        }
        return false;
    }
}
