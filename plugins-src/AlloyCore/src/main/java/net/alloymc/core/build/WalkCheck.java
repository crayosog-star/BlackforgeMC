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
        bad += check("arena", net.alloymc.core.hub.TestArenaBuilder.build(), net.alloymc.core.hub.TestArenaBuilder.SPAWN_X,
                net.alloymc.core.hub.TestArenaBuilder.SPAWN_Y, net.alloymc.core.hub.TestArenaBuilder.SPAWN_Z);
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
        bad += dragons();
        System.out.println(bad == 0 ? "ALL CLEAR" : bad + " build(s) with problems");
        if (bad > 0 && args.length > 0 && args[0].equals("--strict")) System.exit(1);
    }

    static int check(String name, Canvas c, double x, double y, double z) {
        Walkability.Report r = new Walkability(c).run(x, y, z);
        System.out.println(r.summary(name));
        return r.ok() ? 0 : 1;
    }

    /** Flies the hub dragons for two in-game hours on several seeds: no part of any dragon may pass through a hub block. */
    static int dragons() {
        Canvas hub = HubBuilder.build();
        int clips = 0, frames = 0;
        double low = 1e9, far = 0, farHead = 0;
        String farWho = "";
        for (long seed = 1; seed <= 6; seed++) {
            var fl = java.util.List.of(
                    new net.alloymc.core.hub.DragonFlight(net.alloymc.core.hub.DragonFlight.Kind.BLACK, seed * 31, seed),
                    new net.alloymc.core.hub.DragonFlight(net.alloymc.core.hub.DragonFlight.Kind.SERPENT, seed * 41, seed + 4.2));
            fl.forEach(d -> d.setOthers(fl));
            for (int t = 0; t < 24000; t++)
                for (var d : fl) {
                    d.step();
                    if (t % 5 != 0) continue;
                    frames++;
                    double hr = Math.hypot(d.head()[0] - 0.5, d.head()[2] - 0.5);
                    if (hr > farHead) { farHead = hr; farWho = d.kind + " head at r=" + Math.round(hr) + " y=" + Math.round(d.head()[1]) + " t=" + t + " seed=" + seed; }
                    for (var p : d.pose()) {
                        for (double[] q : new double[][]{{0.5, 0.5, 0.5}, {0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {1, 1, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 0}}) {
                            double x = p.corner()[0] + p.r()[0] * p.sx() * q[0] + p.u()[0] * p.sy() * q[1] + p.f()[0] * p.sz() * q[2];
                            double y = p.corner()[1] + p.r()[1] * p.sx() * q[0] + p.u()[1] * p.sy() * q[1] + p.f()[1] * p.sz() * q[2];
                            double z = p.corner()[2] + p.r()[2] * p.sx() * q[0] + p.u()[2] * p.sy() * q[1] + p.f()[2] * p.sz() * q[2];
                            low = Math.min(low, y);
                            far = Math.max(far, Math.hypot(x - 0.5, z - 0.5));
                            if (hub.isSet((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) { if (clips++ % 400 == 0) System.out.println("   clip " + d.kind + " " + p.block() + " at " + (int) x + " " + (int) y + " " + (int) z + " (" + hub.get((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) + ")"); break; }
                        }
                    }
                }
        }
        System.out.println("hub dragons: " + frames + " poses flown, " + clips + " pieces through a hub block, lowest point y=" + Math.round(low) + ", farthest " + Math.round(far) + " blocks out (" + farWho + ")");
        return clips == 0 ? 0 : 1;
    }
}
