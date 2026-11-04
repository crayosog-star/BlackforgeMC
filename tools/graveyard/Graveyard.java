import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;

import static java.lang.Math.*;

/** The Dragon's Graveyard: builds the whole arena in a voxel canvas, previews it and emits fill commands. */
public class Graveyard extends Vox {
    static final double GATE_A = -90, BRIDGE_A = 90;   // degrees: north gate (skull), south bridge

    static double ang(int x, int z) { return toDegrees(atan2(z, x)); }
    static double adiff(double a, double b) { double d = (a - b) % 360; if (d > 180) d -= 360; if (d < -180) d += 360; return abs(d); }
    static int[] pol(double r, double deg) { return new int[]{(int) round(r * cos(toRadians(deg))), (int) round(r * sin(toRadians(deg)))}; }

    // =================== STAGE 2: ARENA FLOOR ===================
    static char[][] sig = new char[65][65];
    static void sline(double x0, double z0, double x1, double z1, double t, char c) {
        for (int i = -32; i <= 32; i++) for (int j = -32; j <= 32; j++) {
            double px = i - x0, pz = j - z0, dx = x1 - x0, dz = z1 - z0, l2 = dx * dx + dz * dz;
            double u = l2 == 0 ? 0 : max(0, min(1, (px * dx + pz * dz) / l2));
            double ex = px - u * dx, ez = pz - u * dz;
            if (ex * ex + ez * ez <= t * t / 4) sig[j + 32][i + 32] = c;
        }
    }
    static void mline(double x0, double z0, double x1, double z1, double t, char c) { sline(x0, z0, x1, z1, t, c); sline(-x0, z0, -x1, z1, t, c); }
    static void makeSigil() {
        for (char[] r : sig) Arrays.fill(r, '.');
        // black outline discs
        for (int i = -32; i <= 32; i++) for (int j = -32; j <= 32; j++) { double r = hypot(i, j); if (r >= 24.0 && r < 26.5) sig[j + 32][i + 32] = 'K'; else if (r >= 21.5 && r < 22.5) sig[j + 32][i + 32] = 'K'; }
        // wings: main arm + 5 fingers + membrane edge
        double[][] arm = {{3, -6}, {8, -13}, {15, -14}};
        for (int s = 0; s < 3; s++) mline(arm[s][0], arm[s][1], s < 2 ? arm[s + 1][0] : 20, s < 2 ? arm[s + 1][1] : -11, 2.4, 'B');
        double[][] tips = {{21.5, -4}, {21, 3.5}, {17.5, 11}, {11, 16.5}, {4.5, 19}};
        for (double[] t : tips) { mline(17, -12, t[0], t[1], 1.8, 'B'); }
        for (int s = 0; s < tips.length - 1; s++) mline(tips[s][0], tips[s][1], tips[s + 1][0], tips[s + 1][1], 1.2, 'K');
        // spine, tail, ribs, head
        sline(0, -14, 0, 14, 3.2, 'B'); sline(0, 14, 0, 21, 1.8, 'B');
        for (int k = 0; k < 5; k++) { double z = -6 + k * 3.4; mline(1.5, z, 5.5 - k * .3, z + 2.2, 1.3, 'B'); }
        sline(0, -20, 0, -13, 5.2, 'B'); sline(0, -21, 0, -19, 2.4, 'B');
        mline(2, -18, 6, -23, 1.6, 'B'); mline(3, -15, 8, -17, 1.4, 'B');   // horns / jaw spikes
        sig[-19 + 32][-2 + 32] = 'E'; sig[-19 + 32][2 + 32] = 'E'; sig[-18 + 32][-2 + 32] = 'E'; sig[-18 + 32][2 + 32] = 'E';
        for (int j = -4; j <= 4; j++) { sig[j + 32][0 + 32] = (j >= -2 && j <= 3) ? 'C' : sig[j + 32][32]; }   // glowing heart line
    }
    static String floorAt(int x, int z) {
        double r = hypot(x, z), a = ang(x, z);
        if (r <= 26.5) {
            char c = sig[z + 32][x + 32];
            switch (c) {
                case 'B': return pick(x, 0, z, "bone_block[axis=y]", "calcite", "smooth_quartz", "quartz_bricks");
                case 'K': return "black_concrete";
                case 'E': return "magma_block";
                case 'C': return "shroomlight";
                default: return pick(x, 0, z, "red_nether_bricks", "nether_wart_block", "red_nether_bricks", "red_concrete", "crimson_planks");
            }
        }
        if (r <= 28.5) return (abs(((a + 360) % 30) - 15) < 1.1 || (int) (a / 30.0 * 4 + 400) % 3 == 0 && r > 27 && r < 28) ? "magma_block" : "polished_blackstone";
        if (r <= 40) { int seg = (int) floor((a + 360) / 15) % 24; int ring = (int) ((r - 28.5) / 3.8);
            if (((seg + ring) & 1) == 0) return pick(x, 0, z, "deepslate_tiles", "deepslate_tiles", "cracked_deepslate_tiles", "polished_deepslate"); return pick(x, 0, z, "polished_blackstone_bricks", "polished_blackstone_bricks", "cracked_polished_blackstone_bricks"); }
        if (r <= 42.5) return (r > 41 && ((int) (a * 2 + 720) % 7 == 0)) ? "magma_block" : (((int) (r * 3 + a / 6)) % 2 == 0 ? "red_nether_bricks" : "chiseled_polished_blackstone");
        if (r <= 51.5) { double h = hn(x, 0, z); if (h < .05) return "gilded_blackstone"; if (h < .15) return "cracked_polished_blackstone_bricks"; if (h < .55) return "polished_blackstone"; return "blackstone"; }
        if (r <= 54) return (r > 52.2 && r < 53.4) ? "magma_block" : "chiseled_polished_blackstone";
        return ((int) floor((a + 360) * 3) % 2 == 0) ? "polished_blackstone_bricks" : "chiseled_polished_blackstone";
    }
    static void arena() {
        makeSigil();
        for (int x = -62; x <= 62; x++) for (int z = -62; z <= 62; z++) {
            double r = hypot(x, z); if (r > 57.4) continue;
            set(x, 0, z, floorAt(x, z));
            for (int y = -1; y >= -6; y--) set(x, y, z, y == -1 ? "polished_blackstone_bricks" : rock(x, y, z));
        }
        // glowing cracks: random walks from the rim toward the sigil
        Random rnd = new Random(7);
        for (int k = 0; k < 40; k++) {
            double aa = rnd.nextDouble() * 2 * PI, r = 53; double cx = r * cos(aa), cz = r * sin(aa);
            while (r > 30) { aa += (rnd.nextDouble() - .5) * .35; r -= .8 + rnd.nextDouble() * .8; cx = r * cos(aa); cz = r * sin(aa); int ix = (int) round(cx), iz = (int) round(cz); if (get(ix, 0, iz).contains("magma")) break; set(ix, 0, iz, "magma_block"); if (rnd.nextDouble() < .35) set(ix + 1, 0, iz, "magma_block"); }
        }
        // underside: inverted jagged cone for the whole fortress island, r 77 -> 18
        for (int x = -80; x <= 80; x++) for (int z = -80; z <= 80; z++) {
            double r = hypot(x, z); if (r > 78) continue;
            double depth = (78 - r) * 0.55 + vn(x, z, 6) * 6 - 2; int d = (int) min(50, max(0, 50 - depth * 1.0));
            int bottom = -7 - (int) max(0, (78 - r) * 0.62 - 16 + vn(x, z, 5) * 9);
            bottom = max(bottom, -62);
            for (int y = -7; y >= bottom; y--) set(x, y, z, rock(x, y, z));
        }
    }

