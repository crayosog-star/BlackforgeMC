package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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
    private final FoundryLab lab;
    private final Allays allays;

    /** Where the fixed benches are, from the hub's layout. */
    public record Layout(int[] laneZ, int laneX, int y, Location forge, Location penMobs, int mobs, Location title, Location[] supply) {}

    public FoundrySystems(BlackForgeCore plugin, World world, Layout at, java.util.function.ToIntFunction<Location> supplyAt, Predicate<Location> zone, BiPredicate<Player, Location> access) {
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
        allays = new Allays(plugin, zone);
        Bukkit.getPluginManager().registerEvents(new FoundrySupply(supplyAt, hoppers), plugin);
        lab = new FoundryLab(plugin, world, hoppers, station, zone, new FoundryLab.Layout(at.laneZ(), at.laneX(), at.y(), at.forge(), at.penMobs(), at.mobs(), at.title(), at.supply()));
    }

    /** Removes this player's agents and everything in them. */
    public void clear(UUID owner) { robots.removeOwnedBy(owner); }

    public void shutdown() { lab.shutdown(); allays.shutdown(); }
}
