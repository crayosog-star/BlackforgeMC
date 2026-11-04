package net.blackforge.core.build;

import net.blackforge.core.survival.InfernalKingdom;
import net.blackforge.core.survival.InfernalLayout;

import java.io.File;

/** Offline renders of the Infernal Kingdom (staging): gradle kingdom. */
public final class KingdomPreview {
    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "previews");
        out.mkdirs();
        PreviewMain.night = true;
        long t = System.currentTimeMillis();
        Canvas k = InfernalKingdom.build();
        System.out.println("kingdom blocks: " + k.size() + " in " + (System.currentTimeMillis() - t) / 1000 + "s");
        int Y = InfernalLayout.Y;
        PreviewMain.pov(k, new File(out, "kingdom_spawn.png"), 0.5, Y + 4.6, 76.5, 180, 2, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_overview.png"), 0.5, Y + 120, 170.5, 180, 24, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_from_gate.png"), 0.5, Y + 40, 150.5, 180, 12, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_castle.png"), 0.5, Y + 60, 20.5, 180, 14, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_terrace.png"), 0.5, Y + 28, -45.5, 180, 14, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_west.png"), -190.5, Y + 70, 40.5, 90, 14, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_throne.png"), 0.5, Y + 24, -92.5, 180, 4, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_skull.png"), 0.5, Y + 34, -150.5, 180, 6, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_cache.png"), -48.5, Y + 3.6, -92.5, 90, 4, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_viewpoint.png"), 0.5, Y + 100, -60.5, 180, 6, 1600, 900);
        PreviewMain.topDown(k, new File(out, "kingdom_top.png"), 3);
        PreviewMain.pov(k, new File(out, "kingdom_treasury.png"), 0.5, Y + 6, -45.5, 180, -4, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_treasury_back.png"), 0.5, Y + 8, -66.5, 180, -6, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_ascension.png"), 76.5, Y + 28, -52.5, -90, 4, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_blacksmith.png"), 62.5, Y + 16, 30.5, -90, 6, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_merchant.png"), -62.5, Y + 16, 30.5, 90, 6, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_gate.png"), 0.5, Y + 8, 120.5, 0, 0, 1600, 900);
        PreviewMain.pov(k, new File(out, "kingdom_gate_outside.png"), 0.5, Y + 12, 300.5, 180, 4, 1600, 900);
    }
}
