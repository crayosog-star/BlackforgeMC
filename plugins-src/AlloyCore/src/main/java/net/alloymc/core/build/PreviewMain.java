package net.alloymc.core.build;

import net.alloymc.core.arcade.ArenaBuilder;
import net.alloymc.core.hub.HubBuilder;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

/** Offline isometric renderer for Canvas builds (no Minecraft needed). */
public final class PreviewMain {
    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "previews");
        out.mkdirs();
        night = true;      // the hub sits under a dark red sky (blackforge:inferno biome, midnight)
        Canvas hub = HubBuilder.build();
        System.out.println("hub blocks: " + hub.size());
        render(hub, new File(out, "hub_southeast.png"), 0, 7);
        render(hub, new File(out, "hub_southwest.png"), 1, 7);
        pov(hub, new File(out, "hub_spawn_view.png"), 0.5, 104.62, 0.5, 180, -10, 1280, 720);
        pov(hub, new File(out, "hub_spawn_view_east.png"), 0.5, 104.62, 0.5, -90, -2, 1280, 720);
        pov(hub, new File(out, "hub_spawn_view_south.png"), 0.5, 104.62, 0.5, 0, -2, 1280, 720);
        pov(hub, new File(out, "hub_view_from_arcade_gate.png"), 0.5, 103.6, 40.5, 180, -4, 1280, 720);
        pov(hub, new File(out, "hub_view_side.png"), 44.5, 112, 30.5, 125, -14, 1280, 720);
        topDown(hub, new File(out, "hub_top.png"), 6);
        pov(hub, new File(out, "hub_dragon_east.png"), 20.5, 112, -20.5, -64, -18, 1280, 720);
        pov(hub, new File(out, "hub_dragon_south.png"), 0.5, 105, 20.5, 0, -31, 1280, 720);
        pov(hub, new File(out, "hub_dragon_west.png"), -20.5, 112, -20.5, 64, -18, 1280, 720);
        pov(hub, new File(out, "hub_serpent_close.png"), -40.5, 135, 30.5, 135, 8, 1280, 720);
        pov(hub, new File(out, "hub_aerial.png"), 0.5, 190, 150.5, 180, 30, 1600, 900);
        night = false;
        Canvas sv = net.alloymc.core.survival.SpawnBuilder.spawn();
        System.out.println("survival spawn blocks: " + sv.size());
        render(sv, new File(out, "survival_spawn.png"), 0, 9);
        pov(sv, new File(out, "survival_spawn_view.png"), 0.5, 152.62, 32.5, 180, -6, 1280, 720);
        pov(sv, new File(out, "survival_dropzone_view.png"), -22.5, 158.62, 0.5, 90, 22, 1280, 720);
        pov(sv, new File(out, "survival_dropzone_top.png"), -30.5, 175, 18.5, 140, 50, 1280, 720);
        pov(sv, new File(out, "survival_hall_view.png"), 30.5, 154.62, 2.5, 180, 14, 1280, 720);
        pov(sv, new File(out, "survival_spawn_left.png"), 0.5, 152.62, 32.5, 90, -4, 1280, 720);
        pov(sv, new File(out, "survival_spawn_right.png"), 0.5, 152.62, 32.5, -90, -4, 1280, 720);
        pov(sv, new File(out, "survival_market_view.png"), 0.5, 152.62, 36.5, 0, -2, 1280, 720);
        pov(sv, new File(out, "survival_aerial.png"), 0.5, 200, 95.5, 180, 28, 1600, 900);
        pov(sv, new File(out, "survival_crates_view.png"), 0.5, 153.62, -19.5, 180, 4, 1280, 720);
        Canvas ar = net.alloymc.core.survival.SpawnBuilder.arena();
        System.out.println("koth arena blocks: " + ar.size());
        render(ar, new File(out, "survival_koth_arena.png"), 0, 8);
        pov(ar, new File(out, "survival_koth_view.png"), 0.5, 152.62, -391.5, 180, -8, 1280, 720);
        Canvas sk = net.alloymc.core.survival.SkyblockBuilder.build();
        System.out.println("sky harbor blocks: " + sk.size());
        pov(sk, new File(out, "skyblock_aerial.png"), 0.5, 200, 75.5, 180, 30, 1600, 900);
        pov(sk, new File(out, "skyblock_spawn_view.png"), 0.5, 152.62, 24.5, 180, -4, 1280, 720);
        pov(sk, new File(out, "skyblock_market_view.png"), -14.5, 152.62, 20.5, 0, 6, 1280, 720);
        night = true;      // the factions spawn sits under a frozen midnight
        Canvas fa = net.alloymc.core.factions.FactionsBuilder.build();
        System.out.println("factions spawn blocks: " + fa.size());
        double[] fs = net.alloymc.core.factions.FactionsBuilder.spawn();
        int fy = net.alloymc.core.factions.FactionsBuilder.Y;
        pov(fa, new File(out, "factions_aerial.png"), 0.5, fy + 70, 120.5, 180, 28, 1600, 900);
        pov(fa, new File(out, "factions_spawn_view.png"), fs[0], fs[1] + 1.62, fs[2], 180, -8, 1280, 720);
        pov(fa, new File(out, "factions_duel.png"), -26.5, fy + 16, 26.5, 225, -22, 1280, 900);
        pov(fa, new File(out, "factions_side.png"), 95.5, fy + 25, 60.5, 122, 8, 1600, 900);
        topDown(fa, new File(out, "factions_top.png"), 4);
        night = false;
        Canvas pv = net.alloymc.core.survival.PvpBuilder.build();
        System.out.println("scrapyard blocks: " + pv.size());
        topDown(pv, new File(out, "pvp_top.png"), 4);
        pov(pv, new File(out, "pvp_aerial.png"), 0.5, 215, 655.5, 180, 30, 1600, 900);
        pov(pv, new File(out, "pvp_entrance_view.png"), 0.5, 166.6, 612.5, 180, -4, 1280, 720);
        pov(pv, new File(out, "pvp_ground_view.png"), 40.5, 166.6, 540.5, 140, -2, 1280, 720);
        for (String a : List.of("lobby", "spleef", "tntrun", "infected")) {
            Canvas c = ArenaBuilder.build(a, 0, 100, 0);
            System.out.println(a + " blocks: " + c.size());
            render(c, new File(out, "arcade_" + a + ".png"), 0, 9);
        }
    }

    record B(int x, int y, int z, String id) {}

    /**
     * Camera looks from the south-east; rot 1 = from the south-west, rot 4 = a
     * near head-on view from the south (sheared so the north side stays visible).
     */
    static void render(Canvas c, File file, int rot, int s) throws Exception {
        List<B> list = new ArrayList<>();
        Map<Long, String> all = new HashMap<>(c.blocks());
        all.putAll(c.lateBlocks());
        all.values().removeIf(v -> v.startsWith("minecraft:light"));   // invisible light blocks
        for (var e : all.entrySet()) {
            if (e.getValue().equals("minecraft:air") || e.getValue().equals("minecraft:barrier")) continue;
            long k = e.getKey();
            int x = Canvas.kx(k), z = Canvas.kz(k);
            if (rot == 1) { int t = x; x = z; z = -t; }
            list.add(new B(x, Canvas.ky(k), z, e.getValue()));
        }
        Set<Long> solid = new HashSet<>();
        for (B b : list) if (occludes(b.id)) solid.add(Canvas.key(b.x, b.y, b.z));
        list.sort(Comparator.comparingInt((B b) -> b.x + b.y + b.z).thenComparingInt(b -> b.y));
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (B b : list) {
            int px = (b.x - b.z) * s, py = (b.x + b.z) * s / 2 - b.y * s;
            minX = Math.min(minX, px); maxX = Math.max(maxX, px);
            minY = Math.min(minY, py); maxY = Math.max(maxY, py);
        }
        int pad = 3 * s;
        int w = maxX - minX + 2 * pad, h = maxY - minY + 2 * pad;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(night ? new GradientPaint(0, 0, new Color(0x5A160A), 0, h, new Color(0x140506))
                : new GradientPaint(0, 0, new Color(0xF2A65A), 0, h, new Color(0x3B2F5C)));
        g.fillRect(0, 0, w, h);
        for (B b : list) {
            int px = (b.x - b.z) * s - minX + pad, py = (b.x + b.z) * s / 2 - b.y * s - minY + pad;
            Color base = color(b.id, b.x * 31 + b.y * 17 + b.z * 7);
            boolean small = !occludes(b.id) && !b.id.contains("glass") && !b.id.contains("portal") && !b.id.contains("leaves") && !b.id.contains("lava");
            double k = small ? 0.45 : 1.0;
            double cx = px, cy = py + (small ? s * 0.5 : 0);
            double hs = s * k;
            boolean top = !solid.contains(Canvas.key(b.x, b.y + 1, b.z));
            boolean right = !solid.contains(Canvas.key(b.x + 1, b.y, b.z));
            boolean left = !solid.contains(Canvas.key(b.x, b.y, b.z + 1));
            if (top) face(g, base, 1.0, cx, cy - hs, cx + hs, cy - hs / 2, cx, cy, cx - hs, cy - hs / 2);
            if (left) face(g, base, 0.78, cx - hs, cy - hs / 2, cx, cy, cx, cy + hs, cx - hs, cy + hs / 2);
            if (right) face(g, base, 0.62, cx, cy, cx + hs, cy - hs / 2, cx + hs, cy + hs / 2, cx, cy + hs);
        }
        g.dispose();
        ImageIO.write(img, "png", file);
    }

    /** Simple voxel raytracer from a player's eye (yaw/pitch in Minecraft convention). */
    static void pov(Canvas c, File file, double ex, double ey, double ez, float yaw, float pitch, int w, int h) throws Exception {
        Map<Long, String> all = new HashMap<>(c.blocks());
        all.putAll(c.lateBlocks());
        all.values().removeIf(v -> v.startsWith("minecraft:light"));   // invisible light blocks
        all.values().removeIf(v -> v.equals("minecraft:air") || v.equals("minecraft:barrier"));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        // forward vector (MC: yaw 0 = +Z, yaw 90 = -X; pitch negative = up)
        double fx = -Math.sin(yr) * Math.cos(pr), fy = -Math.sin(pr), fz = Math.cos(yr) * Math.cos(pr);
        double rx = -fz, rz = fx;                                   // right = forward x worldUp
        double rl = Math.sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl;
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;  // up = right x forward
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz); ux /= ul; uy /= ul; uz /= ul;
        double tan = Math.tan(Math.toRadians(70) / 2), aspect = (double) w / h;
        for (int py = 0; py < h; py++)
            for (int px = 0; px < w; px++) {
                double sx = (2 * (px + 0.5) / w - 1) * tan * aspect, sy = (1 - 2 * (py + 0.5) / h) * tan;
                double dx = fx + sx * rx + sy * ux, dy = fy + sy * uy, dz = fz + sx * rz + sy * uz;
                double dl = Math.sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl;
                img.setRGB(px, py, trace(all, ex, ey, ez, dx, dy, dz));
            }
        ImageIO.write(img, "png", file);
    }

    static int trace(Map<Long, String> all, double ox, double oy, double oz, double dx, double dy, double dz) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int stx = dx > 0 ? 1 : -1, sty = dy > 0 ? 1 : -1, stz = dz > 0 ? 1 : -1;
        double tdx = Math.abs(1 / dx), tdy = Math.abs(1 / dy), tdz = Math.abs(1 / dz);
        double tmx = ((dx > 0 ? x + 1 - ox : ox - x)) * tdx, tmy = ((dy > 0 ? y + 1 - oy : oy - y)) * tdy, tmz = ((dz > 0 ? z + 1 - oz : oz - z)) * tdz;
        double t = 0; int axis = -1;
        float ar = 0, ag = 0, ab = 0, trans = 1;    // accumulated colour through translucent blocks
        while (t < 260) {
            String id = all.get(Canvas.key(x, y, z));
            if (id != null && axis >= 0) {
                Color col = color(id, x * 31 + y * 17 + z * 7);
                boolean glow = col.getAlpha() == 254;
                double shade = glow ? 1.0 : axis == 1 ? (sty < 0 ? 1.0 : 0.5) : axis == 0 ? 0.8 : 0.66;
                double fog = Math.min(1, Math.max(0, (t - 60) / 200));
                boolean seeThrough = id.contains("glass") || id.contains("portal") || id.contains("leaves") || !occludes(id);
                float a = seeThrough ? (id.contains("leaves") ? 0.85f : id.contains("portal") ? 0.6f : id.contains("glass") ? 0.45f : 0.7f) : 1f;
                int sky = sky(dy);
                float r = (float) (col.getRed() * shade * (1 - fog) + ((sky >> 16) & 255) * fog);
                float g = (float) (col.getGreen() * shade * (1 - fog) + ((sky >> 8) & 255) * fog);
                float b = (float) (col.getBlue() * shade * (1 - fog) + (sky & 255) * fog);
                ar += trans * a * r; ag += trans * a * g; ab += trans * a * b; trans *= (1 - a);
                if (trans < 0.03f) break;
            }
            if (tmx < tmy && tmx < tmz) { x += stx; t = tmx; tmx += tdx; axis = 0; }
            else if (tmy < tmz) { y += sty; t = tmy; tmy += tdy; axis = 1; }
            else { z += stz; t = tmz; tmz += tdz; axis = 2; }
            if (y < 20 || y > 330) break;
        }
        int sky = sky(dy);
        ar += trans * ((sky >> 16) & 255); ag += trans * ((sky >> 8) & 255); ab += trans * (sky & 255);
        return (clamp((int) ar) << 16) | (clamp((int) ag) << 8) | clamp((int) ab);
    }

    static boolean night;

    static int sky(double dy) {
        if (night) {
            double t = Math.max(0, Math.min(1, dy * 1.6 + 0.35));
            int r = (int) (0x5A * (1 - t) + 0x14 * t), g = (int) (0x16 * (1 - t) + 0x05 * t), b = (int) (0x0A * (1 - t) + 0x06 * t);
            return (r << 16) | (g << 8) | b;
        }
        double t = Math.max(0, Math.min(1, dy * 1.6 + 0.35));
        int r = (int) (0xF4 * (1 - t) + 0x5B * t), g = (int) (0xA2 * (1 - t) + 0x6E * t), b = (int) (0x6A * (1 - t) + 0xC4 * t);
        return (r << 16) | (g << 8) | b;
    }

    static void face(Graphics2D g, Color c, double shade, double... p) {
        Path2D path = new Path2D.Double();
        path.moveTo(p[0], p[1]);
        for (int i = 2; i < p.length; i += 2) path.lineTo(p[i], p[i + 1]);
        path.closePath();
        boolean glow = c.getAlpha() == 254;
        double f = glow ? 1.0 : shade;
        g.setColor(new Color((int) (c.getRed() * f), (int) (c.getGreen() * f), (int) (c.getBlue() * f)));
        g.fill(path);
    }

    static void topDown(Canvas c, File file, int s) throws Exception {
        Map<Long, String> top = new HashMap<>();
        Map<Long, Integer> topY = new HashMap<>();
        Map<Long, String> all = new HashMap<>(c.blocks());
        all.putAll(c.lateBlocks());
        all.values().removeIf(v -> v.startsWith("minecraft:light"));   // invisible light blocks
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (var e : all.entrySet()) {
            if (e.getValue().equals("minecraft:air")) continue;
            long k = e.getKey();
            int x = Canvas.kx(k), z = Canvas.kz(k), y = Canvas.ky(k);
            long col = ((long) x << 32) | (z & 0xFFFFFFFFL);
            if (topY.getOrDefault(col, Integer.MIN_VALUE) < y) { topY.put(col, y); top.put(col, e.getValue()); }
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        int iw = (maxX - minX + 1) * s, ih = (maxZ - minZ + 1) * s;
        BufferedImage img = new BufferedImage(iw, ih, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x1d1b2a)); g.fillRect(0, 0, iw, ih);
        for (var e : top.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey().longValue();
            Color col = color(e.getValue(), x * 13 + z * 7);
            double f = Math.max(0.55, Math.min(1.15, 0.8 + (topY.get(e.getKey()) - 100) * 0.02));
            g.setColor(new Color(Math.min(255, (int) (col.getRed() * f)), Math.min(255, (int) (col.getGreen() * f)), Math.min(255, (int) (col.getBlue() * f))));
            g.fillRect((x - minX) * s, (z - minZ) * s, s, s);
        }
        g.dispose();
        ImageIO.write(img, "png", file);
    }

    static boolean occludes(String id) {
        String n = id.replace("minecraft:", "");
        for (String t : new String[]{"lantern", "chain", "wall", "grass", "fern", "poppy", "daisy", "cornflower", "glass", "portal",
                "leaves", "slab", "campfire", "anvil", "grate", "grindstone", "lava", "air", "banner", "torch"})
            if (n.contains(t) && !n.equals("grass_block") && !n.equals("moss_block")) return false;
        return true;
    }

    static final LinkedHashMap<String, Integer> COLORS = new LinkedHashMap<>();
    static {
        for (String[] e : new String[][]{{"candle", "FFE0A0!"}, {"cave_vines", "F0A040!"}, {"honeycomb", "E8A02C"}, {"yellow_terracotta", "B98530"},
                {"orange_terracotta", "A0532A"}, {"red_terracotta", "8F3D2E"}, {"light_blue_terracotta", "716C89"}, {"red_wool", "A02722"},
                {"blue_wool", "35399D"}, {"stripped_oak", "C29D62"}, {"stripped_birch", "C4B07C"}, {"dark_oak_wood", "3D2A17"},
                {"chiseled_tuff", "6A6B63"}, {"polished_tuff", "626359"}, {"tuff_bricks", "666760"}, {"cut_sandstone", "D8CB95"},
                {"smooth_sandstone", "E0D4A2"}, {"chiseled_sandstone", "CDBE86"}, {"sandstone", "D5C892"}, {"cracked_stone", "6E6E6E"}})
            COLORS.put(e[0], (int) Long.parseLong(e[1].replace("!", ""), 16) | (e[1].endsWith("!") ? 0xFE000000 : 0xFF000000));
        COLORS.put("red_nether", 0xFF6B1F23);
        COLORS.put("nether_bricks", 0xFF2C1418);
        COLORS.put("warped_wart", 0xFF167E86);
        COLORS.put("warped_nylium", 0xFF2B6F6A);
        COLORS.put("warped_stem", 0xFF3A3A55);
        COLORS.put("warped_planks", 0xFF2B6862);
        COLORS.put("ochre_froglight", 0xFEF5E4A0);
        String[][] t = {
            {"lava", "FF7A1A!"}, {"magma", "B5481E"}, {"bulb", "FFB86B!"}, {"froglight", "F4D9F0!"}, {"shroomlight", "F2A44B!"},
            {"lantern", "FFC870!"}, {"campfire", "FF9A3D!"}, {"nether_portal", "8E3BEA!"}, {"orange_stained_glass", "FF8F3A!"},
            {"oxidized", "4FA88E"}, {"weathered", "6C9F78"}, {"exposed", "A47B66"}, {"copper_grate", "C9714A"}, {"raw_copper", "9C5A3C"},
            {"copper_ore", "8C7B70"}, {"copper", "C6693F"}, {"raw_gold", "D7A13B"}, {"gold_ore", "9C8E6C"}, {"gold", "F2C94C"},
            {"raw_iron", "A88F7A"}, {"iron_ore", "8E857E"}, {"iron_block", "DCDCDC"}, {"iron_chain", "4A4F59"}, {"netherite", "4A4043"},
            {"crying_obsidian", "3E1A70"}, {"red_nether", "6B1F23"}, {"blackstone", "2F2A30"}, {"deepslate_tiles", "3B3B40"},
            {"polished_deepslate", "4A4A50"}, {"deepslate", "4E4E55"}, {"tuff", "6D6E66"}, {"smooth_quartz", "EDE6DD"}, {"quartz", "EDE6DD"},
            {"red_concrete", "B3312C"}, {"orange_concrete", "E06A1A"}, {"yellow_concrete", "F0B828"}, {"lime_concrete", "5EA918"},
            {"light_blue_concrete", "3AAFD9"}, {"purple_concrete", "7B2FBE"}, {"white_concrete", "E9ECEC"}, {"black_concrete", "141519"},
            {"gray_concrete", "36393D"}, {"cyan_concrete", "157788"}, {"blue_concrete", "2C2E8F"}, {"green_concrete", "495B24"},
            {"snow", "F4FAFB"}, {"ice", "9DC3F7"}, {"sand", "DBCFA0"}, {"tnt", "C8402F"}, {"note_block", "5B3A28"}, {"jukebox", "6B4734"},
            {"azalea_leaves", "5E8C2E"}, {"leaves", "4E7A27"}, {"dark_oak", "3D2A17"}, {"oak", "6B5233"}, {"spruce", "4F3A22"},
            {"moss", "5C7A2C"}, {"grass_block", "6FA03C"}, {"short_grass", "7DB547"}, {"fern", "5E8F3B"}, {"poppy", "C8302A"},
            {"daisy", "EDEDE0"}, {"cornflower", "4C6FD6"}, {"coarse_dirt", "6E4E36"}, {"dirt", "86603F"}, {"mossy", "6D7D5A"},
            {"cobblestone", "7B7B7B"}, {"andesite", "8A8B8B"}, {"smooth_stone", "A0A0A0"}, {"stone_bricks", "7E7E7E"}, {"stone", "7D7D7D"},
            {"glass", "C9E6F0"}, {"barrel", "7A5A35"}, {"blast_furnace", "5C5C60"}, {"smithing", "3B3E48"}, {"anvil", "45474D"},
            {"grindstone", "8B8B8B"}, {"barrier", "000000"}, {"terracotta", "985E43"}, {"bricks", "96604E"}, {"planks", "A0814E"},
            {"wool", "DDDDDD"}, {"chest", "A2702F"}, {"crafting", "8A5F36"}, {"hay", "C9A933"}, {"ladder", "8A6B3F"}, {"door", "8A6B3F"},
            {"slime", "77C25B"}, {"redstone_lamp", "E8B060!"}, {"sea_lantern", "CFE8E0!"}, {"glowstone", "F1D08C!"}, {"end_rod", "F5F0E0!"},
            {"farmland", "5A3B22"}, {"wheat", "D4B24A"}, {"carrots", "E07A1F"}, {"potatoes", "8DB045"}, {"beetroots", "8E2A35"}, {"sugar_cane", "8CC060"}, {"stem", "6E8E2A"}, {"sand", "DBCF9E"}, {"coarse_dirt", "6E4E36"}, {"obsidian", "1C1626"}, {"gravel", "8A817C"}, {"cherry_leaves", "F2A7C8"}, {"cherry_log", "4A2A2E"}, {"cherry_planks", "E3B4A6"}, {"stripped_cherry", "D9A08E"}, {"pink_wool", "ED8DAC"}, {"pink_petals", "F2A7C8"}, {"hanging_roots", "A07050"}, {"glow_lichen", "7FA898"}, {"emerald", "41C46E"}, {"azure_bluet", "E0E8F0"}, {"lily_of_the_valley", "F0F0F0"}, {"gilded_blackstone", "6E5A2A"}, {"nether_gold_ore", "6A3A2A"}, {"smooth_basalt", "4A4A50"}, {"chiseled_polished_blackstone", "3A353C"}, {"cracked_polished_blackstone", "2A262C"}, {"soul_campfire", "6FE0E6!"}, {"button", "2F2A30"}, {"diamond_ore", "5FC8C0"}, {"redstone_block", "B01E14"}, {"skull", "CFCFCF"}, {"end_portal_frame", "3E6B5A"}, {"ender_chest", "1F3B36"}, {"end_rod", "F5F0E0!"}, {"purple_stained", "8E3BEA"}, {"red_stained", "C0392B"}, {"packed_mud", "8C6A4F"}, {"rooted_dirt", "90684C"}, {"basalt", "4B4B52"}, {"dead_bush", "8A6A3A"}, {"dry_grass", "B8A060"}, {"cobweb", "E8E8E8"}, {"bone_block", "E4DDC6"}, {"skull", "C8C8C8"}, {"mushroom", "8F6A4E"}, {"rail", "7A6A5A"}, {"coal_block", "1E1E1E"}, {"iron_bars", "8A8D90"}, {"hay", "C9A933"}, {"cauldron", "3E3E42"}, {"prismarine", "5FA89A"}, {"lodestone", "8B8B90"}, {"bell", "E8C14A"}, {"amethyst", "8E6BC9"}, {"allium", "B47AE0"}, {"white_wool", "EAECEC"}, {"orange_wool", "F07613"}, {"melon", "6E9B2A"}, {"pumpkin", "D98A1C"}, {"honey", "F5A623"}, {"lightning_rod", "C6693F"}, {"vault", "3E4A4F"}, {"beacon", "7FE6E0!"}, {"water", "3F76E4"}, {"crimson_nylium", "8A2B2B"}, {"soul_lantern", "6FE0E6!"}, {"mud_bricks", "8E6B53"}, {"fence", "6B5233"}, {"calcite", "E2E2DC"}, {"pressure", "D8B84C"},
        };
        for (String[] e : t) COLORS.put(e[0], (int) Long.parseLong(e[1].replace("!", ""), 16) | (e[1].endsWith("!") ? 0xFE000000 : 0xFF000000));
    }

    static Color color(String id, int seed) {
        String n = id.replace("minecraft:", "");
        int bracket = n.indexOf('[');
        if (bracket >= 0) n = n.substring(0, bracket);
        for (var e : COLORS.entrySet())
            if (n.contains(e.getKey())) {
                int v = e.getValue();
                boolean glow = (v >>> 24) == 0xFE;
                int jitter = ((seed * 1103515245 + 12345) >>> 16) % 13 - 6;
                if (glow) jitter /= 3;
                int r = clamp(((v >> 16) & 255) + jitter), g = clamp(((v >> 8) & 255) + jitter), b = clamp((v & 255) + jitter);
                if (night && !glow) { r = r * 11 / 20; g = g * 10 / 20; b = b * 10 / 20; }
                return new Color(r, g, b, glow ? 254 : 255);
            }
        return new Color(255, 0, 255);
    }

    static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
