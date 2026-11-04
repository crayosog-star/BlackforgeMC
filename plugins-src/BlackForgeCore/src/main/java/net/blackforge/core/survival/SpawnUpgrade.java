package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import net.blackforge.core.build.Canvas;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.*;

/**
 * Applies (or undoes) a phase of the volcanic-kingdom upgrade around the Survival spawn: /blackforge spawnupgrade apply|undo 1.
 *
 * Apply only ever places a block where the world has <b>air</b> and where {@link SpawnUpgradeBuilder#allowedAt} allows,
 * so no existing block can be replaced. Undo regenerates the same build and turns back to air exactly the positions
 * that still hold the block it placed (anything it didn't place was never air, so it can't match).
 * Works through the blocks a few thousand at a time (25 ms per tick) so the server keeps running smoothly.
 */
final class SpawnUpgrade {
    private final BlackForgeCore plugin;
    private final World world;
    private boolean running;

    SpawnUpgrade(BlackForgeCore plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    private File marker(int phase) { return new File(plugin.getDataFolder(), "spawn-upgrade-phase" + phase); }

    boolean command(CommandSender s, String[] a) {
        if (a.length < 2 || !a[0].equalsIgnoreCase("spawnupgrade")) return false;
        if (!s.hasPermission("blackforge.admin")) return true;
        int phase = a.length > 2 ? Integer.parseInt(a[2]) : 1;
        if (phase < 1 || phase > 4) { s.sendMessage(Ui.mm("<red>Phases 1, 2, 3 and 4 exist.")); return true; }
        if (running) { s.sendMessage(Ui.mm("<red>Already working - wait for it to finish.")); return true; }
        switch (a[1].toLowerCase()) {
            case "apply" -> run(s, phase, true);
            case "undo" -> run(s, phase, false);
            case "status" -> s.sendMessage(Ui.mm("<gray>Phase " + phase + ": " + (marker(phase).exists() ? "<green>applied" : "<yellow>not applied")));
            default -> s.sendMessage(Ui.mm("<gray>/blackforge spawnupgrade apply|undo|status 1|2|3|4"));
        }
        return true;
    }

    private void run(CommandSender s, int phase, boolean apply) {
        running = true;
        Canvas c = phase == 1 ? SpawnUpgradeBuilder.phase1() : phase == 2 ? SpawnUpgradeBuilder.phase2() : phase == 3 ? SpawnUpgradeBuilder.phase3() : SpawnUpgradeBuilder.phase4();
        Map<Long, String> all = new HashMap<>(c.blocks());
        all.putAll(c.lateBlocks());
        // chunk by chunk, so each chunk loads once
        Map<Long, List<long[]>> byChunk = new TreeMap<>();
        Map<String, BlockData> data = new HashMap<>();
        List<String> bad = new ArrayList<>();
        for (var e : all.entrySet()) {
            long k = e.getKey();
            int x = Canvas.kx(k), y = Canvas.ky(k), z = Canvas.kz(k);
            if (!SpawnUpgradeBuilder.allowedAt(x, y, z)) continue;
            data.computeIfAbsent(e.getValue(), st -> {
                try { return Bukkit.createBlockData(st); } catch (IllegalArgumentException ex) { bad.add(st); return null; }
            });
            byChunk.computeIfAbsent(((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL), kk -> new ArrayList<>()).add(new long[]{x, y, z, e.getValue().hashCode()});
        }
        if (!bad.isEmpty()) plugin.getLogger().warning("spawn upgrade: unknown block ids skipped: " + bad);
        // phase 2 also cuts a doorway through the rampart for each bridge: only wall blocks are replaced (undo puts bricks back)
        List<int[]> carves = new ArrayList<>();
        if (phase == 2) SpawnUpgradeBuilder.phase2Carve().blocks().forEach((k, v) -> carves.add(new int[]{Canvas.kx(k), Canvas.ky(k), Canvas.kz(k)}));
        Map<Integer, String> byHash = new HashMap<>();
        all.values().forEach(v -> byHash.put(v.hashCode(), v));
        Deque<List<long[]>> todo = new ArrayDeque<>(byChunk.values());
        int total = all.size();
        long[] done = {0, 0, 0};   // visited, changed, skipped (not air / not ours)
        BlockData air = Material.AIR.createBlockData();
        s.sendMessage(Ui.mm("<gold>Spawn upgrade phase " + phase + ": " + (apply ? "placing" : "removing") + " up to " + total + " blocks..."));
        plugin.getLogger().info("Spawn upgrade phase " + phase + (apply ? " apply" : " undo") + ": " + total + " blocks in " + byChunk.size() + " chunks");
        int[] lastPct = {-1};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            long until = System.nanoTime() + 25_000_000L;
            while (!todo.isEmpty() && System.nanoTime() < until) {
                for (long[] p : todo.poll()) {
                    done[0]++;
                    Block b = world.getBlockAt((int) p[0], (int) p[1], (int) p[2]);
                    BlockData want = data.get(byHash.get((int) p[3]));
                    if (want == null) continue;
                    if (apply) {
                        if (!b.getType().isAir()) { done[2]++; continue; }   // never replace anything
                        b.setBlockData(want, false);
                        done[1]++;
                    } else {
                        if (b.getType() != want.getMaterial()) { done[2]++; continue; }
                        b.setBlockData(air, false);
                        done[1]++;
                    }
                }
                int pct = (int) (100 * done[0] / Math.max(1, total));
                if (pct / 10 != lastPct[0] / 10) { lastPct[0] = pct; plugin.getLogger().info("Spawn upgrade phase " + phase + ": " + pct + "%"); }
            }
            if (!todo.isEmpty()) return;
            if (!carves.isEmpty()) {
                Set<Material> wall = Set.of(Material.POLISHED_BLACKSTONE_BRICKS, Material.BLACKSTONE, Material.POLISHED_DEEPSLATE,
                        Material.CRACKED_POLISHED_BLACKSTONE_BRICKS, Material.ORANGE_STAINED_GLASS, Material.POLISHED_BLACKSTONE);
                for (int[] p : carves) {
                    Block b = world.getBlockAt(p[0], p[1], p[2]);
                    if (apply && wall.contains(b.getType())) { b.setType(Material.AIR, false); done[1]++; }
                    else if (!apply && b.getType().isAir()) { b.setType(Material.POLISHED_BLACKSTONE_BRICKS, false); done[1]++; }
                }
            }
            task.cancel();
            running = false;
            try {
                if (apply) marker(phase).createNewFile(); else marker(phase).delete();
            } catch (Exception ignored) { }
            String msg = "Spawn upgrade phase " + phase + (apply ? " applied: " + done[1] + " blocks placed, " + done[2] + " spots left as they were (not air)."
                    : " undone: " + done[1] + " blocks removed, " + done[2] + " left (not ours).");
            plugin.getLogger().info(msg);
            s.sendMessage(Ui.mm("<green>" + msg));
        }, 1L, 1L);
    }
}
