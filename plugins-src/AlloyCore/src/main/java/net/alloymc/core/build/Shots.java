package net.alloymc.core.build;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * Website screenshots of the live worlds with {@link WorldCam}:
 * gradle shots -Pjar=&lt;26.3 client jar&gt; [-Pshot=name] [-Pw=1920 -Ph=1080 -Pss=2]
 * Renders into plugins-src/AlloyCore/previews/live/. Each shot names its server, dimension, area to load and camera.
 */
public final class Shots {

    /** A still: server folder, dimension, the square to load (centre + radius), the look and the camera. */
    record Shot(String name, String server, String dim, int cx, int cz, int radius, WorldCam.Look look, WorldCam.Cam cam) {}

    static final File NET = new File("C:/MinecraftNetwork");

    static List<Shot> shots() {
        WorldCam.Look inferno = WorldCam.Look.inferno(), sunset = WorldCam.Look.sunset(), day = WorldCam.Look.day(), frozen = WorldCam.Look.frozenNight();
        return List.of(
                new Shot("hub_spawn", "hub", "overworld", 0, 0, 230, inferno, new WorldCam.Cam(-18, 110, 22, 0.5, 104, 0.5, 72)),
                new Shot("hub_aerial", "hub", "overworld", 0, 0, 230, inferno, new WorldCam.Cam(60, 150, 95, 0.5, 106, 0.5, 70)),
                new Shot("hub_castle", "hub", "overworld", 0, 0, 230, inferno, new WorldCam.Cam(0.5, 205, 235, 0.5, 112, 0, 72)),
                new Shot("survival_spawn", "survival", "overworld", 0, 0, 200, sunset, WorldCam.Cam.yawPitch(0.5, 152.62, 32.5, 180, -6, 75)),
                new Shot("survival_aerial", "survival", "overworld", 0, 0, 200, sunset, WorldCam.Cam.yawPitch(0.5, 200, 95.5, 180, 28, 70)),
                new Shot("survival_crates", "survival", "overworld", 0, 0, 200, day, WorldCam.Cam.yawPitch(0.5, 153.62, -19.5, 180, 4, 75)),
                new Shot("koth", "survival", "overworld", 0, -400, 140, sunset, new WorldCam.Cam(26, 166, -368, 0.5, 152, -400, 70)),
                new Shot("scrapyard", "survival", "overworld", 0, 600, 160, sunset, WorldCam.Cam.yawPitch(0.5, 215, 655.5, 180, 30, 72)),
                new Shot("scrapyard_ground", "survival", "overworld", 0, 600, 160, day, new WorldCam.Cam(32, 170, 556, 0, 166, 612, 75)),
                new Shot("skyblock", "skyblock", "overworld", 0, 0, 180, day, WorldCam.Cam.yawPitch(0.5, 200, 75.5, 180, 30, 72)),
                new Shot("skyblock_spawn", "skyblock", "overworld", 0, 0, 180, sunset, WorldCam.Cam.yawPitch(0.5, 152.62, 24.5, 180, -4, 75)),
                new Shot("factions", "factions", "overworld", 0, 0, 160, frozen, new WorldCam.Cam(62, 218, 84, 0.5, 192, 0, 70)),
                new Shot("arcade", "arcade", "overworld", 0, 0, 120, sunset, new WorldCam.Cam(-32, 116, 42, 0.5, 102, 0, 72)));
    }

    static WorldCam.World load(WorldCam cam, Shot s) throws Exception {
        File region = new File(NET, s.server + "/world/dimensions/minecraft/" + s.dim + "/region");
        return cam.load(region, s.cx - s.radius, s.cz - s.radius, s.cx + s.radius, s.cz + s.radius);
    }

    public static void main(String[] a) throws Exception {
        File jar = new File(a[0]);
        String only = a.length > 1 && !a[1].equals("all") ? a[1] : "";
        int w = a.length > 2 ? Integer.parseInt(a[2]) : 1920, h = a.length > 3 ? Integer.parseInt(a[3]) : 1080, ss = a.length > 4 ? Integer.parseInt(a[4]) : 2;
        File out = new File(a.length > 5 ? a[5] : "previews/live");
        out.mkdirs();
        WorldCam cam = new WorldCam(jar);
        String loadedKey = null;
        WorldCam.World world = null;
        for (Shot s : shots()) {
            if (!only.isEmpty() && !only.equals(s.name)) continue;
            String key = s.server + "/" + s.dim + "/" + s.cx + "," + s.cz + "," + s.radius;
            long t0 = System.currentTimeMillis();
            if (!key.equals(loadedKey)) { world = load(cam, s); loadedKey = key; }
            long t1 = System.currentTimeMillis();
            BufferedImage img = cam.render(world, s.look, s.cam, s.server.equals("hub") ? Trailer.dragons(600) : null, w, h, ss);
            ImageIO.write(img, "png", new File(out, s.name + ".png"));
            System.out.printf("%s: loaded %d chunks in %d ms, rendered in %d ms%n", s.name, world.blocks.size(), t1 - t0, System.currentTimeMillis() - t1);
        }
    }
}
