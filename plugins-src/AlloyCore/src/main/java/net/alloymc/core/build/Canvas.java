package net.alloymc.core.build;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-independent block buffer. Builders draw into a Canvas using block-data
 * strings ("minecraft:lantern[hanging=true]"); the plugin pastes it into a world
 * and PreviewMain renders it to a PNG.
 */
public class Canvas {
    private final Map<Long, String> blocks = new LinkedHashMap<>();
    private final Map<Long, String> late = new LinkedHashMap<>();

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
    public static int kx(long k) { return (int) (k >> 38); }
    public static int kz(long k) { return (int) (k << 26 >> 38); }
    public static int ky(long k) { return (int) (k << 52 >> 52); }

    public void set(int x, int y, int z, String block) {
        long k = key(x, y, z);
        late.remove(k);
        blocks.put(k, block);
    }

    /** Placed after everything else (lava sources, torches, things that need support). */
    public void setLate(int x, int y, int z, String block) {
        long k = key(x, y, z);
        blocks.remove(k);
        late.put(k, block);
    }

    public String get(int x, int y, int z) {
        long k = key(x, y, z);
        String b = late.get(k);
        return b != null ? b : blocks.get(k);
    }

    public boolean isSet(int x, int y, int z) { return get(x, y, z) != null; }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    set(x, y, z, block);
    }

    /** Filled horizontal disc. */
    public void disc(int cx, int y, int cz, double r, String block) {
        int ri = (int) Math.ceil(r);
        for (int x = -ri; x <= ri; x++)
            for (int z = -ri; z <= ri; z++)
                if (x * x + z * z <= r * r) set(cx + x, y, cz + z, block);
    }

    /** Horizontal ring between inner (exclusive) and outer (inclusive) radius. */
    public void ring(int cx, int y, int cz, double inner, double outer, String block) {
        int ri = (int) Math.ceil(outer);
        for (int x = -ri; x <= ri; x++)
            for (int z = -ri; z <= ri; z++) {
                double d = x * x + z * z;
                if (d <= outer * outer && d > inner * inner) set(cx + x, y, cz + z, block);
            }
    }

    public Map<Long, String> blocks() { return blocks; }
    public Map<Long, String> lateBlocks() { return late; }
    public int size() { return blocks.size() + late.size(); }
}
