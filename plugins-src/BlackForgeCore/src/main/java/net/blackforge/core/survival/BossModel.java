package net.blackforge.core.survival;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Function;

/**
 * The look of a world boss: a model built out of display blocks (and a few items) that follows an invisible mob, which keeps doing
 * the fighting. Parts belong to groups (head, arms, legs...) that swing around a pivot, so the boss walks, breathes and raises its
 * arms when it uses an ability. Coordinates are in blocks, from the boss's feet, with the front towards +z.
 */
final class BossModel {
    /** How a group is posed right now: rotation (radians) around its pivot, plus a shift. */
    record Pose(float rx, float ry, float rz, float dx, float dy, float dz) {}

    /** What the animations get to look at. */
    record State(double t, double walk, double spd, double pulse) {}

    static Pose rot(double x, double y, double z) { return new Pose((float) x, (float) y, (float) z, 0, 0, 0); }
    static Pose mv(double x, double y, double z) { return new Pose(0, 0, 0, (float) x, (float) y, (float) z); }
    static final Pose REST = new Pose(0, 0, 0, 0, 0, 0);

    static final class Part {
        final String group;
        final Material block;
        final ItemStack item;
        final float cx, cy, cz, sx, sy, sz;
        float rx, ry, rz;
        boolean glow;
        String model;   // an item model of the resource pack (the whole group in one piece), instead of a block or item
        Part(String group, Material block, ItemStack item, double cx, double cy, double cz, double sx, double sy, double sz) {
            this.group = group; this.block = block; this.item = item;
            this.cx = (float) cx; this.cy = (float) cy; this.cz = (float) cz; this.sx = (float) sx; this.sy = (float) sy; this.sz = (float) sz;
        }
        Part r(double x, double y, double z) { rx = (float) x; ry = (float) y; rz = (float) z; return this; }
        Part glow() { glow = true; return this; }
    }

    private static final class Group {
        final String parent;
        final float px, py, pz;
        Function<State, Pose> anim;
        Group(String parent, double px, double py, double pz, Function<State, Pose> anim) {
            this.parent = parent; this.px = (float) px; this.py = (float) py; this.pz = (float) pz; this.anim = anim;
        }
    }

    private record Fx(double x, double y, double z, Particle p, int n, double chance, double spread) {}

    private final List<Part> parts = new ArrayList<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final List<Fx> fx = new ArrayList<>();
    private final Random rnd = new Random();

    // runtime
    private LivingEntity host;
    private final List<Display> shown = new ArrayList<>();
    private BukkitTask task;
    private Location last;
    private double walk, spd, pulse, t;

    // ------------------------------------------------------------------ building a model

    private Part block(String g, Material m, double cx, double cy, double cz, double sx, double sy, double sz) {
        Part p = new Part(g, m, null, cx, cy, cz, sx, sy, sz);
        parts.add(p);
        return p;
    }

    private Part item(String g, Material m, double cx, double cy, double cz, double size) {
        Part p = new Part(g, null, new ItemStack(m), cx, cy, cz, size, size, size);
        parts.add(p);
        return p;
    }

    private void group(String name, String parent, double px, double py, double pz, Function<State, Pose> anim) {
        groups.put(name, new Group(parent, px, py, pz, anim));
    }

    private void spark(double x, double y, double z, Particle p, int n, double chance, double spread) { fx.add(new Fx(x, y, z, p, n, chance, spread)); }

private float scale = 3f;

/** The rig the pack generator wrote for this boss: its groups (parents, pivots) and which of them have a model. */
private void rig(String id) {
    try (java.io.InputStream in = BossModel.class.getResourceAsStream("/bossrig/" + id + ".json")) {
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        scale = o.get("scale").getAsFloat();
        for (com.google.gson.JsonElement e : o.getAsJsonArray("groups")) {
            com.google.gson.JsonObject g = e.getAsJsonObject();
            com.google.gson.JsonArray pv = g.getAsJsonArray("pivot");
            group(g.get("name").getAsString(), g.get("parent").isJsonNull() ? null : g.get("parent").getAsString(), pv.get(0).getAsDouble(), pv.get(1).getAsDouble(), pv.get(2).getAsDouble(), st -> REST);
        }
        for (com.google.gson.JsonElement e : o.getAsJsonArray("models")) {
            String name = e.getAsString();
            Part p = new Part(name, null, null, 0, 0, 0, 1, 1, 1);
            p.model = "boss/" + id + "_" + name.toLowerCase(java.util.Locale.ROOT);
            p.glow = name.endsWith("_glow");
            parts.add(p);
        }
    } catch (Exception ex) {
        throw new IllegalStateException("boss rig " + id + ": " + ex.getMessage(), ex);
    }
}

/** How a group moves; its glowing twin (name_glow) is a child group and moves with it. */
private void anim(String name, Function<State, Pose> f) {
    Group g = groups.get(name);
    if (g != null) g.anim = f;
}

private static float s(double v) { return (float) v; }

