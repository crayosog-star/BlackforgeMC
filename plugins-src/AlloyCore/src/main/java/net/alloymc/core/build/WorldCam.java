package net.alloymc.core.build;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An offline ray tracer for the servers' real worlds: reads Anvil region files and draws them with Minecraft's own
 * block models and textures (from the client jar), the light levels stored in the chunks, sun shadows, ambient
 * occlusion and distance fog. Used for website screenshots and the trailer ({@link Trailer}); no game client runs.
 *
 * Supported: full and partial block models (slabs, stairs, fences, panes, plants via rotated elements), multipart
 * block states, cutout and translucent textures, biome-style tints (fixed colours), water and lava surfaces, emissive
 * blocks, and extra oriented boxes ({@link Box}) for things that aren't blocks (the hub dragons).
 */
public final class WorldCam {

    // ================================================================== tiny JSON

    static Object json(String s) { return new Json(s).value(); }

    private static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = (String) value(); ws(); i++; m.put(k, value()); ws(); if (s.charAt(i++) == '}') return m; }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>(); i++; ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; }
            }
            if (c == '"') {
                StringBuilder b = new StringBuilder(); i++;
                while (s.charAt(i) != '"') { char ch = s.charAt(i++); if (ch == '\\') { char e = s.charAt(i++); b.append(e == 'n' ? '\n' : e == 'u' ? (char) Integer.parseInt(s.substring(i, i += 4), 16) : e); } else b.append(ch); }
                i++; return b.toString();
            }
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }
    }

    @SuppressWarnings("unchecked") static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
    @SuppressWarnings("unchecked") static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }
    static double num(Object o, double def) { return o instanceof Number n ? n.doubleValue() : def; }

    // ================================================================== NBT

    static Object nbt(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: { int t = in.readByte(), n = in.readInt(); List<Object> l = new ArrayList<>(Math.max(0, n)); for (int k = 0; k < n; k++) l.add(nbt(in, t)); return l; }
            case 10: { Map<String, Object> m = new HashMap<>(); while (true) { int t = in.readByte(); if (t == 0) return m; String name = in.readUTF(); m.put(name, nbt(in, t)); } }
            case 11: { int[] a = new int[in.readInt()]; for (int k = 0; k < a.length; k++) a[k] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int k = 0; k < a.length; k++) a[k] = in.readLong(); return a; }
            default: throw new IOException("bad nbt tag " + type);
        }
    }

    // ================================================================== assets: textures + models

    /** One textured face of a model element: texture pixels and its uv rectangle (0..16). */
    record Face(int[] px, int size, float u0, float v0, float u1, float v1, int tint, boolean translucent) {}

    /** A model element: a box in block units (0..1) with up to six faces (down, up, north, south, west, east). */
    static final class Elem {
        double x0, y0, z0, x1, y1, z1;
        Face[] faces = new Face[6];
        // optional element rotation (degrees about an axis through an origin)
        char axis; double angle, ox, oy, oz;
    }

    /** Everything needed to draw one block state. */
    static final class Model {
        final List<Elem> elems = new ArrayList<>();
        boolean opaqueCube;   // a full, untextured-alpha cube: fast path, occludes light and AO
        boolean emissive, water, lava, invisible;
        double fluidTop = 1;
        int lightTint;
    }

    static final Set<String> INVISIBLE = Set.of("air", "cave_air", "void_air", "light", "barrier", "structure_void", "moving_piston");
    static final Set<String> EMISSIVE_WORDS = Set.of("glowstone", "lava", "lantern", "sea_lantern", "shroomlight", "froglight", "torch", "campfire", "magma_block",
            "end_rod", "jack_o_lantern", "glow_lichen", "beacon", "fire", "redstone_lamp", "candle", "crying_obsidian", "respawn_anchor", "conduit", "copper_bulb", "amethyst_cluster", "sculk_catalyst");

    final ZipFile jar;
    private final Map<String, int[]> texCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> texSize = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> modelJson = new ConcurrentHashMap<>();
    private final Map<String, Model> stateCache = new ConcurrentHashMap<>();

    public WorldCam(File clientJar) throws IOException { this.jar = new ZipFile(clientJar); }

    private String read(String path) {
        try {
            ZipEntry e = jar.getEntry(path);
            if (e == null) return null;
            try (InputStream in = jar.getInputStream(e)) { return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        } catch (IOException ex) { return null; }
    }

    private static String strip(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }

    int[] texture(String id) {
        String path = strip(id);
        return texCache.computeIfAbsent(path, p -> {
            try {
                ZipEntry e = jar.getEntry("assets/minecraft/textures/" + p + ".png");
                if (e == null) { texSize.put(p, 1); return new int[]{0xFFFF00FF}; }
                BufferedImage img;
                try (InputStream in = jar.getInputStream(e)) { img = ImageIO.read(in); }
                int w = img.getWidth();   // animated textures are a vertical strip: first frame only
                int[] px = new int[w * w];
                for (int y = 0; y < w; y++) for (int x = 0; x < w; x++) px[y * w + x] = img.getRGB(x, y);
                texSize.put(p, w);
                return px;
            } catch (IOException ex) { texSize.put(p, 1); return new int[]{0xFFFF00FF}; }
        });
    }

    int texSize(String id) { texture(id); return texSize.getOrDefault(strip(id), 16); }

    private Map<String, Object> modelJson(String id) {
        String path = strip(id);
        if (!path.contains("/")) path = "block/" + path;
        String p = path;
        return modelJson.computeIfAbsent(p, k -> { String s = read("assets/minecraft/models/" + k + ".json"); return s == null ? Map.of() : obj(json(s)); });
    }

    /** The block state "minecraft:oak_stairs[facing=east,half=bottom]" as a drawable model. */
    Model model(String state) { return stateCache.computeIfAbsent(state, this::build); }

    private Model build(String state) {
        String name = strip(state), props = "";
        int b = name.indexOf('[');
        if (b >= 0) { props = name.substring(b + 1, name.length() - 1); name = name.substring(0, b); }
        Map<String, String> p = new HashMap<>();
        for (String kv : props.split(",")) { int e = kv.indexOf('='); if (e > 0) p.put(kv.substring(0, e), kv.substring(e + 1)); }
        Model m = new Model();
        if (INVISIBLE.contains(name)) { m.invisible = true; return m; }
        for (String w : EMISSIVE_WORDS) if (name.contains(w) && !(name.contains("lit") && "false".equals(p.get("lit")))) m.emissive = true;
        if ((name.equals("campfire") || name.equals("soul_campfire") || name.contains("candle") || name.equals("redstone_lamp") || name.contains("copper_bulb")) && !"true".equals(p.get("lit"))) m.emissive = false;
        if (name.equals("water") || name.equals("lava") || "true".equals(p.get("waterlogged")) || name.equals("bubble_column") || name.equals("kelp") || name.equals("kelp_plant") || name.equals("seagrass") || name.equals("tall_seagrass")) {
            boolean lava = name.equals("lava");
            if (name.equals("water") || lava) {
                int level = Integer.parseInt(p.getOrDefault("level", "0"));
                m.fluidTop = level == 0 || level >= 8 ? 14 / 16.0 : (14 - level * 1.75) / 16.0;
                Elem e = new Elem();
                e.x1 = 1; e.y1 = m.fluidTop; e.z1 = 1;
                String tex = lava ? "block/lava_still" : "block/water_still";
                int[] px = texture(tex);
                Face f = new Face(px, texSize(tex), 0, 0, 16, 16, lava ? -1 : 0x3F76E4, !lava);
                Arrays.fill(e.faces, f);
                m.elems.add(e);
                m.water = !lava; m.lava = lava; m.emissive = lava;
                return m;
            }
            m.water = true;   // waterlogged: drawn as the block, the water around it shows through
        }
        String bs = read("assets/minecraft/blockstates/" + name + ".json");
        if (bs == null) return fallback(m, name);
        Map<String, Object> root = obj(json(bs));
        List<Map<String, Object>> parts = new ArrayList<>();
        if (root.containsKey("variants")) {
            Map<String, Object> vars = obj(root.get("variants"));
            Object pick = null;
            for (var en : vars.entrySet()) if (matches(en.getKey(), p)) { pick = en.getValue(); break; }
            if (pick == null && !vars.isEmpty()) pick = vars.values().iterator().next();
            if (pick != null) parts.add(arr(pick) != null ? obj(arr(pick).getFirst()) : obj(pick));
        } else if (root.containsKey("multipart")) {
            for (Object o : arr(root.get("multipart"))) {
                Map<String, Object> part = obj(o);
                if (part.containsKey("when") && !when(obj(part.get("when")), p)) continue;
                Object ap = part.get("apply");
                parts.add(arr(ap) != null ? obj(arr(ap).getFirst()) : obj(ap));
            }
        }
        for (Map<String, Object> part : parts) addModel(m, (String) part.get("model"), (int) num(part.get("x"), 0), (int) num(part.get("y"), 0), name);
        if (m.elems.isEmpty()) return fallback(m, name);
        m.opaqueCube = m.elems.size() == 1 && isFullOpaque(m.elems.getFirst());
        return m;
    }

    private static boolean matches(String key, Map<String, String> p) {
        if (key.isEmpty() || key.equals("normal")) return true;
        for (String kv : key.split(",")) { int e = kv.indexOf('='); if (e > 0 && !kv.substring(e + 1).equals(p.get(kv.substring(0, e)))) return false; }
        return true;
    }

    private static boolean when(Map<String, Object> w, Map<String, String> p) {
        if (w.containsKey("OR")) { for (Object o : arr(w.get("OR"))) if (when(obj(o), p)) return true; return false; }
        if (w.containsKey("AND")) { for (Object o : arr(w.get("AND"))) if (!when(obj(o), p)) return false; return true; }
        for (var en : w.entrySet()) {
            String have = p.get(en.getKey());
            if (have == null) return false;
            if (!Arrays.asList(String.valueOf(en.getValue()).split("\\|")).contains(have)) return false;
        }
        return true;
    }

    private Model fallback(Model m, String name) {
        // block entities with no block model: a rough box so they aren't holes
        String tex = name.contains("ender_chest") ? "block/obsidian" : name.contains("chest") ? "block/oak_planks" : name.contains("shulker_box") ? "block/purpur_block"
                : name.endsWith("_bed") ? "block/" + name.replace("_bed", "_wool") : name.contains("skull") || name.contains("head") ? "block/bone_block_side"
                : name.contains("decorated_pot") ? "block/terracotta" : name.contains("sign") || name.contains("banner") ? null : "block/stone";
        if (tex == null) { m.invisible = true; return m; }
        Elem e = new Elem();
        boolean bed = name.endsWith("_bed"), skull = name.contains("skull") || name.contains("head");
        e.x0 = skull ? 0.25 : 1 / 16.0; e.z0 = e.x0; e.x1 = 1 - e.x0; e.z1 = 1 - e.z0;
        e.y1 = bed ? 9 / 16.0 : skull ? 0.5 : 14 / 16.0;
        Face f = new Face(texture(tex), texSize(tex), 0, 0, 16, 16, -1, false);
        Arrays.fill(e.faces, f);
        m.elems.add(e);
        return m;
    }

    private static boolean isFullOpaque(Elem e) {
        if (e.x0 > 0.001 || e.y0 > 0.001 || e.z0 > 0.001 || e.x1 < 0.999 || e.y1 < 0.999 || e.z1 < 0.999 || e.axis != 0) return false;
        for (Face f : e.faces) {
            if (f == null || f.translucent) return false;
            for (int c : f.px) if ((c >>> 24) < 250) return false;
        }
        return true;
    }

    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    private void addModel(Model m, String id, int rx, int ry, String blockName) {
        if (id == null) return;
        Map<String, String> textures = new HashMap<>();
        List<Object> elements = null;
        // walk up the parents: the child's textures win, the first elements found are used
        List<Map<String, Object>> chain = new ArrayList<>();
        for (String cur = id; cur != null && chain.size() < 12; ) {
            Map<String, Object> j = modelJson(cur);
            if (j.isEmpty()) break;
            chain.add(j);
            cur = (String) j.get("parent");
        }
        for (int k = chain.size() - 1; k >= 0; k--) {
            Map<String, Object> t = obj(chain.get(k).get("textures"));
            if (t != null) for (var en : t.entrySet()) textures.put(en.getKey(), String.valueOf(en.getValue()));
        }
        for (Map<String, Object> j : chain) if (j.containsKey("elements")) { elements = arr(j.get("elements")); break; }
        if (elements == null) return;
        for (Object o : elements) {
            Map<String, Object> el = obj(o);
            List<Object> from = arr(el.get("from")), to = arr(el.get("to"));
            Elem e = new Elem();
            e.x0 = num(from.get(0), 0) / 16; e.y0 = num(from.get(1), 0) / 16; e.z0 = num(from.get(2), 0) / 16;
            e.x1 = num(to.get(0), 16) / 16; e.y1 = num(to.get(1), 16) / 16; e.z1 = num(to.get(2), 16) / 16;
            Map<String, Object> rot = obj(el.get("rotation"));
            if (rot != null && num(rot.get("angle"), 0) != 0) {
                e.axis = String.valueOf(rot.get("axis")).charAt(0);
                e.angle = num(rot.get("angle"), 0);
                List<Object> org = arr(rot.get("origin"));
                e.ox = num(org.get(0), 8) / 16; e.oy = num(org.get(1), 8) / 16; e.oz = num(org.get(2), 8) / 16;
            }
            Map<String, Object> faces = obj(el.get("faces"));
            if (faces != null) for (int f = 0; f < 6; f++) {
                Map<String, Object> fd = obj(faces.get(FACES[f]));
                if (fd == null) continue;
                String tex = resolve(textures, String.valueOf(fd.get("texture")));
                if (tex == null) continue;
                List<Object> uv = arr(fd.get("uv"));
                float[] d = defaultUv(e, f);
                float u0 = uv != null ? (float) num(uv.get(0), 0) : d[0], v0 = uv != null ? (float) num(uv.get(1), 0) : d[1];
                float u1 = uv != null ? (float) num(uv.get(2), 16) : d[2], v1 = uv != null ? (float) num(uv.get(3), 16) : d[3];
                int tint = fd.containsKey("tintindex") ? tintFor(blockName) : -1;
                boolean translucent = blockName.contains("glass") || blockName.contains("ice") && !blockName.contains("packed") && !blockName.contains("blue")
                        || blockName.contains("slime") || blockName.contains("honey_block") || blockName.equals("water");
                e.faces[f] = new Face(texture(tex), texSize(tex), u0, v0, u1, v1, tint, translucent);
            }
            rotate(e, rx, ry);
            m.elems.add(e);
        }
    }

    private static String resolve(Map<String, String> t, String ref) {
        for (int k = 0; k < 8 && ref != null && ref.startsWith("#"); k++) ref = t.get(ref.substring(1));
        return ref;
    }

    private static float[] defaultUv(Elem e, int f) {
        return switch (f) {
            case 0, 1 -> new float[]{(float) e.x0 * 16, (float) e.z0 * 16, (float) e.x1 * 16, (float) e.z1 * 16};
            case 2, 3 -> new float[]{(float) e.x0 * 16, 16 - (float) e.y1 * 16, (float) e.x1 * 16, 16 - (float) e.y0 * 16};
            default -> new float[]{(float) e.z0 * 16, 16 - (float) e.y1 * 16, (float) e.z1 * 16, 16 - (float) e.y0 * 16};
        };
    }

    static int tintFor(String n) {
        if (n.contains("spruce_leaves")) return 0x619961;
        if (n.contains("birch_leaves")) return 0x80A755;
        if (n.contains("mangrove_leaves")) return 0x8DB127;
        if (n.contains("leaves") || n.contains("vine")) return 0x59AE30;
        if (n.contains("water") || n.contains("bubble")) return 0x3F76E4;
        if (n.contains("redstone_wire")) return 0xC81E1E;
        if (n.contains("lily_pad")) return 0x208030;
        if (n.contains("stem")) return 0x8FA83A;
        return 0x7CBD4F;   // grass, ferns, sugar cane
    }

    /** Block-state x/y rotations in 90-degree steps: turn the box and move its faces round with it. */
    private static void rotate(Elem e, int rx, int ry) {
        for (int k = 0; k < ((rx / 90) % 4 + 4) % 4; k++) {   // about X: y -> z
            double y0 = e.y0, y1 = e.y1, z0 = e.z0, z1 = e.z1;
            e.y0 = 1 - z1; e.y1 = 1 - z0; e.z0 = y0; e.z1 = y1;
            Face[] f = e.faces; e.faces = new Face[]{f[3], f[2], f[0], f[1], f[4], f[5]};   // south->down, north->up, down->north, up->south
        }
        for (int k = 0; k < ((ry / 90) % 4 + 4) % 4; k++) {   // about Y, clockwise seen from above: north -> east
            double x0 = e.x0, x1 = e.x1, z0 = e.z0, z1 = e.z1;
            e.x0 = 1 - z1; e.x1 = 1 - z0; e.z0 = x0; e.z1 = x1;
            Face[] f = e.faces; e.faces = new Face[]{f[0], f[1], f[4], f[5], f[3], f[2]};   // west->north, east->south, south->west, north->east
            if (e.axis == 'x') e.axis = 'z'; else if (e.axis == 'z') { e.axis = 'x'; e.angle = -e.angle; }
            double ox = e.ox; e.ox = 1 - e.oz; e.oz = ox;
        }
    }

    // ================================================================== the world

    /** A loaded area of one dimension: block state per voxel plus stored light. */
    public static final class World {
        final Map<Long, short[][]> blocks = new HashMap<>();
        final Map<Long, byte[][]> sky = new HashMap<>(), block = new HashMap<>();
        final List<String> states = new ArrayList<>();
        final Map<String, Short> ids = new HashMap<>();
        Model[] models;
        int minY = -64, sections = 24;

        short id(String s) { return ids.computeIfAbsent(s, k -> { states.add(k); return (short) (states.size() - 1); }); }

        static long ck(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

        // chunk lookups go through flat arrays (built once loading is done): no boxing on the hot path
        int gx0, gz0, gw, gh;
        short[][][] gBlocks;
        byte[][][] gSky, gBlock;

        int[][] tops;   // per chunk slot: highest non-air y of each column

        int top(int x, int z) {
            int sl = slot(x >> 4, z >> 4);
            if (sl < 0 || tops[sl] == null) return Integer.MIN_VALUE;
            return tops[sl][((z & 15) << 4) | (x & 15)];
        }

        void grid() {
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (long k : blocks.keySet()) { int cx = (int) (k >> 32), cz = (int) k; x0 = Math.min(x0, cx); z0 = Math.min(z0, cz); x1 = Math.max(x1, cx); z1 = Math.max(z1, cz); }
            if (x0 == Integer.MAX_VALUE) { gw = gh = 0; gBlocks = new short[0][][]; gSky = gBlock = new byte[0][][]; return; }
            gx0 = x0; gz0 = z0; gw = x1 - x0 + 1; gh = z1 - z0 + 1;
            gBlocks = new short[gw * gh][][]; gSky = new byte[gw * gh][][]; gBlock = new byte[gw * gh][][];
            for (long k : blocks.keySet()) {
                int i = ((int) (k >> 32) - gx0) * gh + ((int) k - gz0);
                gBlocks[i] = blocks.get(k); gSky[i] = sky.get(k); gBlock[i] = block.get(k);
            }
            tops = new int[gw * gh][];
            for (int i = 0; i < gBlocks.length; i++) {
                short[][] c = gBlocks[i];
                if (c == null) continue;
                int[] tp = new int[256];
                Arrays.fill(tp, Integer.MIN_VALUE);
                for (int s = c.length - 1; s >= 0; s--) {
                    if (c[s] == null) continue;
                    for (int col = 0; col < 256; col++) {
                        if (tp[col] != Integer.MIN_VALUE) continue;
                        for (int yy = 15; yy >= 0; yy--) if (c[s][(yy << 8) | col] != 0 && !states.get(c[s][(yy << 8) | col]).contains("air")) { tp[col] = minY + s * 16 + yy; break; }
                    }
                }
                tops[i] = tp;
            }
        }

        private int slot(int cx, int cz) {
            int a = cx - gx0, b = cz - gz0;
            return a < 0 || b < 0 || a >= gw || b >= gh ? -1 : a * gh + b;
        }

        int get(int x, int y, int z) {
            int sl = slot(x >> 4, z >> 4);
            short[][] c = sl < 0 ? null : gBlocks[sl];
            if (c == null) return 0;
            int s = (y - minY) >> 4;
            if (s < 0 || s >= c.length || c[s] == null) return 0;
            return c[s][((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
        }

        int light(boolean skyLight, int x, int y, int z, int def) {
            int sl = slot(x >> 4, z >> 4);
            byte[][] c = sl < 0 ? null : (skyLight ? gSky : gBlock)[sl];
            int s = (y - minY) >> 4;
            if (c == null || s < 0 || s >= c.length || c[s] == null) {
                if (!skyLight) return 0;
                return y > top(x, z) ? 15 : Math.min(def, 4);
            }
            int i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            return (c[s][i >> 1] >> ((i & 1) * 4)) & 15;
        }
    }

    /** Load every chunk with blocks between (x0,z0) and (x1,z1) from {@code regionDir}. */
    public World load(File regionDir, int x0, int z0, int x1, int z1) throws IOException {
        World w = new World();
        w.id("minecraft:air");
        for (int rx = Math.floorDiv(x0, 512); rx <= Math.floorDiv(x1, 512); rx++)
            for (int rz = Math.floorDiv(z0, 512); rz <= Math.floorDiv(z1, 512); rz++) {
                File f = new File(regionDir, "r." + rx + "." + rz + ".mca");
                if (!f.exists()) continue;
                byte[] data = Files.readAllBytes(f.toPath());   // a snapshot: the server may be writing
                for (int i = 0; i < 1024; i++) {
                    int cx = rx * 32 + (i & 31), cz = rz * 32 + (i >> 5);
                    if (cx * 16 + 15 < x0 || cx * 16 > x1 || cz * 16 + 15 < z0 || cz * 16 > z1) continue;
                    int loc = ((data[i * 4] & 255) << 16) | ((data[i * 4 + 1] & 255) << 8) | (data[i * 4 + 2] & 255);
                    if (loc == 0) continue;
                    int off = loc * 4096;
                    if (off + 5 > data.length) continue;
                    int len = ((data[off] & 255) << 24) | ((data[off + 1] & 255) << 16) | ((data[off + 2] & 255) << 8) | (data[off + 3] & 255);
                    int comp = data[off + 4];
                    if (len <= 1 || off + 4 + len > data.length) continue;
                    try {
                        InputStream raw = new ByteArrayInputStream(data, off + 5, len - 1);
                        InputStream in = comp == 2 ? new InflaterInputStream(raw) : comp == 1 ? new java.util.zip.GZIPInputStream(raw) : raw;
                        DataInputStream din = new DataInputStream(new BufferedInputStream(in));
                        int t = din.readByte(); din.readUTF();
                        if (t != 10) continue;
                        chunk(w, cx, cz, obj(nbt(din, 10)));
                    } catch (Exception ex) { /* a chunk mid-write: skip it */ }
                }
            }
        w.grid();
        w.models = new Model[w.states.size()];
        for (int i = 0; i < w.models.length; i++) w.models[i] = model(w.states.get(i));
        return w;
    }

    private void chunk(World w, int cx, int cz, Map<String, Object> root) {
        List<Object> secs = arr(root.get("sections"));
        if (secs == null) return;
        int minSec = Integer.MAX_VALUE, maxSec = Integer.MIN_VALUE;
        for (Object o : secs) { int y = ((Number) obj(o).get("Y")).intValue(); minSec = Math.min(minSec, y); maxSec = Math.max(maxSec, y); }
        w.minY = Math.min(w.minY, minSec * 16);
        int n = (maxSec - w.minY / 16) + 1;
        short[][] bl = new short[n][];
        byte[][] sk = new byte[n][], bk = new byte[n][];
        for (Object o : secs) {
            Map<String, Object> s = obj(o);
            int idx = ((Number) s.get("Y")).intValue() - w.minY / 16;
            if (idx < 0 || idx >= n) continue;
            if (s.get("SkyLight") instanceof byte[] b) sk[idx] = b;
            if (s.get("BlockLight") instanceof byte[] b) bk[idx] = b;
            Map<String, Object> bs = obj(s.get("block_states"));
            if (bs == null) continue;
            List<Object> pal = arr(bs.get("palette"));
            short[] ids = new short[pal.size()];
            boolean allAir = true;
            for (int k = 0; k < ids.length; k++) {
                // 26.x writes a plain string for blocks without properties, a compound otherwise
                Object pe = pal.get(k);
                Map<String, Object> e = obj(pe);
                String name = pe instanceof String str ? str : e.get("Name") instanceof String nm ? nm : String.valueOf(e.get(""));   // mixed lists wrap plain values as {"": value}
                Map<String, Object> props = e == null ? null : obj(e.get("Properties"));
                if (props != null && !props.isEmpty()) {
                    StringBuilder sb = new StringBuilder(name).append('[');
                    new TreeMap<>(props).forEach((a, b) -> sb.append(a).append('=').append(b).append(','));
                    sb.setCharAt(sb.length() - 1, ']');
                    name = sb.toString();
                }
                ids[k] = w.id(name);
                if (ids[k] != 0 && !name.endsWith(":air") && !name.contains("cave_air")) allAir = false;
            }
            if (allAir) continue;
            short[] vox = new short[4096];
            long[] data = bs.get("data") instanceof long[] l ? l : null;
            if (data == null) Arrays.fill(vox, ids[0]);
            else {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(ids.length - 1)), per = 64 / bits;
                long mask = (1L << bits) - 1;
                for (int i = 0; i < 4096; i++) {
                    int li = i / per;
                    if (li >= data.length) break;
                    int v = (int) ((data[li] >>> ((i % per) * bits)) & mask);
                    vox[i] = v < ids.length ? ids[v] : 0;
                }
            }
            bl[idx] = vox;
        }
        long k = World.ck(cx, cz);
        w.blocks.put(k, bl);
        w.sky.put(k, sk);
        w.block.put(k, bk);
    }

    // ================================================================== extra boxes (dragons)

    /** An oriented textured box: corner + unit axes r, u, f with sizes (world units). */
    public record Box(String block, boolean glow, double[] corner, double[] r, double[] u, double[] f, double sx, double sy, double sz) {}

    // ================================================================== rendering

    /** How a scene looks: sun direction, daylight (0 night .. 1 noon), sky colours, fog distance. */
    public static final class Look {
        public double sunX = -0.45, sunY = 0.75, sunZ = 0.35;
        public double daylight = 1;
        public int skyTop = 0x6E9CE6, skyHorizon = 0xC8DCF5, fogColor = 0xC8DCF5;
        public double fogStart = 170, fogEnd = 460, maxDist = 470;
        public boolean stars;
        public int sunColor = 0xFFF2D6;

        public static Look day() { return new Look(); }

        public static Look sunset() {
            Look l = new Look();
            l.sunX = -0.85; l.sunY = 0.22; l.sunZ = 0.3; l.daylight = 0.85;
            l.skyTop = 0x34508F; l.skyHorizon = 0xF2A46A; l.fogColor = 0xE8A878; l.sunColor = 0xFFC48A;
            return l;
        }

        /** The hub: a dark ember-red midnight. */
        public static Look inferno() {
            Look l = new Look();
            l.sunX = 0.3; l.sunY = 0.6; l.sunZ = -0.4; l.daylight = 0.5;
            l.skyTop = 0x1A0607; l.skyHorizon = 0x6A1E0E; l.fogColor = 0x4A140A; l.fogStart = 190; l.fogEnd = 520; l.maxDist = 520; l.stars = true; l.sunColor = 0xFFB088;
            return l;
        }

        public static Look frozenNight() {
            Look l = new Look();
            l.sunX = 0.2; l.sunY = 0.7; l.sunZ = 0.5; l.daylight = 0.5;
            l.skyTop = 0x070B1E; l.skyHorizon = 0x24345E; l.fogColor = 0x1C2848; l.stars = true; l.sunColor = 0xBFD4FF;
            return l;
        }
    }

    /** Camera: position and a point to look at. */
    public record Cam(double x, double y, double z, double lx, double ly, double lz, double fov) {
        public static Cam yawPitch(double x, double y, double z, double yaw, double pitch, double fov) {
            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            return new Cam(x, y, z, x - Math.sin(yr) * Math.cos(pr), y - Math.sin(pr), z + Math.cos(yr) * Math.cos(pr), fov);
        }
    }

    public BufferedImage render(World w, Look look, Cam cam, List<Box> boxes, int width, int height, int ss) {
        double fx = cam.lx - cam.x, fy = cam.ly - cam.y, fz = cam.lz - cam.z, fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl; fy /= fl; fz /= fl;
        double rx = -fz, rz = fx, rl = Math.sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl;
        double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy;
        double tan = Math.tan(Math.toRadians(cam.fov) / 2), aspect = (double) width / height;
        double sl = Math.sqrt(look.sunX * look.sunX + look.sunY * look.sunY + look.sunZ * look.sunZ);
        double[] sun = {look.sunX / sl, look.sunY / sl, look.sunZ / sl};
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final double FX = fx, FY = fy, FZ = fz, RX = rx, RZ = rz, UX = ux, UY = uy, UZ = uz;
        IntStream.range(0, height).parallel().forEach(py -> {
            for (int px = 0; px < width; px++) {
                double r = 0, g = 0, b = 0;
                for (int s = 0; s < ss * ss; s++) {
                    double ox = (s % ss + 0.5) / ss, oy = (s / ss + 0.5) / ss;
                    double sx = (2 * (px + ox) / width - 1) * tan * aspect, sy = (1 - 2 * (py + oy) / height) * tan;
                    double dx = FX + sx * RX + sy * UX, dy = FY + sy * UY, dz = FZ + sx * RZ + sy * UZ;
                    double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    int c = shade(w, look, sun, boxes, cam.x, cam.y, cam.z, dx / dl, dy / dl, dz / dl);
                    r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
                }
                int n = ss * ss;
                img.setRGB(px, py, ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n));
            }
        });
        return img;
    }

    /** Result of a ray: distance, the voxel hit, its face normal and the surface colour (ARGB, alpha = coverage). */
    private static final class Hit { double t, hx, hy, hz; int vx, vy, vz, nx, ny, nz; int color; Model model; boolean glow; }

    private int shade(World w, Look look, double[] sun, List<Box> boxes, double ox, double oy, double oz, double dx, double dy, double dz) {
        double ar = 0, ag = 0, ab = 0, trans = 1, t0 = 0;
        double cx = ox, cy = oy, cz = oz;
        int skyCol = sky(look, dx, dy, dz, sun);
        boolean startInWater = w.models[w.get((int) Math.floor(ox), (int) Math.floor(oy), (int) Math.floor(oz))].water;
        for (int layer = 0; layer < 6 && trans > 0.03; layer++) {
            Hit h = trace(w, boxes, cx, cy, cz, dx, dy, dz, look.maxDist - t0, layer == 0 && startInWater);
            if (h == null) break;
            double t = t0 + h.t;
            int col = light(w, look, sun, boxes, h);
            double fog = Math.max(0, Math.min(1, (t - look.fogStart) / (look.fogEnd - look.fogStart)));
            double a = ((h.color >>> 24) & 255) / 255.0;
            double r = ((col >> 16) & 255) * (1 - fog) + ((look.fogColor >> 16) & 255) * fog;
            double g = ((col >> 8) & 255) * (1 - fog) + ((look.fogColor >> 8) & 255) * fog;
            double b = (col & 255) * (1 - fog) + (look.fogColor & 255) * fog;
            ar += trans * a * r; ag += trans * a * g; ab += trans * a * b; trans *= 1 - a;
            if (a >= 0.999) return (clamp(ar) << 16) | (clamp(ag) << 8) | clamp(ab);
            // through glass/water: carry on just past this surface
            cx = h.hx + dx * 1e-3; cy = h.hy + dy * 1e-3; cz = h.hz + dz * 1e-3; t0 = t + 1e-3;
        }
        ar += trans * ((skyCol >> 16) & 255); ag += trans * ((skyCol >> 8) & 255); ab += trans * (skyCol & 255);
        return (clamp(ar) << 16) | (clamp(ag) << 8) | clamp(ab);
    }

    private static int clamp(double v) { return Math.max(0, Math.min(255, (int) v)); }

    private static int sky(Look l, double dx, double dy, double dz, double[] sun) {
        double t = Math.max(0, Math.min(1, dy * 1.4 + 0.15));
        double r = lerp((l.skyHorizon >> 16) & 255, (l.skyTop >> 16) & 255, t), g = lerp((l.skyHorizon >> 8) & 255, (l.skyTop >> 8) & 255, t), b = lerp(l.skyHorizon & 255, l.skyTop & 255, t);
        double s = dx * sun[0] + dy * sun[1] + dz * sun[2];
        if (s > 0.9985) { r = (l.sunColor >> 16) & 255; g = (l.sunColor >> 8) & 255; b = l.sunColor & 255; }
        else if (s > 0.97) { double k = (s - 0.97) / 0.0285 * 0.5; r += (((l.sunColor >> 16) & 255) - r) * k * k; g += (((l.sunColor >> 8) & 255) - g) * k * k; b += ((l.sunColor & 255) - b) * k * k; }
        if (l.stars && dy > 0.05) {
            long h = Double.doubleToLongBits(Math.floor(dx * 900)) * 31 + Double.doubleToLongBits(Math.floor(dy * 900)) * 17 + Double.doubleToLongBits(Math.floor(dz * 900));
            h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
            if ((h & 1023) == 0) { double k = 140 + (h >>> 40 & 115); r = Math.max(r, k); g = Math.max(g, k * 0.95); b = Math.max(b, k * 0.9); }
        }
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** The nearest surface along the ray (blocks and boxes), cutout pixels skipped. */
    private Hit trace(World w, List<Box> boxes, double ox, double oy, double oz, double dx, double dy, double dz, double maxT, boolean inWater) {
        Hit best = null;
        if (boxes != null) for (Box bx : boxes) { Hit h = hitBox(bx, ox, oy, oz, dx, dy, dz); if (h != null && h.t < maxT && (best == null || h.t < best.t)) best = h; }
        if (best != null) maxT = best.t;
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int stx = dx > 0 ? 1 : -1, sty = dy > 0 ? 1 : -1, stz = dz > 0 ? 1 : -1;
        double tdx = Math.abs(1 / dx), tdy = Math.abs(1 / dy), tdz = Math.abs(1 / dz);
        double tmx = (dx > 0 ? x + 1 - ox : ox - x) * tdx, tmy = (dy > 0 ? y + 1 - oy : oy - y) * tdy, tmz = (dz > 0 ? z + 1 - oz : oz - z) * tdz;
        double t = 0;
        int axis = -1;
        boolean water = inWater;
        while (t < maxT) {
            int id = w.get(x, y, z);
            Model m = w.models[id];
            if (!m.invisible) {
                double tExit = Math.min(tmx, Math.min(tmy, tmz));
                if (m.water && m.elems.size() == 1 && m.elems.getFirst().faces[1] != null && m.elems.getFirst().faces[1].translucent && water) {
                    // inside water: only its surface counts (when the ray leaves it)
                } else {
                    Hit h = (m.opaqueCube && axis >= 0) ? cubeHit(m, x, y, z, ox, oy, oz, dx, dy, dz, t, axis, stx, sty, stz) : elemHit(m, x, y, z, ox, oy, oz, dx, dy, dz, t, tExit);
                    if (h != null && h.t < maxT) return h;
                }
                water = m.water;
            } else water = false;
            if (tmx < tmy && tmx < tmz) { x += stx; t = tmx; tmx += tdx; axis = 0; }
            else if (tmy < tmz) { y += sty; t = tmy; tmy += tdy; axis = 1; }
            else { z += stz; t = tmz; tmz += tdz; axis = 2; }
            if (y < w.minY || y > 330) break;
        }
        return best;
    }

    private Hit cubeHit(Model m, int x, int y, int z, double ox, double oy, double oz, double dx, double dy, double dz, double t, int axis, int stx, int sty, int stz) {
        Hit h = new Hit();
        h.t = t; h.vx = x; h.vy = y; h.vz = z; h.model = m;
        h.hx = ox + dx * t; h.hy = oy + dy * t; h.hz = oz + dz * t;
        int face;
        if (axis == 0) { h.nx = -stx; face = stx > 0 ? 4 : 5; }
        else if (axis == 1) { h.ny = -sty; face = sty > 0 ? 0 : 1; }
        else { h.nz = -stz; face = stz > 0 ? 2 : 3; }
        h.color = sample(m.elems.getFirst(), face, h.hx - x, h.hy - y, h.hz - z);
        h.glow = m.emissive;
        return h;
    }

    private Hit elemHit(Model m, int x, int y, int z, double ox, double oy, double oz, double dx, double dy, double dz, double tEnter, double tExit) {
        Hit best = null;
        double lx = ox - x, ly = oy - y, lz = oz - z;
        for (Elem e : m.elems) {
            double px = lx, py = ly, pz = lz, qx = dx, qy = dy, qz = dz;
            if (e.axis != 0) {   // into the element's own (unrotated) frame
                double a = Math.toRadians(-e.angle), c = Math.cos(a), s = Math.sin(a);
                double[] p = rot(e.axis, px - e.ox, py - e.oy, pz - e.oz, c, s), q = rot(e.axis, qx, qy, qz, c, s);
                px = p[0] + e.ox; py = p[1] + e.oy; pz = p[2] + e.oz; qx = q[0]; qy = q[1]; qz = q[2];
            }
            double tn = -1e9, tf = 1e9; int ax = -1; boolean neg = false;
            double[] o = {px, py, pz}, d = {qx, qy, qz}, lo = {e.x0, e.y0, e.z0}, hi = {e.x1, e.y1, e.z1};
            boolean miss = false;
            for (int k = 0; k < 3 && !miss; k++) {
                if (Math.abs(d[k]) < 1e-12) { if (o[k] < lo[k] - 1e-9 || o[k] > hi[k] + 1e-9) miss = true; continue; }
                double t1 = (lo[k] - o[k]) / d[k], t2 = (hi[k] - o[k]) / d[k];
                boolean n = t1 > t2; if (n) { double tt = t1; t1 = t2; t2 = tt; }
                if (t1 > tn) { tn = t1; ax = k; neg = !n; }
                tf = Math.min(tf, t2);
            }
            // flat elements (cross plants, panes) have zero thickness on one axis
            if (miss || tn > tf || tf < tEnter - 1e-6 || tn > tExit + 1e-6 || ax < 0 || tn < tEnter - 1e-6) continue;
            if (best != null && tn >= best.t) continue;
            int face = ax == 0 ? (neg ? 4 : 5) : ax == 1 ? (neg ? 0 : 1) : (neg ? 2 : 3);
            // a flat element hit from its back: use the opposite face
            if (e.faces[face] == null) face = face ^ 1;
            if (e.faces[face] == null) continue;
            int c = sample(e, face, px + qx * tn, py + qy * tn, pz + qz * tn);
            if (((c >>> 24) & 255) < 110) continue;   // cutout pixel
            Hit h = new Hit();
            h.t = tn; h.vx = x; h.vy = y; h.vz = z; h.model = m; h.color = c; h.glow = m.emissive;
            h.hx = ox + dx * tn; h.hy = oy + dy * tn; h.hz = oz + dz * tn;
            int[] nrm = {0, 0, 0}; nrm[ax] = neg ? -1 : 1;
            if (e.axis != 0) { h.nx = 0; h.ny = 1; h.nz = 0; if (ax != 1) { h.ny = 0; h.nx = (int) -Math.signum(dx); h.nz = 0; } }
            else { h.nx = nrm[0]; h.ny = nrm[1]; h.nz = nrm[2]; }
            best = h;
        }
        return best;
    }

    private static double[] rot(char axis, double x, double y, double z, double c, double s) {
        return switch (axis) {
            case 'x' -> new double[]{x, y * c - z * s, y * s + z * c};
            case 'y' -> new double[]{x * c + z * s, y, -x * s + z * c};
            default -> new double[]{x * c - y * s, x * s + y * c, z};
        };
    }

    /** Texture colour at a point on an element face (block-local coordinates). */
    private static int sample(Elem e, int face, double x, double y, double z) {
        Face f = e.faces[face];
        if (f == null) return 0;
        double u, v;   // 0..1 across the face, then into the face's uv rectangle
        switch (face) {
            case 0, 1 -> { u = x; v = face == 1 ? z : 1 - z; }
            case 2 -> { u = 1 - x; v = 1 - y; }
            case 3 -> { u = x; v = 1 - y; }
            case 4 -> { u = z; v = 1 - y; }
            default -> { u = 1 - z; v = 1 - y; }
        }
        // map the block-space position into the element's uv window
        double[] span = switch (face) {
            case 0, 1 -> new double[]{e.x0, e.x1, face == 1 ? e.z0 : 1 - e.z1, face == 1 ? e.z1 : 1 - e.z0};
            case 2 -> new double[]{1 - e.x1, 1 - e.x0, 1 - e.y1, 1 - e.y0};
            case 3 -> new double[]{e.x0, e.x1, 1 - e.y1, 1 - e.y0};
            case 4 -> new double[]{e.z0, e.z1, 1 - e.y1, 1 - e.y0};
            default -> new double[]{1 - e.z1, 1 - e.z0, 1 - e.y1, 1 - e.y0};
        };
        double fu = span[1] - span[0] < 1e-6 ? 0.5 : (u - span[0]) / (span[1] - span[0]);
        double fv = span[3] - span[2] < 1e-6 ? 0.5 : (v - span[2]) / (span[3] - span[2]);
        double tu = (f.u0 + (f.u1 - f.u0) * fu) / 16, tv = (f.v0 + (f.v1 - f.v0) * fv) / 16;
        int px = Math.floorMod((int) Math.floor(tu * f.size), f.size), py = Math.floorMod((int) Math.floor(tv * f.size), f.size);
        int c = f.px[Math.min(f.px.length - 1, py * f.size + px)];
        if (f.tint >= 0) c = (c & 0xFF000000) | (((((c >> 16) & 255) * ((f.tint >> 16) & 255)) / 255) << 16) | (((((c >> 8) & 255) * ((f.tint >> 8) & 255)) / 255) << 8) | (((c & 255) * (f.tint & 255)) / 255);
        if (f.translucent) c = (c & 0x00FFFFFF) | ((int) (Math.min(255, ((c >>> 24) & 255)) * 0.72) << 24);
        return c;
    }

    private Hit hitBox(Box b, double ox, double oy, double oz, double dx, double dy, double dz) {
        double px = ox - b.corner()[0], py = oy - b.corner()[1], pz = oz - b.corner()[2];
        double[] o = {px * b.r()[0] + py * b.r()[1] + pz * b.r()[2], px * b.u()[0] + py * b.u()[1] + pz * b.u()[2], px * b.f()[0] + py * b.f()[1] + pz * b.f()[2]};
        double[] d = {dx * b.r()[0] + dy * b.r()[1] + dz * b.r()[2], dx * b.u()[0] + dy * b.u()[1] + dz * b.u()[2], dx * b.f()[0] + dy * b.f()[1] + dz * b.f()[2]};
        double[] hi = {b.sx(), Math.max(b.sy(), 0.02), b.sz()};
        double tn = -1e9, tf = 1e9; int ax = -1; boolean neg = false;
        for (int k = 0; k < 3; k++) {
            if (Math.abs(d[k]) < 1e-12) { if (o[k] < 0 || o[k] > hi[k]) return null; continue; }
            double t1 = -o[k] / d[k], t2 = (hi[k] - o[k]) / d[k];
            boolean n = t1 > t2; if (n) { double tt = t1; t1 = t2; t2 = tt; }
            if (t1 > tn) { tn = t1; ax = k; neg = !n; }
            tf = Math.min(tf, t2);
        }
        if (tn > tf || tf < 0 || tn < 0 || ax < 0) return null;
        Model m = model(b.block());
        Elem e = m.elems.isEmpty() ? null : m.elems.getFirst();
        double hu = o[(ax + 1) % 3] + d[(ax + 1) % 3] * tn, hv = o[(ax + 2) % 3] + d[(ax + 2) % 3] * tn;
        int c = 0xFF404040;
        if (e != null) {
            Face f = e.faces[1] != null ? e.faces[1] : e.faces[2];
            if (f != null) {
                int ix = Math.floorMod((int) Math.floor(hu * f.size()), f.size()), iy = Math.floorMod((int) Math.floor(hv * f.size()), f.size());
                c = f.px()[iy * f.size() + ix] | 0xFF000000;
            }
        }
        Hit h = new Hit();
        h.t = tn; h.color = c; h.model = m; h.glow = b.glow() || m.emissive;
        h.hx = ox + dx * tn; h.hy = oy + dy * tn; h.hz = oz + dz * tn;
        h.vx = (int) Math.floor(h.hx); h.vy = (int) Math.floor(h.hy); h.vz = (int) Math.floor(h.hz);
        double[] axv = ax == 0 ? b.r() : ax == 1 ? b.u() : b.f();
        double s = neg ? -1 : 1;
        h.nx = (int) Math.round(axv[0] * s); h.ny = (int) Math.round(axv[1] * s); h.nz = (int) Math.round(axv[2] * s);
        if (h.nx == 0 && h.ny == 0 && h.nz == 0) h.ny = 1;
        return h;
    }

    /** Light a hit: stored sky/block light, face shading, sun shadow, ambient occlusion. */
    private int light(World w, Look look, double[] sun, List<Box> boxes, Hit h) {
        int c = h.color;
        double r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        if (h.glow) return (clamp(r * 1.05) << 16) | (clamp(g * 1.05) << 8) | clamp(b * 1.05);
        int ax = h.vx + h.nx, ay = h.vy + h.ny, az = h.vz + h.nz;
        int sk = Math.max(w.light(true, ax, ay, az, 15), w.light(true, h.vx, h.vy, h.vz, 0));
        int bl = Math.max(w.light(false, ax, ay, az, 0), w.light(false, h.vx, h.vy, h.vz, 0));
        double face = h.ny > 0 ? 1.0 : h.ny < 0 ? 0.5 : h.nz != 0 ? 0.8 : 0.62;
        double ndl = Math.max(0, h.nx * sun[0] + h.ny * sun[1] + h.nz * sun[2]);
        double direct = 0;
        if (ndl > 0 && sk >= 8) {
            Hit sh = trace(w, boxes, h.hx + h.nx * 0.002 + sun[0] * 0.01, h.hy + h.ny * 0.002 + sun[1] * 0.01, h.hz + h.nz * 0.002 + sun[2] * 0.01, sun[0], sun[1], sun[2], 120, false);
            direct = sh == null ? ndl : 0;
        }
        double skyL = Math.pow(sk / 15.0, 1.6) * look.daylight;
        double sunPart = skyL * (0.62 * face + 0.75 * direct);
        double blockL = Math.pow(bl / 15.0, 1.5) * 0.95;
        double ao = occlusion(w, h);
        double lr = Math.max(sunPart * ((look.sunColor >> 16) & 255) / 255.0, blockL * face * 1.0) * ao + 0.035;
        double lg = Math.max(sunPart * ((look.sunColor >> 8) & 255) / 255.0, blockL * face * 0.82) * ao + 0.03;
        double lb = Math.max(sunPart * (look.sunColor & 255) / 255.0, blockL * face * 0.6) * ao + 0.035;
        return (clamp(r * lr * 1.15) << 16) | (clamp(g * lg * 1.15) << 8) | clamp(b * lb * 1.15);
    }

    /** Darken a face near solid neighbours in the layer it faces (Minecraft-style smooth-lighting corners). */
    private double occlusion(World w, Hit h) {
        int ax = h.vx + h.nx, ay = h.vy + h.ny, az = h.vz + h.nz;
        double fx = h.hx - Math.floor(h.hx), fy = h.hy - Math.floor(h.hy), fz = h.hz - Math.floor(h.hz);
        double occ = 0;
        for (int a = -1; a <= 1; a++)
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                int x = ax, y = ay, z = az;
                double du, dv;
                if (h.nx != 0) { y += a; z += b; du = a == 0 ? 0 : (a > 0 ? 1 - fy : fy); dv = b == 0 ? 0 : (b > 0 ? 1 - fz : fz); }
                else if (h.ny != 0) { x += a; z += b; du = a == 0 ? 0 : (a > 0 ? 1 - fx : fx); dv = b == 0 ? 0 : (b > 0 ? 1 - fz : fz); }
                else { x += a; y += b; du = a == 0 ? 0 : (a > 0 ? 1 - fx : fx); dv = b == 0 ? 0 : (b > 0 ? 1 - fy : fy); }
                if (!w.models[w.get(x, y, z)].opaqueCube) continue;
                double dist = Math.sqrt(du * du + dv * dv);
                occ += Math.max(0, 1 - dist / 0.9) * (a != 0 && b != 0 ? 0.6 : 1);
            }
        return 1 - Math.min(0.55, occ * 0.22);
    }
}
