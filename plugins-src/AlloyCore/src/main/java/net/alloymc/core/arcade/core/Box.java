package net.alloymc.core.arcade.core;

/** An axis-aligned block region relative to a map's origin (inclusive). Stored as "x1, y1, z1 ; x2, y2, z2". */
public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
    public Box {
        int a;
        if (x1 > x2) { a = x1; x1 = x2; x2 = a; }
        if (y1 > y2) { a = y1; y1 = y2; y2 = a; }
        if (z1 > z2) { a = z1; z1 = z2; z2 = a; }
    }

    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) { return new Box(x1, y1, z1, x2, y2, z2); }

    public static Box parse(String s) {
        String[] h = s.split(";");
        if (h.length != 2) throw new IllegalArgumentException("box needs two corners 'x1,y1,z1 ; x2,y2,z2': '" + s + "'");
        Pt a = Pt.parse(h[0]), b = Pt.parse(h[1]);
        return new Box((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()), (int) Math.floor(b.x()), (int) Math.floor(b.y()), (int) Math.floor(b.z()));
    }

    public boolean contains(double x, double y, double z) {
        return x >= x1 && x < x2 + 1 && y >= y1 && y < y2 + 1 && z >= z1 && z < z2 + 1;
    }

    public boolean contains(Pt p) { return contains(p.x(), p.y(), p.z()); }

    public boolean containsBlock(int x, int y, int z) { return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2; }

    public boolean intersects(Box o) {
        return x1 <= o.x2 && x2 >= o.x1 && y1 <= o.y2 && y2 >= o.y1 && z1 <= o.z2 && z2 >= o.z1;
    }

    public Box grow(int n) { return new Box(x1 - n, y1 - n, z1 - n, x2 + n, y2 + n, z2 + n); }

    public int sizeX() { return x2 - x1 + 1; }
    public int sizeY() { return y2 - y1 + 1; }
    public int sizeZ() { return z2 - z1 + 1; }
    public long volume() { return (long) sizeX() * sizeY() * sizeZ(); }
    public Pt center() { return new Pt((x1 + x2 + 1) / 2.0, y1, (z1 + z2 + 1) / 2.0, 0, 0); }

    @Override public String toString() { return x1 + ", " + y1 + ", " + z1 + " ; " + x2 + ", " + y2 + ", " + z2; }
}
