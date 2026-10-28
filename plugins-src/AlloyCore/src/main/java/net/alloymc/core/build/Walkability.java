package net.alloymc.core.build;

import java.util.*;

/**
 * Offline walk-through of a Canvas build: from the spawn point, flood-fills every spot a player can reach by
 * walking, stepping up, jumping one block and dropping down, then reports
 *  - traps: reachable spots from which spawn can't be reached again (a pit, a gap behind a wall...)
 *  - hazards: reachable spots where a player would touch lava or fire.
 * Falling off the build counts as safe (every spawn world sends void-fallers back to spawn) and so do portals.
 * Deliberately conservative: no diagonal moves, no sprint-jumps across gaps, no double jump.
 */
public final class Walkability {
    private static final double BODY = 1.8, STEP = 0.6, JUMP = 1.25;
    private enum Kind { FULL, HALF, TALL, THIN, LAVA, FIRE, PORTAL, WATER, CLIMB }

    private record Part(double lo, double hi, Kind kind) {}

    public record Report(int reachable, List<int[]> traps, List<int[]> hazards) {
        public boolean ok() { return traps.isEmpty() && hazards.isEmpty(); }

        public String summary(String name) {
            StringBuilder sb = new StringBuilder(name + ": " + reachable + " reachable spots, " + traps.size() + " trap spots, " + hazards.size() + " hazard spots");
            for (int[] t : cluster(traps)) sb.append("\n   trap   near ").append(t[0]).append(' ').append(t[1]).append(' ').append(t[2]).append("  (").append(t[3]).append(" spots)");
            for (int[] t : cluster(hazards)) sb.append("\n   hazard near ").append(t[0]).append(' ').append(t[1]).append(' ').append(t[2]).append("  (").append(t[3]).append(" spots)");
            return sb.toString();
        }
    }

    private final Map<Long, List<Part>> cols = new HashMap<>();
    private int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

    private static long col(int x, int z) { return ((long) x << 32) ^ (z & 0xFFFFFFFFL); }