    // =================== RIM + PILLARS + INNER WALL ===================
    static boolean gateCut(double a, double r, double w) { return adiff(a, GATE_A) < w; }
    static void rimAndWall() {
        for (int x = -80; x <= 80; x++) for (int z = -80; z <= 80; z++) {
            double r = hypot(x, z), a = ang(x, z);
            boolean gateG = adiff(a, GATE_A) < 22, brG = adiff(a, BRIDGE_A) < 9.5;
            if (r >= 57.4 && r < 62.5 && !gateG && !brG) {
                for (int y = -6; y <= 2; y++) set(x, y, z, y == 2 ? pick(x, y, z, "polished_blackstone_bricks", "chiseled_polished_blackstone") : stone(x, y, z));
                if (((int) floor(a * r / 2.2)) % 2 == 0) set(x, 3, z, "polished_blackstone_brick_slab[type=bottom]");
            }
            if (r >= 62.5 && r < 77.5 && !gateG && !brG) {
                for (int y = -6; y <= 6; y++) set(x, y, z, stone(x, y, z));
                if (r < 64) for (int y = 0; y <= 6; y++) if (y >= 5 || true) set(x, y, z, (y % 3 == 2) ? "chiseled_polished_blackstone" : stone(x, y, z));
                // terrace + parapets
                if (r >= 62.5 && r < 74.5) set(x, 7, z, ((x + z) & 1) == 0 ? "polished_blackstone_bricks" : "deepslate_tiles");
                if (r < 64) { set(x, 8, z, "polished_blackstone_brick_wall"); set(x, 9, z, stone(x, 9, z)); if (((int) floor(a * r / 2.0)) % 2 == 0) set(x, 10, z, "polished_blackstone_brick_slab[type=bottom]"); }
                if (r >= 74.5) {
                    int top = 25 - (hn((int) (a * 2), 3, 5) < .22 ? (int) (hn((int) (a * 2), 4, 5) * 8) : 0);
                    for (int y = 7; y <= top; y++) set(x, y, z, stone(x, y, z));
                    if (((int) floor(a * r / 2.0)) % 2 == 0) set(x, top + 1, z, "polished_blackstone_brick_slab[type=bottom]");
                    // arrow-slit windows
                    if (r < 76 && ((int) floor(a * r / 6.0)) % 1 == 0 && ((int) round(a * r) % 12 == 0)) for (int y = 11; y <= 15; y++) set(x, y, z, "air");
                }
            }
        }
        // arch niches with fire in the plinth wall
        for (int k = 0; k < 32; k++) {
            double a = k * 11.25 + 5.6; if (adiff(a, GATE_A) < 26 || adiff(a, BRIDGE_A) < 12) continue;
            int[] p = pol(63.2, a), q = pol(66, a);
            for (int dy = 0; dy <= 3; dy++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { int xx = p[0] + dx, zz = p[1] + dz; if (hypot(xx, zz) < 66.2) set(xx, dy, zz, "air"); }
            set(q[0], 0, q[1], "netherrack"); set(q[0], 1, q[1], "fire");
            int[] w = pol(63.5, a); for (int dy = 0; dy <= 3; dy++) set(w[0], dy, w[1], "air");
            // lintel
        }
        // perimeter pillars with braziers
        int n = 14;
        for (int k = 0; k < n; k++) {
            double a = k * 360.0 / n + 360.0 / n / 2; if (adiff(a, GATE_A) < 26 || adiff(a, BRIDGE_A) < 14) continue;
            int[] p = pol(61.5, a); if (k % 2 == 0) { pillar(p[0], p[1], 7, 26); double dd = hypot(p[0], p[1]); int bx = (int) round(p[0] - p[0] / dd * 5), bz = (int) round(p[1] - p[1] / dd * 5); if (abs(p[0]) > abs(p[1])) banner(bx, 12, p[1] - 2, 'z', 5, 13); else banner(p[0] - 2, 12, bz, 'x', 5, 13); } else pillar(p[0], p[1], 5, 15);
        }
    }
    static void pillar(int cx, int cz, int w, int h) {
        int hw = w / 2;
        box(cx - hw - 1, -6, cz - hw - 1, cx + hw + 1, 0, cz + hw + 1, (x, y, z) -> "polished_blackstone_bricks");   // plinth
        box(cx - hw, 1, cz - hw, cx + hw, h, cz + hw, (x, y, z) -> {
            boolean corner = (abs(x - cx) == hw && abs(z - cz) == hw);
            if (corner) return "blackstone"; if (y == 9 || y == h) return "chiseled_polished_blackstone"; return stone(x, y, z); });
        box(cx - hw - 1, h + 1, cz - hw - 1, cx + hw + 1, h + 2, cz + hw + 1, "polished_blackstone_bricks");
        box(cx - hw, h + 3, cz - hw, cx + hw, h + 3, cz + hw, "polished_blackstone_brick_slab[type=bottom]");
        box(cx - hw + 1, h + 3, cz - hw + 1, cx + hw - 1, h + 3, cz + hw - 1, "netherrack");
        box(cx - hw + 1, h + 4, cz - hw + 1, cx + hw - 1, h + 4, cz + hw - 1, "fire");
        for (int dx : new int[]{-hw - 1, hw + 1}) for (int dz : new int[]{-hw - 1, hw + 1}) { box(cx + dx, h + 3, cz + dz, cx + dx, h + 4, cz + dz, "polished_blackstone_brick_wall"); set(cx + dx, h + 5, cz + dz, "lantern"); }
        for (int y = 12; y <= 14; y++) for (int s : new int[]{-1, 1}) { /* banner hook bar */ }
    }

    // =================== TOWERS / OUTER FORTRESS ===================
    static void tower(int cx, int cz, int w, int yb, int ytop, int spire) {
        int hw = w / 2;
        for (int y = yb; y <= ytop; y++) {
            int e = y < 4 + 0 && y > -8 ? 1 : (y <= -8 ? 0 : 0);
            int plinth = (y >= -6 && y <= 6) ? 1 : 0;
            for (int x = cx - hw - plinth; x <= cx + hw + plinth; x++) for (int z = cz - hw - plinth; z <= cz + hw + plinth; z++) {
                boolean corner = (abs(x - cx) >= hw - 1 && abs(z - cz) >= hw - 1);
                String b;
                if (y < -12) b = rock(x, y, z); else if (corner) b = (y % 9 == 8) ? "chiseled_polished_blackstone" : "polished_blackstone_bricks"; else if (y % 12 == 11) b = "chiseled_polished_blackstone"; else b = stone(x, y, z);
                set(x, y, z, b);
            }
        }
        // slit windows + balcony band
        for (int y = yb + 14; y < ytop - 4; y += 12) {
            for (int s = -1; s <= 1; s += 2) { for (int dy = 0; dy < 4; dy++) { set(cx, y + dy, cz + s * hw, "air"); set(cx + s * hw, y + dy, cz, "air"); } }
        }
        int by = ytop - 5;
        box(cx - hw - 1, by, cz - hw - 1, cx + hw + 1, by, cz + hw + 1, "polished_blackstone_bricks");
        box(cx - hw - 1, by + 1, cz - hw - 1, cx + hw + 1, by + 1, cz + hw + 1, "polished_blackstone_brick_wall");
        for (int dx : new int[]{-hw - 1, hw + 1}) for (int dz : new int[]{-hw - 1, hw + 1}) { box(cx + dx, by + 1, cz + dz, cx + dx, by + 3, cz + dz, "polished_blackstone_brick_wall"); set(cx + dx, by + 4, cz + dz, "lantern"); }
        // glowing red window band
        for (int s = -1; s <= 1; s += 2) { for (int dy = 1; dy <= 3; dy++) { set(cx, by - 4 + dy, cz + s * hw, "red_stained_glass"); set(cx + s * hw, by - 4 + dy, cz, "red_stained_glass"); } }
        // top room + spire
        box(cx - hw + 1, ytop + 1, cz - hw + 1, cx + hw - 1, ytop + 1, cz + hw - 1, "deepslate_tiles");
        int base = hw;
        for (int k = 0; k <= spire; k++) {
            int half = (int) floor(base * (1.0 - (double) k / spire) + (k == spire ? 0 : 0));
            if (half < 0) break;
            for (int x = cx - half; x <= cx + half; x++) for (int z = cz - half; z <= cz + half; z++) set(x, ytop + 2 + k, z, (abs(x - cx) == half || abs(z - cz) == half) ? pick(x, k, z, "deepslate_tiles", "polished_blackstone_bricks") : "deepslate_tiles");
            if (half <= 0) break;
        }
        for (int k = 0; k < 7; k++) set(cx, ytop + 2 + spire + k, cz, k == 6 ? "lantern" : "polished_blackstone_brick_wall");
        // corner pinnacles
        for (int dx : new int[]{-hw, hw}) for (int dz : new int[]{-hw, hw}) { box(cx + dx, ytop + 1, cz + dz, cx + dx, ytop + 5, cz + dz, "polished_blackstone_brick_wall"); set(cx + dx, ytop + 6, cz + dz, "chiseled_polished_blackstone"); }
        // tall banners on two faces
        banner(cx - 2, by - 20, cz + hw + 1, 'x', 5, 15); banner(cx - 2, by - 20, cz - hw - 1, 'x', 5, 15);
    }
    static void outerRing() {
        for (int x = -118; x <= 118; x++) for (int z = -118; z <= 118; z++) {
            double r = hypot(x, z), a = ang(x, z);
            if (r < 97.5 || r > 112.5) continue;
            if (adiff(a, GATE_A) < 16 || adiff(a, BRIDGE_A) < 11) continue;
            double nz = vn(x, z, 7);
            int top = 20; boolean ruinZone = false;
            for (double[] rz : RUINS) if (adiff(a, rz[0]) < rz[1]) { ruinZone = true; top = 20 - (int) (rz[2] * (1 - adiff(a, rz[0]) / rz[1])); }
            if (hn((int) (a * 3), 9, 1) < .12 && !ruinZone) top = 17;
            boolean butt = nearTower(a);
            for (int y = -95; y <= top; y++) {
                if (y < -14 && !butt) continue;   // open arches under the curtain wall: lava falls through them
                if (y < -20) {   // volcanic cliff: pillar taper with noise
                    double d = (-20 - y) / 75.0; double sh = d * 7 + nz * 5 * d; double inner = 97.5 + sh, outer = 112.5 - sh * .7;
                    if (r < inner || r > outer) continue; set(x, y, z, rock(x, y, z));
                } else set(x, y, z, stone(x, y, z));
            }
            if (top == 20 && ((int) floor(a * r / 2.0)) % 2 == 0 && r > 97.5 && (r < 99 || r > 111)) set(x, 21, z, "polished_blackstone_brick_slab[type=bottom]");
            if (top >= 19 && (r < 99 || r > 111)) { set(x, 21, z, ((int) floor(a * r / 2.0)) % 2 == 0 ? "polished_blackstone_bricks" : "air"); if (((int) floor(a * r / 4.0)) % 2 == 0) set(x, 22, z, "polished_blackstone_brick_slab[type=bottom]"); }
            // arch openings through the wall bottom
        }
        // big towers
        for (int k = 0; k < 8; k++) { double a = 22.5 + 45 * k; int[] p = pol(105, a); tower(p[0], p[1], 11, -40, 42 + (k % 3) * 6, 16 + (k % 2) * 4); }
        for (double a : new double[]{0, 45, 135, 180, 225, 315}) { int[] p = pol(105, a); tower(p[0], p[1], 7, -30, 28 + (int) (hn((int) a, 1, 1) * 8), 12); }
        // walkway bridges from inner wall to outer ring (8)
        for (int k = 0; k < 8; k++) {
            double a = k * 45; if (adiff(a, GATE_A) < 30 || adiff(a, BRIDGE_A) < 30) continue; a += 22.5 * 0;
            double[] dir = {cos(toRadians(a)), sin(toRadians(a))}, nrm = {-dir[1], dir[0]};
            for (double r = 74; r <= 99; r += .5) for (int wd = -3; wd <= 3; wd++) {
                int x = (int) round(dir[0] * r + nrm[0] * wd), z = (int) round(dir[1] * r + nrm[1] * wd);
                double arch = 0; set(x, 6, z, abs(wd) >= 3 ? "polished_blackstone_bricks" : "polished_blackstone"); set(x, 7, z, abs(wd) == 3 ? "polished_blackstone_brick_wall" : "air");
                for (int y = 5; y >= 0; y--) if (abs(wd) < 3) { double h = 6 - 5 * pow((r - 86.5) / 12.5, 2); if (y > h * 0 + 5 - (6 - h) * 0 && false) set(x, y, z, "air"); }
                if (abs(wd) == 3 && ((int) (r * 2)) % 16 == 0) { box(x, 7, z, x, 9, z, "polished_blackstone_brick_wall"); set(x, 10, z, "lantern"); }
                for (int y = 5; y >= -12; y--) { double w = 3.5 * (1 - (y == 5 ? 0 : 0)); boolean pier = abs(r - 86.5) < 2.2 && abs(wd) <= 3; double arch2 = 4 + 10 * pow((r - 86.5) / 12.5, 2); if (y > 5 - arch2 * 0.0 - 0 && false) continue; if (pier || (y >= 4)) set(x, y, z, stone(x, y, z)); }
            }
        }
    }
    static double[][] RUINS = {{15, 6, 10}, {200, 7, 12}, {-150, 5, 9}, {55, 5, 8}, {120, 4, 7}};

    // =================== GATE + NORTH STAIRCASE ===================
    static void gate() {
        // viaduct + stairs: z from -62 (rim) to -98, width 25
        for (int z = -63; z >= -100; z--) {
            int top = min(14, (-z - 62) / 3 + 1);
            for (int x = -15; x <= 15; x++) {
                boolean side = abs(x) >= 13; int ey = side ? top + 3 : top;
                for (int y = -6; y <= ey; y++) set(x, y, z, (y == ey && !side) ? (abs(x) <= 3 ? "red_concrete" : (((x + z) & 1) == 0 ? "polished_blackstone_bricks" : "deepslate_tiles")) : stone(x, y, z));
                if (side) { if (((z) & 1) == 0) set(x, ey + 1, z, "polished_blackstone_brick_slab[type=bottom]"); }
            }
            if (z % 8 == 0) for (int s : new int[]{-1, 1}) { int px = s * 14; box(px - 1, top + 1, z - 1, px + 1, top + 7, z + 1, "polished_blackstone_bricks"); set(px, top + 8, z, "polished_blackstone_brick_slab[type=bottom]"); set(px, top + 9, z, "netherrack"); set(px, top + 10, z, "fire"); }
        }
        // viaduct piers + arches down to the lake
        for (int z = -66; z >= -96; z -= 10) for (int s : new int[]{-1, 1}) for (int px = s * 11 - 3; px <= s * 11 + 3; px++) for (int pz = z - 2; pz <= z + 2; pz++) for (int y = -95; y < -6; y++) set(px, y, pz, y < -40 ? rock(px, y, pz) : stone(px, y, pz));
        // gate floor (hall)
        box(-27, -95, -118, 27, 14, -98, (x, y, z) -> y > 12 ? (abs(x) <= 3 ? "red_concrete" : stone(x, y, z)) : (y < -40 ? rock(x, y, z) : stone(x, y, z)));
        // gate pillars with arch (passage x -17..17, floor y 14)
        for (int s : new int[]{-1, 1}) {
            int xa = s > 0 ? 17 : -27, xb = s > 0 ? 27 : -17;
            box(xa, 15, -116, xb, 52, -96, (x, y, z) -> {
                if (abs(x) <= 18 && abs(z + 106) < 8 && y < 52 && false) return "air";
                boolean edge = (abs(x) == 17 || abs(x) == 27 || z == -96 || z == -116);
                if (y % 10 == 9) return "chiseled_polished_blackstone"; return edge && (y % 5 == 0) ? "polished_blackstone_bricks" : stone(x, y, z); });
            // fluted face + buttress
            for (int y = 15; y <= 46; y++) { set(s * 28, y, -97, "polished_blackstone_bricks"); set(s * 28, y, -115, "polished_blackstone_bricks"); }
            box(xa - (s < 0 ? 1 : -0), 53, -116, xb + (s < 0 ? 0 : 1), 53, -96, "polished_blackstone_bricks");
            // cap + big brazier
            box(xa, 53, -116, xb, 54, -96, "polished_blackstone_bricks");
            int bx = s * 22; box(bx - 3, 55, -110, bx + 3, 56, -102, "polished_blackstone_bricks"); box(bx - 2, 56, -109, bx + 2, 56, -103, "netherrack"); box(bx - 2, 57, -109, bx + 2, 58, -103, "fire");
            // lava falls on the outer faces (sources set later)
        }
        // pointed arch across x -17..17 : top at y 34
        for (int x = -18; x <= 18; x++) for (int z = -116; z <= -96; z++) {
            double h = 34 - 9 * pow(abs(x) / 17.0, 2.4) ; int top = 52;
            for (int y = 15; y <= top; y++) { boolean opening = abs(x) <= 17 && y <= h + 0 && z > -116; if (!opening && abs(x) <= 17) set(x, y, z, (y == (int) floor(h) + 1 ? "chiseled_polished_blackstone" : stone(x, y, z))); }
        }
        // back wall of the gate hall + glowing red lava-colored recess
        box(-27, 15, -122, 27, 52, -117, (x, y, z) -> stone(x, y, z));
        box(-8, 15, -121, 8, 33, -117, (x, y, z) -> "air");
        box(-8, 14, -121, 8, 14, -117, "magma_block");
        box(-7, 15, -122, 7, 31, -122, "shroomlight");
        // heavy chains from gate to towers
        for (int s : new int[]{-1, 1}) catenary(s * 24, 50, -100, s * 85, 44, -75, 12, 'c');
    }

    // =================== SOUTH BRIDGE ===================
    static void bridge() {
        for (int z = 60; z <= 118; z++) {
            for (int x = -9; x <= 9; x++) {
                boolean side = abs(x) >= 8;
                for (int y = -4; y <= 0; y++) set(x, y, z, stone(x, y, z));
                set(x, 0, z, abs(x) <= 2 ? "red_concrete" : (abs(x) == 3 ? "red_nether_bricks" : (((x + z) & 1) == 0 ? "polished_blackstone_bricks" : "deepslate_tiles")));
                if (side) { for (int y = 1; y <= 3; y++) set(x, y, z, stone(x, y, z)); if ((z & 1) == 0) set(x, 4, z, "polished_blackstone_brick_slab[type=bottom]"); }
            }
            if ((z - 62) % 9 == 0 && z < 112) for (int s : new int[]{-1, 1}) { int px = s * 9; box(px - 1, 1, z - 1, px + 1, 10, z + 1, (x, y, zz) -> stone(x, y, zz)); box(px - 1, 11, z - 1, px + 1, 11, z + 1, "polished_blackstone_bricks"); set(px, 12, z, "netherrack"); set(px, 13, z, "fire"); banner(px - 1, 2, z + 2, 'x', 3, 8); banner(px - 1, 2, z - 2, 'x', 3, 8); }
        }
        // piers
        for (int z = 70; z <= 116; z += 14) for (int s : new int[]{-1, 0, 1}) for (int px = s * 8 - 2; px <= s * 8 + 2; px++) for (int pz = z - 2; pz <= z + 2; pz++) for (int y = -90; y < -4; y++) set(px, y, pz, y < -40 ? rock(px, y, pz) : stone(px, y, pz));
        // arrival plaza
        for (int x = -26; x <= 26; x++) for (int z = 118; z <= 142; z++) { double r = hypot(x, z - 128); if (r > 22) continue; for (int y = -6; y <= 0; y++) set(x, y, z, y == 0 ? (r > 19 ? "chiseled_polished_blackstone" : floorBridge(x, z)) : rock(x, y, z)); if (r > 20 && r <= 22) set(x, 1, z, "polished_blackstone_brick_wall"); }
        for (int k = 0; k < 4; k++) { int[] p = pol(19, 55 + k * 23); p[1] += 128 - 0; }
        for (int s : new int[]{-1, 1}) for (int d = 0; d < 2; d++) pillar(s * (12 + d * 6), 136 - d * 6, 5, 11);
        // bridge-side lanterns + chain to south towers
        for (int s : new int[]{-1, 1}) catenary(s * 9, 13, 70, s * 72, 36, 118, 10, 'c');
    }
    static String floorBridge(int x, int z) { return ((int) (hypot(x, z - 128) / 3) % 2 == 0) ? "polished_blackstone_bricks" : "deepslate_tiles"; }

    // =================== PROPS ===================
    static final String[] EMB = {"#.....#", "##...##", ".#.#.#.", ".#####.", "..###..", "..#.#..", "...#...", "...#...", "..###.."};
    static void banner(int x, int y, int z, char axis, int w, int h) {   // pixel banner, hanging from (x,y+h) ; axis 'x': spans x, flat in z ; 'z': spans z
        for (int i = 0; i < w; i++) for (int j = 0; j < h; j++) {
            if (j < 2 && false) continue;
            int tail = (j >= h - 2 && (i == w / 2 || (w > 3 && abs(i - w / 2) == 0))) ? 1 : 0; if (tail == 1) continue;
            int row = h - 1 - j; String b;
            boolean border = (i == 0 || i == w - 1 || row == 0);
            int ei = i - (w - 7) / 2, ej = row - 2;
            boolean emb = w >= 5 && ei >= 0 && ei < 7 && ej >= 0 && ej < EMB.length && EMB[ej].charAt(ei) == '#' ;
            b = border || emb ? (hn(x + i, y + j, z) < .5 ? "black_concrete" : "black_concrete") : pick(x + i, y + j, z, "red_concrete", "red_concrete", "nether_wart_block");
            if (row == 0) b = "black_concrete";
            if (axis == 'x') set(x + i, y + j, z, b); else set(x, y + j, z + i, b);
        }
    }
    static void catenary(double ax, double ay, double az, double bx, double by, double bz, double sag, char kind) {
        int n = (int) (hypot(bx - ax, bz - az) * 1.4);
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n; double x = ax + (bx - ax) * t, z = az + (bz - az) * t, y = ay + (by - ay) * t - sag * 4 * t * (1 - t);
            int xi = (int) round(x), yi = (int) round(y), zi = (int) round(z);
            double dx = abs(bx - ax), dz = abs(bz - az);
            String ax_ = dx > dz ? "x" : "z"; if (i % 2 == 1) ax_ = "y";
            set(xi, yi, zi, "iron_chain[axis=" + ax_ + "]");
        }
    }

