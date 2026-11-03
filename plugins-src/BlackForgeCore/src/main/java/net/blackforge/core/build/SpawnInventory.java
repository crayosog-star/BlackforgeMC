package net.blackforge.core.build;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.InflaterInputStream;

/**
 * Inventory of the live Survival spawn before anything is changed: compares the world's blocks with the spawn as
 * generated (SpawnBuilder) and lists what was added or changed since (clustered), plus every saved entity (villagers,
 * armour stands, item frames...) from the entities region files.
 * java ... SpawnInventory <client jar>
 */
public final class SpawnInventory {
    public static void main(String[] a) throws Exception {
        WorldCam cam = new WorldCam(new File(a[0]));
        int Y = net.blackforge.core.survival.SurvivalLayout.Y, R = 96;
        WorldCam.World w = cam.load(new File(Shots.NET, "survival/world/dimensions/minecraft/overworld/region"), -R, -R, R, R);
        Canvas spawn = net.blackforge.core.survival.SpawnBuilder.spawn();
        Map<Long, String> want = new HashMap<>(spawn.blocks());
        want.putAll(spawn.lateBlocks());
        Map<String, List<int[]>> added = new TreeMap<>(), changed = new TreeMap<>();
        int missing = 0, same = 0;
        for (int x = -R; x <= R; x++)
            for (int z = -R; z <= R; z++)
                for (int y = Y - 50; y <= Y + 60; y++) {
                    String have = w.states.get(w.get(x, y, z));
                    String haveName = base(have);
                    String exp = want.get(Canvas.key(x, y, z));
                    boolean haveAir = haveName.endsWith("air");
                    if (exp == null) {
                        if (!haveAir) added.computeIfAbsent(haveName, k -> new ArrayList<>()).add(new int[]{x, y, z});
                    } else if (base(exp).equals(haveName)) same++;
                    else if (haveAir) missing++;
                    else changed.computeIfAbsent(base(exp) + " -> " + haveName, k -> new ArrayList<>()).add(new int[]{x, y, z});
                }
        System.out.println("== blocks: " + same + " as built, " + missing + " built blocks now air");
        System.out.println("== added since (not in the generated spawn), by type:");
        added.entrySet().stream().sorted((p, q) -> q.getValue().size() - p.getValue().size()).limit(40).forEach(e ->
                System.out.println("  " + e.getKey() + " x" + e.getValue().size() + "  e.g. " + Arrays.toString(e.getValue().getFirst()) + " .. " + bounds(e.getValue())));
        System.out.println("== changed (built as -> now):");
        changed.entrySet().stream().sorted((p, q) -> q.getValue().size() - p.getValue().size()).limit(25).forEach(e ->
                System.out.println("  " + e.getKey() + " x" + e.getValue().size() + "  e.g. " + Arrays.toString(e.getValue().getFirst())));
        // entities
        System.out.println("== saved entities within " + R + " blocks:");
        File ed = new File(Shots.NET, "survival/world/dimensions/minecraft/overworld/entities");
        Map<String, Integer> counts = new TreeMap<>();
        File[] files = ed.listFiles((d, n) -> n.endsWith(".mca"));
        if (files != null) for (File f : files) {
            byte[] data = Files.readAllBytes(f.toPath());
            for (int i = 0; i < 1024; i++) {
                int loc = ((data[i * 4] & 255) << 16) | ((data[i * 4 + 1] & 255) << 8) | (data[i * 4 + 2] & 255);
                if (loc == 0) continue;
                int off = loc * 4096;
                if (off + 5 > data.length) continue;
                int len = ((data[off] & 255) << 24) | ((data[off + 1] & 255) << 16) | ((data[off + 2] & 255) << 8) | (data[off + 3] & 255);
                try {
                    DataInputStream din = new DataInputStream(new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(data, off + 5, len - 1))));
                    int t = din.readByte(); din.readUTF();
                    List<Object> ents = WorldCam.arr(WorldCam.obj(WorldCam.nbt(din, t)).get("Entities"));
                    if (ents == null) continue;
                    for (Object o : ents) {
                        Map<String, Object> e = WorldCam.obj(o);
                        List<Object> pos = WorldCam.arr(e.get("Pos"));
                        double x = ((Number) pos.get(0)).doubleValue(), y = ((Number) pos.get(1)).doubleValue(), z = ((Number) pos.get(2)).doubleValue();
                        if (Math.abs(x) > R || Math.abs(z) > R) continue;
                        String id = String.valueOf(e.get("id")).replace("minecraft:", "");
                        String name = e.get("CustomName") != null ? " name=" + String.valueOf(e.get("CustomName")).replaceAll("\\s+", " ") : "";
                        String extra = id.equals("villager") && e.get("VillagerData") instanceof Map<?, ?> vd ? " " + vd.get("profession") : "";
                        counts.merge(id, 1, Integer::sum);
                        if (!id.equals("item") && !id.equals("experience_orb"))
                            System.out.printf("  %s%s%s at %.1f %.1f %.1f%n", id, extra, name.length() > 80 ? name.substring(0, 80) : name, x, y, z);
                    }
                } catch (Exception ignored) { }
            }
        }
        System.out.println("== entity totals: " + counts);
    }

    static String base(String s) { int b = s.indexOf('['); return (b < 0 ? s : s.substring(0, b)).replace("minecraft:", ""); }

    static String bounds(List<int[]> l) {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] p : l) for (int k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], p[k]); hi[k] = Math.max(hi[k], p[k]); }
        return Arrays.toString(hi) + " (from " + Arrays.toString(lo) + ")";
    }
}
