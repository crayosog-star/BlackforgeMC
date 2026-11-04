package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
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
    public record Layout(int[] laneZ, int laneX, int y, Location forge, Location penMobs, int mobs, Location title, Location[] supply, Location allayBench) {}

    /** {@code region}: which base a spot counts as part of. {@code sharedRegion}: the Forge's, which belongs to nobody. */
    public FoundrySystems(BlackForgeCore plugin, World world, Layout at, java.util.function.ToIntFunction<Location> supplyAt, Predicate<Location> zone,
                          Function<Location, String> region, String sharedRegion, BiPredicate<Player, Location> access) {
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
        // the island is one base around the shared Forge, so every bench's agents can deliver to it; the Build Plot is for your own
        station.setRegion(l -> zone.test(l) ? region.apply(l) : null);
        station.setRadius(c.getInt("foundry.agent-radius", 40));
        allays = new Allays(plugin, station, zone, true);
        station.setAllays(allays);
        allays.setRobots(robots);
        allays.setSandboxLimit(c.getInt("foundry.max-allays-total", 12));
        allays.setShared(sharedRegion, () -> {
            for (Player p : world.getPlayers()) if (zone.test(p.getLocation())) return p.getUniqueId();
            return null;
        });
        Bukkit.getPluginManager().registerEvents(new FoundrySupply(supplyAt, hoppers), plugin);
        lab = new FoundryLab(plugin, world, hoppers, station, zone, new FoundryLab.Layout(at.laneZ(), at.laneX(), at.y(), at.forge(), at.penMobs(), at.mobs(), at.title(), at.supply(), at.allayBench()), allays::resetShared);
    }

    /** Removes this player's agents and allays, and everything in them. */
    public void clear(UUID owner) { robots.removeOwnedBy(owner); allays.clear(owner); }

    public void shutdown() { lab.shutdown(); allays.shutdown(); }
}
