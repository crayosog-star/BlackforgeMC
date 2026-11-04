package net.blackforge.core.survival;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Sundering's layout, wake-up timeline, scaling and loot split. No server types, so it can all be unit tested. */
public final class SunderingMath {
    private SunderingMath() {}

    // Far from the Survival coordinates: several Survival listeners check x/y/z without checking the world.
    public static final int CX = 3000, CZ = 3000, Y = 100, RADIUS = 100, PIT_RING = 70, PIT_RADIUS = 12, ANCHOR_RADIUS = 13, PILLAR_HEIGHT = 11, PITS = 5, CHAINS = 4;

    // Wake-up timeline, in ticks: tremor, four chains snap one by one, break-out, leap to the centre.
    public static final int TREMOR_END = 100, BREAK_OUT = 300, LANDS = 400;
    private static final int[] SNAPS = {120, 170, 220, 280};
    public static final int RETURN_TICKS = 100;

    /** Block coordinates of a pit's centre. */
    public static int[] pit(int i) {
        double a = -Math.PI / 2 + i * 2 * Math.PI / PITS;
        return new int[]{CX + (int) Math.round(Math.cos(a) * PIT_RING), CZ + (int) Math.round(Math.sin(a) * PIT_RING)};
    }

    /** Block coordinates of one of a pit's chain pillars. */
    public static int[] anchor(int pit, int chain) {
        int[] c = pit(pit);
        double a = Math.PI / 4 + chain * Math.PI / 2;
        return new int[]{c[0] + (int) Math.round(Math.cos(a) * ANCHOR_RADIUS), c[1] + (int) Math.round(Math.sin(a) * ANCHOR_RADIUS)};
    }

    public static boolean inPit(int x, int z) {
        for (int i = 0; i < PITS; i++) {
            int[] c = pit(i);
            long dx = x - c[0], dz = z - c[1];
            if (dx * dx + dz * dz <= PIT_RADIUS * PIT_RADIUS) return true;
        }
        return false;
    }

    /** Which chain snaps on this tick of the wake-up, or -1. */
    public static int snapAt(int tick) {
        for (int i = 0; i < SNAPS.length; i++) if (SNAPS[i] == tick) return i;
        return -1;
    }

    public static double smooth(double p) {
        p = Math.max(0, Math.min(1, p));
        return p * p * (3 - 2 * p);
    }

    /** How far the chosen boss is slumped: 1 asleep, 0 upright, easing up as its chains break. */
    public static double slump(int tick) { return 1 - smooth((tick - TREMOR_END) / (double) (BREAK_OUT - TREMOR_END)); }

    /** A leap between two points: {x, y, z} at this step, eased sideways, on an arc. */
    public static double[] leap(double fx, double fy, double fz, double tx, double ty, double tz, int step, int steps, double peak) {
        double raw = Math.max(0, Math.min(1, step / (double) steps)), p = smooth(raw);
        return new double[]{fx + (tx - fx) * p, fy + (ty - fy) * p + peak * 4 * raw * (1 - raw), fz + (tz - fz) * p};
    }

    /** What share of each hit reaches the boss, so a crowd of any size takes about the same time. Never above 1. */
    public static double incomingMultiplier(double health, int fighters, double targetSeconds, double playerDps) {
        return Math.min(1, health / (targetSeconds * playerDps * Math.max(1, fighters)));
    }

    /** Seconds until the next attack (roll 0..1): quicker at 66% and 33% health. */
    public static double cadenceSeconds(double healthFraction, double roll) {
        double base = 6 + 4 * roll;
        return healthFraction < 1 / 3.0 ? base * 0.6 : healthFraction < 2 / 3.0 ? base * 0.8 : base;
    }

    /** Everyone's share of the total damage, biggest first. */
    public static <K> Map<K, Double> shares(Map<K, Double> damage) {
        double total = damage.values().stream().mapToDouble(Double::doubleValue).sum();
        List<Map.Entry<K, Double>> rows = new ArrayList<>(damage.entrySet());
        rows.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<K, Double> out = new LinkedHashMap<>();
        if (total > 0) for (Map.Entry<K, Double> e : rows) out.put(e.getKey(), e.getValue() / total);
        return out;
    }

    /** Drops anyone under the minimum and rescales the rest to 100%, so the whole pool gets paid. */
    public static <K> Map<K, Double> eligible(Map<K, Double> shares, double minimum) {
        double kept = 0;
        for (double s : shares.values()) if (s >= minimum) kept += s;
        Map<K, Double> out = new LinkedHashMap<>();
        for (Map.Entry<K, Double> e : shares.entrySet()) if (e.getValue() >= minimum) out.put(e.getKey(), e.getValue() / kept);
        return out;
    }

    public static long payout(long pool, double share) { return (long) Math.floor(pool * share); }

    /** A loot roll: roll 0..1 lands on a player with a chance equal to their share. Null if nobody qualifies. */
    public static <K> K rollWinner(Map<K, Double> shares, double roll) {
        double seen = 0;
        K last = null;
        for (Map.Entry<K, Double> e : shares.entrySet()) {
            seen += e.getValue();
            last = e.getKey();
            if (roll < seen) return last;
        }
        return last;
    }

    /** A boss index from 0..count-1, never the last one (roll 0..1). */
    public static int pickBoss(int count, int last, double roll) {
        if (count <= 1) return 0;
        if (last < 0 || last >= count) return Math.min(count - 1, (int) (roll * count));
        int i = Math.min(count - 2, (int) (roll * (count - 1)));
        return i >= last ? i + 1 : i;
    }
}
