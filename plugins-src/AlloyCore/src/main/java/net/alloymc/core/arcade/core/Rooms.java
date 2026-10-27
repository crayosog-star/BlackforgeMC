package net.alloymc.core.arcade.core;

import java.util.*;

/**
 * Generates a multi-room, multi-floor building (manors, libraries, workshops): a grid of rooms joined by
 * doorways, ladders between floors, and themed furniture. Returns the floor spots it used so games can put
 * spawns, gold or hiding places in them.
 */
public final class Rooms {
    public record Theme(String wall, String floor, String ceiling, String trim, String light, String window, List<String> furniture, List<String> tall) {}

    public final List<Pt> floorSpots = new ArrayList<>();
    public final List<Pt> roomCentres = new ArrayList<>();
    public int width, depth, height;

    /** rooms cx x cz, each `size` blocks inside, `floors` storeys of 5 blocks. Origin is the building's centre at ground level. */
    public static Rooms build(MapKit k, Theme t, int cx, int cz, int size, int floors) {
        Rooms r = new Rooms();
        int cell = size + 1, W = cx * cell + 1, D = cz * cell + 1, x0 = -W / 2, z0 = -D / 2, storey = 5;
        r.width = W; r.depth = D; r.height = floors * storey + 1;
        for (int f = 0; f < floors; f++) {
            int y0 = f * storey;
            for (int x = 0; x < W; x++) for (int z = 0; z < D; z++) {
                int X = x0 + x, Z = z0 + z;
                k.set(X, y0, Z, f == 0 && (x == 0 || z == 0 || x == W - 1 || z == D - 1) ? t.trim() : t.floor());
                boolean outer = x == 0 || z == 0 || x == W - 1 || z == D - 1, inner = x % cell == 0 || z % cell == 0;
                for (int y = 1; y < storey; y++) {
                    if (outer || inner) {
                        boolean corner = x % cell == 0 && z % cell == 0;
                        String b = corner ? t.trim() : t.wall();
                        if (outer && !corner && y == 2 && (x + z) % 3 == 0) b = t.window();
                        k.set(X, y0 + y, Z, b);
                    } else k.set(X, y0 + y, Z, "air");
                }
            }
            // doorways through every inner wall (and one per side to outside on the ground floor)
            for (int i = 0; i < cx; i++) for (int j = 0; j < cz; j++) {
                int rx = x0 + i * cell + 1 + size / 2, rz = z0 + j * cell + 1 + size / 2;
                if (i < cx - 1) door(k, x0 + (i + 1) * cell, y0, rz, true, k.rnd.nextInt(3) - 1);
                if (j < cz - 1) door(k, rx, y0, z0 + (j + 1) * cell, false, k.rnd.nextInt(3) - 1);
                r.roomCentres.add(Pt.of(rx + 0.5, y0 + 1, rz + 0.5));
                // furniture: along the walls of each room, leaving the middle walkable
                for (int n = 0; n < size; n++) {
                    int fx = x0 + i * cell + 1 + k.rnd.nextInt(size), fz = z0 + j * cell + 1 + k.rnd.nextInt(size);
                    boolean edge = fx == x0 + i * cell + 1 || fx == x0 + i * cell + size || fz == z0 + j * cell + 1 || fz == z0 + j * cell + size;
                    if (!edge || Math.abs(fx - rx) + Math.abs(fz - rz) < 2) continue;
                    if (k.rnd.nextInt(3) == 0 && !t.tall().isEmpty()) { String b = t.tall().get(k.rnd.nextInt(t.tall().size())); k.set(fx, y0 + 1, fz, b); k.set(fx, y0 + 2, fz, b); }
                    else k.set(fx, y0 + 1, fz, t.furniture().get(k.rnd.nextInt(t.furniture().size())));
                }
                k.late(rx, y0 + storey - 1, rz, t.light());
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) r.floorSpots.add(Pt.of(rx + a * 2 + 0.5, y0 + 1, rz + b * 2 + 0.5));
            }
            // ladder up to the next floor in the first room
            if (f < floors - 1) {
                // against the outer west wall, through a hole in the ceiling
                int lx = x0 + 1, lz = z0 + 2;
                k.set(lx, y0 + storey, lz, "air");
                for (int y = 1; y <= storey; y++) k.late(lx, y0 + y, lz, "ladder[facing=east]");
                k.set(lx + 1, y0 + 1, lz, "air"); k.set(lx + 1, y0 + 2, lz, "air");
            }
        }
        // ground-floor exits
        door(k, x0, 0, 0, true, 0); door(k, x0 + W - 1, 0, 0, true, 0);
        // flat ceiling, then a hipped roof stepping in one block per layer (hollow shells, trim on the edges)
        for (int x = 0; x < W; x++) for (int z = 0; z < D; z++) k.set(x0 + x, floors * storey, z0 + z, t.ceiling());
        for (int i = 1; 2 * i < Math.min(W, D) + 2; i++) {
            int y = floors * storey + i;
            for (int x = i - 1; x <= W - i; x++) for (int z = i - 1; z <= D - i; z++)
                if (x == i - 1 || z == i - 1 || x == W - i || z == D - i) k.set(x0 + x, y, z0 + z, (x == i - 1 || x == W - i) && (z == i - 1 || z == D - i) ? t.trim() : t.ceiling());
            r.height = Math.max(r.height, y + 1);
        }
        return r;
    }

    private static void door(MapKit k, int x, int y0, int z, boolean alongZ, int shift) {
        int dx = alongZ ? 0 : shift, dz = alongZ ? shift : 0;
        for (int w = 0; w <= 1; w++) for (int y = 1; y <= 2; y++) k.set(x + dx + (alongZ ? 0 : w), y0 + y, z + dz + (alongZ ? w : 0), "air");
    }
}
