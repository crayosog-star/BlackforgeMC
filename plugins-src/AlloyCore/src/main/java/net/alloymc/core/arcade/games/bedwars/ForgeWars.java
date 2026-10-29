package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.*;
import org.bukkit.Material;

import java.util.*;

/**
 * Forge Wars: BlackForgeMC's own BedWars. Standard BedWars rules (it runs on {@link BedWarsMatch}) plus an Arsenal
 * villager on every island that sells the BlackForgeMC ability items, on new maps with an objective plaza in the centre.
 * Players reach it from the hub's server menu; it runs on the Arcade server like every other mode.
 */
public final class ForgeWars extends ArcadeGame {
    public static final String ID = "forgewars";

    public ForgeWars() {
        super(ID, "Forge Wars", "<#ff6a00>", Material.RESPAWN_ANCHOR, Category.COMBAT,
                "BedWars with BlackForgeMC abilities: buy them from your island's Arsenal.");
        variants.add(new Variant("solo", "Solo", 8, 1, 2, 8, false, ""));
        variants.add(new Variant("doubles", "Doubles", 8, 2, 3, 16, false, ""));
        variants.add(new Variant("squads", "4v4v4v4", 4, 4, 4, 16, false, ""));
    }

    @Override public MapRequirements requirements() {
        return new MapRequirements().teamPoint("spawn", "bed", "generator", "shop", "upgrades", "arsenal")
                .point("core").list("diamond_generators", 1).list("emerald_generators", 1);
    }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new BedWars().defaultSettings();
        // Arsenal: what each ability costs and how many a player may carry at once
        m.put("arsenal-carry-limit", 2);
        for (ForgeArsenal.Price p : ForgeArsenal.PRICES) m.put("arsenal-price-" + p.id(), p.amount());
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("BedWars rules: iron and gold from your forge, diamonds on the outer islands, emeralds in the Core.",
                "Buy BlackForgeMC abilities from the Arsenal on your island. You can carry two at a time and lose them when you die.");
    }

    @Override public Match newMatch(MatchContext c) { return new BedWarsMatch(c, true); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(
            ForgeWarsMaps.build("molten_crown", "Molten Crown", "Four blackstone forts round a glowing Core.", 4, 64, ForgeWarsMaps.MOLTEN, "squads"),
            ForgeWarsMaps.build("emberfall", "Emberfall", "Nether-brick keeps over a red Core.", 4, 70, ForgeWarsMaps.EMBER, "squads"),
            ForgeWarsMaps.build("skyforge", "Skyforge", "Eight copper-and-tuff foundries round the Core.", 8, 90, ForgeWarsMaps.SKYFORGE, "solo", "doubles"));
    }
}
