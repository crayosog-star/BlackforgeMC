package net.alloymc.core.build;

import net.alloymc.core.arcade.core.ArcadeGame;
import net.alloymc.core.arcade.core.BuiltMap;

import java.io.File;
import java.util.List;

/** Renders every built-in arcade map (isometric) so maps can be checked without a server: gradle arcadePreview. */
public final class ArcadePreview {
    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "previews/arcade");
        out.mkdirs();
        String only = args.length > 1 && !args[1].isEmpty() ? args[1] : null;
        if ("factions".equals(only)) {
            Canvas f = net.alloymc.core.factions.FactionsBuilder.build();
            System.out.println("factions spawn: " + f.size() + " blocks");
            PreviewMain.render(f, new File(out, "factions_spawn.png"), 0, 5);
            double[] s = net.alloymc.core.factions.FactionsBuilder.spawn();
            PreviewMain.pov(f, new File(out, "factions_spawn_view.png"), s[0], s[1] + 1.62, s[2], 180, -18, 1280, 720);
            PreviewMain.pov(f, new File(out, "factions_statues_side.png"), 0.5, s[1] + 10, 40.5, 180, -12, 1280, 720);
            PreviewMain.pov(f, new File(out, "factions_statues_front.png"), 30.5, s[1] + 14, 0.5, 90, -18, 1280, 720);
            return;
        }
        if (only == null || only.equals("lobby")) {
            Canvas lobby = net.alloymc.core.arcade.LobbyBuilder.build();
            System.out.println("arcade lobby: " + lobby.size() + " blocks");
            PreviewMain.render(lobby, new File(out, "lobby.png"), 0, 7);
            double[] s = net.alloymc.core.arcade.LobbyBuilder.spawn();
            PreviewMain.pov(lobby, new File(out, "lobby_spawn_view.png"), s[0], s[1] + 1.62, s[2], 180, -4, 1280, 720);
            if ("lobby".equals(only)) return;
        }
        for (ArcadeGame g : net.alloymc.core.arcade.Games.all()) {
            if (only != null && !g.id.equals(only)) continue;
            List<BuiltMap> maps = g.builtInMaps();
            for (BuiltMap b : maps) {
                System.out.println(g.id + "/" + b.def().id + ": " + b.canvas().size() + " blocks, problems " + b.def().validate(g.requirements()));
                PreviewMain.render(b.canvas(), new File(out, g.id + "_" + b.def().id + ".png"), 0, 6);
            }
        }
    }
}
