package net.alloymc.core.arcade.games.tycoon;

import net.alloymc.core.arcade.core.*;
import org.bukkit.Material;

import java.util.*;

/**
 * Forge Tycoon: an original BlackForge property board game for 2-6 players. Roll the Forge Die round a ring of
 * forge districts, buy streets or send them to the Auction Anvil, build Workshops, Foundries and Great Forges,
 * survive Forge Events (dragon raids!) and bankrupt your friends. Rules: {@link TycoonEngine}; the table: {@link TycoonMatch}.
 */
public final class ForgeTycoon extends ArcadeGame {
    public ForgeTycoon() {
        super("tycoon", "Forge Tycoon", "<#ffb52e>", Material.GOLD_INGOT, Category.CASUAL,
                "A property board game: roll the Forge Die, buy up the forge districts, build Great Forges and bankrupt everyone else.");
        variants.add(Variant.ffa("classic", "Classic", 2, 6));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().list("spawns", 6).point("center"); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 1500);          // 25 minutes, then the richest player wins
        m.put("roll-seconds", 25);
        m.put("buy-seconds", 20);
        m.put("manage-seconds", 30);
        m.put("auction-seconds", 8);
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("Right-click the Forge Die on your turn. A 10 rolls again.",
                "Buy what you land on, or everyone bids on it at the Auction Anvil.",
                "Own a whole district to build Workshop, Foundry and Great Forge - rent soars.",
                "Pass the Forge Heart for wages; land on it exactly to scoop the Forge Pool.",
                "Last player solvent wins - or the richest when the 25-minute clock runs out.");
    }

    @Override public int queueCountdown() { return 20; }

    @Override public Match newMatch(MatchContext c) { return new TycoonMatch(c); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(
                board("ember_ring", "Ember Ring", "Twenty-four tiles round a molten forge.", 4401, new Ring(22, 29),
                        "polished_blackstone_bricks", "gold_block", "polished_blackstone_wall", "shroomlight", "gilded_blackstone", "soul_lantern"),
                board("obsidian_crown", "Obsidian Crown", "A wider ring of longer tiles round a cold obsidian plaza lit by soul fire.", 4402, new Ring(26, 35),
                        "obsidian", "crying_obsidian", "deepslate_tile_wall", "soul_lantern", "polished_deepslate", "amethyst_cluster"));
    }

    // ------------------------------------------------------------------ board geometry (shared with the match)

    static final int TILES = 24;
    static final double STEP = Math.PI * 2 / TILES, START = -Math.PI / 2;   // tile 0 (the Forge Heart) is north

    /** Angle of the middle of tile i. Tiles run clockwise seen from above. */
    static double angle(int i) { return START + i * STEP; }

    /** The ring of tiles: wedge-shaped tiles between two radii (each map picks its own, in "ring-inner"/"ring-outer"). */
    record Ring(int inner, int outer) {
        /** Which tile a column belongs to, or -1 for the paths between tiles and everything off the ring. */
        int tileAt(int x, int z) {
            double r = Math.hypot(x + 0.5, z + 0.5);
            if (r < inner - 0.5 || r >= outer + 0.5) return -1;
            double pos = (Math.atan2(z + 0.5, x + 0.5) - START) / STEP + 0.5;
            double frac = pos - Math.floor(pos);
            if (frac < 0.07 || frac > 0.93) return -1;
            return Math.floorMod((int) Math.floor(pos), TILES);
        }

        /** The rim of a tile, which shows the owner's colour. */
        boolean border(int x, int z) {
            double r = Math.hypot(x + 0.5, z + 0.5);
            double pos = (Math.atan2(z + 0.5, x + 0.5) - START) / STEP + 0.5;
            double frac = pos - Math.floor(pos);
            return frac < 0.17 || frac > 0.83 || r < inner + 0.6 || r > outer - 0.4;
        }

        /** Where a player's piece stands on tile i (the inner end), facing the middle of the board. */
        Pt stand(int i, int slot) {
            double a = angle(i) + (slot - 2.5) * 1.0 / (inner + 1.6), r = inner + 1.6;   // a block apart
            return Pt.of(Math.cos(a) * r, 1, Math.sin(a) * r).facing(0, 0);
        }

        /** Block column at the outer end of tile i where its buildings go up. */
        int[] lot(int i) { double a = angle(i), r = outer - 2.6; return new int[]{(int) Math.floor(Math.cos(a) * r), (int) Math.floor(Math.sin(a) * r)}; }
    }

    // ------------------------------------------------------------------ the map

    private static BuiltMap board(String id, String display, String desc, long seed, Ring ring,
                                  String floor, String accent, String wall, String light, String dais, String lamp) {
        MapKit k = new MapKit(seed);
        TycoonEngine rules = new TycoonEngine(new Random(0), s -> {}, t -> {});
        int INNER = ring.inner(), OUTER = ring.outer(), e = OUTER + 6;
        for (int x = -e; x <= e; x++)
            for (int z = -e; z <= e; z++) {
                double r = Math.hypot(x + 0.5, z + 0.5);
                int t = ring.tileAt(x, z);
                if (t >= 0) {
                    TycoonEngine.Tile tile = rules.tile(t);
                    String pad = switch (tile.type) {
                        case HEART -> "gold_block";
                        case SMELTER -> "orange_glazed_terracotta";
                        case EVENT -> "purple_glazed_terracotta";
                        default -> tile.district.block();
                    };
                    k.set(x, 0, z, ring.border(x, z) ? "polished_blackstone" : pad);
                    for (int y = -1; y >= -3; y--) k.set(x, y, z, "blackstone");
                } else if (r < OUTER + 0.5) {
                    // paths between tiles and the floor of the middle
                    String f = r < 8 ? (r < 5 ? dais : floor)
                            : Math.abs(r - 15) < 0.6 || Math.abs(r - INNER + 1.2) < 0.5 ? accent : r < INNER - 0.5 ? floor : "blackstone";
                    k.set(x, 0, z, f);
                    for (int y = -1; y >= -3 - (int) Math.max(0, (OUTER - r) / 3); y--) k.set(x, y, z, "blackstone");
                } else if (r < OUTER + 2.5) {
                    // a low rim so nobody wanders off the board, with braziers
                    k.set(x, 0, z, "polished_blackstone");
                    k.set(x, 1, z, wall);
                    for (int y = -1; y >= -3; y--) k.set(x, y, z, "blackstone");
                }
            }
        // braziers on the rim between every tile
        for (int i = 0; i < TILES; i++) {
            double a = angle(i) + STEP / 2, r = OUTER + 1.5;
            int bx = (int) Math.floor(Math.cos(a) * r), bz = (int) Math.floor(Math.sin(a) * r);
            k.set(bx, 1, bz, "polished_blackstone_bricks");
            k.set(bx, 2, bz, "polished_blackstone_bricks");
            k.set(bx, 3, bz, light);
        }
        // the centrepiece: an anvil on a stepped forge with soul lanterns
        k.disc(0, 1, 0, 4.5, floor);
        k.disc(0, 2, 0, 2.5, dais);
        k.set(0, 3, 0, "anvil");
        for (int[] c : new int[][]{{3, 3}, {-3, 3}, {3, -3}, {-3, -3}}) { k.set(c[0], 2, c[1], "blast_furnace"); k.set(c[0], 3, c[1], lamp); }
        // Smelters and Forge Event tiles get a marker at the outer end
        for (int i = 0; i < TILES; i++) {
            int[] l = ring.lot(i);
            switch (rules.tile(i).type) {
                case SMELTER -> { k.set(l[0], 1, l[1], "blast_furnace"); k.set(l[0], 2, l[1], "campfire"); }
                case EVENT -> { k.set(l[0], 1, l[1], "crying_obsidian"); k.set(l[0], 2, l[1], "amethyst_cluster"); }
                case HEART -> { k.set(l[0], 1, l[1], "gold_block"); k.set(l[0], 2, l[1], "gold_block"); k.set(l[0], 3, l[1], "shroomlight"); }
                default -> {}
            }
        }
        MapDef d = new MapDef("tycoon", id);
        d.display = display;
        d.description = desc;
        d.minPlayers = 2; d.maxPlayers = 6;
        d.bounds = Box.of(-e - 2, -12, -e - 2, e + 2, 30, e + 2);
        d.voidY = -8;
        d.spectator = new Pt(0.5, 24, 0.5, 0, 70);
        d.point("center", Pt.of(0.5, 3, 0.5));
        d.set("ring-inner", INNER);
        d.set("ring-outer", OUTER);
        for (int s = 0; s < 6; s++) d.add("spawns", ring.stand(0, s));
        return new BuiltMap(d, k.c);
    }
}
