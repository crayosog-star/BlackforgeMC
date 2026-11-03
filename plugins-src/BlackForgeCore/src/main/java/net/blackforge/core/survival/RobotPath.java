package net.blackforge.core.survival;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Short A* walk for a robot: N/E/S/W moves only (no corner cutting), 1-block step-ups, drops of up to 2 blocks, never
 * into or onto liquid, at most {@link #MAX_STEPS} moves. Pure: the world is read through {@link WorldAccess}, so no
 * Bukkit types or scheduler here.
 *
 * <p>A robot stands at (x,y,z) when that block is neither solid nor liquid and the block below is solid. A 1-high gap
 * is enough (copper golems are under a block tall); a step-up also needs the block above the robot's current spot clear.
 */
final class RobotPath {
    static final int MAX_STEPS = 24;
    static final int MAX_DROP = 2;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    interface WorldAccess {
        boolean isSolid(int x, int y, int z);
        boolean isLiquid(int x, int y, int z);
    }

    private final WorldAccess world;

    RobotPath(WorldAccess world) { this.world = world; }

    /**
     * Waypoints from start to goal inclusive (so a path of n moves has n + 1 points), or an empty list when the goal
     * can't be stood on or isn't reachable within {@link #MAX_STEPS} moves. start == goal gives just [start].
     */
    List<int[]> find(int sx, int sy, int sz, int gx, int gy, int gz) {
        if (!standable(gx, gy, gz)) return List.of();
        long start = key(sx, sy, sz), goal = key(gx, gy, gz);
        if (start == goal) return List.of(new int[]{sx, sy, sz});

        Map<Long, Integer> g = new HashMap<>();
        Map<Long, Long> from = new HashMap<>();
        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[2], b[2]));
        long seq = 0;
        g.put(start, 0);
        open.add(new long[]{start, h(sx, sz, gx, gz), seq++});
        while (!open.isEmpty()) {
            long[] top = open.poll();
            long cur = top[0];
            int cg = g.get(cur);
            if (top[1] > cg + h(x(cur), z(cur), gx, gz)) continue;   // stale entry
            if (cur == goal) return rebuild(from, goal);
            if (cg >= MAX_STEPS) continue;
            int x = x(cur), y = y(cur), z = z(cur);
            for (int[] d : DIRS) {
                int nx = x + d[0], nz = z + d[1];
                Integer ny = landing(x, y, z, nx, nz);
                if (ny == null) continue;
                long nk = key(nx, ny, nz);
                int ng = cg + 1;
                Integer old = g.get(nk);
                if (old != null && old <= ng) continue;
                g.put(nk, ng);
                from.put(nk, cur);
                open.add(new long[]{nk, ng + h(nx, nz, gx, gz), seq++});
            }
        }
        return List.of();
    }

    /** Where a robot at (x,y,z) ends up stepping toward column (nx,nz): same level, one up, or 1-2 down; null if blocked. */
    private Integer landing(int x, int y, int z, int nx, int nz) {
        if (standable(nx, y, nz)) return y;
        if (clear(nx, y, nz)) {   // open in front: fall up to MAX_DROP
            for (int d = 1; d <= MAX_DROP; d++) {
                if (standable(nx, y - d, nz)) return y - d;
                if (!clear(nx, y - d, nz)) return null;
            }
            return null;
        }
        if (clear(x, y + 1, z) && standable(nx, y + 1, nz)) return y + 1;   // step up needs headroom above
        return null;
    }

    private boolean clear(int x, int y, int z) { return !world.isSolid(x, y, z) && !world.isLiquid(x, y, z); }

    private boolean standable(int x, int y, int z) {
        return clear(x, y, z) && world.isSolid(x, y - 1, z) && !world.isLiquid(x, y - 1, z);
    }

    private static long h(int x, int z, int gx, int gz) { return Math.abs(x - gx) + Math.abs(z - gz); }   // admissible: each move shifts 1 horizontally

    private static List<int[]> rebuild(Map<Long, Long> from, long goal) {
        List<int[]> path = new ArrayList<>();
        for (Long k = goal; k != null; k = from.get(k)) path.add(new int[]{x(k), y(k), z(k)});
        Collections.reverse(path);
        return path;
    }

    // pack x/z into 26 bits each and y into 12 bits (same layout as BlockPos.asLong)
    private static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
    private static int x(long k) { return (int) (k >> 38); }
    private static int y(long k) { return (int) (k << 52 >> 52); }
    private static int z(long k) { return (int) (k << 26 >> 38); }
}
