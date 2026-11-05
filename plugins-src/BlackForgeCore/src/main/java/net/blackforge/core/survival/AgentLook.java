package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What an Agent looks like: a skin for its tier, its own tool, and real movement when it works. The golem underneath goes
 * invisible and keeps the hitbox and name. These are displays that never save, so they can't outlive a restart, and they
 * only exist near a player, so an empty base costs nothing. With no resource pack on the server it does nothing at all.
 */
final class AgentLook {
    private static final String[] SKINS = {"oxidized", "copper", "ember", "gold", "prism"};
    private static final int STEP_TICKS = 2, VIEW_BLOCKS = 40, AMBIENT_AFTER_TICKS = 160;
    private static final Color ORANGE = Color.fromRGB(0xF28C2C), GOLD = Color.fromRGB(0xFFE096);

    private static final class Parts {
        ItemDisplay body, arm, tool;
        int tier; Robots.Job job;
        long start = -1, lastJob; int length; double reach; boolean impact, impactDone;
        Location target;
        /** The frame last shown, as bob, lean, arm, walk x, walk z, facing. A new animation melts out of it. */
        double[] shown, fadeFrom;
        long fadeStart = -1;
        boolean hadFuel;
    }

    private final Robots robots;
    private final java.util.logging.Logger log;
    private final boolean active;
    private final Map<Robots.Bot, Parts> parts = new HashMap<>();
    private long now;

    AgentLook(BlackForgeCore plugin, Robots robots) {
        this.robots = robots;
        this.log = plugin.getLogger();
        this.active = plugin.getConfig().getBoolean("resource-pack-live", false) && plugin.getConfig().getBoolean("agent-looks", true);
        if (active) Bukkit.getScheduler().runTaskTimer(plugin, this::tick, STEP_TICKS, STEP_TICKS);
    }

    boolean active() { return active; }

    /** The job just done. Its target and material are on the Agent. */
    void play(Robots.Bot b) {
        Parts p = parts.get(b);
        if (p == null || b.lastTarget == null) return;
        start(b, p, b.lastTarget, true);
    }

    private void start(Robots.Bot b, Parts p, Location target, boolean impact) {
        AgentMotion.Kind kind = AgentMotion.Kind.valueOf(b.job.name());
        p.length = AgentMotion.ticks(kind, b.interval());
        p.start = now;
        p.lastJob = now;
        p.target = target;
        p.impact = impact;
        p.impactDone = false;
        fade(p);
        Location from = b.loc;
        p.reach = target.getWorld() == from.getWorld() ? AgentMotion.reach(kind, p.length, Math.hypot(target.getX() - from.getX(), target.getZ() - from.getZ())) : 0;
    }

    /** The next frames melt out of whatever's showing now. */
    private void fade(Parts p) {
        if (p.shown == null) return;
        p.fadeFrom = p.shown;
        p.fadeStart = now;
    }

