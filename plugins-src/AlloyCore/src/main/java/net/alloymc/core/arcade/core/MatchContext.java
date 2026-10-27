package net.alloymc.core.arcade.core;

import org.bukkit.World;

/** Everything a new Match needs: which game/variant/map, and where its private instance is. */
public record MatchContext(Arcade arcade, String id, ArcadeGame game, Variant variant, MapDef map, int slot,
                           World world, int ox, int oy, int oz, Settings settings) {}
