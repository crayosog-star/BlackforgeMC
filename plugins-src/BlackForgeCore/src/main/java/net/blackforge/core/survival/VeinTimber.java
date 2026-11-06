package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.*;

/**
 * Vein Mine + Timber (Survival 2 experiment "vein-timber"): a reward for levelling a job. Sneak while breaking an ore
 * with a pickaxe (Miner level 20+) to mine the whole vein, or a log with an axe (Lumberjack level 20+) to fell the tree.
 * Every extra block is broken through the normal break path, so claims, job pay, drops and tool wear all still apply,
 * and it stops before the tool would break. Timber only fells real trees: logs with natural leaves next to them.
 */
final class VeinTimber implements Listener {
    static final String NAME = "<gradient:#c8c8c8:#8fb36a><bold>Vein Mine & Timber</bold></gradient>";
    private static final String ID = "vein-timber";

    private final LabExperiments.Ctx c;
    private final int level, maxVein, maxTree;
    private final Set<UUID> busy = new HashSet<>();

    VeinTimber(LabExperiments.Ctx c) {
        this.c = c;
        var cfg = c.plugin().getConfig();
        level = cfg.getInt("lab-experiments.vein-timber.job-level", 20);
        maxVein = cfg.getInt("lab-experiments.vein-timber.max-ore", 16);
        maxTree = cfg.getInt("lab-experiments.vein-timber.max-logs", 64);
        Bukkit.getPluginManager().registerEvents(this, c.plugin());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking() || busy.contains(p.getUniqueId()) || !Lab.enabled(ID)) return;
        Block b = e.getBlock();
        ItemStack tool = p.getInventory().getItemInMainHand();
        String toolName = tool.getType().name();
        List<Block> more;
        if (ore(b.getType()) && toolName.endsWith("_PICKAXE")) {
            if (c.jobs().level(p, Jobs.Job.MINER) < level) { LabExperiments.bar(p, "<gray>Vein Mine unlocks at <white>Miner level " + level); return; }
            more = connected(b, this::sameOre, maxVein);
        } else if (Tag.LOGS.isTagged(b.getType()) && toolName.endsWith("_AXE")) {
            if (c.jobs().level(p, Jobs.Job.LUMBERJACK) < level) { LabExperiments.bar(p, "<gray>Timber unlocks at <white>Lumberjack level " + level); return; }
            more = connected(b, (start, x) -> Tag.LOGS.isTagged(x.getType()), maxTree);
            if (!naturalTree(more, b)) return;
        } else return;
        if (more.isEmpty()) return;
        // after this break finishes, break the rest one by one through the player (claims, jobs and wear all apply)
        busy.add(p.getUniqueId());
        Bukkit.getScheduler().runTask(c.plugin(), () -> {
            try {
                for (Block x : more) {
                    if (!p.isOnline() || nearlyBroken(p.getInventory().getItemInMainHand())) break;
                    if (x.getType().isAir()) continue;
                    if (!p.breakBlock(x)) break;
                }
            } finally { busy.remove(p.getUniqueId()); }
        });
    }

    /** Blocks of the same kind touching the start (26 neighbours), nearest first, not counting the start itself. */
    private static List<Block> connected(Block start, java.util.function.BiPredicate<Block, Block> same, int max) {
        List<Block> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>(Set.of(start));
        Deque<Block> todo = new ArrayDeque<>(List.of(start));
        while (!todo.isEmpty() && out.size() < max) {
            Block cur = todo.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                Block n = cur.getRelative(dx, dy, dz);
                if (out.size() >= max || !seen.add(n) || !same.test(start, n)) continue;
                out.add(n);
                todo.add(n);
            }
        }
        return out;
    }

    private static boolean ore(Material m) { return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS; }

    /** Deepslate and normal versions of an ore count as one vein. */
    private boolean sameOre(Block start, Block x) {
        return ore(x.getType()) && base(x.getType()).equals(base(start.getType()));
    }

    private static String base(Material m) { return m.name().replace("DEEPSLATE_", ""); }

    /** A log pile in a build has no natural leaves: only fell when the logs touch leaves that grew there. */
    private static boolean naturalTree(List<Block> logs, Block start) {
        List<Block> all = new ArrayList<>(logs);
        all.add(start);
        for (Block l : all)
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                if (l.getRelative(dx, dy, dz).getBlockData() instanceof Leaves leaves && !leaves.isPersistent()) return true;
        return false;
    }

    private static boolean nearlyBroken(ItemStack tool) {
        if (tool == null || !(tool.getItemMeta() instanceof Damageable d)) return false;
        int max = tool.getType().getMaxDurability();
        return max > 0 && d.getDamage() >= max - 2;
    }
}
