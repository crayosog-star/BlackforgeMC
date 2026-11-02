package net.alloymc.core.build;

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
                c = net.alloymc.core.survival.SkyHubBuilder.build();
                int Y = net.alloymc.core.survival.SkyHub.Y;
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
                c = net.alloymc.core.arcade.ArcadeHubBuilder.build();
                int Y = net.alloymc.core.arcade.ArcadeHubBuilder.Y;
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
            default -> throw new IllegalArgumentException("unknown design " + design);
        }
        WorldCam.World world = cam.fromCanvas(c);
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
