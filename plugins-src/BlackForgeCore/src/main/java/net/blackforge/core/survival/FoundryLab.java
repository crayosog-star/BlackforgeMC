package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Runs the Foundry's fixed test benches: the three hopper lanes with their live counters, the shared Forge, and the
 * Grinder pen. It checks once a second and waits until the island is built, so an Arena rebuild needs no special handling.
 */
final class FoundryLab {
    /** Where the hub's layout says everything is. */
    record Layout(int[] laneZ, int laneX, int y, Location forge, Location penMobs, int mobs, Location title, Location[] supply, Location allayBench) {}

    private static final class Lane {
        final int tier; final Block source, hopper, sink; final Deque<Integer> counts = new ArrayDeque<>(); TextDisplay holo;
        Lane(int tier, Block source, Block hopper, Block sink) { this.tier = tier; this.source = source; this.hopper = hopper; this.sink = sink; }
    }

    private final HopperUpgrades hoppers;
    private final BaseStation station;
    private final Layout layout;
    private final World world;
    private final Predicate<Location> zone;
    private final List<Lane> lanes = new ArrayList<>();
    private final List<TextDisplay> labels = new ArrayList<>();
    private final List<Husk> mobs = new ArrayList<>();
    private final java.util.Random rnd = new java.util.Random();
    private static final Material[] BENCH_ITEMS = {Material.COBBLESTONE, Material.WHEAT, Material.BONE, Material.ROTTEN_FLESH, Material.OAK_LOG, Material.RAW_IRON};
    private static final int BENCH_MAX = 6, BENCH_SECONDS = 5;
    private final List<org.bukkit.entity.Item> benchDrops = new ArrayList<>();
    private int benchTimer;
    /** The Courier Bench's chests, each with what its frame holds: the Allay Bench's own drops, so the whole chain can be watched. */
    private static final Material[] COURIER_ITEMS = {Material.COBBLESTONE, Material.WHEAT, Material.RAW_IRON};
    private static final int COURIER_Z_OFFSET = -5, COURIER_SPACING = 2;
    private final org.bukkit.entity.ItemFrame[] courierFrames = new org.bukkit.entity.ItemFrame[COURIER_ITEMS.length];

    FoundryLab(BlackForgeCore plugin, World world, HopperUpgrades hoppers, BaseStation station, Predicate<Location> zone, Layout layout) {
        this.world = world; this.hoppers = hoppers; this.station = station; this.zone = zone; this.layout = layout;
        for (int i = 0; i < layout.laneZ().length; i++) {
            int z = layout.laneZ()[i], x = layout.laneX(), y = layout.y();
            lanes.add(new Lane(i + 1, world.getBlockAt(x, y + 1, z), world.getBlockAt(x, y, z), world.getBlockAt(x + 1, y, z)));
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 20L);
    }

    private void tick() {
        if (layout.forge().getBlock().getType() != Material.BARREL) return;   // still being built
        station.adopt(layout.forge().getBlock());
        if (labels.isEmpty()) spawnLabels();
        boolean occupied = false;
        for (Player p : world.getPlayers()) if (zone.test(p.getLocation())) occupied = true;
        if (!occupied && layout.forge().getBlock().getState(false) instanceof Barrel b) b.getInventory().clear();   // shared storage, so it resets too
        for (Lane l : lanes) runLane(l);
        runAllayBench(occupied);
        runCourierBench(occupied);
        mobs.removeIf(m -> !m.isValid());
        // spread out and standing still, so they stay in the pen and an agent placed beside one always reaches it
        while (mobs.size() < layout.mobs()) mobs.add(world.spawn(layout.penMobs().clone().add(rnd.nextInt(7) - 3, 0, rnd.nextInt(7) - 3), Husk.class,
                h -> { h.setPersistent(false); h.setRemoveWhenFarAway(false); h.setSilent(true); h.setAware(false); }));
    }

    /** Keeps a few loose items landing while someone's here, and clears them when nobody is. They're never saved. */
    private void runAllayBench(boolean occupied) {
        benchDrops.removeIf(i -> !i.isValid());
        if (!occupied) {
            benchDrops.forEach(org.bukkit.entity.Item::remove);
            benchDrops.clear();
            return;
        }
        if (++benchTimer < BENCH_SECONDS || benchDrops.size() >= BENCH_MAX) return;
        benchTimer = 0;
        Location at = layout.allayBench().clone().add(rnd.nextDouble() * 3 - 1.5, 1, rnd.nextDouble() * 3 - 1.5);
        benchDrops.add(world.dropItem(at, new ItemStack(BENCH_ITEMS[rnd.nextInt(BENCH_ITEMS.length)], 1 + rnd.nextInt(32)), i -> i.setPersistent(false)));
    }

