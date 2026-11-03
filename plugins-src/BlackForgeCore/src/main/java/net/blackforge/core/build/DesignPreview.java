package net.blackforge.core.build;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;

/**
 * Renders a generated build before it goes in game (real textures, see {@link WorldCam}).
 * java ... DesignPreview <client jar> <design> <out dir> [w h ss]   designs: skyhub
 */
public final class DesignPreview {
    record View(String name, WorldCam.Cam cam) {}

    public static void main(String[] a) throws Exception {
        WorldCam cam = new WorldCam(new File(a[0]));
        String design = a[1];
        File out = new File(a[2]);
        out.mkdirs();
        int w = a.length > 3 ? Integer.parseInt(a[3]) : 1280, h = a.length > 4 ? Integer.parseInt(a[4]) : 720, ss = a.length > 5 ? Integer.parseInt(a[5]) : 1;
        Canvas c;
        WorldCam.Look look;
        List<View> views;
        switch (design) {
            case "skyhub" -> {
                c = net.blackforge.core.survival.SkyHubBuilder.build();
                int Y = net.blackforge.core.survival.SkyHub.Y;
                look = WorldCam.Look.day();
                look.sunX = -0.5; look.sunY = 0.55; look.sunZ = 0.6;
                look.skyTop = 0x5E92E8; look.skyHorizon = 0xD8E8FA; look.fogColor = 0xD8E8FA;
                views = List.of(
                        new View("spawn", new WorldCam.Cam(0.5, Y + 2.62, 27.5, 0.5, Y + 8, -32, 75)),
                        new View("arch", new WorldCam.Cam(0.5, Y + 4, -2, 0.5, Y + 12, -34, 72)),
                        new View("aerial", new WorldCam.Cam(0.5, Y + 70, 110, 0.5, Y, -5, 70)),
                        new View("overview", new WorldCam.Cam(0.5, Y + 120, 170, 0.5, Y, 0, 75)),
                        new View("plaza", new WorldCam.Cam(-22, Y + 12, 34, 0, Y, 8, 72)),
                        new View("vanilla", new WorldCam.Cam(-60, Y + 14, 22, -105, Y + 3, -2, 72)),
                        new View("oneblock", new WorldCam.Cam(60, Y + 14, 22, 105, Y + 8, -2, 72)),
                        new View("oneblock_portal", new WorldCam.Cam(100, Y + 4, 6, 120, Y + 4, 0, 72)));
            }
            case "arcade" -> {
                c = net.blackforge.core.arcade.ArcadeHubBuilder.build();
                int Y = net.blackforge.core.arcade.ArcadeHubBuilder.Y;
                look = WorldCam.Look.neonNight();
                views = List.of(
                        new View("spawn", new WorldCam.Cam(0.5, Y + 2.62, 33.5, 0.5, Y + 12, -10, 78)),
                        new View("aerial", new WorldCam.Cam(0.5, Y + 95, 165, 0.5, Y, -10, 72)),
                        new View("overview", new WorldCam.Cam(0.5, Y + 170, 60, 0.5, Y, -5, 80)),
                        new View("pvp", new WorldCam.Cam(52, Y + 16, -20, 78, Y + 6, -52, 72)),
                        new View("competitive", new WorldCam.Cam(0.5, Y + 18, -66, 0.5, Y + 12, -110, 72)),
                        new View("parkour", new WorldCam.Cam(66, Y + 22, 18, 96, Y + 26, 30, 72)),
                        new View("casual", new WorldCam.Cam(-66, Y + 14, 18, -96, Y + 4, 30, 72)),
                        new View("party", new WorldCam.Cam(-52, Y + 16, -20, -78, Y + 12, -40, 72)));
            }
            case "spawnupgrade" -> {
                c = net.blackforge.core.survival.SpawnUpgradeBuilder.phase1();
                int Y = net.blackforge.core.survival.SurvivalLayout.Y;
                look = WorldCam.Look.sunset();
                look.fogStart = 220; look.fogEnd = 520; look.maxDist = 520;
                views = List.of(
                        new View("spawn", new WorldCam.Cam(0.5, Y + 2.62, 32.5, 0.5, Y + 30, -60, 80)),
                        new View("plaza", new WorldCam.Cam(0.5, Y + 2.62, 14, 0.5, Y + 24, -2, 80)),
                        new View("aerial", new WorldCam.Cam(0.5, Y + 80, 150, 0.5, Y + 10, -40, 75)),
                        new View("overview", new WorldCam.Cam(160, Y + 140, 220, 0, Y + 10, -30, 75)),
                        new View("forge", new WorldCam.Cam(14, Y + 30, 22, 0.5, Y + 28, 0, 70)),
                        new View("tower", new WorldCam.Cam(40, Y + 6, 40, 70, Y + 14, 70, 75)));
            }
            case "spawnupgrade2" -> {
                c = net.blackforge.core.survival.SpawnUpgradeBuilder.phase2();
                int Y = net.blackforge.core.survival.SurvivalLayout.Y;
                look = WorldCam.Look.sunset();
                look.fogStart = 220; look.fogEnd = 520; look.maxDist = 520;
                views = List.of(
                        new View("overview", new WorldCam.Cam(260, Y + 150, 260, 0, Y + 5, 0, 75)),
                        new View("smithy", new WorldCam.Cam(70, Y + 18, 40, 111, Y + 6, 0, 72)),
                        new View("market", new WorldCam.Cam(-40, Y + 18, 70, 0, Y + 6, 111, 72)),
                        new View("wilds", new WorldCam.Cam(-70, Y + 18, -40, -111, Y + 8, 0, 72)),
                        new View("hall", new WorldCam.Cam(40, Y + 18, -70, 0, Y + 8, -111, 72)),
                        new View("smithy_aerial", new WorldCam.Cam(92, Y + 40, 40, 112, Y + 4, -2, 70)),
                        new View("hall_aerial", new WorldCam.Cam(40, Y + 40, -92, 2, Y + 4, -112, 70)),
                        new View("gate_aerial", new WorldCam.Cam(-92, Y + 40, 40, -112, Y + 4, -2, 70)),
                        new View("market_aerial", new WorldCam.Cam(40, Y + 40, 92, 2, Y + 4, 112, 70)),
                        new View("hall_inside", new WorldCam.Cam(0.5, Y + 3, -106, 0.5, Y + 4, -118, 80)));
            }
            case "spawnupgrade3" -> {
                c = net.blackforge.core.survival.SpawnUpgradeBuilder.phase3();
                int Y = net.blackforge.core.survival.SurvivalLayout.Y;
                look = WorldCam.Look.sunset();
                look.fogStart = 220; look.fogEnd = 520; look.maxDist = 520;
                views = List.of(
                        new View("trading_aerial", new WorldCam.Cam(10, Y + 38, 80, 33, Y + 4, 101, 70)),
                        new View("trading_inside", new WorldCam.Cam(33.5, Y + 3, 96.5, 33.5, Y + 3, 106, 80)),
                        new View("fame_aerial", new WorldCam.Cam(8, Y + 38, -84, 33, Y + 6, -102, 70)),
                        new View("drop_aerial", new WorldCam.Cam(-84, Y + 36, 8, -103, Y + 4, 31, 70)),
                        new View("overview", new WorldCam.Cam(150, Y + 120, 150, 0, Y + 5, 0, 75)));
            }
            case "coliseum" -> {
                c = net.blackforge.core.survival.ColiseumBuilder.build();
                int Y = net.blackforge.core.survival.SurvivalLayout.Y, CZ = net.blackforge.core.survival.ColiseumBuilder.CZ;
                look = WorldCam.Look.sunset();
                look.fogStart = 300; look.fogEnd = 700; look.maxDist = 700;
                views = List.of(
                        new View("front", new WorldCam.Cam(0.5, Y + 10, CZ - 120, 0.5, Y + 24, CZ - 40, 70)),
                        new View("front_low", new WorldCam.Cam(0.5, Y + 4, CZ - 92, 0.5, Y + 26, CZ - 46, 72)),
                        new View("aerial", new WorldCam.Cam(90, Y + 80, CZ - 130, 0.5, Y + 14, CZ - 10, 70)),
                        new View("side", new WorldCam.Cam(-120, Y + 40, CZ - 60, 0.5, Y + 20, CZ - 20, 70)),
                        new View("pit", new WorldCam.Cam(0.5, Y + 28, CZ - 36, 0.5, Y + 3, CZ + 8, 80)),
                        new View("throne", new WorldCam.Cam(0.5, Y + 22, CZ + 4, 0.5, Y + 26, CZ + 39, 75)));
            }
            default -> throw new IllegalArgumentException("unknown design " + design);
        }
        WorldCam.World world;
        if (design.startsWith("spawnupgrade")) {   // the live Survival world, with phase 1 laid over it (only into air)
            world = cam.load(new File(Shots.NET, "survival/world/dimensions/minecraft/overworld/region"), -200, -200, 200, 200);
            System.out.println("the upgrade would place " + cam.overlay(world, c) + " blocks (all into air)");
        } else world = cam.fromCanvas(c);
        System.out.println(design + ": " + c.size() + " blocks");
        String only = System.getenv("VIEW");
        for (View v : views) {
            if (only != null && !only.equals(v.name)) continue;
            long t0 = System.currentTimeMillis();
            ImageIO.write(cam.render(world, look, v.cam, null, w, h, ss), "png", new File(out, design + "_" + v.name + ".png"));
            System.out.println("  " + v.name + " " + (System.currentTimeMillis() - t0) + " ms");
        }
    }
}
