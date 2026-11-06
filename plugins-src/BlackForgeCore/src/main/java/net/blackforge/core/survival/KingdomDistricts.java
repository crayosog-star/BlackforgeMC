package net.blackforge.core.survival;

import static net.blackforge.core.survival.InfernalLayout.*;

/**
 * The two working districts either side of the Molten Plaza, each on a terrace four blocks above it: the Merchant District
 * (west), one building for every existing player service, and the Blacksmith Quarter (east), a great forge, smithies, lava
 * channels and chimneys. Every building has a front door on the street and a door-side lantern; names come from holograms.
 */
final class KingdomDistricts {
    private KingdomDistricts() {}

    private static final int D = DISTRICT_Y;

    static void build(KBuild k) {
        steps(k);
        merchant(k);
        blacksmith(k);
    }

    // ------------------------------------------------------------------ shared

    private static void steps(KBuild k) {
        for (int s : new int[]{-1, 1})
            for (int x = 47; x <= 57; x++) for (int z = 20; z <= 40; z++) {
                int top = Math.min(D, x - 46);
                for (int y = -1; y <= top; y++) k.set(s * x, y, z, y == top ? (z == 20 || z == 40 ? "gilded_blackstone" : "polished_blackstone_bricks") : "blackstone");
                if (z == 20 || z == 40) { k.set(s * x, top + 1, z, "polished_blackstone_brick_wall"); if (x % 4 == 0) k.late(s * x, top + 2, z, "lantern"); }
            }
    }

    private static void pave(KBuild k, int x1, int x2, int street, int z1, int z2) {
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                boolean road = Math.abs(x - street) <= 4;
                String b = Math.abs(x - street) <= 1 ? "red_nether_bricks" : road ? (((x + z) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles") : (((Math.floorDiv(x, 4) + Math.floorDiv(z, 4)) & 1) == 0 ? "deepslate_bricks" : "polished_blackstone_bricks");
                k.set(x, D, z, b);
                k.set(x, D - 1, z, "stone");
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                if (edge) { k.set(x, D + 1, z, "polished_blackstone_bricks"); k.set(x, D + 2, z, "polished_blackstone_brick_wall"); if (Math.floorMod(x + z, 10) == 0) { k.set(x, D + 3, z, "polished_blackstone_brick_wall"); k.late(x, D + 4, z, "lantern"); } }
            }
        for (int z = z1 + 6; z <= z2 - 6; z += 12) for (int s : new int[]{-1, 1}) { for (int y = 1; y <= 5; y++) k.set(street + s * 5, D + y, z, "polished_blackstone_brick_wall"); k.late(street + s * 5, D + 6, z, "lantern"); }
    }

    /** A medieval house: dark base, timber frame over stone infill, orange windows, a door on {@code front}, a gabled roof along its long side. */
    private static void house(KBuild k, int x1, int z1, int x2, int z2, int h, char front, String infill, String frame, String roof) {
        int cx = (x1 + x2) / 2, cz = (z1 + z2) / 2;
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) {
                boolean edge = x == x1 || x == x2 || z == z1 || z == z2;
                boolean corner = (x == x1 || x == x2) && (z == z1 || z == z2);
                k.set(x, D, z, "spruce_planks");
                for (int y = 1; y <= h; y++) {
                    if (!edge) { k.set(x, D + y, z, "air"); continue; }
                    String b = y <= 2 ? "polished_blackstone_bricks" : corner || ((x - x1) % 6 == 0 && (x == x1 || x == x2)) || ((z - z1) % 6 == 0 && (z == z1 || z == z2)) ? frame : infill;
                    if (y == h) b = frame;
                    k.set(x, D + y, z, b);
                }
            }
        // windows in the middle of each side, orange from inside
        for (int s : new int[]{-1, 1}) {
            for (int off : new int[]{-5, 5}) {
                if (Math.abs(off) < (x2 - x1) / 2 - 1) for (int y = 4; y <= 5; y++) { k.set(cx + off, D + y, s < 0 ? z1 : z2, "orange_stained_glass"); k.set(cx + off + 1, D + y, s < 0 ? z1 : z2, "orange_stained_glass"); }
                if (Math.abs(off) < (z2 - z1) / 2 - 1) for (int y = 4; y <= 5; y++) { k.set(s < 0 ? x1 : x2, D + y, cz + off, "orange_stained_glass"); k.set(s < 0 ? x1 : x2, D + y, cz + off + 1, "orange_stained_glass"); }
            }
        }
        // door
        int dx = front == 'e' ? x2 : front == 'w' ? x1 : cx, dz = front == 's' ? z2 : front == 'n' ? z1 : cz;
        int ax = front == 'e' || front == 'w' ? 0 : 1, az = 1 - ax;
        for (int w = -1; w <= 1; w++) for (int y = 1; y <= 4; y++) k.set(dx + w * ax, D + y, dz + w * az, y == 4 ? KBuild.TRIM : "air");
        for (int s : new int[]{-2, 2}) { k.set(dx + s * ax + (front == 'e' ? 1 : front == 'w' ? -1 : 0), D + 1, dz + s * az + (front == 's' ? 1 : front == 'n' ? -1 : 0), "polished_blackstone_brick_wall"); k.late(dx + s * ax + (front == 'e' ? 1 : front == 'w' ? -1 : 0), D + 2, dz + s * az + (front == 's' ? 1 : front == 'n' ? -1 : 0), "lantern"); }
        // roof
        if (x2 - x1 >= z2 - z1) k.gableX(x1 - 1, x2 + 1, cz, (z2 - z1) / 2 + 1, D + h + 1, 1.0, roof);
        else gableZ(k, z1 - 1, z2 + 1, cx, (x2 - x1) / 2 + 1, D + h + 1, roof);
        for (int x = x1 + 2; x <= x2 - 2; x += 5) for (int z = z1 + 2; z <= z2 - 2; z += 5) { k.set(x, D + h - 1, z, "iron_chain[axis=y]"); k.late(x, D + h - 2, z, "lantern[hanging=true]"); }
    }

