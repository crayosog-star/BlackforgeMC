package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Silk spawners (Survival, Skyblock/OneBlock and Factions): mine a spawner with a Silk Touch pickaxe to pick
 * it up with its mob type - and its upgrades, tier and kills - and place it somewhere else. Dungeon spawners
 * included. Runs at MONITOR so a break another plugin cancels (claims, factions land, islands) never drops one.
 */
public final class SilkSpawners implements Listener {
    static final NamespacedKey TYPE = new NamespacedKey("alloycore", "spawner_type");

    public SilkSpawners(AlloyCore plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** A spawner item that places as the given mob's spawner. */
    public static ItemStack item(EntityType t) {
        ItemStack it = new ItemStack(Material.SPAWNER);
        it.editMeta(BlockStateMeta.class, m -> {
            CreatureSpawner cs = (CreatureSpawner) m.getBlockState();
            cs.setSpawnedType(t);
            m.setBlockState(cs);
            m.displayName(Ui.mm("<gold><bold>" + Spawners.mobName(t) + " Spawner"));
            m.lore(List.of(Ui.mm("<gray>Place it to spawn " + Spawners.mobName(t).toLowerCase() + "s"), Ui.mm("<gray>Mine it with a <white>Silk Touch<gray> pickaxe to move it")));
            m.getPersistentDataContainer().set(TYPE, PersistentDataType.STRING, t.name());
        });
        return it;
    }

    /** Vanilla only lets ops place spawners with a mob type, so apply the type (and upgrades) ourselves. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        if (it.getType() != Material.SPAWNER || !it.hasItemMeta()) return;
        String type = it.getItemMeta().getPersistentDataContainer().get(TYPE, PersistentDataType.STRING);
        if (type == null) return;
        EntityType t;
        try { t = EntityType.valueOf(type); } catch (IllegalArgumentException ex) { return; }
        if (e.getBlockPlaced().getState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(t);
            Spawners.fromItem(it, cs);
            cs.update(true, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.SPAWNER || !(e.getBlock().getState() instanceof CreatureSpawner cs) || cs.getSpawnedType() == null) return;
        ItemStack tool = e.getPlayer().getInventory().getItemInMainHand();
        if (!tool.getType().name().endsWith("_PICKAXE")) return;
        if (!tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            e.getPlayer().sendActionBar(Ui.mm("<gray>Tip: a <white>Silk Touch pickaxe<gray> picks spawners up."));
            return;
        }
        e.setExpToDrop(0);
        ItemStack drop = item(cs.getSpawnedType());
        Spawners.toItem(cs, drop);
        var at = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        e.getBlock().getWorld().dropItemNaturally(at, drop);
        e.getBlock().getWorld().spawnParticle(Particle.ENCHANT, at, 30, 0.4, 0.4, 0.4, 0.5);
        e.getPlayer().playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.8f);
        e.getPlayer().sendActionBar(Ui.mm("<gold>Picked up a <white>" + Spawners.mobName(cs.getSpawnedType()) + " Spawner<gold>!"));
    }
}