    private static Pose legSwing(State st, double phase, double amp) { return rot(Math.sin(st.walk + phase) * amp * st.spd, 0, 0); }

    private static Pose breathe(State st, double amp) { return mv(0, Math.sin(st.t * 1.8) * amp, 0); }

    // ------------------------------------------------------------------ the five bosses
    // The models themselves (boxes and textures) are made by tools/gen_boss_models.py; here they only get their movement.

    static BossModel of(String id) {
        BossModel m = new BossModel();
        switch (id) {
            case "ravager" -> m.ravager();
            case "colossus" -> m.colossus();
            case "revenant" -> m.revenant();
            case "titan" -> m.titan();
            case "brute" -> m.brute();
            default -> { return null; }
        }
        return m;
    }

    /** Molten Ravager: a beast of black stone and lava with a mane of fire. */
    private void ravager() {
        rig("ravager");
        anim("body", st -> new Pose((float) (-st.pulse * 0.1), 0, 0, 0, (float) (Math.sin(st.t * 1.8) * 0.05 + Math.abs(Math.sin(st.walk)) * 0.08 * st.spd), 0));
        anim("head", st -> rot(-st.pulse * 0.55 + Math.sin(st.t * 0.9) * 0.05, Math.sin(st.t * 0.6) * 0.3, 0));
        anim("jaw", st -> rot(0.12 + st.pulse * 0.5 + Math.sin(st.t * 2.2) * 0.04, 0, 0));
        anim("tail", st -> rot(0, Math.sin(st.t * 2.0) * 0.45, 0));
        anim("legFL", st -> legSwing(st, 0, 0.6)); anim("legBR", st -> legSwing(st, 0, 0.6));
        anim("legFR", st -> legSwing(st, Math.PI, 0.6)); anim("legBL", st -> legSwing(st, Math.PI, 0.6));
        for (int i = 0; i < 5; i++) block("body", Material.FIRE, 0, 4.2, -1.8 + i * 0.9 + 0.45, 1.5, 1.5, 1.5).glow();   // the mane
        block("tail", Material.FIRE, 0, 3.0, -4.95, 1.6, 1.6, 1.6).glow();
        spark(0, 4.8, 0, Particle.FLAME, 2, 0.8, 1.6);
        spark(0, 1.5, 0, Particle.LAVA, 1, 0.12, 1.4);
        spark(0, 3.6, 3.8, Particle.SMALL_FLAME, 1, 0.5, 0.3);
    }

    /** Bone Colossus: a skeleton far too big to be alive, with a soul burning in its ribs. */
    private void colossus() {
        rig("colossus");
        anim("body", st -> new Pose((float) (-st.pulse * 0.08), 0, 0, 0, (float) (Math.sin(st.t * 1.6) * 0.06), 0));
        anim("head", st -> rot(-st.pulse * 0.45 + Math.sin(st.t * 0.8) * 0.06, Math.sin(st.t * 0.5) * 0.35, Math.sin(st.t * 0.7) * 0.05));
        anim("jaw", st -> rot(0.1 + st.pulse * 0.5 + Math.abs(Math.sin(st.t * 1.4)) * 0.08, 0, 0));
        anim("armL", st -> rot(-Math.sin(st.walk) * 0.5 * st.spd - st.pulse * 1.5, 0, -0.08 - st.pulse * 0.4));
        anim("armR", st -> rot(Math.sin(st.walk) * 0.5 * st.spd - st.pulse * 1.5, 0, 0.08 + st.pulse * 0.4));
        anim("legL", st -> legSwing(st, 0, 0.5)); anim("legR", st -> legSwing(st, Math.PI, 0.5));
        block("body", Material.SOUL_FIRE, 0, 3.4, 0.25, 1.3, 1.3, 1.3).glow();
        spark(0, 3.6, 0.2, Particle.SOUL_FIRE_FLAME, 1, 0.7, 0.5);
        spark(0, 6.0, 0.8, Particle.SOUL, 1, 0.25, 0.3);
    }