    /** Three chests on the open floor with framed items. Set up here, not in the Arena's build, so the Arena needn't rebuild. They reset when nobody's here. */
    private void runCourierBench(boolean occupied) {
        for (int i = 0; i < COURIER_ITEMS.length; i++) {
            Block chest = layout.allayBench().getBlock().getRelative(i * COURIER_SPACING, 0, COURIER_Z_OFFSET);
            if (chest.getType().isAir() && chest.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid()) chest.setType(Material.CHEST);
            if (chest.getType() != Material.CHEST || !(chest.getState(false) instanceof Container c)) continue;
            if (!occupied) c.getInventory().clear();
            if (courierFrames[i] != null && courierFrames[i].isValid()) continue;
            ItemStack shown = new ItemStack(COURIER_ITEMS[i]);
            try {
                courierFrames[i] = world.spawn(chest.getRelative(org.bukkit.block.BlockFace.SOUTH).getLocation(), org.bukkit.entity.ItemFrame.class, f -> {
                    f.setFacingDirection(org.bukkit.block.BlockFace.SOUTH, true);
                    f.setItem(shown, false);
                    f.setPersistent(false);
                });
            } catch (IllegalArgumentException ex) {
                return;   // no room for a frame here: the check on the plan's gate shows it
            }
        }
    }

    /** Fills the source chest, empties the sink and shows what really came through over the last 5 seconds. */
    private void runLane(Lane l) {
        if (l.hopper.getType() != Material.HOPPER || !(l.source.getState(false) instanceof Container src) || !(l.sink.getState(false) instanceof Container sink)) return;
        hoppers.applyTier(l.hopper, l.tier);
        for (int s = 0; s < src.getInventory().getSize(); s++) src.getInventory().setItem(s, new ItemStack(Material.COBBLESTONE, 64));
        int through = 0;
        for (ItemStack it : sink.getInventory().getContents()) if (it != null) through += it.getAmount();
        sink.getInventory().clear();
        l.counts.addLast(through);
        if (l.counts.size() > 5) l.counts.removeFirst();
        double rate = l.counts.stream().mapToInt(Integer::intValue).average().orElse(0);
        if (l.holo == null || !l.holo.isValid()) {
            l.holo = Ui.hologram(l.hopper.getLocation().add(0.5, 3.1, 0.5), "", 1f);
            l.holo.setPersistent(false);
        }
        l.holo.text(Ui.mm("<gold><bold>Hopper Tier " + Spawners.roman(l.tier) + "</bold><newline><gray>Rated <white>" + hoppers.rate(l.tier)
                + "<gray>/s <dark_gray>· <gray>Now <#ffb347>" + String.format(java.util.Locale.ROOT, "%.1f", rate) + "<gray>/s"));
    }

    private void spawnLabels() {
        label(layout.title(), "<gradient:#ffb347:#ff5a1a><bold>THE FOUNDRY</bold></gradient><newline><gray>Build and test anything<newline><dark_gray>It all goes when you leave", 1.3f);
        label(layout.forge().clone().add(0.5, 2.3, 0.5), "<gold><bold>The Forge</bold><newline><gray>Upgrades are free here", 1f);
        label(layout.allayBench().clone().add(COURIER_SPACING, 1.6, COURIER_Z_OFFSET), "<#4fd1b5><bold>Courier Bench</bold><newline><gray>A Tier II allay sorts the Forge's<newline><gray>storage into these framed chests", 1f);
        label(layout.allayBench().clone().add(0, 1.8, 0), "<#4fd1b5><bold>Allay Bench</bold><newline><gray>Summon a Forge Allay from slot 34<newline><gray>of the Forge to clear these drops", 1f);
        for (int i = 0; i < layout.supply().length; i++)
            label(layout.supply()[i].clone().add(0.5, 1.5, 0.5), "<#ffb347><bold>" + FoundrySupply.NAMES[i] + "</bold><newline><gray>Never runs out", 0.9f);
    }

    private void label(Location at, String text, float scale) {
        TextDisplay td = Ui.hologram(at, text, scale);
        td.setPersistent(false);
        labels.add(td);
    }

    void shutdown() {
        labels.forEach(TextDisplay::remove);
        mobs.forEach(Husk::remove);
        for (Lane l : lanes) if (l.holo != null) l.holo.remove();
    }
}
