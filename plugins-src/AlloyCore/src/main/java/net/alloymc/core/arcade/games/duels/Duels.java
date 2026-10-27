package net.alloymc.core.arcade.games.duels;

import net.alloymc.core.arcade.core.*;
import org.bukkit.Material;

import java.util.*;

/**
 * Duels: queue-matched 1v1 / 2v2 fights with fixed kits. Each kit has its own rules and its own arenas
 * (Sumo platforms, open arenas, BuildUHC terrain); ranked queues use Elo.
 */
public final class Duels extends ArcadeGame {
    static final String[] KITS = {"classic", "nodebuff", "sumo", "boxing", "builduhc", "combo"};
    static final String[] NAMES = {"Classic", "NoDebuff", "Sumo", "Boxing", "BuildUHC", "Combo"};

    public Duels() {
        super("duels", "Duels", "<#ffb347>", Material.GOLDEN_SWORD, Category.COMBAT,
                "1v1 and 2v2 fights with set kits: Classic, NoDebuff, Sumo, Boxing, BuildUHC and Combo. Ranked queues use Elo.");
        for (int i = 0; i < KITS.length; i++) variants.add(new Variant(KITS[i] + "_1v1", NAMES[i] + " 1v1", 2, 1, 2, 2, false, KITS[i]));
        variants.add(new Variant("classic_2v2", "Classic 2v2", 2, 2, 4, 4, false, "classic"));
        variants.add(new Variant("builduhc_2v2", "BuildUHC 2v2", 2, 2, 4, 4, false, "builduhc"));
        variants.add(new Variant("sumo_2v2", "Sumo 2v2", 2, 2, 4, 4, false, "sumo"));
        variants.add(new Variant("ranked_classic", "Ranked Classic", 2, 1, 2, 2, true, "classic"));
        variants.add(new Variant("ranked_nodebuff", "Ranked NoDebuff", 2, 1, 2, 2, true, "nodebuff"));
        variants.add(new Variant("ranked_sumo", "Ranked Sumo", 2, 1, 2, 2, true, "sumo"));
    }

    @Override public int queueCountdown() { return 3; }

    @Override public MapRequirements requirements() { return new MapRequirements().teamPoint("spawn"); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 300);
        m.put("rounds.classic", 1); m.put("rounds.nodebuff", 1); m.put("rounds.sumo", 3); m.put("rounds.boxing", 1); m.put("rounds.builduhc", 1); m.put("rounds.combo", 1);
        m.put("boxing-hits", 100);
        m.put("ranked-enabled", true);
        m.put("kit.classic", List.of("IRON_SWORD 1", "BOW 1", "ARROW 16", "FISHING_ROD 1", "GOLDEN_APPLE 3", "IRON_HELMET 1", "IRON_CHESTPLATE 1", "IRON_LEGGINGS 1", "IRON_BOOTS 1"));
        m.put("kit.nodebuff", List.of("DIAMOND_SWORD 1 sharpness:3 unbreaking:3", "ENDER_PEARL 16", "COOKED_BEEF 64", "POTION 1 speed", "POTION 1 fire",
                "DIAMOND_HELMET 1 protection:2 unbreaking:3", "DIAMOND_CHESTPLATE 1 protection:2 unbreaking:3", "DIAMOND_LEGGINGS 1 protection:2 unbreaking:3", "DIAMOND_BOOTS 1 protection:2 unbreaking:3 feather_falling:4",
                "SPLASH_POTION 32 heal"));
        m.put("kit.sumo", List.of());
        m.put("kit.boxing", List.of("DIAMOND_SWORD 1 sharpness:1"));
        m.put("kit.builduhc", List.of("DIAMOND_SWORD 1 sharpness:2", "FISHING_ROD 1", "BOW 1 power:1", "ARROW 24", "GOLDEN_APPLE 6", "COBBLESTONE 64", "OAK_PLANKS 64",
                "WATER_BUCKET 1", "LAVA_BUCKET 1", "DIAMOND_PICKAXE 1", "DIAMOND_AXE 1", "DIAMOND_HELMET 1 protection:2", "DIAMOND_CHESTPLATE 1 protection:2", "IRON_LEGGINGS 1 protection:2", "DIAMOND_BOOTS 1 protection:2"));
        m.put("kit.combo", List.of("DIAMOND_SWORD 1 sharpness:2", "GOLDEN_APPLE 32", "DIAMOND_HELMET 1 protection:3", "DIAMOND_CHESTPLATE 1 protection:3", "DIAMOND_LEGGINGS 1 protection:3", "DIAMOND_BOOTS 1 protection:3",
                "POTION 1 speed"));
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("Pick a kit and queue - you're matched with the next player. Sumo: knock them off. Boxing: first to 100 hits.",
                "Ranked queues change your Elo rating. After a fight, click [Rematch] to go again.");
    }

    @Override public Match newMatch(MatchContext c) { return new DuelMatch(c); }

    @Override public List<BuiltMap> builtInMaps() { return DuelMaps.all(); }
}
