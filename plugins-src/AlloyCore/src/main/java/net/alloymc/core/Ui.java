package net.alloymc.core;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.List;

/** Small UI helpers: MiniMessage text, named items, holograms and sidebars. */
public final class Ui {
    private Ui() {}
    private static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String TITLE = "<gradient:#ffb347:#ff5a1a><bold>BLACKFORGE</bold></gradient><gray><bold>MC</bold>";

    public static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        it.editMeta(meta -> {
            meta.displayName(mm(name));
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(mm(s));
            meta.lore(l);
            meta.addItemFlags(ItemFlag.values());
        });
        return it;
    }

    public static boolean named(ItemStack it, String plainNamePart) {
        if (it == null || !it.hasItemMeta() || it.getItemMeta().displayName() == null) return false;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(it.getItemMeta().displayName()).contains(plainNamePart);
    }

    public static TextDisplay hologram(Location at, String text, float scale) {
        return at.getWorld().spawn(at, TextDisplay.class, td -> {
            td.text(mm(text));
            td.setBillboard(Display.Billboard.CENTER);
            td.setShadowed(true);
            td.setPersistent(false);
            td.setLineWidth(240);
            td.setBackgroundColor(Color.fromARGB(0x70, 0x14, 0x10, 0x18));
            td.setViewRange(2.0f);
            td.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(), new org.joml.Quaternionf(), new org.joml.Vector3f(scale, scale, scale), new org.joml.Quaternionf()));
        });
    }

    /** A hologram that stays facing one way, like text painted on a board (yaw: -90 faces east, 90 faces west). */
    public static TextDisplay board(Location at, String text, float scale, float yaw) {
        TextDisplay td = hologram(at, text, scale);
        td.setBillboard(Display.Billboard.FIXED);
        td.setRotation(yaw, 0);
        td.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        td.setLineWidth(400);
        return td;
    }

    /** Per-player sidebar; lines are MiniMessage strings, top to bottom. */
    public static void sidebar(Player p, List<String> lines) {
        Scoreboard sb = p.getScoreboard();
        if (sb == Bukkit.getScoreboardManager().getMainScoreboard()) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            p.setScoreboard(sb);
        }
        Objective o = sb.getObjective("alloy");
        if (o == null) {
            o = sb.registerNewObjective("alloy", Criteria.DUMMY, mm(TITLE));
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
        }
        for (String entry : sb.getEntries())
            if (entry.startsWith("line") && Integer.parseInt(entry.substring(4)) >= lines.size()) sb.resetScores(entry);
        for (int i = 0; i < lines.size(); i++) {
            var score = o.getScore("line" + i);
            score.setScore(lines.size() - i);
            score.customName(mm(lines.get(i)));
        }
    }
}
