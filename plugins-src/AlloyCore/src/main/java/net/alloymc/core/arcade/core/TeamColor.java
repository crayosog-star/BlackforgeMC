package net.alloymc.core.arcade.core;

import org.bukkit.Color;
import org.bukkit.Material;

/** Team colours, in the order teams are assigned on a map (team.0 = RED, team.1 = BLUE ...). */
public enum TeamColor {
    RED("Red", "<#ff5555>", "red", Color.fromRGB(0xd63a3a)),
    BLUE("Blue", "<#5f9dff>", "blue", Color.fromRGB(0x3a62d6)),
    GREEN("Green", "<#55e06a>", "lime", Color.fromRGB(0x4cc23a)),
    YELLOW("Yellow", "<#ffe066>", "yellow", Color.fromRGB(0xe6c629)),
    AQUA("Aqua", "<#4fe3e3>", "cyan", Color.fromRGB(0x2ab3b3)),
    WHITE("White", "<#f2f2f2>", "white", Color.fromRGB(0xe8e8e8)),
    PINK("Pink", "<#ff8ad8>", "pink", Color.fromRGB(0xe87ac0)),
    GRAY("Gray", "<#a0a0a0>", "gray", Color.fromRGB(0x707070));

    public final String label, mm, dye;
    public final Color rgb;

    TeamColor(String label, String mm, String dye, Color rgb) { this.label = label; this.mm = mm; this.dye = dye; this.rgb = rgb; }

    public Material wool() { return Material.valueOf(dye.toUpperCase() + "_WOOL"); }
    public Material concrete() { return Material.valueOf(dye.toUpperCase() + "_CONCRETE"); }
    public Material glass() { return Material.valueOf(dye.toUpperCase() + "_STAINED_GLASS"); }
    public Material terracotta() { return Material.valueOf(dye.toUpperCase() + "_TERRACOTTA"); }
    public Material bed() { return Material.valueOf(dye.toUpperCase() + "_BED"); }
    public String block(String kind) { return "minecraft:" + dye + "_" + kind; }

    public static TeamColor of(int index) { return values()[index % values().length]; }
}
