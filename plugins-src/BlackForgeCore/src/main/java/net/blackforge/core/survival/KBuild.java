package net.blackforge.core.survival;

import net.blackforge.core.build.BlockFont;
import net.blackforge.core.build.Canvas;

import java.util.Random;

import static net.blackforge.core.survival.InfernalLayout.Y;

/** Drawing tools shared by the Infernal Kingdom builders. y is relative to the plaza floor (world Y = {@link InfernalLayout#Y}). */
final class KBuild {
    final Canvas c;
    final Random rnd = new Random(0xB1AC4F06EL);

    static final String[] BRICKS = {"polished_blackstone_bricks", "polished_blackstone_bricks", "deepslate_bricks", "deepslate_bricks", "cracked_polished_blackstone_bricks",
            "cracked_deepslate_bricks", "stone_bricks", "blackstone", "cracked_stone_bricks", "nether_bricks"};
    static final String TRIM = "polished_deepslate", GLOW = "shroomlight", GOLD = "gilded_blackstone", ROOF = "deepslate_tiles";

    KBuild(Canvas c) { this.c = c; }

    static String mc(String s) { return s.startsWith("minecraft:") ? s : "minecraft:" + s; }

    void set(int x, int y, int z, String b) { c.set(x, Y + y, z, mc(b)); }
    void late(int x, int y, int z, String b) { c.setLate(x, Y + y, z, mc(b)); }
    void put(int x, int y, int z, String b) { if (!c.isSet(x, Y + y, z)) c.set(x, Y + y, z, mc(b)); }
    boolean has(int x, int y, int z) { return c.isSet(x, Y + y, z); }

