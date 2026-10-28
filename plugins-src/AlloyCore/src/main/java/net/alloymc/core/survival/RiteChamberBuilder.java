package net.alloymc.core.survival;

import net.alloymc.core.build.Canvas;

import java.util.List;
import java.util.Random;

/**
 * The Last Rite chamber: a sealed 25 x 11 hall of blackstone floating in the void, crying obsidian veins in the floor,
 * a glowing rune line across the middle, purple froglights in the ceiling and soul lanterns hanging over each start.
 * The two duellists start at opposite ends; the three cover walls are added (and taken away) by the plugin at runtime.
 * Coordinates are relative to the slot's origin; server-independent so gradle walkcheck and gradle preview cover it.
 */
public final class RiteChamberBuilder {
    public static final int Y = 100;                       // floor; players stand at Y+1
    public static final int LEN = 25, WID = 11, HEADROOM = 7;
    public static final double CASTER_X = 3.5, TARGET_X = 22.5, SPAWN_Z = 5.5;
    public static final float CASTER_YAW = -90f, TARGET_YAW = 90f;   // facing each other along x
    /** Cover walls {x, z from, z to}, 3 high: two shield the caster, one the target (Iso's layout). */
    public static final List<int[]> COVER = List.of(new int[]{6, 3, 8}, new int[]{10, 6, 10}, new int[]{18, 3, 8});
    public static final int COVER_HEIGHT = 3;

    private RiteChamberBuilder() {}

    public static Canvas build() {
        Canvas c = new Canvas();
        Random rnd = new Random(1307);
        // floor, with a sub-floor so nothing ever shows the void
        for (int x = -1; x <= LEN; x++)
            for (int z = -1; z <= WID; z++) {
                c.set(x, Y - 1, z, "minecraft:blackstone");
                boolean inside = x >= 0 && x < LEN && z >= 0 && z < WID;
                if (!inside) continue;
                String b;
                if (x == LEN / 2) b = (z % 2 == 0) ? "minecraft:crying_obsidian" : "minecraft:chiseled_polished_blackstone";   // the rune line
                else if (rnd.nextInt(14) == 0) b = "minecraft:crying_obsidian";                                               // veins
                else if (rnd.nextInt(6) == 0) b = "minecraft:cracked_polished_blackstone_bricks";
                else b = "minecraft:polished_blackstone_bricks";
                c.set(x, Y, z, b);
            }
        // the two start pads
        for (double sx : new double[]{CASTER_X, TARGET_X}) {
            int px = (int) Math.floor(sx);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                c.set(px + dx, Y, 5 + dz, dx == 0 && dz == 0 ? "minecraft:chiseled_polished_blackstone" : "minecraft:polished_blackstone");
        }
        // walls: blackstone with basalt pillars every 4 blocks, sealed by a ceiling
        for (int y = Y + 1; y <= Y + HEADROOM; y++)
            for (int x = -1; x <= LEN; x++)
                for (int z = -1; z <= WID; z++) {
                    boolean wall = x == -1 || x == LEN || z == -1 || z == WID;
                    if (!wall) continue;
                    boolean pillar = (x == -1 || x == LEN) ? (z % 4 == 1) : (x % 4 == 0);
                    String b = pillar ? "minecraft:polished_basalt" : (y == Y + 1 || y == Y + HEADROOM) ? "minecraft:polished_blackstone_bricks"
                            : rnd.nextInt(9) == 0 ? "minecraft:gilded_blackstone" : "minecraft:blackstone";
                    c.set(x, y, z, b);
                }
        for (int x = -1; x <= LEN; x++)
            for (int z = -1; z <= WID; z++) {
                boolean light = x >= 0 && x < LEN && z >= 0 && z < WID && x % 4 == 2 && (z == 2 || z == 8);
                c.set(x, Y + HEADROOM + 1, z, light ? "minecraft:pearlescent_froglight" : "minecraft:polished_blackstone");
            }
        // crying obsidian "eyes" in the end walls, at head height
        for (int z : new int[]{3, 7}) { c.set(-1, Y + 3, z, "minecraft:crying_obsidian"); c.set(LEN, Y + 3, z, "minecraft:crying_obsidian"); }
        // soul lanterns hanging over each start and the rune line
        for (int[] l : new int[][]{{3, 5}, {22, 5}, {12, 2}, {12, 8}}) {
            c.set(l[0], Y + HEADROOM, l[1], "minecraft:iron_chain[axis=y]");
            c.setLate(l[0], Y + HEADROOM - 1, l[1], "minecraft:soul_lantern[hanging=true]");
        }
        return c;
    }
}
