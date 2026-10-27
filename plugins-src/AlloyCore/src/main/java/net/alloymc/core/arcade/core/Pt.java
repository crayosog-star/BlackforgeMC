package net.alloymc.core.arcade.core;

/** A position relative to a map's origin, with facing. Stored in map files as "x, y, z[, yaw, pitch]". */
public record Pt(double x, double y, double z, float yaw, float pitch) {
    public static Pt of(double x, double y, double z) { return new Pt(x, y, z, 0, 0); }
    public static Pt of(double x, double y, double z, float yaw) { return new Pt(x, y, z, yaw, 0); }

    /** Block centre of (x, y, z), standing on top of the block below. */
    public static Pt block(int x, int y, int z, float yaw) { return new Pt(x + 0.5, y, z + 0.5, yaw, 0); }

    public static Pt parse(String s) {
        String[] p = s.split(",");
        if (p.length < 3) throw new IllegalArgumentException("point needs x, y, z: '" + s + "'");
        return new Pt(Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()),
                p.length > 3 ? Float.parseFloat(p[3].trim()) : 0, p.length > 4 ? Float.parseFloat(p[4].trim()) : 0);
    }

    public Pt add(double dx, double dy, double dz) { return new Pt(x + dx, y + dy, z + dz, yaw, pitch); }

    public double dist(Pt o) { return Math.sqrt((x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) + (z - o.z) * (z - o.z)); }

    /** Yaw that looks from this point towards another (Minecraft convention: 0 = south, 90 = west). */
    public Pt facing(double tx, double tz) {
        float yw = (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z));
        return new Pt(x, y, z, yw, pitch);
    }

    private static String num(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0); }

    @Override public String toString() {
        return num(x) + ", " + num(y) + ", " + num(z) + (yaw != 0 || pitch != 0 ? ", " + num(yaw) + (pitch != 0 ? ", " + num(pitch) : "") : "");
    }
}