    // =================== SKULL + SKELETON ===================
    static void skullOld() {
        double sx = 0, sy = 70, sz = -126;   // cranium centre
        BF b = (x, y, z) -> bone(x, y, z);
        ell(sx, sy, sz, 15, 12, 17, b);                       // cranium
        ell(sx, sy + 7, sz - 3, 12, 8, 13, b);                // dome
        // brow ridges + cheek bones
        for (int s : new int[]{-1, 1}) { ell(sx + s * 11, sy + 4, sz + 12, 5, 4, 6, b); ell(sx + s * 12, sy - 2, sz + 8, 4, 5, 8, b); }
        // upper snout (tapers forward / south, slopes down)
        for (int i = 0; i <= 34; i++) { double t = i / 34.0; double w = 11 - 6 * t, hgt = 7 - 3 * t; ell(sx, sy - 2 - 9 * t - 3, sz + 14 + i, w, hgt, 2, b); }
        // lower jaw (hinged at the cheeks, hanging open)
        for (int s : new int[]{-1, 1}) { double[][] jp = {{s * 11, sy - 8, sz + 6}, {s * 9, sy - 16, sz + 16}, {s * 6.5, sy - 22, sz + 30}, {s * 3.5, sy - 22, sz + 46}}; tube(jp, t -> 3.4 - 1.7 * t, b); }
        for (int i = 0; i <= 8; i++) ell(sx, sy - 22 + (i < 4 ? 0 : 0), sz + 44 + i * 0, 4, 2, 2, b);
        // teeth: upper row and lower row
        for (int i = 0; i < 12; i++) for (int s : new int[]{-1, 1}) {
            double t = i / 11.0; double z = sz + 19 + i * 3, w = 11 - 6 * t, y0 = sy - 5 - 9 * t - 7;
            capsule(sx + s * (w - 1), y0 + 1, z, sx + s * (w - 1), y0 - 6 - (i % 3), z, 1.5, .4, (x, y, zz) -> "smooth_quartz");
            double lw = 8 - 4 * t; double ly = sy - 22 + 1;
            if (i > 1) capsule(sx + s * lw, ly + 1, sz + 14 + i * 3, sx + s * lw, ly + 6 + (i % 3), sz + 14 + i * 3, 1.3, .4, (x, y, zz) -> "smooth_quartz");
        }
        // front fangs
        for (int s : new int[]{-1, 1}) capsule(sx + s * 3.5, sy - 14, sz + 46, sx + s * 3.5, sy - 26, sz + 47, 2, .5, (x, y, zz) -> "smooth_quartz");
        // inside of mouth: dark + lava glow
        ell(sx, sy - 12, sz + 24, 8, 4, 16, (x, y, z) -> "magma_block");
        // eye sockets
        for (int s : new int[]{-1, 1}) { carve(sx + s * 9.5, sy + 3, sz + 14, 4.2, 3.6, 3.5); ell(sx + s * 9.5, sy + 3, sz + 13, 2.7, 2.4, 2.4, (x, y, z) -> "redstone_block"); set((int) (sx + s * 9.5), (int) sy + 3, (int) sz + 14, "shroomlight"); }
        // nostrils
        for (int s : new int[]{-1, 1}) carve(sx + s * 3, sy - 8, sz + 44, 1.4, 1.4, 1.6);
        // big back-swept horns + small cheek horns
        for (int s : new int[]{-1, 1}) {
            double[][] h = {{s * 8, sy + 9, sz - 5}, {s * 20, sy + 22, sz - 14}, {s * 30, sy + 40, sz - 28}, {s * 28, sy + 56, sz - 48}};
            tube(h, t -> 4.6 - 3.8 * t, b);
            double[][] h2 = {{s * 14, sy - 2, sz + 4}, {s * 26, sy + 2, sz - 2}, {s * 36, sy + 12, sz - 12}};
            tube(h2, t -> 3.2 - 2.6 * t, b);
            double[][] h3 = {{s * 13, sy + 8, sz + 8}, {s * 20, sy + 14, sz + 8}, {s * 25, sy + 24, sz + 2}};
            tube(h3, t -> 2.6 - 2.1 * t, b);
        }
        // crown spikes along cranium crest
        for (int i = 0; i < 7; i++) capsule(sx, sy + 16, sz - 12 + i * 2.5, sx, sy + 22 + (i % 2) * 4, sz - 16 + i * 2.5, 1.8, .4, b);
        // neck wall into the gate back wall
        box(-12, 40, -126, 12, 62, -112, (x, y, z) -> bone(x, y, z));
    }
    static void spineOld() {
        double[][] pts = {{8, 62, -138}, {38, 84, -154}, {82, 100, -142}, {118, 84, -98}, {136, 58, -40}, {132, 34, 24}, {112, 18, 80}, {84, 6, 122}};
        tube(pts, t -> 2.8 - 1.2 * t, (x, y, z) -> bone(x, y, z));
        int nv = 26;
        for (int i = 1; i < nv; i++) {
            double t = i / (double) nv; double[] c = cr(pts, t); double[] tg = tgt(pts, t);
            ell(c[0], c[1], c[2], 3.8 - 1.4 * t, 3.4 - 1.2 * t, 3.8 - 1.4 * t, (x, y, z) -> bone(x, y, z));
            capsule(c[0], c[1] + 2, c[2], c[0], c[1] + 8 + (i % 3) * 2, c[2], 1.7, .4, (x, y, z) -> bone(x, y, z));   // spinous process
            double[] nr = {-tg[2], 0, tg[0]}; double nl = hypot(nr[0], nr[2]); nr[0] /= nl; nr[2] /= nl;
            if (i % 2 == 0 && t > .12 && t < .85) for (int s : new int[]{-1, 1}) {
                double len = 30 + 16 * sin(t * PI);
                double[][] rb = {{c[0], c[1], c[2]}, {c[0] + nr[0] * s * len * .38, c[1] + 7, c[2] + nr[2] * s * len * .38}, {c[0] + nr[0] * s * len * .78, c[1] - 5, c[2] + nr[2] * s * len * .78}, {c[0] + nr[0] * s * len * .86, c[1] - 26 - len * .3, c[2] + nr[2] * s * len * .86}};
                tube(rb, u -> 2.3 - 1.8 * u, (x, y, z) -> bone(x, y, z));
            }
        }
        // giant curved wing/tusk bones on the west and east skyline
        double[][] w1 = {{-118, 24, 10}, {-146, 52, -14}, {-140, 92, -50}, {-100, 118, -92}, {-62, 120, -118}};
        tube(w1, t -> 4.6 - 3.8 * t, (x, y, z) -> bone(x, y, z));
        double[][] w2 = {{-110, 18, 44}, {-150, 40, 22}, {-160, 74, -20}};
        tube(w2, t -> 3.6 - 3.0 * t, (x, y, z) -> bone(x, y, z));
        double[][] w3 = {{140, 30, 40}, {158, 60, 6}, {150, 96, -30}};
        tube(w3, t -> 3.8 - 3.1 * t, (x, y, z) -> bone(x, y, z));
        // claws beside the gate
        for (int s : new int[]{-1, 1}) for (int f = 0; f < 4; f++) {
            double fx = s * (30 + f * 5.5); double[][] fp = {{s * 30, 52, -112}, {fx, 40 - f, -100 + f * 2}, {fx + s * 3, 28 - f * 2, -92 + f * 4}, {fx + s * 5, 22 - f * 3, -86 + f * 6}};
            tube(fp, t -> 2.2 - 1.7 * t, (x, y, z) -> bone(x, y, z));
        }
    }

