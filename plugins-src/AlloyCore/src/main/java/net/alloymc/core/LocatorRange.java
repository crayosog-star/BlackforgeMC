package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Locator bar: other players only show up on it within {@link #RANGE} blocks (vanilla shows them from anywhere). */
public final class LocatorRange implements Listener {
    static final double RANGE = 200;

    LocatorRange(AlloyCore plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(LocatorRange::apply);
    }

    static void apply(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (a != null && a.getBaseValue() != RANGE) a.setBaseValue(RANGE);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { apply(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { apply(e.getPlayer()); }
}