    /** Void Revenant: a hooded wraith that floats on a ragged robe, circled by shards of the void. */
    private void revenant() {
        rig("revenant");
        anim("body", st -> new Pose((float) (-st.pulse * 0.15 + st.spd * 0.18), 0, (float) (Math.sin(st.t * 0.9) * 0.04), 0, (float) (0.9 + Math.sin(st.t * 1.4) * 0.35), 0));
        anim("head", st -> rot(-st.pulse * 0.4 + Math.sin(st.t * 1.1) * 0.05, Math.sin(st.t * 0.6) * 0.4, 0));
        anim("armR", st -> rot(-0.7 - st.pulse * 1.0 + Math.sin(st.t * 1.6) * 0.06, 0, 0.1));
        anim("armL", st -> rot(-0.4 - st.pulse * 0.9 + Math.sin(st.t * 1.6 + 1) * 0.06, 0, -0.1));
        anim("hem", st -> rot(Math.sin(st.t * 2.0) * 0.06, 0, Math.sin(st.t * 1.7) * 0.06));
        anim("orbs", st -> rot(0, st.t * 1.6, 0));
        spark(0, 4.5, 0, Particle.PORTAL, 4, 0.8, 2.0);
        spark(0, 1.8, 0, Particle.SMOKE, 2, 0.6, 1.2);
        spark(0, 5.4, 0.8, Particle.WITCH, 1, 0.3, 0.2);
    }

    /** Frost Titan: a glacier that learned to walk, with a heart of sea light and a crown of icicles. */
    private void titan() {
        rig("titan");
        anim("body", st -> new Pose((float) (-st.pulse * 0.1), 0, (float) (Math.sin(st.walk) * 0.04 * st.spd), 0, (float) (Math.sin(st.t * 1.2) * 0.05 + Math.abs(Math.sin(st.walk)) * 0.1 * st.spd), 0));
        anim("head", st -> rot(-st.pulse * 0.4 + Math.sin(st.t * 0.6) * 0.04, Math.sin(st.t * 0.4) * 0.3, 0));
        anim("armL", st -> rot(-Math.sin(st.walk) * 0.45 * st.spd - st.pulse * 1.8 + Math.sin(st.t * 1.2) * 0.04, 0, -0.06 - st.pulse * 0.3));
        anim("armR", st -> rot(Math.sin(st.walk) * 0.45 * st.spd - st.pulse * 1.8 + Math.sin(st.t * 1.2 + 2) * 0.04, 0, 0.06 + st.pulse * 0.3));
        anim("legL", st -> legSwing(st, 0, 0.35)); anim("legR", st -> legSwing(st, Math.PI, 0.35));
        spark(0, 4.5, 0, Particle.SNOWFLAKE, 5, 0.9, 2.2);
        spark(0, 1.0, 0, Particle.CLOUD, 1, 0.3, 1.8);
        spark(0, 7.4, 0, Particle.END_ROD, 1, 0.2, 0.6);
    }

    /** Storm Brute: a broad, copper-and-gold brawler with lightning rods bristling off its back. */
    private void brute() {
        rig("brute");
        anim("body", st -> new Pose((float) (-st.pulse * 0.12), (float) (Math.sin(st.walk) * 0.1 * st.spd), 0, 0, (float) (Math.sin(st.t * 2.2) * 0.04 + Math.abs(Math.sin(st.walk)) * 0.08 * st.spd), 0));
        anim("head", st -> rot(-st.pulse * 0.4 + Math.sin(st.t * 1.0) * 0.05, Math.sin(st.t * 0.7) * 0.25, 0));
        anim("armL", st -> rot(-Math.sin(st.walk) * 0.55 * st.spd - st.pulse * 1.6, 0, -0.1));
        anim("armR", st -> rot(Math.sin(st.walk) * 0.55 * st.spd - 0.5 - st.pulse * 1.4, 0, 0.1));
        anim("legL", st -> legSwing(st, 0, 0.55)); anim("legR", st -> legSwing(st, Math.PI, 0.55));
        spark(0, 5.7, -1.0, Particle.ELECTRIC_SPARK, 3, 0.9, 1.6);
        spark(1.95, 6.0, 0, Particle.ELECTRIC_SPARK, 2, 0.7, 0.3);
        spark(-1.95, 6.0, 0, Particle.ELECTRIC_SPARK, 2, 0.7, 0.3);
    }

    // ------------------------------------------------------------------ running it

    /** Show the model on {@code host}; it follows it until the host is gone. */
    void attach(Plugin plugin, LivingEntity host, String tag) {
        this.host = host;
        Location at = host.getLocation();
        last = at.clone();
        for (Part p : parts) {
            Display d;
            if (p.model != null) {
                ItemStack is = new ItemStack(Material.PAPER);
                is.editMeta(m -> m.setItemModel(new org.bukkit.NamespacedKey("blackforge", p.model)));
                d = host.getWorld().spawn(at, ItemDisplay.class, x -> {
                    x.setItemStack(is);
                    x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                });
            } else if (p.block != null) {
                d = host.getWorld().spawn(at, BlockDisplay.class, x -> x.setBlock(p.block.createBlockData()));
            } else {
                d = host.getWorld().spawn(at, ItemDisplay.class, x -> {
                    x.setItemStack(p.item);
                    x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                });
            }
            d.setPersistent(false);
            d.setViewRange(2.5f);
            d.setShadowRadius(0f);
            d.setTeleportDuration(2);
            d.setInterpolationDuration(2);
            d.addScoreboardTag(tag);
            d.setBrightness(p.glow ? new Display.Brightness(15, 15) : new Display.Brightness(p.model != null ? 12 : 15, p.model != null ? 12 : 15));
            shown.add(d);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 2L);
        tick();
    }

