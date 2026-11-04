package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Agents, hopper tiers and Base Stations for the Arena's Foundry, on the hub server. They run in sandbox mode: everything
 * is free, nothing touches the economy, nothing is saved, and they only work inside the Foundry.
 */
public final class FoundrySystems {
    private final Robots robots;

    public FoundrySystems(BlackForgeCore plugin, Predicate<Location> zone, BiPredicate<Player, Location> access) {
        var c = plugin.getConfig();
        HopperUpgrades hoppers = new HopperUpgrades(plugin);
        hoppers.setSandbox(c.getInt("foundry.max-upgraded-hoppers-per-chunk", 64));
        hoppers.setAccess(access);
        robots = new Robots(plugin, true);
        robots.setSandboxLimits(c.getInt("foundry.max-agents-per-player", 20), c.getInt("foundry.max-agents-total", 60));
        robots.setAccess(access);
        robots.setWorkArea((owner, l) -> zone.test(l));
        BaseStation station = new BaseStation(plugin, robots, hoppers, true);
        station.setAccess(access);
        // one station per chunk: the island is shared, so a region can't be one player's
        station.setRegion(l -> zone.test(l) ? "foundry:" + (l.getBlockX() >> 4) + ":" + (l.getBlockZ() >> 4) : null);
    }

    /** Removes this player's agents and everything in them. */
    public void clear(UUID owner) { robots.removeOwnedBy(owner); }
}
