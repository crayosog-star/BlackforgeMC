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

import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the BlackForgeMC resource pack's texture on every custom item, on every server. Items are recognised by the
 * plugin tag their own module gives them (alloycore:darkness_potion, ...) and get item model blackforge:&lt;id&gt;, so
 * copies made before the pack existed are fixed too: on join, on pickup, when a chest opens, and every few seconds.
 * Only while resource-pack-live is true: without the pack a model id renders as the purple/black missing model.
 * Ability items also get a last lore line saying where they work ({@link #WHERE}), whatever the pack.
 * To add an item: draw it in tools/pack_art.py, rebuild the pack, add its tag here.
 */
public final class ItemSkins implements Listener {

    /** plugin tag -> pack model (both without namespace) */
    private static final Map<String, String> SKINS = new LinkedHashMap<>();
    static {
        for (String id : new String[]{"ashen_bonfire", "ashen_chain", "darkness_potion", "gaia_potion", "gods_eye",
                "last_rite", "rimelock", "soul_candy", "gale_bellows", "frost_shard"}) SKINS.put(id, id);
        SKINS.put("surge_item", "surge");
    }

    /** plugin tag -> where the item works, added as the last lore line ("why didn't it fire?") */
    static final Map<String, String> WHERE = new LinkedHashMap<>();
    static {
        WHERE.put("gale_bellows", "anywhere you could hit them (claims and spawn protect)");
        WHERE.put("surge_item", "Survival outside spawn · hub Arena");
        WHERE.put("darkness_potion", "players in PvP zones · mobs anywhere");
        WHERE.put("gaia_potion", "Survival, not in spawn or others' claims · hub Arena");
        for (String id : new String[]{"rimelock", "ashen_chain", "ashen_bonfire", "gods_eye", "emp_item"})
            WHERE.put(id, "Survival PvP zones (KOTH, Scrapyard) · hub Arena");
        WHERE.put("last_rite", "Survival outside spawn · only catches where PvP is on");
    }
    static final String WHERE_PREFIX = "Works in: ";

    private final Map<NamespacedKey, NamespacedKey> keys = new LinkedHashMap<>();
    private final Map<NamespacedKey, String> where = new LinkedHashMap<>();

    public ItemSkins(AlloyCore plugin) {
        if (plugin.getConfig().getBoolean("resource-pack-live", false))
            SKINS.forEach((tag, model) -> keys.put(new NamespacedKey(plugin, tag), new NamespacedKey("blackforge", model)));
        WHERE.forEach((tag, text) -> where.put(new NamespacedKey(plugin, tag), text));
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

    /** True if the item needed (and got) its model or its "Works in" line. */
    private boolean skin(ItemStack it) {
        if (!it.hasItemMeta()) return false;
        var pdc = it.getPersistentDataContainer();
        boolean changed = false;
        for (Map.Entry<NamespacedKey, NamespacedKey> k : keys.entrySet()) {
            if (!pdc.has(k.getKey())) continue;
            if (k.getValue().equals(it.getItemMeta().getItemModel())) break;
            // the bellows only glints as a stand-in for its texture; other items keep their own glint
            boolean bellows = k.getKey().getKey().equals("gale_bellows");
            it.editMeta(m -> { m.setItemModel(k.getValue()); if (bellows) m.setEnchantmentGlintOverride(null); });
            changed = true;
            break;
        }
        for (Map.Entry<NamespacedKey, String> w : where.entrySet()) {
            if (!pdc.has(w.getKey())) continue;
            List<Component> lore = it.getItemMeta().lore();
            List<Component> out = lore == null ? new ArrayList<>() : new ArrayList<>(lore);
            if (!out.isEmpty() && PLAIN.serialize(out.getLast()).startsWith(WHERE_PREFIX)) {
                if (PLAIN.serialize(out.getLast()).equals(WHERE_PREFIX + w.getValue())) break;
                out.removeLast();   // an older wording
            }
            out.add(Ui.mm("<dark_gray>" + WHERE_PREFIX + "<gray>" + w.getValue()));
            it.editMeta(m -> m.lore(out));
            changed = true;
            break;
        }
        return changed;
    }

    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer PLAIN =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
}
