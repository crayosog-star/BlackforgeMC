package net.blackforge.core.survival;

import static net.blackforge.core.survival.InfernalLayout.*;

/**
 * The three great interiors of the Infernal Kingdom, all themed on the forge's metals so they read as one family:
 * the Royal Treasury (under the citadel terrace, the five crate vaults), the Hall of Ascension (the five ranks, each display
 * grander than the last) and the Hall of Champions (the five leaderboards).
 */
final class KingdomHalls {
    private KingdomHalls() {}

    static void build(KBuild k) {
        treasury(k);
        wing(k, ASC_X1, ASC_X2, true);
        wing(k, CHAMP_X1, CHAMP_X2, false);
    }

    // ================================================================== Royal Treasury

    private static void treasury(KBuild k) {
        int x1 = TREASURY_X1, x2 = TREASURY_X2, z1 = TREASURY_Z1, z2 = TREASURY_Z2, h = TREASURY_H;
        // the vault itself: thick walls, polished floor, a groined ceiling under the terrace
        for (int x = x1 - 2; x <= x2 + 2; x++)
            for (int z = z1 + 1; z >= z2 - 2; z--) {
                boolean wall = x <= x1 || x >= x2 || z <= z2;
                for (int y = -1; y <= h + 1; y++) {
                    if (wall && !(z >= z1 - 1)) { k.set(x, y, z, (y % 9 == 8) ? KBuild.TRIM : k.brick(x, y, z)); continue; }
                    if (y == -1) k.set(x, y, z, "blackstone");
                    else if (y == 0) k.set(x, y, z, floorTreasury(x, z));
                    else if (y == h + 1) k.set(x, y, z, "deepslate_tiles");
                    else if (y == h && ((Math.floorMod(x, 10) == 0) || (Math.floorMod(z, 10) == 0))) k.set(x, y, z, "polished_blackstone");   // ceiling ribs
                    else k.set(x, y, z, "air");
                }
            }
        // side walls inside: brick faces with gold courses
        // lava channels beneath glass floors, running the length of the vault
        for (int s : new int[]{-1, 1}) for (int z = z1 - 2; z >= z2 + 2; z--) { for (int dx = -1; dx <= 1; dx++) { int x = s * 14 + dx; k.lavaStrip(x, 0, z); k.set(x, -1, z, "polished_blackstone"); k.late(x, -1, z, "lava"); k.set(x, -2, z, "magma_block"); } }
        for (int s : new int[]{-1, 1}) for (int z = z1 - 2; z >= z2 + 2; z--) { k.set(s * 12, 0, z, "gilded_blackstone"); k.set(s * 16, 0, z, "gilded_blackstone"); }
        // pillars with gold bands in two rows, chandeliers between
        for (int s : new int[]{-1, 1})
            for (int z = z1 - 8; z >= z2 + 6; z -= 10) {
                int px = s * 22;
                for (int y = 1; y < h; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) k.set(px + dx, y, z + dz, (y % 6 == 0) ? "gold_block" : (Math.abs(dx) + Math.abs(dz) == 2 ? "polished_blackstone" : k.brick(px + dx, y, z + dz)));
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) { k.set(px + dx, 1, z + dz, "gilded_blackstone"); k.set(px + dx, h - 1, z + dz, "gilded_blackstone"); }
                k.late(px - s * 3, 3, z, "campfire[lit=true]");
                k.set(px - s * 3, 2, z, "chiseled_polished_blackstone");
            }
        for (int z = z1 - 10; z >= z2 + 8; z -= 10) {
            for (int s : new int[]{-1, 1}) { int cx = s * 6; for (int y = h - 1; y >= h - 4; y--) k.late(cx, y, z, "iron_chain[axis=y]"); k.late(cx, h - 5, z, "lantern[hanging=true]"); k.late(cx - 1, h - 5, z, "lantern[hanging=true]"); k.late(cx + 1, h - 5, z, "lantern[hanging=true]"); }
        }
        // the great doors: a gothic arch in the terrace face between the two flights, open leaves, guard statues and runes
        for (int dy = 0; dy <= 16; dy++) {
            double half = dy <= 10 ? 7 : Math.max(0, 7 - (dy - 10) * 1.1);
            for (int xx = -8; xx <= 8; xx++) {
                boolean in = Math.abs(xx) <= half;
                for (int z = z1; z >= z1 - 1; z--) {
                    if (in) k.set(xx, 1 + dy, z, "air");
                    else if (Math.abs(xx) <= half + 1.6 && dy <= 17) k.set(xx, 1 + dy, z, dy % 3 == 1 ? KBuild.GLOW : "gilded_blackstone");
                }
            }
        }
        for (int s : new int[]{-1, 1}) {      // the door leaves, swung back along the passage
            for (int z = z1 - 2; z >= z1 - 6; z--) for (int y = 1; y <= 12; y++) { k.set(s * 6, y, z, (y % 4 == 0 || z == z1 - 6) ? "iron_block" : "dark_oak_planks"); k.set(s * 7, y, z, y % 4 == 0 ? "gold_block" : "polished_blackstone"); }
            k.statue(s * 8, 0, z1 + 6, 's', "netherite_block", "red_wool");
            for (int y = 3; y <= 7; y += 2) k.late(s * 5, y, z1 + 1, "soul_lantern");
        }
        for (int r : new int[]{3, 5}) for (int i = 0; i < 24; i++) { double a = i * Math.PI / 12; k.set((int) Math.round(Math.cos(a) * r), 0, z1 + 14 + (int) Math.round(Math.sin(a) * r), i % 2 == 0 ? KBuild.GLOW : "crying_obsidian"); }
        k.lettersX("ROYAL TREASURY", 0, 22, z1 + 2, 1, 1, "gold_block", "polished_blackstone");
        // the five crates, lowest tier by the doors, Forgeheart at the back under its canopy
        for (Spot s : CRATES) {
            switch (s.id()) {
                case "vote" -> spark(k, s.x(), s.z());
                case "iron" -> cinder(k, s.x(), s.z());
                case "koth" -> kothCrate(k, s.x(), s.z());
                case "gold" -> molten(k, s.x(), s.z());
                default -> forgeheart(k, s.x(), s.z());
            }
        }
        k.lettersZ("SPARK", -56, 11, x1 + 1, 1, 1, "copper_block", "polished_blackstone");
        k.lettersZ("CINDER", -74, 11, x1 + 1, 1, 1, "red_nether_bricks", "polished_blackstone");
        k.lettersZ("KOTH", -56, 11, x2 - 1, 1, -1, "crying_obsidian", "gilded_blackstone");
        k.lettersZ("MOLTEN", -74, 11, x2 - 1, 1, -1, "gold_block", "polished_blackstone");
        k.lettersX("FORGEHEART", 0, 8, z2 + 2, 1, 1, "netherite_block", "shroomlight");
    }

    private static String floorTreasury(int x, int z) {
        int ax = Math.abs(x);
        if (ax <= 4) return ((x + z) & 1) == 0 ? "red_nether_bricks" : "polished_blackstone";
        if (ax == 5) return "gilded_blackstone";
        return ((Math.floorDiv(x, 4) + Math.floorDiv(z, 4)) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles";
    }

    private static void vault(KBuild k, int x, int z, char facing) {
        String f = facing == 'e' ? "east" : facing == 'w' ? "west" : facing == 's' ? "south" : "north";
        k.set(x, 1, z, "polished_blackstone");
        k.set(x, 2, z, "vault[facing=" + f + "]");
    }

    private static void disc(KBuild k, int cx, int cz, int r, int y, String a, String b) {
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) { double d = Math.hypot(dx, dz); if (d <= r + 0.3) k.set(cx + dx, y, cz + dz, d > r - 1 ? b : a); }
    }

    /** Spark (vote): the humblest crate, but electric: a copper arch hung with lightning rods. */
    private static void spark(KBuild k, int x, int z) {
        disc(k, x, z, 4, 0, "copper_block", "cut_copper");
        vault(k, x, z, 'e');
        for (int dz : new int[]{-3, 3}) { for (int y = 1; y <= 7; y++) k.set(x - 1, y, z + dz, y % 3 == 0 ? "gold_block" : "copper_block"); k.set(x - 1, 8, z + dz, "lightning_rod"); }
        for (int dz = -3; dz <= 3; dz++) k.set(x - 1, 8, z + dz, "cut_copper");
        for (int dz = -2; dz <= 2; dz += 2) k.late(x - 1, 7, z + dz, "lantern[hanging=true]");
        k.late(x + 1, 3, z, "end_rod");
    }

    /** Cinder (iron): embers: a ring of braziers round a nether-brick plinth, chains and lanterns above. */
    private static void cinder(KBuild k, int x, int z) {
        disc(k, x, z, 5, 0, "red_nether_bricks", "nether_bricks");
        vault(k, x, z, 'e');
        for (int dx : new int[]{-3, 3}) for (int dz : new int[]{-3, 3}) { k.set(x + dx, 1, z + dz, "chiseled_polished_blackstone"); k.late(x + dx, 2, z + dz, "campfire[lit=true]"); }
        for (int dz : new int[]{-4, 4}) for (int y = 1; y <= 9; y++) k.set(x - 2, y, z + dz, y % 4 == 0 ? "gilded_blackstone" : "red_nether_bricks");
        for (int dz = -4; dz <= 4; dz++) k.set(x - 2, 10, z + dz, "red_nether_bricks");
        for (int y = 9; y >= 6; y--) k.late(x - 2, y, z, "iron_chain[axis=y]");
        k.late(x - 2, 5, z, "lantern[hanging=true]");
    }

    /** KOTH: a crown of gold spikes over crying obsidian, between red banners. */
    private static void kothCrate(KBuild k, int x, int z) {
        disc(k, x, z, 5, 0, "polished_blackstone", "crying_obsidian");
        vault(k, x, z, 'w');
        for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; int px = x + (int) Math.round(Math.cos(a) * 4), pz = z + (int) Math.round(Math.sin(a) * 4); for (int y = 1; y <= 3 + (i % 2) * 2; y++) k.set(px, y, pz, "gold_block"); k.set(px, 4 + (i % 2) * 2, pz, GLOW()); }
        k.banner(x + 5, 12, z - 4, 'w', 1, 9);
        k.banner(x + 5, 12, z + 4, 'w', 1, 9);
    }

    private static String GLOW() { return KBuild.GLOW; }

    /** Molten (gold): a gilded shrine with a lava curtain behind glass. */
    private static void molten(KBuild k, int x, int z) {
        disc(k, x, z, 6, 0, "gold_block", "gilded_blackstone");
        vault(k, x, z, 'w');
        // the lava curtain on the wall behind, 11 wide, sealed behind glass
        int wallX = TREASURY_X2 - 1;
        for (int dz = -5; dz <= 5; dz++) for (int y = 2; y <= 14; y++) {
            k.set(wallX - 1, y, z + dz, "orange_stained_glass");
            k.late(wallX, y, z + dz, "lava");
            k.set(wallX + 1, y, z + dz, "polished_blackstone");
        }
        for (int dz = -5; dz <= 5; dz++) { k.set(wallX, 1, z + dz, "magma_block"); k.set(wallX, 15, z + dz, "polished_blackstone"); }
        for (int dz : new int[]{-6, 6}) for (int y = 1; y <= 15; y++) k.set(wallX - 2, y, z + dz, y % 5 == 0 ? "gold_block" : "gilded_blackstone");
        for (int dz = -6; dz <= 6; dz++) k.set(wallX - 2, 16, z + dz, "gold_block");
    }

    /** Forgeheart (netherite): the crown of the vault: a netherite and gold canopy on four tall pillars over a stepped dais, a lava wall, a ring of runes. */
    private static void forgeheart(KBuild k, int x, int z) {
        for (int r = 9; r >= 5; r--) disc(k, x, z, r, 0, r % 2 == 0 ? "netherite_block" : "polished_blackstone", "gold_block");
        disc(k, x, z, 4, 0, "gilded_blackstone", "gold_block");
        vault(k, x, z, 's');
        k.set(x, 1, z, "netherite_block");
        for (int i = 0; i < 16; i++) { double a = i * Math.PI / 8; k.set(x + (int) Math.round(Math.cos(a) * 10), 0, z + (int) Math.round(Math.sin(a) * 10), i % 2 == 0 ? KBuild.GLOW : "crying_obsidian"); }
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            int px = x + sx * 7, pz = z + sz * 7;
            for (int y = 1; y <= 15; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) k.set(px + dx, y, pz + dz, y % 5 == 0 ? "gold_block" : (Math.abs(dx) + Math.abs(dz) == 2 ? "netherite_block" : "polished_blackstone"));
            k.late(px, 16, pz, "campfire[lit=true]");
            k.set(px, 16 - 0, pz, "gold_block");
        }
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) { double d = Math.max(Math.abs(dx), Math.abs(dz)); if (d >= 6 && d <= 8 && (Math.abs(dx) == 7 || Math.abs(dz) == 7 || d == 8)) k.set(x + dx, 17, z + dz, d == 7 ? "gold_block" : "netherite_block"); }
        // lava wall in the back niche
        int wz = TREASURY_Z2 + 1;
        for (int dx = -9; dx <= 9; dx++) for (int y = 2; y <= 15; y++) {
            k.set(x + dx, y, wz + 2, "orange_stained_glass");
            k.late(x + dx, y, wz + 1, "lava");
            k.set(x + dx, y, wz, "polished_blackstone");
        }
        for (int dx = -9; dx <= 9; dx++) { k.set(x + dx, 1, wz + 1, "magma_block"); k.set(x + dx, 16, wz + 1, "polished_blackstone"); }
        // a giant anvil emblem over the dais, built of gold
        for (int dx = -5; dx <= 5; dx++) for (int dy = 0; dy < 2; dy++) k.set(x + dx, 18 - 1 + dy - 4 + 4, wz + 3, "gold_block");
    }

    // ================================================================== the wings on the citadel terrace

    private static final String[][] RANK_LOOK = {
            // metal, accent, glass, banner wool
            {"copper_block", "cut_copper", "orange_stained_glass", "orange_wool"},
            {"iron_block", "smooth_stone", "white_stained_glass", "white_wool"},
            {"gold_block", "gilded_blackstone", "yellow_stained_glass", "yellow_wool"},
            {"diamond_block", "prismarine_bricks", "light_blue_stained_glass", "cyan_wool"},
            {"netherite_block", "crying_obsidian", "purple_stained_glass", "purple_wool"}};

    private static void wing(KBuild k, int xa, int xb, boolean ranks) {
        int T = TERRACE_Y, z1 = -48, z2 = -90, cz = (z1 + z2) / 2, wallH = 26;
        // shell: walls, floor with glass-covered lava channels, gabled vault
        for (int x = xa; x <= xb; x++)
            for (int z = z1; z >= z2; z--) {
                boolean edge = x <= xa + 1 || x >= xb - 1 || z >= z1 - 1 || z <= z2 + 1;
                for (int y = T; y <= T + wallH; y++) {
                    if (y == T) { k.set(x, y, z, floorWing(x, z, xa, xb)); continue; }
                    if (edge) k.set(x, y, z, (y - T) % 9 == 8 ? KBuild.TRIM : k.brick(x, y, z)); else k.set(x, y, z, "air");
                }
            }
        k.gableX(xa, xb, cz, (z1 - z2) / 2, T + wallH + 1, 0.8, KBuild.ROOF);
        // molten glass channels running the length of the floor
        for (int dz : new int[]{-11, 11}) for (int x = xa + 4; x <= xb - 4; x++) { k.lavaStrip(x, T, cz + dz); k.set(x, T - 1, cz + dz, "polished_blackstone"); k.late(x, T - 1, cz + dz, "lava"); k.set(x, T - 2, cz + dz, "magma_block"); k.set(x, T, cz + dz - 1, "gilded_blackstone"); k.set(x, T, cz + dz + 1, "gilded_blackstone"); }
        // pillars in two rows with gold bands, tall windows between them on both long walls
        for (int x = xa + 6; x <= xb - 6; x += 13)
            for (int dz : new int[]{-17, 17}) {
                int pz = cz + dz;
                for (int y = T + 1; y < T + wallH; y++) for (int dx = -1; dx <= 1; dx++) for (int dzz = -1; dzz <= 1; dzz++) k.set(x + dx, y, pz + dzz, y % 7 == 0 ? "gold_block" : (Math.abs(dx) + Math.abs(dzz) == 2 ? "polished_blackstone" : k.brick(x + dx, y, pz + dzz)));
                for (int dx = -2; dx <= 2; dx++) for (int dzz = -2; dzz <= 2; dzz++) { k.set(x + dx, T + 1, pz + dzz, "gilded_blackstone"); k.set(x + dx, T + wallH - 1, pz + dzz, "gilded_blackstone"); }
                k.late(x, T + 2, pz + (dz < 0 ? 2 : -2), "campfire[lit=true]");
            }
        for (int x = xa + 12; x <= xb - 12; x += 13) {
            k.window(x, T + 7, z1 - 1 + 1, 's', 3, 14);
            k.window(x, T + 7, z2 + 1 - 1, 'n', 3, 14);
        }
        // entrance on the courtyard side, with banners and braziers
        int door = (xa + xb) / 2;
        k.archX(door, T + 1, z1, 2, 11, 16, "air");
        for (int dy = 0; dy <= 17; dy++) { double half = dy <= 10 ? 6 : Math.max(0, 6 - (dy - 10)); for (int s : new int[]{-1, 1}) { k.set(door + (int) Math.round(s * (half + 1)), T + 1 + dy, z1, dy % 3 == 1 ? KBuild.GLOW : KBuild.GOLD); k.set(door + (int) Math.round(s * (half + 1)), T + 1 + dy, z1 - 1, KBuild.GOLD); } }
        k.lettersX(ranks ? "ASCENSION" : "CHAMPIONS", door, T + wallH - 8, z1 + 1, 1, 1, "gold_block", "polished_blackstone");
        for (int s : new int[]{-1, 1}) { k.brazier(door + s * 12, T, z1 + 4); k.banner(door + s * 20, T + wallH - 4, z1 + 1, 's', 2, 16); }
        // statues at both ends of the hall, facing in
        k.statue(xa + 5, T, cz, 'e', "iron_block", "black_wool");
        k.statue(xb - 5, T, cz, 'w', "iron_block", "black_wool");
        // spires at the four corners and on the ridge
        k.spireTower(xa, z1, T, 6, wallH + 8, 24, false);
        k.spireTower(xb, z1, T, 6, wallH + 8, 24, false);
        k.spireTower(xa, z2, T, 6, wallH + 12, 28, true);
        k.spireTower(xb, z2, T, 6, wallH + 12, 28, true);
        if (ranks) rankDisplays(k, cz); else boards(k, cz);
    }

    private static String floorWing(int x, int z, int xa, int xb) {
        int cz = (-48 + -90) / 2;
        if (Math.abs(z - cz) <= 3) return ((x + z) & 1) == 0 ? "red_nether_bricks" : "polished_blackstone";
        if (Math.abs(z - cz) == 4) return "gilded_blackstone";
        return ((Math.floorDiv(x, 3) + Math.floorDiv(z, 3)) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles";
    }

    // ------------------------------------------------------------------ the five rank displays, each grander than the last

    private static void rankDisplays(KBuild k, int cz) {
        int T = TERRACE_Y;
        for (int i = 0; i < RANKS.size(); i++) {
            Spot s = RANKS.get(i);
            String[] look = RANK_LOOK[i];
            int r = 3 + i, plinthH = 1 + i;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > r + 0.3) continue;
                for (int y = 1; y <= plinthH; y++) k.set(s.x() + dx, T + y, s.z() + dz, y == plinthH ? (d > r - 1 ? look[1] : look[0]) : "polished_blackstone");
            }
            // the rank's knight in its metal, set on the plinth
            k.statue(s.x(), T + plinthH, s.z(), 's', look[0], look[3]);
            // pillars of the rank's metal framing it; more of them, and lava and fire, the higher the rank
            for (int p = 0; p <= i; p++) {
                double a = (p + 0.5) * 2 * Math.PI / (i + 1) + 0.7;
                int px = s.x() + (int) Math.round(Math.cos(a) * (r + 2)), pz = s.z() + (int) Math.round(Math.sin(a) * (r + 2));
                int ph = 8 + i * 2;
                for (int y = 1; y <= ph; y++) k.set(px, T + y, pz, y % 4 == 0 ? look[1] : look[0]);
                if (i >= 2) k.late(px, T + ph + 1, pz, "campfire[lit=true]"); else k.set(px, T + ph + 1, pz, GLOWBLOCK());
            }
            if (i >= 3) {   // diamond and netherite: a ring of molten glass round the plinth
                for (int dx = -r - 2; dx <= r + 2; dx++) for (int dz = -r - 2; dz <= r + 2; dz++) { double d = Math.hypot(dx, dz); if (d > r + 0.6 && d <= r + 1.6) { k.lavaStrip(s.x() + dx, T, s.z() + dz); k.set(s.x() + dx, T - 1, s.z() + dz, "polished_blackstone"); k.late(s.x() + dx, T - 1, s.z() + dz, "lava"); } }
            }
            if (i == 4) {   // the crown: a netherite arch and a column of light behind the netherite knight
                for (int y = 1; y <= 22; y++) for (int dx : new int[]{-8, 8}) k.set(s.x() + dx, T + y, s.z() - 4, y % 5 == 0 ? "gold_block" : "netherite_block");
                for (int dx = -8; dx <= 8; dx++) { k.set(s.x() + dx, T + 23, s.z() - 4, "gold_block"); k.set(s.x() + dx, T + 24, s.z() - 4, "netherite_block"); }
                for (int y = 12; y <= 22; y++) for (int dx = -7; dx <= 7; dx++) if (Math.abs(dx) <= 7 - Math.max(0, y - 18)) k.set(s.x() + dx, T + y, s.z() - 4, "purple_stained_glass");
            }
            // the rank's banner and a plaque
            k.banner(s.x(), T + 22, cz - 19, 's', 2, 14 + i);
            String name = new String[]{"COPPER", "IRON", "GOLD", "DIAMOND", "NETHERITE"}[i];
            k.lettersX(name, s.x(), T + 2, cz + 20 + 0, 1, -1, look[0], "polished_blackstone");
        }
    }

    private static String GLOWBLOCK() { return KBuild.GLOW; }

    // ------------------------------------------------------------------ the five leaderboards

    private static void boards(KBuild k, int cz) {
        int T = TERRACE_Y;
        String[] names = {"RICHEST", "MINERS", "SLAYERS", "VETERANS", "FALLEN"};
        for (int i = 0; i < BOARDS.size(); i++) {
            Spot s = BOARDS.get(i);
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) { double d = Math.hypot(dx, dz); if (d > 4.3) continue; k.set(s.x() + dx, T + 1, s.z() + dz, d > 3.2 ? "gilded_blackstone" : "polished_blackstone"); k.set(s.x() + dx, T + 2, s.z() + dz, d > 3.2 ? "gold_block" : "polished_blackstone"); }
            // a trophy column with a gold cup, the board floats above it (hologram)
            for (int y = 3; y <= 6; y++) k.set(s.x(), T + y, s.z(), "chiseled_polished_blackstone");
            k.set(s.x(), T + 7, s.z(), "gold_block");
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (Math.abs(dx) + Math.abs(dz) == 1) k.set(s.x() + dx, T + 8, s.z() + dz, "gold_block");
            k.set(s.x(), T + 9, s.z(), "gold_block");
            k.late(s.x(), T + 10, s.z(), "end_rod");
            for (int dz : new int[]{-4, 4}) { k.set(s.x() - 4, T + 3, s.z() + dz, "polished_blackstone_brick_wall"); k.late(s.x() - 4, T + 4, s.z() + dz, "soul_lantern"); }
            k.lettersX(names[i], s.x(), T + 3, cz + 20, 1, -1, "gold_block", "polished_blackstone");
        }
    }
}