    // =================== SCALE-UP: every ring beyond the arena shifts outward by A, the arena floor stretches ===================
    static final int A = 26;
    static boolean nearTower(double a) {
        for (int k = 0; k < 8; k++) if (adiff(a, 22.5 + 45 * k) < 8) return true;
        for (double m : new double[]{0, 45, 135, 180, 225, 315}) if (adiff(a, m) < 5) return true;
        return false;
    }
    static void remap(double add) {
        final double R = 57.4; short[] n = new short[g.length];
        for (int x = X0; x < X0 + W; x++) for (int z = Z0; z < Z0 + D; z++) {
            double xc = x + .5, zc = z + .5, r = hypot(xc, zc); double k = r < R + add ? R / (R + add) : (r - add) / r;
            int sx = (int) floor(xc * k), sz = (int) floor(zc * k); if (!in(sx, 0, sz)) continue;
            for (int y = Y0; y < Y0 + H; y++) n[ix(x, y, z)] = g[ix(sx, y, sz)];
        }
        System.arraycopy(n, 0, g, 0, g.length);
    }
    static void fall(int x, int y, int z) {
        set(x, y, z, "lava");
        int cx = x, cz = z, cy = y - 1, guard = 0;
        while (cy >= -91 && guard++ < 900) {
            if (air(cx, cy, cz)) { set(cx, cy, cz, "lava[level=8]"); cy--; continue; }
            int sx = Integer.signum(cx), sz = Integer.signum(cz); boolean moved = false;
            for (int[] d : new int[][]{{sx, 0}, {0, sz}, {-sx, 0}, {0, -sz}}) {
                if (d[0] == 0 && d[1] == 0) continue; int nx = cx + d[0], nz = cz + d[1];
                if (air(nx, cy + 1, nz) && air(nx, cy, nz)) { set(nx, cy + 1, nz, "lava[level=8]"); cx = nx; cz = nz; moved = true; break; }
            }
            if (!moved) break;
        }
    }
    /** Ramps that climb from the arena floor to the wall terrace, for spectators (before the radial stretch). */
    static void specStairs() {
        for (double a : new double[]{0, 180, 51.4, 128.6, -51.4, -128.6}) {
            double dx = cos(toRadians(a)), dz = sin(toRadians(a));
            String face = abs(dx) > abs(dz) ? (dx > 0 ? "west" : "east") : (dz > 0 ? "north" : "south");
            for (int x = -76; x <= 76; x++) for (int z = -76; z <= 76; z++) {
                double t = x * dx + z * dz, sd = abs(-x * dz + z * dx);
                if (sd > 3.2 || t < 57.4 || t > 67) continue;
                int top = t < 63.4 ? (int) floor(t - 57.4) + 1 : 7;
                for (int y = -6; y < (t < 63.4 ? top : 7); y++) set(x, y, z, "polished_blackstone_bricks");
                if (t < 63.4) set(x, top, z, "polished_blackstone_brick_stairs[facing=" + face + ",half=bottom]"); else set(x, 7, z, "polished_blackstone_bricks");
                for (int y = top + 1; y <= top + 6; y++) if (y > 7 || t < 63.4) set(x, y, z, "air");
                if (sd > 2.2) set(x, top + 1, z, t < 66 ? "polished_blackstone_brick_wall" : "air");
            }
        }
    }
    /** The Molten Ravager: a lattice of cooling magma crust. */
    static void magmaCocoon(int cx, int cz) {
        for (int y = 1; y <= 15; y++) for (int x = cx - 13; x <= cx + 13; x++) for (int z = cz - 13; z <= cz + 13; z++) {
            double d = sqrt((x - cx) * (x - cx) + y * y * 0.9 + (z - cz) * (z - cz));
            if (d <= 9.8 || d > 12.4) continue;
            if (hn(x, y, z) < .3 || (x + y * 2 + z) % 5 == 0) continue;
            double q = hn(x + 3, y, z); set(x, y, z, q < .38 ? "basalt[axis=y]" : q < .72 ? "magma_block" : "blackstone");
        }
        for (int k = 0; k < 6; k++) { double a = k * PI / 3 + .4; int sx = cx + (int) round(cos(a) * 6), sz = cz + (int) round(sin(a) * 6); capsule(sx, 11, sz, sx - cos(a) * 1.5, 17 + (k % 2) * 2, sz - sin(a) * 1.5, 1.8, .3, (x, y, z) -> pick(x, y, z, "basalt[axis=y]", "magma_block", "blackstone")); }
    }
    /** The Bone Colossus: a giant ribcage closing over its pit. */
    static void ribcage(int cx, int cz) {
        int n = 9;
        for (int k = 0; k < n; k++) {
            double a = k * 2 * PI / n + .2; double c = cos(a), sn = sin(a);
            double[][] rb = {{cx + 12.2 * c, 1, cz + 12.2 * sn}, {cx + 11.6 * c, 7, cz + 11.6 * sn}, {cx + 7.5 * c, 14, cz + 7.5 * sn}, {cx + 1.8 * c, 18, cz + 1.8 * sn}};
            tube(rb, t -> 1.7 - 0.8 * t, (x, y, z) -> bone(x, y, z).equals("diorite") || bone(x, y, z).equals("white_terracotta") ? "calcite" : bone(x, y, z));
        }
        ell(cx, 18, cz, 2.6, 1.7, 2.6, (x, y, z) -> "bone_block[axis=y]");
        for (int k = 0; k < n; k++) { double a = k * 2 * PI / n + .2 + PI / n; int sx = cx + (int) round(cos(a) * 12), sz = cz + (int) round(sin(a) * 12); box(sx, 1, sz, sx, 3 + (k % 3), sz, "bone_block[axis=y]"); }
    }
    /** The Void Revenant: obsidian shards leaning in around purple glass. */
    static void voidShards(int cx, int cz) {
        int n = 8;
        for (int k = 0; k < n; k++) {
            double a = k * 2 * PI / n + PI / 8; double c = cos(a), sn = sin(a);
            double[][] sh = {{cx + 12.3 * c, 1, cz + 12.3 * sn}, {cx + 10.8 * c, 9, cz + 10.8 * sn}, {cx + 4.5 * c, 19, cz + 4.5 * sn}};
            tube(sh, t -> 2.0 - 1.5 * t, (x, y, z) -> hn(x, y, z) < .72 ? "obsidian" : "crying_obsidian");
        }
        for (int x = cx - 14; x <= cx + 14; x++) for (int z = cz - 14; z <= cz + 14; z++) {
            double r = hypot(x - cx, z - cz);
            if (r >= 11.3 && r <= 12.7) for (int y = 1; y <= 8; y++) if (get(x, y, z).equals("air") && hn(x, y, z) < .9) set(x, y, z, "purple_stained_glass_pane");
        }
        for (int k = 0; k < 5; k++) ell(cx + cos(k * 1.3) * 3, 15 + k * .6, cz + sin(k * 1.3) * 3, 1.1, 1.6, 1.1, (x, y, z) -> "crying_obsidian");
    }
    /** Iron cage around a pit: bar wall, lid and a crown of spikes; the plugin shatters it when this boss wakes. */
    static void cage(int cx, int cz, int i) {
        for (int x = cx - 14; x <= cx + 14; x++) for (int z = cz - 14; z <= cz + 14; z++) {
            double r = hypot(x - cx, z - cz);
            if (r >= 11.3 && r <= 12.7) { for (int y = 0; y <= 10; y++) set(x, y, z, "iron_bars"); }
            if (r <= 12.7 && ((x + z) & 1) == 0 || (r <= 12.7 && ((x - cx) % 4 == 0 || (z - cz) % 4 == 0))) set(x, 11, z, "iron_bars");
            if (r >= 11.3 && r <= 12.7 && ((x * 7 + z * 3) % 5 == 0)) { set(x, 12, z, "iron_bars"); set(x, 13, z, ((x + z) % 3 == 0) ? "lightning_rod[facing=up]" : "iron_bars"); if ((x + z) % 3 == 0) { set(x, 14, z, "lightning_rod[facing=up]"); } }
        }
        // heavy bands + cage floor frame in the boss's colour
        String band = "copper_block";
        for (int x = cx - 14; x <= cx + 14; x++) for (int z = cz - 14; z <= cz + 14; z++) { double r = hypot(x - cx, z - cz); if (r >= 11.3 && r <= 12.7) { set(x, 0, z, "polished_blackstone_bricks"); if ((x + z) % 6 == 0) set(x, 5, z, band); } }
    }
    /** The Frost Titan's iceberg: a translucent shell over a cavity, spikes and all. Melts away when he is the one chosen. */
    static void iceberg(int cx, int cz) {
        for (int x = cx - 13; x <= cx + 13; x++) for (int z = cz - 13; z <= cz + 13; z++) {
            double r = hypot(x - cx, z - cz); if (r > 12.5) continue;
            double ridge = 13 + vn(x * 1.7, z * 1.7, 3.5) * 7;
            for (int y = 0; y <= 20; y++) {
                double h = (y - 0.0) / ridge; double inner = 6.8 * sqrt(max(0, 1 - pow(y / 12.5, 2)));
                double outer = 11.8 * (1 - 0.8 * pow(h, 1.7)) + vn(x, y * 1.3 + z, 2.5) * 1.4;
                if (y == 0 && r > 6.2 && r < 11.5) { set(x, y, z, "packed_ice"); continue; }
                if (r > inner && r <= outer && h < 1) { double q = hn(x, y, z); set(x, y, z, q < .55 ? "ice" : q < .8 ? "packed_ice" : "blue_ice"); }
            }
        }
        for (int k = 0; k < 7; k++) { double a = k * 2 * PI / 7 + .3; int sx = cx + (int) round(cos(a) * 9), sz = cz + (int) round(sin(a) * 9); capsule(sx, 4, sz, sx + cos(a) * 2, 15 + (k % 3) * 3, sz + sin(a) * 2, 2.0, .3, (x, y, z) -> pick(x, y, z, "ice", "blue_ice", "packed_ice")); }
    }
    /** The Sundering's five boss pits and chain pillars, in the plugin's coordinates (centre 0,0). */
    static void pits() {
        for (int i = 0; i < 5; i++) {
            double a = -PI / 2 + i * 2 * PI / 5; int cx = (int) round(cos(a) * 70), cz = (int) round(sin(a) * 70);
            for (int x = cx - 12; x <= cx + 12; x++) for (int z = cz - 12; z <= cz + 12; z++) {
                if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > 144) continue;
                set(x, 0, z, "air"); set(x, -1, z, i == 3 ? pick(x, 1, z, "packed_ice", "blue_ice", "packed_ice", "snow_block") : (hn(x, 1, z) < .14 ? "shroomlight" : "crimson_nylium"));
                for (int y = 1; y <= 22; y++) set(x, y, z, "air");
            }
            for (int y = 1; y <= 22; y++) for (int x = cx - 14; x <= cx + 14; x++) for (int z = cz - 14; z <= cz + 14; z++) if (hypot(x - cx, z - cz) <= 14.2) set(x, y, z, "air");
            switch (i) { case 0 -> magmaCocoon(cx, cz); case 1 -> ribcage(cx, cz); case 2 -> voidShards(cx, cz); case 3 -> iceberg(cx, cz); default -> cage(cx, cz, i); }
            for (int j = 0; j < 4; j++) {
                double b = PI / 4 + j * PI / 2; int ax = cx + (int) round(cos(b) * 13), az = cz + (int) round(sin(b) * 13);
                box(ax - 1, 0, az - 1, ax + 1, 10, az + 1, "polished_blackstone_bricks"); box(ax - 1, 11, az - 1, ax + 1, 11, az + 1, "gold_block"); set(ax, 12, az, "soul_lantern");
            }
        }
    }
    static void curtainBanners() {
        for (double a = 7.5; a < 360; a += 15) {
            if (adiff(a, GATE_A) < 26 || adiff(a, BRIDGE_A) < 13) continue;
            int[] p = pol(74.2, a); boolean zface = abs(sin(toRadians(a))) > abs(cos(toRadians(a)));
            if (zface) banner(p[0] - 2, 9, p[1], 'x', 5, 15); else banner(p[0], 9, p[1] - 2, 'z', 5, 15);
        }
    }
    static void ringChains() {
        for (int k = 0; k < 8; k++) { double a0 = 22.5 + 45 * k, a1 = a0 + 45; if (adiff(a0 + 22.5, GATE_A) < 30 || adiff(a0 + 22.5, BRIDGE_A) < 30) continue; int[] p = pol(104 + A, a0), q = pol(104 + A, a1); catenary(p[0], 36, p[1], q[0], 36, q[1], 9, 'c'); }
    }

    // skull in local coordinates (cranium centre = 0,0,0, snout toward +z) scaled by S around the anchor
    static final double S = 1.6, AX = 0, AY = 78, AZ = -168;
    static void lell(double cx, double cy, double cz, double rx, double ry, double rz, BF f) { ell(AX + cx * S, AY + cy * S, AZ + cz * S, rx * S, ry * S, rz * S, f); }
    static void lcap(double ax, double ay, double az, double bx, double by, double bz, double r0, double r1, BF f) { capsule(AX + ax * S, AY + ay * S, AZ + az * S, AX + bx * S, AY + by * S, AZ + bz * S, r0 * S, r1 * S, f); }
    static void ltube(double[][] p, RF rad, BF f) { double[][] q = new double[p.length][3]; for (int i = 0; i < p.length; i++) q[i] = new double[]{AX + p[i][0] * S, AY + p[i][1] * S, AZ + p[i][2] * S}; tube(q, t -> rad.at(t) * S, f); }
    static void skull() {
        BF b = (x, y, z) -> bone(x, y, z); BF tooth = (x, y, z) -> "smooth_quartz";
        lell(0, 0, 0, 15, 12, 17, b); lell(0, 7, -3, 12, 8, 13, b);
        for (int s : new int[]{-1, 1}) { lell(s * 11, 4, 12, 5, 4, 6, b); lell(s * 12, -2, 8, 4, 5, 8, b); }
        for (int i = 0; i <= 36; i++) { double t = i / 36.0; lell(0, -5 - 9 * t, 14 + i, 11 - 6 * t, 7 - 3 * t, 2, b); }
        for (int s : new int[]{-1, 1}) { double[][] jp = {{s * 11, -8, 6}, {s * 9, -16, 16}, {s * 6.5, -22, 30}, {s * 3.5, -22, 46}}; ltube(jp, t -> 3.4 - 1.7 * t, b); }
        lell(0, -22, 45, 4, 2, 2.5, b);
        for (int i = 0; i < 12; i++) for (int s : new int[]{-1, 1}) {
            double t = i / 11.0, z = 19 + i * 3, w = 11 - 6 * t, y0 = -12 - 9 * t;
            lcap(s * (w - 1), y0 + 1, z, s * (w - 1), y0 - 6 - (i % 3), z, 1.5, .4, tooth);
            double lw = 8 - 4 * t; if (i > 1) lcap(s * lw, -21, 14 + i * 3, s * lw, -16 - (i % 3), 14 + i * 3, 1.3, .4, tooth);
        }
        for (int s : new int[]{-1, 1}) lcap(s * 3.5, -14, 46, s * 3.5, -27, 47, 2, .5, tooth);
        lell(0, -12, 24, 8, 4, 16, (x, y, z) -> "magma_block");
        for (int s : new int[]{-1, 1}) {
            ell(AX + s * 9.5 * S, AY + 3 * S, AZ + 14 * S, 3.3 * S, 2.6 * S, 3.0 * S, (x, y, z) -> "air");
            ell(AX + s * 9.5 * S, AY + 3 * S, AZ + 13.5 * S, 2.0 * S, 1.5 * S, 2.0 * S, (x, y, z) -> "redstone_block");
            set((int) (AX + s * 9.5 * S), (int) (AY + 3 * S), (int) (AZ + 15 * S), "shroomlight");
            ell(AX + s * 3 * S, AY - 8 * S, AZ + 44 * S, 1.4 * S, 1.4 * S, 1.6 * S, (x, y, z) -> "air");
            double[][] h = {{s * 8, 9, -5}, {s * 20, 22, -14}, {s * 30, 40, -28}, {s * 28, 56, -48}}; ltube(h, t -> 4.6 - 3.8 * t, b);
            double[][] h2 = {{s * 14, -2, 4}, {s * 26, 2, -2}, {s * 36, 12, -12}}; ltube(h2, t -> 3.2 - 2.6 * t, b);
            double[][] h3 = {{s * 13, 8, 8}, {s * 20, 14, 8}, {s * 25, 24, 2}}; ltube(h3, t -> 2.6 - 2.1 * t, b);
        }
        for (int i = 0; i < 7; i++) lcap(0, 16, -12 + i * 2.5, 0, 22 + (i % 2) * 4, -16 + i * 2.5, 1.8, .4, b);
    }
    static void spine() {
        double[][] pts = {{8, 72, -196}, {44, 94, -208}, {90, 110, -184}, {132, 92, -130}, {150, 64, -64}, {146, 36, 8}, {126, 18, 76}, {96, 6, 126}};
        tube(pts, t -> 3.8 - 1.4 * t, (x, y, z) -> bone(x, y, z));
        int nv = 22;
        for (int i = 1; i < nv; i++) {
            double t = i / (double) nv; double[] c = cr(pts, t); double[] tg = tgt(pts, t);
            ell(c[0], c[1], c[2], 5.2 - 1.8 * t, 4.6 - 1.6 * t, 5.2 - 1.8 * t, (x, y, z) -> bone(x, y, z));
            capsule(c[0], c[1] + 3, c[2], c[0], c[1] + 11 + (i % 3) * 2, c[2], 2.3, .5, (x, y, z) -> bone(x, y, z));
            double[] nr = {-tg[2], 0, tg[0]}; double nl = hypot(nr[0], nr[2]); nr[0] /= nl; nr[2] /= nl;
            if (i % 3 == 0 && t > .1 && t < .9) for (int s : new int[]{-1, 1}) {
                double len = 36 + 18 * sin(t * PI);
                double[][] rb = {{c[0], c[1], c[2]}, {c[0] + nr[0] * s * len * .38, c[1] + 9, c[2] + nr[2] * s * len * .38}, {c[0] + nr[0] * s * len * .78, c[1] - 6, c[2] + nr[2] * s * len * .78}, {c[0] + nr[0] * s * len * .86, c[1] - 30 - len * .3, c[2] + nr[2] * s * len * .86}};
                tube(rb, u -> 3.3 - 2.4 * u, (x, y, z) -> bone(x, y, z));
            }
        }
        double[][] w1 = {{-135, 26, 20}, {-165, 56, -10}, {-160, 98, -60}, {-120, 126, -110}, {-78, 128, -150}};
        tube(w1, t -> 5.6 - 4.6 * t, (x, y, z) -> bone(x, y, z));
        double[][] w2 = {{-130, 20, 60}, {-170, 44, 30}, {-182, 80, -20}};
        tube(w2, t -> 4.4 - 3.6 * t, (x, y, z) -> bone(x, y, z));
        double[][] w3 = {{152, 32, 60}, {172, 64, 20}, {164, 100, -30}};
        tube(w3, t -> 4.6 - 3.8 * t, (x, y, z) -> bone(x, y, z));
        for (int s : new int[]{-1, 1}) for (int f = 0; f < 4; f++) {
            double fx = s * (40 + f * 6.5); double[][] fp = {{s * 34, 58, -138}, {fx, 46 - f, -124 + f * 2}, {fx + s * 3, 32 - f * 2, -114 + f * 4}, {fx + s * 5, 24 - f * 3, -106 + f * 6}};
            tube(fp, t -> 2.6 - 2.0 * t, (x, y, z) -> bone(x, y, z));
        }
    }
    static void abyssFloor() {
        final int LAKE = 176;
        for (int x = -189; x <= 189; x++) for (int z = -189; z <= 189; z++) {
            double r = hypot(x, z); if (r > 190) continue;
            for (int y = -100; y <= -98; y++) set(x, y, z, "basalt[axis=y]");
            if (r < LAKE) for (int y = -97; y <= -91; y++) set(x, y, z, "lava");
            else for (int y = -97; y <= -20 + (int) (vn(x, z, 10) * 34); y++) set(x, y, z, rock(x, y, z));
            if (r < LAKE - 4 && hn(x, 5, z) < .0035) { int h = 14 + (int) (hn(x, 6, z) * 44); for (int y = -91; y <= -91 + h; y++) { int rr = max(0, 3 - (y + 91) / 9); for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) set(x + dx, y, z + dz, rock(x + dx, y, z + dz)); } }
        }
    }
    static void lavaFalls() {
        for (double a = 11.25; a < 360; a += 22.5) {
            if (adiff(a, GATE_A) < 20 || adiff(a, BRIDGE_A) < 20) continue;
            for (int d = -1; d <= 1; d++) { int[] q = pol(121.8, a + d * 1.6); fall(q[0], 20, q[1]); }   // outer ring inner face
            for (int d = -1; d <= 1; d++) { int[] q = pol(140.2, a + d * 1.4); fall(q[0], 19, q[1]); }   // outer face
            for (int d = -1; d <= 1; d++) { int[] q = pol(131.0, a + d * 1.2); fall(q[0], -15, q[1]); }  // through the arches
        }
        for (double a : new double[]{20, 60, 150, 190, 230, 300, 340}) for (int d = -1; d <= 1; d++) { int[] q = pol(104.6, a + d * 2.5); fall(q[0], 25, q[1]); }   // inner wall
        for (int s : new int[]{-1, 1}) {
            for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 3; dz++) fall(s * (36 + dx), 54, -126 - dz * 5);   // gate pillars
            for (int dx = 0; dx < 3; dx++) fall((int) (AX + s * (21 + dx)), 62, (int) (AZ + 8 * S));   // jaw hinge falls
        }
    }

    // =================== FLOATING ISLANDS ===================
    static void island(int cx, int cy, int cz, int r, long seed, boolean ruin, boolean drip) {
        Random rnd = new Random(seed);
        for (int x = cx - r - 3; x <= cx + r + 3; x++) for (int z = cz - r - 3; z <= cz + r + 3; z++) {
            double d = hypot(x - cx, z - cz) / (r + vn(x * 1.3, z * 1.3, 4) * 4 - 2); if (d > 1) continue;
            int th = (int) ((1 - d * d) * r * 1.15 + vn(x, z, 3) * 4);
            for (int k = 0; k <= th; k++) set(x, cy - k, z, k == 0 ? (d < .8 ? pick(x, cy, z, "blackstone", "deepslate", "moss_block", "polished_blackstone") : "blackstone") : (k < 3 ? "deepslate" : rock(x, cy - k, z)));
            if (d < .6 && hn(x, cy, z) < .06) { set(x, cy + 1, z, "dark_oak_log[axis=y]"); set(x, cy + 2, z, "dark_oak_log[axis=y]"); ell(x, cy + 4, z, 2, 2, 2, (xx, y, zz) -> hn(xx, y, zz) < .7 ? "dark_oak_leaves[persistent=true]" : "air"); }
            if (d < .5 && hn(x, cy + 3, z) < .05) set(x, cy + 1, z, "red_nether_bricks");
        }
        if (ruin) tower(cx, cz, 5, cy + 1, cy + 14 + r / 2, 7);
        if (drip) set(cx, cy - (int) (r * 1.15) - 1, cz, "lava");
    }

    // =================== ABYSS ===================
    static void abyssOld() {
        for (int x = -158; x <= 158; x++) for (int z = -158; z <= 158; z++) {
            double r = hypot(x, z); if (r > 160) continue;
            for (int y = -100; y <= -98; y++) set(x, y, z, "basalt[axis=y]");
            if (r < 152) for (int y = -97; y <= -91; y++) set(x, y, z, "lava");
            else for (int y = -97; y <= -20 + (int) (vn(x, z, 10) * 30); y++) set(x, y, z, rock(x, y, z));
            // basalt spires rising from the lava
            if (r < 150 && hn(x, 5, z) < .004) { int h = 12 + (int) (hn(x, 6, z) * 40); for (int y = -91; y <= -91 + h; y++) { int rr = max(0, 3 - (y + 91) / 8); for (int dx = -rr; dx <= rr; dx++) for (int dz = -rr; dz <= rr; dz++) set(x + dx, y, z + dz, rock(x + dx, y, z + dz)); } }
        }
    }
    static void islands() {
        int[][] il = {{-90, 120, -170, 16, 1}, {-135, 148, -120, 12, 0}, {-40, 165, -165, 9, 0}, {70, 150, -175, 14, 1}, {120, 170, -140, 11, 0}, {160, 140, -60, 13, 1},
            {-160, 110, -40, 10, 0}, {-150, 160, 40, 12, 1}, {150, 128, 90, 9, 0}, {-60, 130, 150, 11, 1}, {20, 175, 160, 8, 0}, {90, 165, 140, 12, 1}, {-110, 175, 110, 7, 0}, {-8, 185, -45, 7, 0}, {165, 180, 20, 8, 1}, {-165, 190, -120, 9, 0}};
        long s = 1; for (int[] i : il) island(i[0], i[1], i[2], i[3], s++ * 31, i[4] == 1, i[3] % 2 == 0);
    }

    // =================== lava ===================
    static void lavaOld() {
        // outer ring inner-face falls
        double[] angs = {10, 35, 62, 100, 125, 150, 170, 200, 225, 250, 295, 320, 350};
        for (double a : angs) { if (adiff(a, GATE_A) < 22 || adiff(a, BRIDGE_A) < 14) continue; int[] p = pol(96.4, a); for (int d = -1; d <= 1; d++) { int[] q = pol(96.4, a + d * 2.2); set(q[0], 20, q[1], "lava"); } }
        for (double a : new double[]{22.5, 67.5, 112.5, 157.5, 202.5, 247.5, 292.5, 337.5}) { int[] p = pol(114, a); set(p[0], 19, p[1], "lava"); int[] q = pol(114, a + 2); set(q[0], 19, q[1], "lava"); }
        // inner-wall falls (outer face of inner wall, r 78)
        for (double a : new double[]{20, 60, 150, 190, 230, 300, 340}) { int[] p = pol(78, a); set(p[0], 19, p[1], "lava"); int[] q = pol(78, a + 3); set(q[0], 19, q[1], "lava"); }
        // gate: both sides of the pillars + the skull's jaw hinge
        for (int s : new int[]{-1, 1}) for (int dx = 0; dx < 3; dx++) for (int dz = 0; dz < 2; dz++) set(s * (29 + dx), 52, -110 + dz * 4, "lava");
        for (int s : new int[]{-1, 1}) { set(s * 12, 40, -100, "lava"); }
    }

    // =================== EMIT ===================
    static boolean onlyPits = false;
    static boolean nearPit(int x, int z) { for (int i = 0; i < 5; i++) { double a = -PI / 2 + i * 2 * PI / 5; if (abs(x - round(cos(a) * 70)) <= 16 && abs(z - round(sin(a) * 70)) <= 16) return true; } return false; }
    static void emit(File out) throws IOException {
        boolean[] done = new boolean[g.length]; List<String> cmds = new ArrayList<>(), lava = new ArrayList<>();
        if (onlyPits) for (int pi = 0; pi < 5; pi++) { double pa = -PI / 2 + pi * 2 * PI / 5; int pcx = (int) round(cos(pa) * 70), pcz = (int) round(sin(pa) * 70); for (int zz = -14; zz <= 14; zz++) { int sp = (int) floor(sqrt(14.2 * 14.2 - zz * zz)); cmds.add("execute in minecraft:sundering run fill " + (3000 + pcx - sp) + " 101 " + (3000 + pcz + zz) + " " + (3000 + pcx + sp) + " 122 " + (3000 + pcz + zz) + " minecraft:air"); } }
        final int WX = 3000, WY = 100, WZ = 3000; final String PRE = "execute in minecraft:sundering run ";
        for (int y = Y0; y < Y0 + H; y++) for (int z = Z0; z < Z0 + D; z++) for (int x = X0; x < X0 + W; x++) {
            int i = ix(x, y, z); if (g[i] == 0 || done[i]) continue; if (onlyPits && !(y >= -1 && y <= 22 && nearPit(x, z))) continue; short v = g[i];
            int x1 = x; while (x1 + 1 < X0 + W && g[ix(x1 + 1, y, z)] == v && !done[ix(x1 + 1, y, z)]) x1++;
            int z1 = z; outer: while (z1 + 1 < Z0 + D) { for (int k = x; k <= x1; k++) { int j = ix(k, y, z1 + 1); if (g[j] != v || done[j]) break outer; } z1++; }
            int y1 = y; outer2: while (y1 + 1 < Y0 + H && (long) (x1 - x + 1) * (z1 - z + 1) * (y1 - y + 2) <= 32768) { for (int kz = z; kz <= z1; kz++) for (int k = x; k <= x1; k++) { int j = ix(k, y1 + 1, kz); if (g[j] != v || done[j]) break outer2; } y1++; }
            for (int yy = y; yy <= y1; yy++) for (int zz = z; zz <= z1; zz++) for (int xx = x; xx <= x1; xx++) done[ix(xx, yy, zz)] = true;
            String b = pal.get(v);
            String c = PRE + ((x == x1 && y == y1 && z == z1) ? "setblock " + (WX + x) + " " + (WY + y) + " " + (WZ + z) + " minecraft:" + b : "fill " + (WX + x) + " " + (WY + y) + " " + (WZ + z) + " " + (WX + x1) + " " + (WY + y1) + " " + (WZ + z1) + " minecraft:" + b);
            (b.equals("lava") ? lava : cmds).add(c);
        }
        try (PrintWriter w = new PrintWriter(new FileWriter(out))) { for (String c : cmds) w.println(c); w.println("#LAVA"); for (String c : lava) w.println(c); }
        System.out.println("commands: " + cmds.size() + " + lava " + lava.size());
    }

    // =================== PREVIEW ===================
    static int[] colorOf(String b) {
        if (b.startsWith("lava")) return new int[]{255, 120, 20};
        if (b.startsWith("fire")) return new int[]{255, 170, 40};
        if (b.startsWith("magma")) return new int[]{210, 80, 20};
        if (b.startsWith("shroomlight") || b.startsWith("lantern")) return new int[]{255, 190, 90};
        if (b.startsWith("redstone_block")) return new int[]{230, 20, 20};
        if (b.startsWith("red_concrete")) return new int[]{150, 20, 25};
        if (b.startsWith("nether_wart") || b.startsWith("red_nether")) return new int[]{110, 20, 28};
        if (b.startsWith("red_")) return new int[]{150, 40, 40};
        if (b.startsWith("black_concrete")) return new int[]{15, 15, 20};
        if (b.startsWith("bone") || b.startsWith("smooth_quartz") || b.startsWith("calcite") || b.startsWith("quartz")) return new int[]{226, 218, 196};
        if (b.startsWith("white_terra")) return new int[]{209, 178, 161};
        if (b.startsWith("diorite")) return new int[]{190, 190, 190};
        if (b.startsWith("basalt") || b.startsWith("smooth_basalt")) return new int[]{70, 70, 78};
        if (b.contains("deepslate")) return new int[]{62, 62, 70};
        if (b.contains("leaves") || b.startsWith("moss")) return new int[]{40, 70, 36};
        if (b.contains("log")) return new int[]{60, 40, 28};
        if (b.contains("chain")) return new int[]{40, 40, 46};
        if (b.equals("ice") || b.contains("packed_ice") || b.contains("blue_ice") || b.contains("snow")) return new int[]{150, 205, 240};
        if (b.contains("copper") || b.startsWith("lightning_rod")) return new int[]{190, 112, 72};
        if (b.contains("obsidian")) return new int[]{40, 18, 64};
        if (b.contains("purple")) return new int[]{150, 60, 200};
        if (b.startsWith("iron_bars")) return new int[]{110, 112, 120};
        if (b.contains("glass")) return new int[]{200, 40, 40};
        return new int[]{54, 48, 56};
    }
    static void preview(File out, double ex, double ey, double ez, double tx, double ty, double tz, double fov, int wpx, int hpx) throws IOException {
        BufferedImage im = new BufferedImage(wpx, hpx, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < hpx; j++) for (int i = 0; i < wpx; i++) { double t = j / (double) hpx; int r = (int) (70 + 185 * pow(t, 1.2)), gg = (int) (50 + 90 * pow(t, 1.3)), bl = (int) (130 - 40 * t); im.setRGB(i, j, (min(255, r) << 16) | (min(255, gg) << 8) | max(0, bl)); }
        float[] zb = new float[wpx * hpx]; Arrays.fill(zb, Float.MAX_VALUE);
        double fx = tx - ex, fy = ty - ey, fz = tz - ez, fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl;
        double rx = -fz, ry = 0, rz = fx; double rl = hypot(rx, rz); rx /= rl; rz /= rl;
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        double f = (wpx / 2.0) / tan(toRadians(fov / 2));
        int[][] col = new int[pal.size()][]; for (int i = 0; i < pal.size(); i++) col[i] = colorOf(pal.get(i));
        for (int y = Y0; y < Y0 + H; y++) for (int z = Z0; z < Z0 + D; z++) for (int x = X0; x < X0 + W; x++) {
            short v = g[ix(x, y, z)]; if (v == 0) continue;
            boolean top = air(x, y + 1, z), side = air(x + 1, y, z) || air(x - 1, y, z) || air(x, y, z + 1) || air(x, y, z - 1) || air(x, y - 1, z);
            if (!top && !side) continue;
            double dx = x + .5 - ex, dy = y + .5 - ey, dz = z + .5 - ez; double zv = dx * fx + dy * fy + dz * fz; if (zv < 1) continue;
            double xv = dx * rx + dz * rz, yv = dx * ux + dy * uy + dz * uz;
            int px = (int) (wpx / 2.0 + f * xv / zv), py = (int) (hpx / 2.0 - f * yv / zv);
            int sz = (int) max(1, ceil(f / zv * 1.05));
            int[] c = col[v]; boolean emis = c[0] > 200 && c[1] < 200 && c[2] < 100;
            double sh = emis ? 1 : (top ? 1.0 : .62) * (0.85 + 0.15 * hn(x, y, z));
            double fog = min(.8, max(0, (zv - 120) / 500));
            int rr = (int) (c[0] * sh * (1 - fog) + 140 * fog), gg2 = (int) (c[1] * sh * (1 - fog) + 90 * fog), bb = (int) (c[2] * sh * (1 - fog) + 150 * fog);
            int rgb = (min(255, rr) << 16) | (min(255, gg2) << 8) | min(255, bb);
            for (int a = 0; a < sz; a++) for (int b2 = 0; b2 < sz; b2++) { int qx = px + a - sz / 2, qy = py + b2 - sz / 2; if (qx < 0 || qy < 0 || qx >= wpx || qy >= hpx) continue; int k = qy * wpx + qx; if (zv < zb[k]) { zb[k] = (float) zv; im.setRGB(qx, qy, rgb); } }
        }
        ImageIO.write(im, "png", out);
    }

    static void build(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        String only = a.length > 1 ? a[1] : "all";
        arena(); rimAndWall(); specStairs(); curtainBanners(); outerRing(); gate(); bridge();
        remap(A);
        abyssFloor(); pits(); skull(); spine(); ringChains(); islands(); lavaFalls();
        System.out.println("built in " + (System.currentTimeMillis() - t0) + "ms");
        File dir = new File(a[0]); dir.mkdirs();
        if (a.length > 3 && a[3].equals("pits")) onlyPits = true;
        if (a.length > 2 && a[2].equals("emit")) emit(new File(dir, "commands.txt"));
        if (a.length > 2 && a[2].equals("sample")) { Random rr = new Random(5); try (PrintWriter w = new PrintWriter(new File(dir, "sample.txt"))) { int c = 0; while (c < 500) { int x = X0 + rr.nextInt(W), y = Y0 + rr.nextInt(H), z = Z0 + rr.nextInt(D); String b = get(x, y, z); if (b.equals("air") || b.startsWith("lava")) continue; w.println((3000 + x) + " " + (100 + y) + " " + (3000 + z) + " " + b); c++; } } }
        preview(new File(dir, "view_front.png"), 0, 78, 236, 0, 46, -70, 78, 1536, 864);
        preview(new File(dir, "view_arena.png"), 20, 40, 120, 0, 10, -20, 80, 1536, 864);
        preview(new File(dir, "view_high.png"), 0, 190, 330, 0, 30, -40, 70, 1536, 864);
        preview(new File(dir, "view_gate.png"), 0, 70, -20, 0, 62, -120, 70, 1536, 864);
        preview(new File(dir, "view_bridge.png"), 0, 16, 170, 0, 44, -110, 85, 1536, 864);
        preview(new File(dir, "view_wall.png"), -40, 22, 40, 60, 12, -60, 90, 1536, 864);
        for (int pi = 0; pi < 5; pi++) { double pa = -PI / 2 + pi * 2 * PI / 5; double pcx = round(cos(pa) * 70), pcz = round(sin(pa) * 70); preview(new File(dir, "view_pit" + pi + ".png"), pcx * .45, 24, pcz * .45, pcx, 8, pcz, 55, 960, 540); }
        preview(new File(dir, "view_ice.png"), 0, 22, 10, -41, 8, 57, 60, 1536, 864);
        preview(new File(dir, "view_cage.png"), 5, 22, 20, 66, 7, -22, 60, 1536, 864);
        System.out.println("total " + (System.currentTimeMillis() - t0) + "ms");
    }
}
