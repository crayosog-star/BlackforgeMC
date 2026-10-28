package net.alloymc.core.arcade.games.skywars;

import net.alloymc.core.arcade.core.*;
import org.bukkit.Material;

import java.util.*;

/** SkyWars: everyone starts on their own sky island with loot chests; refills, a closing border, last one alive wins. */
public final class SkyWars extends ArcadeGame {
    public SkyWars() {
        super("skywars", "SkyWars", "<#7fd6ff>", Material.ENDER_EYE, Category.COMBAT,
                "Loot your island, bridge to the middle for better gear and knock everyone else into the void.");
        variants.add(Variant.ffa("solo", "Solo", 2, 12));
        variants.add(new Variant("doubles", "Doubles", 6, 2, 3, 12, false, ""));
        variants.add(Variant.ffa("ranked", "Ranked Solo", 2, 8).asRanked());
    }

    @Override public MapRequirements requirements() {
        return new MapRequirements().list("spawns", 8).list("island_chests", 8).list("center_chests", 2);
    }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 900);
        m.put("cage-seconds", 5);
        m.put("refill-at-seconds", List.of(180, 360));
        m.put("border-start-seconds", 120);
        m.put("border-end-seconds", 540);
        m.put("border-final-size", 12);
        m.put("island-chest-items", 6);
        m.put("center-chest-items", 8);
        m.put("loot.island", List.of(
            "STONE_SWORD 1 10", "IRON_SWORD 1 4", "STONE_AXE 1 6", "STONE_PICKAXE 1 8", "IRON_PICKAXE 1 4", "BOW 1 4", "ARROW 8 8",
            "OAK_PLANKS 32 14", "STONE 32 12", "COBBLESTONE 24 10", "IRON_HELMET 1 5", "IRON_CHESTPLATE 1 4", "IRON_LEGGINGS 1 4", "IRON_BOOTS 1 5",
            "CHAINMAIL_CHESTPLATE 1 6", "LEATHER_LEGGINGS 1 6", "GOLDEN_APPLE 1 5", "COOKED_BEEF 4 8", "SNOWBALL 8 6", "EGG 8 5",
            "WATER_BUCKET 1 4", "LAVA_BUCKET 1 3", "EXPERIENCE_BOTTLE 8 3", "FISHING_ROD 1 3"));
        m.put("loot.center", List.of(
            "DIAMOND_SWORD 1 6", "IRON_SWORD 1 8 sharpness:1", "DIAMOND_CHESTPLATE 1 5", "DIAMOND_HELMET 1 5", "DIAMOND_LEGGINGS 1 4", "DIAMOND_BOOTS 1 5",
            "IRON_CHESTPLATE 1 6 protection:2", "BOW 1 6 power:2", "ARROW 16 8", "ENDER_PEARL 2 6", "GOLDEN_APPLE 2 7", "ENCHANTED_GOLDEN_APPLE 1 1",
            "DIAMOND_PICKAXE 1 4 efficiency:2", "OBSIDIAN 4 3", "TNT 3 4", "FLINT_AND_STEEL 1 3", "OAK_PLANKS 64 8", "SPLASH_POTION 1 4 heal",
            "POTION 1 3 speed", "ENCHANTED_BOOK 1 3 sharpness:2", "LAVA_BUCKET 1 3"));
        m.put("kits", List.of("Builder: STONE_PICKAXE 1, OAK_PLANKS 32, WOODEN_AXE 1",
                              "Archer: BOW 1, ARROW 10, LEATHER_HELMET 1",
                              "Fighter: STONE_SWORD 1, LEATHER_CHESTPLATE 1, COOKED_BEEF 3",
                              "Miner: IRON_PICKAXE 1, COBBLESTONE 16, TORCH 4"));
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("Grab what you can from your island's chests, bridge to the centre for the best loot and eliminate everyone else.",
                "Chests refill twice. After 2 minutes a red border wall slowly closes in - stay inside it. You can craft from what you mine.");
    }

    @Override public Match newMatch(MatchContext c) { return new SkyWarsMatch(c); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(
            SkyWarsMaps.build("skyreach_ruins", "Skyreach Ruins", "Eight crumbling stone islands around an old temple.", 8, 56, SkyWarsMaps.RUINS),
            SkyWarsMaps.build("gilded_atoll", "Gilded Atoll", "Twelve lush islands circling a golden shrine.", 12, 80, SkyWarsMaps.GILDED),
            SkyWarsMaps.build("cinderfall", "Cinderfall", "Eight scorched islands over a burning sky.", 8, 54, SkyWarsMaps.CINDER));
    }
}
