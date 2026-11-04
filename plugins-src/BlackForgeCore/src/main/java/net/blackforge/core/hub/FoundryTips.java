package net.blackforge.core.hub;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import net.blackforge.core.survival.BaseStation;
import net.blackforge.core.survival.HopperUpgrades;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.function.Predicate;

/**
 * One short chat tip, sent only to that player, the first time they use each thing in the Foundry. What they've seen is
 * saved on the player, so a tip never repeats. To add one: add its text here and call show() with its id.
 */
final class FoundryTips implements Listener {
    private static final Map<String, String> TIPS = Map.ofEntries(
            Map.entry("allay", "Forge Allays are already flying here, carrying loose drops to the Forge. /foundry allays 2 or 3 changes their tier, and so does slot 34 of the Forge."),
            Map.entry("supply", "Click anything in this chest for a full stack. It never runs out."),
            Map.entry("forge", "Upgrades here are free. Click an agent's card to raise its tier, and use the arrow if you have more than five."),
            Map.entry("lane", "Each lane shows its rated speed and the speed it's really moving. Tier I is a plain hopper."),
            Map.entry("farm", "Place a Farmer agent next to this wheat. It starts with fuel here, harvests and replants on its own, and the wheat regrows for the next test."),
            Map.entry("pond", "Place a Fisherman agent within two blocks of this water. It catches much faster here than in Survival, so you can see it work."),
            Map.entry("mine", "Face a Miner agent at this wall when you place it. The ore grows back after a few seconds."),
            Map.entry("pen", "Place a Grinder agent right against any fence. Even Tier I reaches the mobs from there, and higher tiers reach further."),
            Map.entry("agent", "Agents face the way you're looking when you place them. They start with an hour of fuel here, and what they make goes to the Forge."),
            Map.entry("agent_menu", "Click the fuel slot with Fuel Cells in your inventory. From here you can also switch its job or pick it up."),
            Map.entry("station", "On the Build Plot, agents in the same marked square deliver to this Base Station. One fits per square. Everywhere else on the island delivers to the Forge."),
            Map.entry("upgrade", "Sneak and right-click a hopper to raise its tier. One upgrade is enough for each tier here."));

    private final BlackForgeCore plugin;
    private final Predicate<Location> zone;

    FoundryTips(BlackForgeCore plugin, Predicate<Location> zone) {
        this.plugin = plugin;
        this.zone = zone;
    }

    private void show(Player p, String id) {
        NamespacedKey seen = new NamespacedKey(plugin, "tip_" + id);
        var pdc = p.getPersistentDataContainer();
        if (pdc.has(seen, PersistentDataType.BYTE)) return;
        pdc.set(seen, PersistentDataType.BYTE, (byte) 1);
        p.sendMessage(Ui.mm("<#ffb347><bold>Tip</bold> <dark_gray>» <gray>" + TIPS.get(id)));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.4f, 1.5f);
    }

    /** Runs first, because the systems behind these clicks cancel them. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || !zone.test(e.getClickedBlock().getLocation())) return;
        Player p = e.getPlayer();
        ItemStack held = e.getItem();
        if (HopperUpgrades.isUpgrade(held)) show(p, "upgrade");
        else if (BaseStation.isStationItem(held)) show(p, "station");
        else if (held != null && held.getType() == Material.COPPER_GOLEM_SPAWN_EGG) show(p, "agent");
        else {
            String bench = FoundryZone.benchAt(e.getClickedBlock().getX(), e.getClickedBlock().getY(), e.getClickedBlock().getZ());
            if (bench != null) show(p, bench);
            if ("forge".equals(bench)) show(p, "allay");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAgent(PlayerInteractEntityEvent e) {
        if (e.getHand() == EquipmentSlot.HAND && e.getRightClicked() instanceof CopperGolem g && zone.test(g.getLocation())) show(e.getPlayer(), "agent_menu");
    }
}