    /** The boss just used an ability: raise the arms and rear back for a moment. */
    void pulse() { pulse = 1; }

    void remove() {
        if (task != null) task.cancel();
        for (Display d : shown) d.remove();
        shown.clear();
    }

    private Float fixedYaw;

    /** Hold the model at one heading instead of following the host (display bosses). */
    void fixYaw(float yaw) { fixedYaw = yaw; }

    private float yaw() {
        if (fixedYaw != null) return fixedYaw;
        try { return host.getBodyYaw(); } catch (Throwable t) { return host.getLocation().getYaw(); }
    }

    private Matrix4f matrix(String name, State st, Map<String, Matrix4f> cache) {
        if (name == null) return new Matrix4f();
        Matrix4f cached = cache.get(name);
        if (cached != null) return cached;
        Group g = groups.get(name);
        Matrix4f m = new Matrix4f(matrix(g.parent, st, cache));
        Pose p = g.anim.apply(st);
        m.translate(p.dx, p.dy, p.dz).translate(g.px, g.py, g.pz).rotateXYZ(p.rx, p.ry, p.rz).translate(-g.px, -g.py, -g.pz);
        cache.put(name, m);
        return m;
    }

    private void tick() {
        if (host == null || !host.isValid()) { remove(); return; }
        Location now = host.getLocation();
        double moved = last.getWorld() == now.getWorld() ? Math.hypot(now.getX() - last.getX(), now.getZ() - last.getZ()) / 2.0 : 0;
        last = now.clone();
        spd += (Math.min(1.0, moved / 0.18) - spd) * 0.35;
        walk += moved * 3.2 + spd * 0.02;
        pulse = Math.max(0, pulse - 0.06);
        t += 0.1;
        State st = new State(t, walk, spd, pulse);
        Map<String, Matrix4f> cache = new HashMap<>();
        Matrix4f yaw = new Matrix4f().rotateY((float) -Math.toRadians(yaw()));
        Location base = new Location(now.getWorld(), now.getX(), now.getY(), now.getZ());
        int i = 0;
        for (Part p : parts) {
            Display d = shown.get(i++);
            if (!d.isValid()) continue;
            Matrix4f w = new Matrix4f(yaw).mul(matrix(p.group, st, cache));
            if (p.model != null) {
                Group g = groups.get(p.group.endsWith("_glow") ? p.group.substring(0, p.group.length() - 5) : p.group);
                Vector3f pv = w.transformPosition(new Vector3f(g.px, g.py, g.pz));
                d.teleport(base);
                d.setInterpolationDelay(0);
                d.setTransformation(new Transformation(pv, w.getUnnormalizedRotation(new Quaternionf()).rotateY((float) Math.PI), new Vector3f(scale), new Quaternionf()));   // item models face the other way round
                continue;
            }
            Vector3f c = w.transformPosition(new Vector3f(p.cx, p.cy, p.cz));
            Quaternionf q = w.getUnnormalizedRotation(new Quaternionf()).mul(new Quaternionf().rotateXYZ(p.rx, p.ry, p.rz));
            Vector3f size = new Vector3f(p.sx, p.sy, p.sz);
            Vector3f tr = p.block != null ? new Vector3f(c).sub(q.transform(new Vector3f(size).mul(0.5f))) : c;
            d.teleport(base);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(tr, q, size, new Quaternionf()));
        }
        Matrix4f body = new Matrix4f(yaw).mul(matrix("body", st, cache));
        for (Fx f : fx) {
            if (rnd.nextDouble() > f.chance) continue;
            Vector3f v = body.transformPosition(new Vector3f(s(f.x + rnd.nextGaussian() * f.spread * 0.5), s(f.y + rnd.nextGaussian() * f.spread * 0.3), s(f.z + rnd.nextGaussian() * f.spread * 0.5)));
            now.getWorld().spawnParticle(f.p, now.getX() + v.x, now.getY() + v.y, now.getZ() + v.z, f.n, 0.1, 0.1, 0.1, 0.01);
        }
    }
}
