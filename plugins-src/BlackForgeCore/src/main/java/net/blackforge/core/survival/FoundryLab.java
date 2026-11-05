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
import org.bukkit.inventory.Inventory;
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
    record Layout(int[] laneZ, int laneX, int y, Location forge, Location penMobs, int[][] mobs, Location title, Location[] supply, Location allayBench, int[] farm, FoundrySystems.Sign[] signs) {}

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
    private final Husk[] mobs;
    private final java.util.Random rnd = new java.util.Random();
    private final Runnable onEmpty;
    /** Is anyone testing, counting a player in the leave grace who has stepped out? */
    private final java.util.function.BooleanSupplier testing;
    private static final int WHEAT_SECONDS = 30;
    private static final float LABEL_VIEW_RANGE = 0.3f;
    private int wheatTimer;
    private boolean wasOccupied;
    private static final Material[] BENCH_ITEMS = {Material.COBBLESTONE, Material.WHEAT, Material.BONE, Material.ROTTEN_FLESH, Material.OAK_LOG, Material.RAW_IRON};
    private static final int BENCH_MAX = 6, BENCH_SECONDS = 5;
    private final List<org.bukkit.entity.Item> benchDrops = new ArrayList<>();
    private int benchTimer;
    /** The Courier Bench's chests, each with what its frame holds: the Allay Bench's own drops, so the whole chain can be watched. */
    private static final Material[] COURIER_ITEMS = {Material.COBBLESTONE, Material.WHEAT, Material.RAW_IRON, Material.EMERALD};
    /** The last one's the sell chest: goods the Trader buys, and cobblestone, which it doesn't, to show that stays. */
    private static final int SELL_INDEX = 3;
    private static final int COURIER_Z_OFFSET = -5, COURIER_SPACING = 2;
    private final org.bukkit.entity.ItemFrame[] courierFrames = new org.bukkit.entity.ItemFrame[COURIER_ITEMS.length];

    FoundryLab(BlackForgeCore plugin, World world, HopperUpgrades hoppers, BaseStation station, Predicate<Location> zone, Layout layout, java.util.function.BooleanSupplier testing, Runnable onEmpty) {
        this.onEmpty = onEmpty;
        this.testing = testing;
        this.mobs = new Husk[layout.mobs().length];
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
        boolean standing = false;
        for (Player p : world.getPlayers()) if (zone.test(p.getLocation())) standing = true;
        boolean occupied = FoundryTestRules.occupied(standing, testing.getAsBoolean());
        if (layout.forge().getBlock().getState(false) instanceof Barrel b) {
            if (!occupied) b.getInventory().clear();   // shared storage, so it resets too
            else if (b.getInventory().firstEmpty() < 0) clearGoods(b);   // a full Forge would stop every bench; a plot's own Station can still fill up
        }
        if (wasOccupied && !occupied) onEmpty.run();
        wasOccupied = occupied;
        for (Lane l : lanes) runLane(l);
        runAllayBench(occupied);
        runCourierBench(occupied);
        if (occupied && ++wheatTimer >= WHEAT_SECONDS) { wheatTimer = 0; regrowWheat(); }
        // along the inside of the fence and standing still, so an agent placed against any side always reaches one
        for (int i = 0; i < mobs.length; i++) {
            if (mobs[i] != null && mobs[i].isValid()) continue;
            mobs[i] = world.spawn(layout.penMobs().clone().add(layout.mobs()[i][0], 0, layout.mobs()[i][1]), Husk.class,
                    h -> { h.setPersistent(false); h.setRemoveWhenFarAway(false); h.setSilent(true); h.setAware(false); });
        }
    }

    /** Keeps the Fuel Cells, since a Courier may be counting on them. */
    private static void clearGoods(Barrel b) {
        Inventory inv = b.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (Robots.fuelTier(inv.getItem(i)) <= 0) inv.setItem(i, null);
    }

    /** Natural growth takes far too long for a test, so the Farm Bench ripens itself. */
    private void regrowWheat() {
        int[] f = layout.farm();
        int y = layout.y();   // the floor's top is y - 1, and the wheat stands on it
        for (int x = f[0]; x <= f[1]; x++) for (int z = f[2]; z <= f[3]; z++) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getBlockData() instanceof org.bukkit.block.data.Ageable a && a.getAge() < a.getMaximumAge() && b.getType() == Material.WHEAT) {
                a.setAge(a.getMaximumAge());
                b.setBlockData(a);
            }
        }
    }

    /** Back to how it started: the Forge's storage, the courier chests, the drops, the counters and the pen. */
    void reset() {
        if (layout.forge().getBlock().getState(false) instanceof Barrel b) b.getInventory().clear();
        for (int i = 0; i < COURIER_ITEMS.length; i++)
            if (layout.allayBench().getBlock().getRelative(i * COURIER_SPACING, 0, COURIER_Z_OFFSET).getState(false) instanceof Container c) c.getInventory().clear();
        benchDrops.forEach(org.bukkit.entity.Item::remove);
        benchDrops.clear();
        for (Lane l : lanes) l.counts.clear();
        for (Husk m : mobs) if (m != null) m.remove();
        regrowWheat();
    }

    /** What each lane is really moving, for /foundry status. */
    List<String> laneStatus() {
        List<String> out = new ArrayList<>();
        for (Lane l : lanes) out.add("<gray>Hopper Tier " + Spawners.roman(l.tier) + ": rated <white>" + hoppers.rate(l.tier) + "<gray>/s, now <white>" + String.format(java.util.Locale.ROOT, "%.1f", rate(l)) + "<gray>/s");
        return out;
    }

    /** Is each lane moving what it's rated for? For /foundry check. A lane's first seconds count low, so it gets some room. */
    List<String> laneChecks() {
        List<String> out = new ArrayList<>();
        for (Lane l : lanes) {
            double rated = Double.parseDouble(hoppers.rate(l.tier)), real = rate(l);
            out.add(FoundrySystems.result(real >= rated * 0.7 && real <= rated * 1.3, "Hopper Tier " + Spawners.roman(l.tier), String.format(java.util.Locale.ROOT, "%.1f a second, rated %.1f", real, rated)));
        }
        return out;
    }

    /** Are the benches actually there? For /foundry check. */
    List<String> benchChecks(int[] mine, int[] pond) {
        int wheat = 0, f[] = layout.farm();
        for (int x = f[0]; x <= f[1]; x++) for (int z = f[2]; z <= f[3]; z++) if (world.getBlockAt(x, layout.y(), z).getType() == Material.WHEAT) wheat++;
        int water = 0;
        for (int x = pond[0]; x <= pond[1]; x++) for (int z = pond[2]; z <= pond[3]; z++) if (world.getBlockAt(x, layout.y() - 1, z).getType() == Material.WATER) water++;
        int ore = 0;
        for (int x = mine[0]; x <= mine[1]; x++) for (int y = 0; y < mine[3]; y++) if (world.getBlockAt(x, layout.y() + y, mine[2]).getType().name().endsWith("_ORE")) ore++;
        int husks = 0;
        for (Husk m : mobs) if (m != null && m.isValid()) husks++;
        int chests = 0;
        for (Location s : layout.supply()) if (s.getBlock().getState(false) instanceof Container) chests++;
        return List.of(FoundrySystems.result(chests == layout.supply().length, "Supply chests", chests + " of " + layout.supply().length),
                FoundrySystems.result(wheat > 0, "Farm", wheat + " wheat"), FoundrySystems.result(water > 0, "Pond", water + " water"),
                FoundrySystems.result(ore > 0, "Mine", ore + " ore"), FoundrySystems.result(husks > 0, "Grinder Pen", husks + " mobs"));
    }

    private static double rate(Lane l) { return l.counts.stream().mapToInt(Integer::intValue).average().orElse(0); }

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
            else if (i == SELL_INDEX && c.getInventory().isEmpty()) {
                c.getInventory().addItem(new ItemStack(Material.WHEAT, 32), new ItemStack(Material.BONE, 32), new ItemStack(Material.COBBLESTONE, 8));
            } else if (i == SELL_INDEX && !c.getInventory().contains(Material.WHEAT) && !c.getInventory().contains(Material.BONE)) {
                c.getInventory().clear();   // sold out: restock next second
            }
            if (courierFrames[i] != null && courierFrames[i].isValid()) continue;
            ItemStack shown = new ItemStack(COURIER_ITEMS[i]);
            try {
                courierFrames[i] = world.spawn(chest.getRelative(org.bukkit.block.BlockFace.SOUTH).getLocation(), org.bukkit.entity.ItemFrame.class, f -> {
                    f.setFacingDirection(org.bukkit.block.BlockFace.SOUTH, true);
                    f.setItem(shown, false);
                    f.setFixed(true);   // part of the bench, so it can't be knocked off or turned
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
        double rate = rate(l);
        if (l.holo == null || !l.holo.isValid()) {
            l.holo = Ui.hologram(l.hopper.getLocation().add(0.5, 3.1, 0.5), "", 1f);
            l.holo.setPersistent(false);
        }
        l.holo.text(Ui.mm("<gold><bold>Hopper Tier " + Spawners.roman(l.tier) + "</bold><newline><gray>Rated <white>" + hoppers.rate(l.tier)
                + "<gray>/s <dark_gray>· <gray>Now <#ffb347>" + String.format(java.util.Locale.ROOT, "%.1f", rate) + "<gray>/s"));
    }

    private void spawnLabels() {
        label(layout.title(), "<gradient:#ffb347:#ff5a1a><bold>THE FOUNDRY</bold></gradient><newline><white>1 <gray>Open the gold chests for gear<newline><white>2 <gray>Follow the gold path to the Forge<newline><white>3 <gray>Each coloured path leads to a test<newline><white>/foundry check <gray>tests it all for you", 1.1f);
        label(layout.forge().clone().add(0.5, 3.4, 0.5), "<gold><bold>The Forge</bold><newline><gray>Right-click: free upgrades, allays in slot 34<newline><gray>Sneak with an empty hand: its storage", 1f);
        label(layout.allayBench().clone().add(COURIER_SPACING, 1.6, COURIER_Z_OFFSET), "<#4fd1b5><bold>Courier Bench</bold><newline><white>/foundry allays 2<gray> sorts into these framed chests<newline><white>/foundry allays 3<gray> sells from the emerald one", 1f);
        label(layout.allayBench().clone().add(0, 1.8, 0), "<#4fd1b5><bold>Allay Bench</bold><newline><gray>Allays carry these drops to the Forge<newline><white>/foundry status<gray> shows what they're doing", 1f);
        for (FoundrySystems.Sign s : layout.signs()) label(s.at(), s.text(), 1f);
        for (int i = 0; i < layout.supply().length; i++)
            label(layout.supply()[i].clone().add(0.5, 1.5, 0.5), "<#ffb347><bold>" + FoundrySupply.NAMES[i] + "</bold><newline><gray>Never runs out", 0.9f);
    }

    private void label(Location at, String text, float scale) {
        TextDisplay td = Ui.hologram(at, text, scale);
        td.setPersistent(false);
        td.setViewRange(LABEL_VIEW_RANGE);   // only the bench you're near, so signs don't pile up in front of each other
        labels.add(td);
    }

    void shutdown() {
        labels.forEach(TextDisplay::remove);
        for (Husk m : mobs) if (m != null) m.remove();
        for (Lane l : lanes) if (l.holo != null) l.holo.remove();
    }
}