    private static void gableZ(KBuild k, int z1, int z2, int cx, int halfWidth, int y, String mat) {
        for (int z = z1; z <= z2; z++)
            for (int dx = -halfWidth - 1; dx <= halfWidth + 1; dx++) {
                int ry = y - 1 + (halfWidth + 1 - Math.abs(dx));
                k.set(cx + dx, ry, z, (z == z1 || z == z2 || Math.abs(dx) == halfWidth + 1) ? KBuild.TRIM : mat);
                k.set(cx + dx, ry - 1, z, mat);
            }
        for (int z : new int[]{z1, z2})
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                int top = y - 1 + (halfWidth + 1 - Math.abs(dx)) - 2;
                for (int yy = y; yy <= top; yy++) k.set(cx + dx, yy, z, k.brick(cx + dx, yy, z));
            }
    }

    private static void barrels(KBuild k, int x, int z, int n) {
        for (int i = 0; i < n; i++) { k.set(x + i, D + 1, z, "barrel[facing=up]"); if (i % 2 == 0) k.set(x + i, D + 2, z, "barrel[facing=up]"); }
    }

    // ================================================================== Merchant District (west)

    private static void merchant(KBuild k) {
        int street = -98;
        pave(k, MERCH_X1, MERCH_X2, street, DISTRICT_Z1, DISTRICT_Z2);
        // Trading Hall: the biggest building, ten workstations along its walls
        house(k, -122, 7, -102, 29, 14, 'e', "stone_bricks", "dark_oak_log[axis=y]", "deepslate_tiles");
        String[] st = {"lectern[facing=east]", "blast_furnace[facing=east]", "smithing_table", "grindstone[face=floor,facing=east]", "brewing_stand", "fletching_table", "composter[level=3]", "smoker[facing=east]", "stonecutter[facing=east]", "loom[facing=east]"};
        for (int i = 0; i < st.length; i++) { k.set(-120, D + 1, 9 + i * 2, st[i]); k.set(-119, D + 1, 9 + i * 2, "polished_blackstone_bricks"); k.set(-121, D + 3, 9 + i * 2, "gilded_blackstone"); }
        // Auction House: a round tower with the bell on a gilded pedestal
        k.roundShell(-84, 30, D, D + 16, 8, 8);
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) if (Math.hypot(x, z) < 6.7) { k.set(-84 + x, D, 30 + z, ((x + z) & 1) == 0 ? "polished_deepslate" : "gilded_blackstone"); for (int y = 1; y < 16; y++) k.set(-84 + x, D + y, 30 + z, "air"); }
        for (int y = 1; y <= 4; y++) k.set(-92, D + y, 30, y == 4 ? KBuild.TRIM : "air");
        k.set(-84, D + 1, 30, "gold_block"); k.set(-84, D + 2, 30, "polished_blackstone");
        k.set(-84, D + 3, 30, "bell[attachment=floor,facing=north]");
        k.cone(-84, D + 17, 30, 9, 16, KBuild.ROOF);
        // Daily Shop: a house with a canopy of black and red, barrels and crates out front
        house(k, -122, 49, -102, 67, 11, 'e', "dark_oak_planks", "dark_oak_log[axis=y]", "deepslate_tiles");
        barrels(k, -101, 52, 3); barrels(k, -101, 62, 3);
        for (int z = 50; z <= 66; z++) for (int y = 0; y <= 1; y++) k.set(-101 + 0, D + 5 - y, z, (z & 1) == 0 ? "red_wool" : "black_wool");
        // Grove Trader (/sell): a greenhouse feel, moss, leaves, azaleas
        house(k, -94, 54, -74, 72, 11, 'w', "stone_bricks", "spruce_log[axis=y]", "deepslate_tiles");
        for (int x = -92; x <= -76; x++) for (int z = 56; z <= 70; z++) if ((x + z) % 3 == 0) { k.set(x, D, z, "moss_block"); k.set(x, D + 1, z, "azalea"); }
        // Scrap Shop: grim, iron and anvils
        house(k, -122, 83, -102, 101, 11, 'e', "stone_bricks", "polished_blackstone", "deepslate_tiles");
        for (int i = 0; i < 4; i++) { k.set(-119, D + 1, 86 + i * 4, "anvil[facing=east]"); k.set(-118, D + 1, 86 + i * 4, "grindstone[face=floor,facing=east]"); }
        // chest shops: a row of open stalls with striped awnings
        for (int i = 0; i < 4; i++) {
            int z0 = 90 + i * 6 - 0, x0 = -94;
            for (int y = 1; y <= 3; y++) { k.set(x0, D + y, z0, "dark_oak_log[axis=y]"); k.set(x0 + 5, D + y, z0, "dark_oak_log[axis=y]"); k.set(x0, D + y, z0 + 4, "dark_oak_log[axis=y]"); k.set(x0 + 5, D + y, z0 + 4, "dark_oak_log[axis=y]"); }
            for (int x = x0 - 1; x <= x0 + 6; x++) for (int z = z0 - 1; z <= z0 + 5; z++) k.set(x, D + 4, z, ((x + i) & 1) == 0 ? "red_wool" : "black_wool");
            k.set(x0 + 1, D + 1, z0 + 2, "chest[facing=west]"); k.set(x0 + 2, D + 1, z0 + 2, "barrel[facing=up]"); k.set(x0 + 3, D + 1, z0 + 2, "chest[facing=west]");
        }
        // the market well in the middle of the street
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) { double d = Math.hypot(x, z); if (d > 4.2) continue; k.set(street + x, D + 1, 76 + z, d > 3.2 ? "polished_blackstone_bricks" : "air"); if (d <= 3.2) { k.set(street + x, D, 76 + z, "polished_blackstone"); k.late(street + x, D, 76 + z, "water"); } if (d > 3.2) k.set(street + x, D + 2, 76 + z, "polished_blackstone_brick_wall"); }
        k.statue(street, D, 76, 's', "gold_block", "red_wool");
        // banners down the street
        for (int z = 8; z <= 100; z += 16) { k.banner(street - 5, D + 9, z, 'e', 1, 6); k.banner(street + 5, D + 9, z + 8, 'w', 1, 6); }
    }

    // ================================================================== Blacksmith Quarter (east)

    private static void blacksmith(KBuild k) {
        int street = 100;
        pave(k, BLACK_X1, BLACK_X2, street, DISTRICT_Z1, DISTRICT_Z2);
        // molten channels down the middle of the street, under glass
        for (int z = DISTRICT_Z1 + 2; z <= DISTRICT_Z2 - 2; z++) { k.set(street, D, z, "orange_stained_glass"); k.late(street, D - 1, z, "lava"); k.set(street, D - 2, z, "magma_block"); k.set(street - 1, D - 1, z, "polished_blackstone"); k.set(street + 1, D - 1, z, "polished_blackstone"); }
        // the great forge: lava trough, furnaces, anvils, three chimneys with smoke
        int fx1 = street - 16, fx2 = street + 16, fz1 = 36, fz2 = 66;
        house(k, fx1, fz1, fx2, fz2, 16, 'n', "stone_bricks", "polished_blackstone", "deepslate_tiles");
        for (int z = fz1 + 6; z <= fz2 - 6; z++) for (int x = street - 4; x <= street + 4; x++) { if (Math.abs(x - street) <= 1) { k.set(x, D, z, "orange_stained_glass"); k.late(x, D - 1, z, "lava"); k.set(x, D - 2, z, "magma_block"); } else k.set(x, D, z, "gilded_blackstone"); }
        for (int i = 0; i < 6; i++) k.set(fx1 + 3 + i * 5, D + 1, fz2 - 1, "blast_furnace[facing=north,lit=true]");
        for (int i = 0; i < 4; i++) { k.set(street - 8 + i * 5, D + 1, fz1 + 8, "anvil[facing=east]"); k.set(street - 8 + i * 5, D + 1, fz1 + 12, "smithing_table"); }
        for (int i = 0; i < 3; i++) {
            int cx = fx1 + 5 + i * 11, cz = fz2 + 2;
            for (int y = 1; y <= 38; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) { boolean core = dx == 0 && dz == 0; k.set(cx + dx, D + y, cz + dz, core ? "air" : (y % 8 == 7 ? KBuild.TRIM : k.brick(cx + dx, y, cz + dz))); }
            k.late(cx, D + 38, cz, "campfire[lit=true,signal_fire=true]");
        }
        k.statue(street - 22, D, 28, 's', "iron_block", "red_wool");
        k.statue(street + 22, D, 28, 's', "iron_block", "red_wool");
        // smithies along the street: armorer, weaponsmith, toolsmith and two workshops
        house(k, 64, 6, 84, 24, 11, 'e', "stone_bricks", "polished_blackstone", "deepslate_tiles");
        house(k, 116, 6, 136, 24, 11, 'w', "stone_bricks", "polished_blackstone", "deepslate_tiles");
        house(k, 64, 80, 84, 98, 11, 'e', "stone_bricks", "polished_blackstone", "deepslate_tiles");
        house(k, 116, 80, 136, 98, 11, 'w', "stone_bricks", "polished_blackstone", "deepslate_tiles");
        for (int[] b : new int[][]{{64, 6, 1}, {116, 6, -1}, {64, 80, 1}, {116, 80, -1}}) {
            int bx = b[0] + (b[2] > 0 ? 1 : 19);
            for (int i = 0; i < 3; i++) { k.set(bx, D + 1, b[1] + 4 + i * 5, i == 0 ? "anvil[facing=east]" : i == 1 ? "blast_furnace[facing=east,lit=true]" : "grindstone[face=floor,facing=east]"); k.set(bx, D + 4, b[1] + 4 + i * 5, "iron_bars"); k.set(bx, D + 5, b[1] + 4 + i * 5, "gold_block"); }
            int chx = b[0] + (b[2] > 0 ? 3 : 17), chz = b[1] + 16;
            for (int y = 1; y <= 22; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) k.set(chx + dx, D + y, chz + dz, dx == 0 && dz == 0 ? "air" : "polished_blackstone_bricks");
            k.late(chx, D + 22, chz, "campfire[lit=true,signal_fire=true]");
        }
        // the Blacksmith's anvil in front of the great forge
        k.set(BLACKSMITH.x(), D + 1, BLACKSMITH.z() - 24, "anvil[facing=east]");
        for (int z = 10; z <= 100; z += 18) { k.banner(street - 5, D + 9, z, 'e', 1, 6); k.banner(street + 5, D + 9, z + 9, 'w', 1, 6); }
    }
}