    /** Weathered wall stone: a mix of dark bricks, stable per position. */
    String brick(int x, int y, int z) {
        long h = (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
        return BRICKS[(int) Math.floorMod(h >> 3, (long) BRICKS.length)];
    }

    void box(int x1, int y1, int z1, int x2, int y2, int z2, String b) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, b);
    }

    /** Solid stone walls (weathered bricks, a trim course every {@code course} blocks) of a box, optionally with floor and ceiling; the inside is cleared to air. */
    void hall(int x1, int y1, int z1, int x2, int y2, int z2, int course, String floor, String ceiling) {
        int xa = Math.min(x1, x2), xb = Math.max(x1, x2), za = Math.min(z1, z2), zb = Math.max(z1, z2);
        for (int x = xa; x <= xb; x++)
            for (int z = za; z <= zb; z++) {
                boolean edge = x == xa || x == xb || z == za || z == zb;
                for (int y = y1; y <= y2; y++) {
                    if (edge) set(x, y, z, (y - y1) % course == course - 1 ? TRIM : brick(x, y, z));
                    else set(x, y, z, y == y1 && floor != null ? floor : y == y2 && ceiling != null ? ceiling : "air");
                }
            }
    }

    /** Hollow shell only (no interior clearing): for big towers and massing that nobody enters. */
    void shell(int x1, int y1, int z1, int x2, int y2, int z2, int course) {
        int xa = Math.min(x1, x2), xb = Math.max(x1, x2), za = Math.min(z1, z2), zb = Math.max(z1, z2);
        for (int x = xa; x <= xb; x++)
            for (int z = za; z <= zb; z++) {
                boolean edge = x <= xa + 1 || x >= xb - 1 || z <= za + 1 || z >= zb - 1;
                for (int y = y1; y <= y2; y++)
                    if (edge || y == y2 || y == y1) set(x, y, z, (y - y1) % course == course - 1 ? TRIM : brick(x, y, z));
            }
    }

    /** Battlements round the top edge of a rectangle at y. */
    void battlements(int x1, int z1, int x2, int z2, int y) {
        int xa = Math.min(x1, x2), xb = Math.max(x1, x2), za = Math.min(z1, z2), zb = Math.max(z1, z2);
        for (int x = xa; x <= xb; x++)
            for (int z = za; z <= zb; z++) {
                if (!(x == xa || x == xb || z == za || z == zb)) continue;
                set(x, y, z, TRIM);
                if (Math.floorMod(x + z, 2) == 0) set(x, y + 1, z, brick(x, y + 1, z));
            }
    }

    /** A pointed gothic window on a wall facing {@code face} (n/s/e/w), lit orange from inside; (x,z) is the wall block at the window's centre bottom. */
    void window(int x, int y, int z, char face, int w, int h) {
        int half = w / 2;
        for (int dy = 0; dy < h; dy++)
            for (int dx = -half; dx <= half; dx++) {
                double limit = dy < h - half - 1 ? half : Math.max(0, half - (dy - (h - half - 1)) * 1.0 + 0.4);
                if (Math.abs(dx) > limit) continue;
                int px = face == 'n' || face == 's' ? x + dx : x, pz = face == 'e' || face == 'w' ? z + dx : z;
                set(px, y + dy, pz, dy % 3 == 1 ? "orange_stained_glass" : "red_stained_glass");
                int ix = px + (face == 'e' ? -1 : face == 'w' ? 1 : 0), iz = pz + (face == 's' ? -1 : face == 'n' ? 1 : 0);
                set(ix, y + dy, iz, dy % 2 == 0 ? GLOW : "magma_block");
            }
        // stone surround
        for (int dy = -1; dy <= h; dy++)
            for (int dx = -half - 1; dx <= half + 1; dx++) {
                boolean top = dy >= h - half - 1;
                if (!top && Math.abs(dx) != half + 1) continue;
                if (top && Math.abs(dx) <= half && dy < h) continue;
                int px = face == 'n' || face == 's' ? x + dx : x, pz = face == 'e' || face == 'w' ? z + dx : z;
                if (!has(px, y + dy, pz) || (dy == -1 || dy == h)) set(px, y + dy, pz, TRIM);
            }
    }

    /** Black banner with a red anvil; face 's' hangs on a wall whose outside looks south, etc. topY = its top edge. */
    void banner(int x, int topY, int z, char face, int halfW, int h) {
        for (int dy = 0; dy < h; dy++)
            for (int dx = -halfW; dx <= halfW; dx++) {
                if (dy == h - 1 && dx == 0) continue;
                int row = h - 1 - dy;
                boolean anvil = (row == 2 && Math.abs(dx) <= halfW - 1) || (row == 3 && Math.abs(dx) <= 1) || (row == 4 && Math.abs(dx) <= 1) || (row == 5 && Math.abs(dx) <= halfW - 1 && halfW >= 2);
                boolean edge = Math.abs(dx) == halfW || dy == 0;
                String b = anvil ? "red_wool" : edge ? "red_concrete" : "black_wool";
                int px = face == 'n' || face == 's' ? x + dx : x, pz = face == 'e' || face == 'w' ? z + dx : z;
                set(px, topY - dy, pz, b);
            }
        int ax = face == 'n' || face == 's' ? x - halfW : x, az = face == 'e' || face == 'w' ? z - halfW : z;
        int bx = face == 'n' || face == 's' ? x + halfW : x, bz = face == 'e' || face == 'w' ? z + halfW : z;
        set(ax, topY + 1, az, "iron_chain[axis=y]");
        set(bx, topY + 1, bz, "iron_chain[axis=y]");
    }

    /** A hollow cone (steep slate roof) with ribs; r base radius, h height. */
    void cone(int cx, int y, int cz, int r, int h, String mat) {
        for (int k = 0; k <= h; k++) {
            double rr = (r + 0.5) * Math.pow(1 - (double) k / h, 0.9);
            int ri = (int) Math.ceil(rr) + 1;
            for (int x = -ri; x <= ri; x++)
                for (int z = -ri; z <= ri; z++) {
                    double d = Math.hypot(x, z);
                    if (d > rr || d <= rr - 1.5) continue;
                    boolean rib = Math.floorMod((int) Math.round(Math.atan2(z, x) * 8 / Math.PI), 2) == 0 && k % 3 != 2;
                    set(cx + x, y + k, cz + z, rib ? "blackstone" : mat);
                }
        }
    }

    /** A round tower: a stone shell with string courses, molten pointed windows on the four faces, a corbelled top. */
    void roundShell(int cx, int cz, int y0, int y1, int r, int course) {
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                double d = Math.hypot(x, z);
                if (d > r + 0.4) continue;
                boolean wall = d > r - 1.3;
                for (int y = y0; y <= y1; y++) {
                    if (!wall && y != y0 && y != y1) continue;
                    set(cx + x, y, cz + z, (y - y0) % course == course - 1 ? TRIM : brick(cx + x, y, cz + z));
                }
            }
    }

    /** Round tower with windows and a pointed spire on top; returns the world y of the spire's tip (relative). */
    int spireTower(int cx, int cz, int y0, int r, int h, int roofH, boolean lightning) {
        roundShell(cx, cz, y0, y0 + h, r, 10);
        for (int y = y0 + 6; y < y0 + h - 8; y += 11) {
            window(cx, y, cz + r - 1, 's', 3, 6);
            window(cx, y, cz - r + 1, 'n', 3, 6);
            window(cx + r - 1, y, cz, 'e', 3, 6);
            window(cx - r + 1, y, cz, 'w', 3, 6);
        }
        // corbelled gallery under the roof, a ring of merlons and crockets
        for (int x = -r - 1; x <= r + 1; x++)
            for (int z = -r - 1; z <= r + 1; z++) {
                double d = Math.hypot(x, z);
                if (d > r + 1.5 || d < r - 0.5) continue;
                set(cx + x, y0 + h, cz + z, TRIM);
                set(cx + x, y0 + h + 1, cz + z, Math.floorMod(x + z, 2) == 0 ? brick(cx + x, 0, cz + z) : "air");
            }
        cone(cx, y0 + h + 2, cz, r + 1, roofH, ROOF);
        for (int k = 0; k < 4; k++) {   // four little pinnacles round the base of the spire
            double a = k * Math.PI / 2 + Math.PI / 4;
            int px = cx + (int) Math.round(Math.cos(a) * (r + 1)), pz = cz + (int) Math.round(Math.sin(a) * (r + 1));
            box(px, y0 + h + 2, pz, px, y0 + h + 6, pz, "polished_blackstone_bricks");
            cone(px, y0 + h + 7, pz, 1, 5, ROOF);
        }
        int top = y0 + h + 2 + roofH;
        for (int y = top + 1; y <= top + 6; y++) set(cx, y, cz, "iron_chain[axis=y]");
        set(cx, top + 7, cz, lightning ? "lightning_rod" : "end_rod");
        late(cx, top + 5, cz, "lantern[hanging=true]");
        return top + 7;
    }

    /** A pointed gothic arch opening in a wall running along x (face n/s): w wide, h high at (cx, y0, z), thickness along z. */
    void archX(int cx, int y0, int z, int thick, int w, int h, String fill) {
        int half = w / 2;
        for (int dz = 0; dz < thick; dz++)
            for (int dy = 0; dy <= h; dy++) {
                double limit = dy <= h - half ? half : Math.max(-1, half - (dy - (h - half)) * 1.0);
                for (int dx = -half; dx <= half; dx++)
                    if (Math.abs(dx) <= limit) set(cx + dx, y0 + dy, z + dz, fill);
            }
    }

    /** Lettering on a wall facing south/north along x: text, scale, base y, wall plane z; dir +1 reads from the south side. */
    void lettersX(String text, int cx, int y0, int plane, int scale, int dir, String face, String edge) {
        int w = BlockFont.width(text, 1) * scale;
        BlockFont.draw(text, 1, (col, row, gr) -> {
            for (int a = 0; a < scale; a++)
                for (int b = 0; b < scale; b++) {
                    int x = dir > 0 ? cx - w / 2 + col * scale + a : cx + w / 2 - col * scale - a;
                    int y = y0 + row * scale + b;
                    set(x, y, plane, face);
                    set(x, y, plane + dir, face);
                    for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) if (!has(x + ox, y + oy, plane - dir)) set(x + ox, y + oy, plane - dir, edge);
                }
        });
    }

    /** A brazier on a stone pedestal. */
    void brazier(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            set(x + dx, y, z + dz, "polished_blackstone_bricks");
            set(x + dx, y + 1, z + dz, Math.abs(dx) + Math.abs(dz) == 2 ? "chiseled_polished_blackstone" : "polished_blackstone_bricks");
            if (dx != 0 || dz != 0) set(x + dx, y + 2, z + dz, "polished_blackstone_brick_wall");
        }
        set(x, y + 2, z, "polished_blackstone_bricks");
        late(x, y + 3, z, "campfire[lit=true]");
    }

    /** Lava under orange glass: safe to walk beside and over, glowing like a channel. */
    void lavaStrip(int x, int y, int z) {
        set(x, y, z, "orange_stained_glass");
        late(x, y - 1, z, "lava");
        set(x, y - 2, z, "magma_block");
    }

    /** Four-sided stone seal for a lava column at (x,y,z) so it cannot flow when a neighbour block changes. */
    void seal(int x, int y, int z) {
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (!has(x + o[0], y, z + o[1])) set(x + o[0], y, z + o[1], "polished_blackstone");
    }

    /** A knight statue (about 9 blocks tall) facing a compass direction: legs, tabard, pauldrons, helm with a molten visor, a sword planted point-down. */
    void statue(int x, int y, int z, char face, String metal, String cloth) {
        int fx = face == 'e' ? 1 : face == 'w' ? -1 : 0, fz = face == 's' ? 1 : face == 'n' ? -1 : 0;
        int sx = fz != 0 ? 1 : 0, sz = fx != 0 ? 1 : 0;           // sideways unit
        box(x - 2 * sx - 2 * sz, y, z - 2 * sz - 2 * sx, x + 2 * sx + 2 * sz, y + 1, z + 2 * sz + 2 * sx, "polished_blackstone");
        for (int s : new int[]{-1, 1}) box(x + s * sx - 0, y + 2, z + s * sz, x + s * sx, y + 4, z + s * sz, metal);
        box(x - sx, y + 5, z - sz, x + sx, y + 7, z + sz, cloth);
        box(x - 2 * sx, y + 8, z - 2 * sz, x + 2 * sx, y + 8, z + 2 * sz, metal);
        for (int s : new int[]{-1, 1}) box(x + 2 * s * sx, y + 5, z + 2 * s * sz, x + 2 * s * sx, y + 7, z + 2 * s * sz, metal);
        box(x, y + 9, z, x, y + 11, z, metal);
        set(x + fx, y + 10, z + fz, GLOW);
        set(x, y + 12, z, "polished_blackstone");
        // greatsword in front, point down
        int bx = x + 2 * fx, bz = z + 2 * fz;
        box(bx, y + 2, bz, bx, y + 9, bz, "iron_block");
        box(bx - sx, y + 9, bz - sz, bx + sx, y + 9, bz + sz, "gold_block");
        set(bx, y + 10, bz, "gold_block");
    }

    /** Lettering on a wall in the plane x = plane, text running along z (dir +1 reads from the east, -1 from the west). */
    void lettersZ(String text, int cz, int y0, int plane, int scale, int dir, String face, String edge) {
        int w = BlockFont.width(text, 1) * scale;
        BlockFont.draw(text, 1, (col, row, gr) -> {
            for (int a = 0; a < scale; a++)
                for (int b = 0; b < scale; b++) {
                    int z = dir > 0 ? cz + w / 2 - col * scale - a : cz - w / 2 + col * scale + a;
                    int y = y0 + row * scale + b;
                    set(plane, y, z, face);
                    for (int oy = -1; oy <= 1; oy++) for (int oz = -1; oz <= 1; oz++) if (!has(plane - dir, y + oy, z + oz)) set(plane - dir, y + oy, z + oz, edge);
                }
        });
    }

    /** A gabled stone roof over a hall whose long axis runs along x: eaves at y, ridge at y + rise, centred on cz. */
    void gableX(int x1, int x2, int cz, int halfDepth, int y, double slope, String mat) {
        for (int x = x1; x <= x2; x++)
            for (int dz = -halfDepth - 1; dz <= halfDepth + 1; dz++) {
                int ry = y + (int) Math.round((halfDepth + 1 - Math.abs(dz)) * slope);
                set(x, ry, cz + dz, (x == x1 || x == x2 || Math.abs(dz) == halfDepth + 1) ? TRIM : mat);
                set(x, ry - 1, cz + dz, mat);
            }
        for (int x : new int[]{x1, x2})
            for (int dz = -halfDepth; dz <= halfDepth; dz++) {
                int top = y + (int) Math.round((halfDepth + 1 - Math.abs(dz)) * slope) - 2;
                for (int yy = y; yy <= top; yy++) set(x, yy, cz + dz, brick(x, yy, cz + dz));
            }
    }
}
