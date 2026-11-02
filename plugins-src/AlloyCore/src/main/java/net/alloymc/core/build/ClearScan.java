package net.alloymc.core.build;

import java.io.File;
import java.util.*;

/**
 * Before clearing the land around a spawn: reads the live region files and lists player-made blocks (chests, beds,
 * furnaces, doors...) in the area that are NOT part of the spawn builds, grouped by chunk, so nobody's base is deleted.
 * java ... ClearScan <client jar> <server> <x1> <z1> <x2> <z2> <canvas ids...>
 */
public final class ClearScan {
    static final String[] PLAYER_MADE = {"crafting_table", "furnace", "smoker", "blast_furnace", "_bed", "shulker_box", "enchanting_table", "anvil", "torch", "_sign", "item_frame", "jukebox", "beacon", "ender_chest", "hopper", "brewing_stand", "white_wool", "glass_pane", "chest"};

    public static void main(String[] a) throws Exception {
        WorldCam cam = new WorldCam(new File(a[0]));
        String server = a[1];
        int x1 = Integer.parseInt(a[2]), z1 = Integer.parseInt(a[3]), x2 = Integer.parseInt(a[4]), z2 = Integer.parseInt(a[5]);
        Set<Long> keep = new HashSet<>();
        for (int i = 6; i < a.length; i++) for (Canvas c : canvases(a[i])) { keep.addAll(c.blocks().keySet()); keep.addAll(c.lateBlocks().keySet()); }
        WorldCam.World w = cam.load(new File(Shots.NET, server + "/world/dimensions/minecraft/overworld/region"), x1, z1, x2, z2);
        Map<String, Integer> byChunk = new TreeMap<>(), byType = new TreeMap<>();
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++)
                for (int y = w.minY; y < 320; y++) {
                    int id = w.get(x, y, z);
                    if (id == 0) continue;
                    String s = w.states.get(id);
                    String n = s.replace("minecraft:", "");
                    int b = n.indexOf('['); if (b >= 0) n = n.substring(0, b);
                    boolean made = false;
                    for (String m : PLAYER_MADE) if (n.contains(m)) { made = true; break; }
                    if (!made || keep.contains(Canvas.key(x, y, z))) continue;
                    byChunk.merge((x >> 4) + "," + (z >> 4), 1, Integer::sum);
                    if (System.getenv("VERBOSE") != null && !n.contains("torch")) System.out.println("    " + n + " @ " + x + " " + y + " " + z);
                    byType.merge(n, 1, Integer::sum);
                }
        System.out.println(server + " area " + x1 + "," + z1 + " .. " + x2 + "," + z2 + ": " + byChunk.values().stream().mapToInt(Integer::intValue).sum() + " player-made blocks outside the builds");
        byType.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(15).forEach(e -> System.out.println("  " + e.getKey() + " x" + e.getValue()));
        byChunk.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(12).forEach(e -> System.out.println("  chunk " + e.getKey() + ": " + e.getValue()));
    }

    static List<Canvas> canvases(String id) {
        return switch (id) {
            case "survival" -> List.of(net.alloymc.core.survival.SpawnBuilder.spawn(), net.alloymc.core.survival.SpawnBuilder.arena(), net.alloymc.core.survival.PvpBuilder.build());
            case "skyblock" -> List.of(net.alloymc.core.survival.SkyblockBuilder.build());
            case "factions" -> List.of(net.alloymc.core.factions.FactionsBuilder.build());
            default -> List.of();
        };
    }
}
