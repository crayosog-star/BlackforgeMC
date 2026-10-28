package net.alloymc.core.build;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.*;

/** Pastes a Canvas into a world a few thousand blocks per tick. */
public final class Paster {
    private Paster() {}

    public static void paste(Plugin plugin, World world, Canvas canvas, int perTick, Runnable done) {
        Map<String, BlockData> cache = new HashMap<>();
        Set<String> bad = new TreeSet<>();
        List<Map.Entry<Long, String>> main = new ArrayList<>(canvas.blocks().entrySet());
        List<Map.Entry<Long, String>> late = new ArrayList<>(canvas.lateBlocks().entrySet());
        Iterator<Map.Entry<Long, String>> it = main.iterator();
        Iterator<Map.Entry<Long, String>> lateIt = late.iterator();
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = 0;
            while (n < perTick && (it.hasNext() || lateIt.hasNext())) {
                boolean isLate = !it.hasNext();
                Map.Entry<Long, String> e = isLate ? lateIt.next() : it.next();
                BlockData bd = cache.computeIfAbsent(e.getValue(), s -> {
                    try { return Bukkit.createBlockData(s); }
                    catch (IllegalArgumentException ex) { bad.add(s); return Bukkit.createBlockData("minecraft:stone"); }
                });
                long k = e.getKey();
                world.getBlockAt(Canvas.kx(k), Canvas.ky(k), Canvas.kz(k)).setBlockData(bd, isLate);
                n++;
            }
            if (!it.hasNext() && !lateIt.hasNext()) {
                task.cancel();
                if (!bad.isEmpty()) plugin.getLogger().warning("Unknown block ids replaced with stone: " + bad);
                if (done != null) done.run();
            }
        }, 1L, 1L);
    }
}
