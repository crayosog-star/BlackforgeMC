package net.alloymc.core.arcade.core;

import net.alloymc.core.build.Canvas;

/** A shipped map: its definition plus the generated blocks (relative to the map origin). */
public record BuiltMap(MapDef def, Canvas canvas) {}
