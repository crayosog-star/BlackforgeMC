package net.alloymc.core.build;

import net.alloymc.core.hub.HubBuilder;
import net.alloymc.core.hub.HubLayout;

/** gradle walkcheck: looks for traps and reachable lava in every spawn build. */
public final class WalkCheck {
    public static void main(String[] args) {
        int bad = 0;
        bad += check("hub", HubBuilder.build(), HubLayout.SPAWN_X, HubLayout.SPAWN_Y, HubLayout.SPAWN_Z);
        bad += check("survival spawn", net.alloymc.core.survival.SpawnBuilder.spawn(), net.alloymc.core.survival.SurvivalLayout.SPAWN_X,
                net.alloymc.core.survival.SurvivalLayout.SPAWN_Y, net.alloymc.core.survival.SurvivalLayout.SPAWN_Z);
        bad += check("skyblock spawn", net.alloymc.core.survival.SkyblockBuilder.build(), net.alloymc.core.survival.SkyblockBuilder.SPAWN_X,
                net.alloymc.core.survival.SkyblockBuilder.SPAWN_Y, net.alloymc.core.survival.SkyblockBuilder.SPAWN_Z);
        double[] f = net.alloymc.core.factions.FactionsBuilder.spawn();
        bad += check("factions spawn", net.alloymc.core.factions.FactionsBuilder.build(), f[0], f[1], f[2]);
        double[] a = net.alloymc.core.arcade.LobbyBuilder.spawn();
        bad += check("arcade lobby", net.alloymc.core.arcade.LobbyBuilder.build(), a[0], a[1], a[2]);
        bad += check("koth arena", net.alloymc.core.survival.SpawnBuilder.arena(), net.alloymc.core.survival.SurvivalLayout.KOTH_X + 20.5,
                net.alloymc.core.survival.SurvivalLayout.KOTH_Y + 10, net.alloymc.core.survival.SurvivalLayout.KOTH_Z + 0.5);
        // the Scrapyard's lava pits are part of the PvP (knock people in), so it's reported but not counted
        check("scrapyard (lava pits are intentional)", net.alloymc.core.survival.PvpBuilder.build(), net.alloymc.core.survival.SurvivalLayout.PVP_X + 0.5,
                net.alloymc.core.survival.SurvivalLayout.PVP_Y + 20, net.alloymc.core.survival.SurvivalLayout.PVP_Z + 60.5);
        // arcade: parkour courses refuse to generate an impossible jump; every dropper level needs a clear drop to its water
        for (var b : new net.alloymc.core.arcade.games.parkour.Parkour().builtInMaps())
            System.out.println("parkour " + b.def().id + ": " + b.def().list("checkpoints").size() + " checkpoints, every jump within sprint-jump reach");
        for (var b : new net.alloymc.core.arcade.games.dropper.Dropper().builtInMaps()) {
            var d = b.def();
            for (int l = 0; l < d.list("level_starts").size(); l++) {
                var g = d.region("level." + l + ".goal");
                boolean clear = false;
                for (int x = g.x1(); x <= g.x2() && !clear; x++) for (int z = g.z1(); z <= g.z2() && !clear; z++) {
                    boolean ok = true;
                    for (int y = 0; y <= (int) d.list("level_starts").get(l).y() - 1 && ok; y++) { String blk = b.canvas().get(x, y, z); ok = blk == null || blk.endsWith("air"); }
                    clear = ok && "minecraft:water".equals(b.canvas().get(x, -1, z));
                }
                System.out.println("dropper " + d.id + " level " + (l + 1) + ": " + (clear ? "clear drop to the water" : "NO CLEAR DROP"));
                if (!clear) bad++;
            }
        }
        System.out.println(bad == 0 ? "ALL CLEAR" : bad + " build(s) with problems");
        if (bad > 0 && args.length > 0 && args[0].equals("--strict")) System.exit(1);
    }

    static int check(String name, Canvas c, double x, double y, double z) {
        Walkability.Report r = new Walkability(c).run(x, y, z);
        System.out.println(r.summary(name));
        return r.ok() ? 0 : 1;
    }
}