    private void tick() {
        now += STEP_TICKS;
        for (Iterator<Map.Entry<Robots.Bot, Parts>> it = parts.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!robots.isPlaced(e.getKey())) { remove(e.getValue()); it.remove(); }
        }
        for (Robots.Bot b : robots.allBots()) {
            // each Agent on its own, like Robots.tick: one bad Agent must not stop the looks of every Agent after it
            try {
                if (b.loc.getWorld() == null || !b.loc.getWorld().isChunkLoaded(b.loc.getBlockX() >> 4, b.loc.getBlockZ() >> 4)) { drop(b); continue; }
                if (!anyoneNear(b.loc)) { drop(b); continue; }
                CopperGolem g = robots.golem(b);
                if (g == null) { drop(b); continue; }
                Parts p = parts.computeIfAbsent(b, k -> new Parts());
                dress(b, g, p);
                ambient(b, p);
                pose(b, p);
                sparkle(b, p);
            } catch (Exception ex) {
                try { drop(b); } catch (Exception ignored) { parts.remove(b); }
                if (b.warned.add("look:" + ex)) log.warning("agent " + b.id + " " + b.job + " look: " + ex);
            }
        }
    }

    private void drop(Robots.Bot b) {
        Parts p = parts.remove(b);
        if (p != null) remove(p);
    }

    private static void remove(Parts p) {
        if (p.body != null) p.body.remove();
        if (p.arm != null) p.arm.remove();
        if (p.tool != null) p.tool.remove();
    }

    void shutdown() {
        parts.values().forEach(AgentLook::remove);
        parts.clear();
    }

    private static boolean anyoneNear(Location at) {
        for (Player pl : at.getWorld().getPlayers()) if (pl.getLocation().distanceSquared(at) < VIEW_BLOCKS * VIEW_BLOCKS) return true;
        return false;
    }

    private void dress(Robots.Bot b, CopperGolem g, Parts p) {
        if (!g.isInvisible()) g.setInvisible(true);   // an Agent placed a moment ago hasn't been styled yet
        boolean gone = p.body == null || !p.body.isValid() || !p.arm.isValid() || !p.tool.isValid();
        if (!gone && p.tier == b.tier && p.job == b.job) return;
        remove(p);
        p.tier = b.tier;
        p.job = b.job;
        String skin = SKINS[Math.max(1, Math.min(b.tier, SKINS.length)) - 1];
        p.body = part(b, modelItem(skin, "body", b.tier >= 3), ItemDisplay.ItemDisplayTransform.NONE);
        p.arm = part(b, modelItem(skin, "arm", b.tier >= 3), ItemDisplay.ItemDisplayTransform.NONE);
        p.tool = part(b, new ItemStack(b.job.icon), ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
    }

    private ItemDisplay part(Robots.Bot b, ItemStack item, ItemDisplay.ItemDisplayTransform mode) {
        Location at = b.loc.clone();
        at.setYaw(0);
        at.setPitch(0);
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(STEP_TICKS);
            d.setViewRange(0.8f);
            d.setItemDisplayTransform(mode);
            d.setItemStack(item);
            if (b.tier >= 3) d.setBrightness(new Display.Brightness(15, 15));   // higher tiers light up a dark base
        });
    }

    private static ItemStack modelItem(String skin, String part, boolean shine) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> {
            m.setItemModel(new NamespacedKey("blackforge", "agent/" + skin + "_" + part));
            if (shine) m.setEnchantmentGlintOverride(true);
        });
        return it;
    }

    /** A working Agent with nothing to do right now still looks busy: a farmer checks its rows, a fisherman casts. Nothing's gained by it. */
    private void ambient(Robots.Bot b, Parts p) {
        if (p.start >= 0 || b.fuel <= 0 || now - p.lastJob < AMBIENT_AFTER_TICKS + Math.floorMod(b.id.hashCode(), 80)) return;
        Location near = b.loc.clone().add(Math.floorMod(now, 5) - 2, 0, Math.floorMod(now / 2, 5) - 2);
        start(b, p, b.job == Robots.Job.FISHERMAN && b.lastTarget != null ? b.lastTarget : near, false);
    }

    private void pose(Robots.Bot b, Parts p) {
        boolean hasFuel = b.fuel > 0;
        if (hasFuel != p.hadFuel) { p.hadFuel = hasFuel; fade(p); }   // running out slumps it gradually, and refuelling brings it back up
        AgentMotion.Kind kind = AgentMotion.Kind.valueOf(b.job.name());
        AgentPose pose;
        double aim = Math.toRadians(-b.loc.getYaw()), walkX = 0, walkZ = 0;
        boolean ended = false;
        if (p.start >= 0) {
            double done = (double) (now - p.start) / p.length;
            pose = AgentMotion.work(kind, done);
            if (p.target != null) {
                double dx = p.target.getX() - b.loc.getX(), dz = p.target.getZ() - b.loc.getZ(), flat = Math.hypot(dx, dz);
                if (flat > 0.3) {
                    double toward = Math.atan2(dx, dz);
                    aim = turn(aim, toward, AgentMotion.ease(Math.min(1, done * 5)) * AgentMotion.ease(Math.min(1, (1 - done) * 5)));
                    walkX = dx / flat * pose.travel() * p.reach;
                    walkZ = dz / flat * pose.travel() * p.reach;
                }
            }
            if (!p.impactDone && done >= AgentMotion.impactAt(kind)) {
                p.impactDone = true;
                if (p.impact) impact(b, p);
            }
            if (done >= 1) { p.start = -1; ended = true; }
        } else {
            pose = AgentMotion.idle(now, b.id.hashCode(), b.fuel > 0);
            if (b.job == Robots.Job.FISHERMAN && b.fuel > 0) pose = new AgentPose(pose.bob(), pose.lean(), pose.arm() + 0.75, 0);   // holds its rod out
        }
        double[] frame = {pose.bob(), pose.lean(), pose.arm(), walkX, walkZ, aim};
        if (p.fadeStart >= 0) {
            double t = (double) (now - p.fadeStart) / AgentMotion.BLEND_TICKS;
            if (t >= 1) p.fadeStart = -1;
            else frame = AgentMotion.blend(p.fadeFrom, frame, t);
        }
        p.shown = frame;
        if (ended) fade(p);   // from this frame into its idle
        double bob = frame[0], lean = frame[1], armUp = frame[2];
        Matrix4f base = new Matrix4f().translate((float) frame[3], (float) bob, (float) frame[4]).rotateY((float) frame[AgentMotion.AIM]);
        set(p.body, new Matrix4f(base).translate(0, 0.5f, 0).rotateX((float) lean));
        // the arm's on the Agent's right, so -x with +z as forward, and raising it means turning it forward
        Matrix4f arm = new Matrix4f(base).translate(0, 0.52f, 0).rotateX((float) lean).translate(-0.27f, 0, 0).rotateX((float) -armUp);
        set(p.arm, arm);
        set(p.tool, new Matrix4f(arm).translate(0, -0.3f, 0.02f).scale(0.8f));
    }

    private static void set(ItemDisplay d, Matrix4f m) {
        d.setInterpolationDelay(0);
        d.setTransformationMatrix(m);
    }

    /** The short way round from one angle to another. */
    private static double turn(double from, double to, double amount) {
        double diff = Math.atan2(Math.sin(to - from), Math.cos(to - from));
        return from + diff * amount;
    }

    /** The moment the job lands. The work itself was already done by Robots, this is what you see and hear. */
    private void impact(Robots.Bot b, Parts p) {
        Location at = p.target;
        World w = at.getWorld();
        switch (b.job) {
            case MINER -> {
                if (b.lastMaterial != null && b.lastMaterial.isBlock() && !b.lastMaterial.isAir()) w.spawnParticle(Particle.BLOCK, at, 16, 0.3, 0.3, 0.3, 0, b.lastMaterial.createBlockData());
                w.playSound(at, Sound.BLOCK_STONE_HIT, 0.5f, 0.8f);
            }
            case FARMER -> {
                w.spawnParticle(Particle.COMPOSTER, at, 8, 0.3, 0.3, 0.3, 0);
                w.playSound(at, Sound.BLOCK_CROP_BREAK, 0.5f, 1f);
            }
            case GRINDER -> w.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0, 0, 0, 0);
            case FISHERMAN -> {
                w.spawnParticle(Particle.SPLASH, at, 14, 0.25, 0.1, 0.25, 0.1);
                w.playSound(at, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.5f, 1f);
            }
        }
    }

    /** Tier III glitters, Tier IV throws embers and Tier V leaves a little colour in the air. */
    private void sparkle(Robots.Bot b, Parts p) {
        if (b.tier < 3 || b.fuel <= 0 || Math.floorMod(now + b.id.hashCode(), 16) >= STEP_TICKS) return;
        Location at = b.loc.clone().add(0, 0.8, 0);
        World w = at.getWorld();
        if (b.tier == 3) w.spawnParticle(Particle.WAX_ON, at, 1, 0.3, 0.4, 0.3, 0);
        else if (b.tier == 4) {
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 2, 0.3, 0.4, 0.3, 0, new Particle.DustTransition(ORANGE, GOLD, 1f));
            w.spawnParticle(Particle.FLAME, at, 1, 0.25, 0.3, 0.25, 0.003);
        } else {
            for (int i = 0; i < 3; i++)
                w.spawnParticle(Particle.DUST, at, 1, 0.35, 0.45, 0.35, 0, new Particle.DustOptions(Color.fromRGB(AllayFlight.rainbow(now * 0.01 + i * 0.33)), 0.9f));
            w.spawnParticle(Particle.END_ROD, at, 1, 0.3, 0.4, 0.3, 0.005);
        }
    }
}
