package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Agents, hopper tiers and Base Stations for the Arena's Foundry, on the hub server. They run in sandbox mode: everything
 * is free, nothing touches the economy, nothing is saved, and they only work inside the Foundry.
 */
public final class FoundrySystems {
    private static final long FUEL_LOW_MS = 60_000L;
    private final Robots robots;
    private final FoundryLab lab;
    private final Allays allays;
    private final BaseStation station;
    private final Function<Location, String> region;
    private final String sharedRegion;
    private final Location[] demoSpots;

    /** Where the fixed benches are, from the hub's layout. */
    public record Layout(int[] laneZ, int laneX, int y, Location forge, Location penMobs, int mobs, Location title, Location[] supply, Location allayBench, Location[] demo) {}

    /** {@code region}: which base a spot counts as part of. {@code sharedRegion}: the Forge's, which belongs to nobody. */
    public FoundrySystems(BlackForgeCore plugin, World world, Layout at, java.util.function.ToIntFunction<Location> supplyAt, Predicate<Location> zone,
                          Function<Location, String> region, String sharedRegion, BiPredicate<Player, Location> access) {
        var c = plugin.getConfig();
        this.region = region;
        this.sharedRegion = sharedRegion;
        this.demoSpots = at.demo();
        HopperUpgrades hoppers = new HopperUpgrades(plugin);
        hoppers.setSandbox(c.getInt("foundry.max-upgraded-hoppers-per-chunk", 64));
        hoppers.setAccess(access);
        robots = new Robots(plugin, true);
        robots.setSandboxLimits(c.getInt("foundry.max-agents-per-player", 20), c.getInt("foundry.max-agents-total", 60));
        robots.setAccess(access);
        robots.setWorkArea((owner, l) -> zone.test(l));
        station = new BaseStation(plugin, robots, hoppers, true);
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

    // ------------------------------------------------------------------ what /foundry uses

    /** The test tools on the Foundry's hotbar. Slot 7 stays empty: sneak-right-clicking the Forge needs a free hand. */
    public ItemStack[] hotbar() {
        return new ItemStack[]{Robots.robotItem(Robots.Job.MINER, 1, 0, 0, 1), Robots.robotItem(Robots.Job.FARMER, 1, 0, 0, 1),
                Robots.robotItem(Robots.Job.GRINDER, 1, 0, 0, 1), Robots.robotItem(Robots.Job.FISHERMAN, 1, 0, 0, 1),
                Robots.fuelCell(1, 16), HopperUpgrades.upgradeItem(), BaseStation.item(), null};
    }

    /** What the tests need that doesn't fit on the hotbar: a hopper to upgrade, and what links a chest for the allays. */
    public ItemStack[] extras() {
        return new ItemStack[]{new ItemStack(org.bukkit.Material.HOPPER, 16), new ItemStack(org.bukkit.Material.CHEST, 16),
                new ItemStack(org.bukkit.Material.ITEM_FRAME, 16), new ItemStack(org.bukkit.Material.EMERALD, 16)};
    }

    public int allayTier() { return allays.sharedTier(); }
    public boolean setAllayTier(UUID by, int tier) { return allays.setSharedTier(by, tier); }
    public String allayTierText(int tier) { return allays.tierText(tier); }
    public void reset() { lab.reset(); }

    /** One Tier I agent at each bench. Returns how many went down. */
    public int demo(Player p) {
        Robots.Job[] jobs = {Robots.Job.MINER, Robots.Job.FARMER, Robots.Job.FISHERMAN, Robots.Job.GRINDER};
        int n = 0;
        for (int i = 0; i < jobs.length; i++) if (robots.place(p, Robots.robotItem(jobs[i], 1, 0, 0, 1), demoSpots[i].getBlock(), 180f)) n++;
        return n;
    }

    /** Runs this player's agents low, and makes sure the Forge has Fuel Cells for a Courier to bring. */
    public String fuelLow(Player p) {
        List<Robots.Bot> agents = robots.agentsOf(p.getUniqueId());
        if (agents.isEmpty()) return "<gray>You have no agents placed. <white>/foundry demo<gray> places some.";
        for (Robots.Bot agent : agents) agent.fuel = Math.min(agent.fuel, FUEL_LOW_MS);
        Inventory forge = station.storageIn(sharedRegion);
        boolean stocked = false;
        if (forge != null) {
            for (ItemStack it : forge.getContents()) if (Robots.fuelTier(it) > 0) stocked = true;
            if (!stocked) forge.addItem(Robots.fuelCell(1, 16));
        }
        return "<green>" + agents.size() + " agent" + (agents.size() == 1 ? "" : "s") + " down to a minute of fuel, and the Forge has Fuel Cells. "
                + (allays.sharedTier() >= 2 ? "<gray>A Courier should fly over within a few seconds." : "<gray>Couriers do the refuelling: run <white>/foundry allays 2<gray>.");
    }

    /** Everything worth checking when something looks wrong, as chat lines. */
    public List<String> status(Player p) {
        List<String> out = new ArrayList<>();
        out.add("<gradient:#ffb347:#ff5a1a><bold>Foundry status</bold></gradient>");
        out.add(station.storageIn(sharedRegion) != null ? "<gray>The Forge: <green>ready" : "<gray>The Forge: <red>not ready yet, give it a moment");
        out.addAll(allays.sharedStatus());
        List<Robots.Bot> agents = robots.agentsOf(p.getUniqueId());
        if (agents.isEmpty()) out.add("<gray>Your agents: <white>none <dark_gray>· <white>/foundry demo<gray> places one at each bench");
        for (Robots.Bot b : agents) {
            String to = !station.delivers(b.loc) ? "<yellow>its own storage, no station in range" : sharedRegion.equals(region.apply(b.loc)) ? "<green>the Forge" : "<green>your Base Station";
            out.add("<gray>" + b.job.label + " " + Spawners.roman(b.tier) + " at <white>" + b.loc.getBlockX() + ", " + b.loc.getBlockZ() + "<gray>: " + Robots.time(b.fuel) + " of fuel, delivers to " + to);
        }
        out.addAll(lab.laneStatus());
        return out;
    }

    /** Removes this player's agents and allays, and everything in them. */
    public void clear(UUID owner) { robots.removeOwnedBy(owner); allays.clear(owner); }

    public void shutdown() { lab.shutdown(); allays.shutdown(); }
}
