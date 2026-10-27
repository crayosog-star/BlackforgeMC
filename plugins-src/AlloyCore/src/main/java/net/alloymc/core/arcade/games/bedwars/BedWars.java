package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.*;
import org.bukkit.Material;

import java.util.*;

/**
 * BedWars: protect your bed, collect resources from your island generator, buy gear and team upgrades,
 * break the other beds and finish every other team. Solo, Doubles, 3v3v3v3 and 4v4v4v4.
 */
public final class BedWars extends ArcadeGame {
    public BedWars() {
        super("bedwars", "BedWars", "<#ff5555>", Material.RED_BED, Category.COMBAT,
                "Defend your bed, gather resources, buy gear and upgrades, then destroy every other team's bed.");
        variants.add(new Variant("solo", "Solo", 8, 1, 2, 8, false, ""));
        variants.add(new Variant("doubles", "Doubles", 8, 2, 3, 16, false, ""));
        variants.add(new Variant("3v3v3v3", "3v3v3v3", 4, 3, 4, 12, false, ""));
        variants.add(new Variant("4v4v4v4", "4v4v4v4", 4, 4, 5, 16, false, ""));
    }

    @Override public MapRequirements requirements() {
        return new MapRequirements().teamPoint("spawn", "bed", "generator", "shop", "upgrades").list("diamond_generators", 1).list("emerald_generators", 1);
    }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 2400);
        m.put("respawn-seconds", 5);
        m.put("iron-interval-ticks", 20);
        m.put("gold-interval-ticks", 140);
        m.put("diamond-interval-seconds", 30);
        m.put("emerald-interval-seconds", 60);
        m.put("generator-cap", 48);
        m.put("beds-destroyed-at-seconds", 1800);
        m.put("upgrade-sharpness-cost", 4);
        m.put("upgrade-protection-costs", List.of(2, 4, 8, 16));
        m.put("upgrade-haste-costs", List.of(2, 4));
        m.put("upgrade-forge-costs", List.of(2, 4, 6));
        m.put("upgrade-healpool-cost", 1);
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("Your island generator makes iron and gold; diamonds and emeralds spawn on the outer and middle islands.",
                "Spend them at the Item Shop and Team Upgrades villagers. While your bed stands you respawn - once it's broken, death is final.");
    }

    @Override public Match newMatch(MatchContext c) { return new BedWarsMatch(c); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(
            BedWarsMaps.build("forgeworks", "Forgeworks", "Copper foundries floating over the void.", 4, 34, BedWarsMaps.FORGE),
            BedWarsMaps.build("verdant_isles", "Verdant Isles", "Eight overgrown islands around an emerald shrine.", 8, 46, BedWarsMaps.VERDANT),
            BedWarsMaps.build("frostspire", "Frostspire", "Icy outposts around a frozen spire.", 4, 38, BedWarsMaps.FROST));
    }
}