    public Walkability(Canvas c) {
        Map<Long, String> all = new HashMap<>(c.blocks());
        all.putAll(c.lateBlocks());
        for (var e : all.entrySet()) {
            Kind k = kind(e.getValue());
            if (k == null) continue;
            int x = Canvas.kx(e.getKey()), y = Canvas.ky(e.getKey()), z = Canvas.kz(e.getKey());
            double hi = switch (k) { case HALF -> y + 0.5; case TALL -> y + 1.5; case THIN -> y + 0.1; default -> y + 1; };
            cols.computeIfAbsent(col(x, z), q -> new ArrayList<>()).add(new Part(y, hi, k));
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        for (List<Part> l : cols.values()) l.sort(Comparator.comparingDouble(Part::lo));
    }

    /** Block id -> collision kind, or null for no collision (air, flowers, buttons, signs...). */
    static Kind kind(String id) {
        String n = id.replace("minecraft:", "");
        String base = n.contains("[") ? n.substring(0, n.indexOf('[')) : n;
        if (base.equals("air") || base.equals("light") || base.equals("structure_void")) return null;
        if (base.equals("lava")) return Kind.LAVA;
        if (base.equals("fire") || base.equals("soul_fire")) return Kind.FIRE;
        if (base.equals("nether_portal") || base.equals("end_portal") || base.equals("end_gateway")) return Kind.PORTAL;
        if (base.equals("water") || base.equals("bubble_column")) return Kind.WATER;
        if (base.equals("ladder") || base.equals("vine") || base.contains("scaffolding") || base.endsWith("_vines")) return Kind.CLIMB;
        if (base.contains("button") || base.contains("torch") || base.endsWith("_sign") || base.endsWith("_banner") || base.contains("pressure_plate")
                || base.contains("flower") && !base.contains("pot") && !base.contains("flowering_azalea") || base.endsWith("tulip") || base.equals("short_grass") || base.equals("tall_grass")
                || base.equals("fern") || base.equals("large_fern") || base.contains("sapling") || base.contains("rail") || base.equals("lever")
                || base.equals("redstone_wire") || base.contains("coral_fan") || base.equals("dead_bush") || base.contains("mushroom") && !base.contains("block")
                || base.equals("cobweb") || base.contains("roots") || base.contains("fungus") || base.equals("sugar_cane") || base.equals("glow_lichen")
                || base.equals("tripwire") || base.contains("sculk_vein") || base.equals("pink_petals") || base.contains("leaf_litter") || base.equals("dandelion")
                || base.equals("poppy") || base.equals("allium") || base.equals("azure_bluet") || base.equals("cornflower") || base.equals("oxeye_daisy")
                || base.equals("lily_of_the_valley") || base.equals("sunflower") || base.equals("lilac") || base.equals("peony") || base.equals("rose_bush")
                || base.equals("wither_rose") || base.equals("bush") || base.equals("firefly_bush") || base.contains("dry_grass"))
            return null;
        if (base.contains("carpet") || base.equals("snow") || base.contains("trapdoor") && !n.contains("open=true") && n.contains("half=bottom")) return Kind.THIN;
        if (base.endsWith("_slab")) return n.contains("type=top") || n.contains("type=double") ? Kind.FULL : Kind.HALF;
        if (base.endsWith("_stairs")) return Kind.HALF;   // you walk up stairs without jumping
        if (base.equals("campfire") || base.equals("soul_campfire")) return Kind.HALF;
        if (base.contains("fence") || base.endsWith("_wall") || base.contains("pane") || base.equals("iron_bars")) return Kind.TALL;
        return Kind.FULL;
    }

    private List<Part> parts(int x, int z) { return cols.getOrDefault(col(x, z), List.of()); }

    private static boolean solid(Kind k) { return k == Kind.FULL || k == Kind.HALF || k == Kind.TALL || k == Kind.THIN; }

    /** Does a body standing at height h in column (x,z) fit, ignoring non-solid parts? */
    private boolean fits(int x, int z, double h) {
        for (Part p : parts(x, z)) if (solid(p.kind()) && p.hi() > h + 1e-6 && p.lo() < h + BODY - 1e-6) return false;
        return true;
    }

    /** What the body touches standing at h: 0 nothing, 1 hazard, 2 portal. */
    private int touches(int x, int z, double h) {
        int r = 0;
        for (Part p : parts(x, z)) {
            if (p.hi() <= h - 0.01 || p.lo() >= h + BODY) continue;
            if (p.kind() == Kind.LAVA || p.kind() == Kind.FIRE) return 1;
            if (p.kind() == Kind.PORTAL) r = 2;
        }
        return r;
    }

    /** Standable surfaces in a column (tops of solid parts with room above). */
    private List<Double> surfaces(int x, int z) {
        List<Double> out = new ArrayList<>();
        for (Part p : parts(x, z)) if (solid(p.kind()) && fits(x, z, p.hi())) out.add(p.hi());
        return out;
    }

    /** Highest surface at or below h, or NaN if the player would fall into the void. Lava on the way down = hazard. */
    private double fall(int x, int z, double h, boolean[] hazard) {
        double best = Double.NaN;
        for (double s : surfaces(x, z)) if (s <= h + 1e-6 && (Double.isNaN(best) || s > best)) best = s;
        double bottom = Double.isNaN(best) ? -1e9 : best;
        for (Part p : parts(x, z)) {
            if ((p.kind() == Kind.LAVA || p.kind() == Kind.FIRE) && p.hi() > bottom && p.lo() < h + BODY) hazard[0] = true;
            if (p.kind() == Kind.WATER && p.hi() > bottom && p.lo() < h) return Math.max(bottom, p.hi() - 1);   // lands in water, swims
        }
        return best;
    }

    private record Node(int x, int z, double h) {}

    public Report run(double sx, double sy, double sz) {
        int x0 = (int) Math.floor(sx), z0 = (int) Math.floor(sz);
        boolean[] hz = new boolean[1];
        double h0 = fall(x0, z0, sy + 0.5, hz);
        if (Double.isNaN(h0)) throw new IllegalStateException("spawn has no floor");
        Node start = new Node(x0, z0, h0);
        Map<Node, List<Node>> edges = new HashMap<>();
        Set<Node> goal = new HashSet<>(), hazards = new HashSet<>();
        Node VOID = new Node(Integer.MIN_VALUE, 0, 0);
        goal.add(start);
        goal.add(VOID);
        ArrayDeque<Node> q = new ArrayDeque<>(List.of(start));
        Set<Node> seen = new HashSet<>(List.of(start));
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            Node n = q.poll();
            List<Node> out = new ArrayList<>();
            edges.put(n, out);
            int t = touches(n.x(), n.z(), n.h());
            if (t == 1) { hazards.add(n); continue; }
            if (t == 2) { goal.add(n); continue; }
            if (n.x() < minX - 2 || n.x() > maxX + 2 || n.z() < minZ - 2 || n.z() > maxZ + 2) continue;
            boolean headroom = fits(n.x(), n.z(), n.h() + JUMP);
            for (int[] d : dirs) {
                int nx = n.x() + d[0], nz = n.z() + d[1];
                // step / jump up onto (or across to) a surface
                for (double s : surfaces(nx, nz)) {
                    if (s < n.h() - 1e-6) continue;
                    double rise = s - n.h();
                    if (rise <= STEP + 1e-6 || rise <= JUMP + 1e-6 && headroom) out.add(new Node(nx, nz, s));
                }
                // walk off an edge and drop
                if (fits(nx, nz, n.h())) {
                    boolean[] lava = new boolean[1];
                    double s = fall(nx, nz, n.h(), lava);
                    if (lava[0]) { Node hn = new Node(nx, nz, Double.isNaN(s) ? -999 : s); hazards.add(hn); }
                    else out.add(Double.isNaN(s) ? VOID : new Node(nx, nz, s));
                }
            }
            for (Node m : out) if (m != VOID && seen.add(m)) q.add(m);
        }
        // reverse walk from the safe exits: spawn, the void and portals
        Map<Node, List<Node>> rev = new HashMap<>();
        for (var e : edges.entrySet()) for (Node m : e.getValue()) rev.computeIfAbsent(m, k -> new ArrayList<>()).add(e.getKey());
        Set<Node> ok = new HashSet<>(goal);
        ArrayDeque<Node> rq = new ArrayDeque<>(goal);
        while (!rq.isEmpty()) for (Node p : rev.getOrDefault(rq.poll(), List.of())) if (ok.add(p)) rq.add(p);
        List<int[]> traps = new ArrayList<>(), haz = new ArrayList<>();
        for (Node n : seen) if (!ok.contains(n) && !hazards.contains(n)) traps.add(new int[]{n.x(), (int) Math.floor(n.h()), n.z()});
        for (Node n : hazards) haz.add(new int[]{n.x(), (int) Math.floor(n.h()), n.z()});
        return new Report(seen.size(), traps, haz);
    }

    /** Groups nearby spots: {x, y, z, count} per cluster. */
    static List<int[]> cluster(List<int[]> pts) {
        List<int[]> out = new ArrayList<>();
        boolean[] used = new boolean[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            if (used[i]) continue;
            int[] a = pts.get(i);
            int n = 0;
            for (int j = i; j < pts.size(); j++) {
                int[] b = pts.get(j);
                if (!used[j] && Math.abs(a[0] - b[0]) <= 4 && Math.abs(a[2] - b[2]) <= 4 && Math.abs(a[1] - b[1]) <= 4) { used[j] = true; n++; }
            }
            out.add(new int[]{a[0], a[1], a[2], n});
        }
        return out;
    }
}
