package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/**
 * Gods-eye: a BlackForgeMC ability item. Right-click and it finds the closest other player (players only - never mobs)
 * and marks them for 10 seconds: a golden halo drops out of the sky onto their head, a beam of light shoots from it
 * into the sky (its glowing core shows through walls), and a banner floats over them with their face (their own skin)
 * and their name. Everyone can see the mark; the marked player is told who's watching. The caster gets a live
 * distance and direction arrow on their action bar. Casting sets off a golden blast at the caster's feet with glowing
 * yellow cracks running across the ground that seal up again ({@link EyeBurst}, cosmetic only).
 *
 * Spectators and players the caster can't see (vanished) are never picked. Only where {@code allowedAt} says (the
 * hub Arena for now). Never used up; one mark per caster at a time.
 */
public final class GodsEye implements Listener {
    public static final String NAME = "<gradient:#FFF6C8:#FFD34D:#FFB000><bold>Gods-eye</bold></gradient>";
    public static final int DURATION_TICKS = 10 * 20;

    static final Color LIGHT = Color.fromRGB(0xFFF6C8), GOLD = Color.fromRGB(0xFFD34D), AMBER = Color.fromRGB(0xFFB000), WHITE = Color.fromRGB(0xFFFFFF);
    private static final int SEGS = 20;                 // halo segments
    private static final double HALO_R = 0.9;
    private static final float BEAM_HEIGHT = 120f, BANNER_HEAD_Y = 6.4f, BANNER_TEXT_Y = 4.6f;
    private static final int DROP_TICKS = 10;           // the halo falls out of the sky onto the head

    private final AlloyCore plugin;
    private final boolean enabled;
    private final Predicate<Location> allowedAt;
    private final NamespacedKey tagKey;
    private final Map<UUID, Mark> marks = new HashMap<>();   // by caster
    private final EyeBurst burst;
    private final Random rnd = new Random();
    private final BlockData haloBlock = Material.YELLOW_CONCRETE.createBlockData();
    private final BlockData beamGlass = Material.YELLOW_STAINED_GLASS.createBlockData();
    private final BlockData beamCore = Material.WHITE_CONCRETE.createBlockData();

    public GodsEye(AlloyCore plugin, Predicate<Location> allowedAt) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        this.enabled = plugin.getConfig().getBoolean("gods-eye.enabled", true);
        this.tagKey = new NamespacedKey(plugin, "gods_eye");
        this.burst = new EyeBurst(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------------ the item

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.ENDER_EYE);
        it.editMeta(m -> {
            m.displayName(Ui.mm(NAME));
            m.lore(List.of(
                    Ui.mm("<gray>Right-click to mark the <white>closest player"),
                    Ui.mm("<gray>for <white>10s<gray>: a halo over their head, a beam"),
                    Ui.mm("<gray>into the sky and a banner with their face"),
                    Ui.mm("<gray>Shows you how far away they are, and which way"),
                    Ui.mm(""),
                    Ui.mm("<dark_gray>BlackForgeMC ability")));
            m.setMaxStackSize(1);
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    public boolean isEye(ItemStack it) {
        return it != null && it.getType() == Material.ENDER_EYE && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ casting

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isEye(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);   // never throw it like a real eye of ender
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>Gods-eye isn't switched on yet.")); return; }
        if (!allowedAt.test(p.getLocation())) { p.sendActionBar(Ui.mm("<gray>" + NAME + " <gray>only works in the <white>Arena <gray>for now.")); return; }
        Mark old = marks.get(p.getUniqueId());
        if (old != null) {
            p.sendActionBar(Ui.mm("<#FFD34D>👁 <gray>Still watching <white>" + old.target.getName() + " <gray>- " + secs(old.left()) + "s"));
            return;
        }
        Player target = closest(p);
        if (target == null) {
            p.sendActionBar(Ui.mm("<#FFD34D>👁 <gray>The eye opens... and sees no one."));
            p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.PLAYERS, 0.8f, 0.6f);
            return;
        }
        Mark m = new Mark(p, target);
        marks.put(p.getUniqueId(), m);
        burst.play(p.getLocation());
        m.start();
    }

    /** The nearest other player in the same world: no spectators, nobody the caster can't see. Mobs never count. */
    private static Player closest(Player p) {
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player q : p.getWorld().getPlayers()) {
            if (q == p || q.isDead() || q.getGameMode() == GameMode.SPECTATOR || !p.canSee(q)) continue;
            double d = q.getLocation().distanceSquared(p.getLocation());
            if (d < bestD) { bestD = d; best = q; }
        }
        return best;
    }

    /** Plugin disable: take every mark down. */
    public void shutdown() {
        for (Mark m : new ArrayList<>(marks.values())) m.remove();
        marks.clear();
        burst.shutdown();
    }

    private static String secs(int ticks) { return String.format(Locale.ROOT, "%.1f", Math.max(0, ticks) / 20.0); }

    /** One halo segment: a short bar along the ring at {@code angle}, {@code lift} above the feet. */
    private static Transformation segment(double angle, float lift, float scale) {
        float w = (float) (2 * HALO_R * Math.sin(Math.PI / SEGS) * 1.08) * scale, h = 0.1f * scale, th = 0.18f * scale;
        double r = HALO_R * scale;
        Quaternionf q = new Quaternionf().rotateY((float) CageShape.panelYaw(angle));
        Vector3f mid = new Vector3f((float) (Math.cos(angle) * r), lift, (float) (Math.sin(angle) * r));
        return new Transformation(q.transform(new Vector3f(-w / 2, 0, -th / 2)).add(mid), q, new Vector3f(w, h, th), new Quaternionf());
    }

    /** A square column {@code size} wide, {@code h} tall, from {@code lift} up. */
    private static Transformation column(float size, float h, float lift) {
        return new Transformation(new Vector3f(-size / 2, lift, -size / 2), new Quaternionf(), new Vector3f(size, h, size), new Quaternionf());
    }

    private static Transformation floating(float y, float scale) {
        return new Transformation(new Vector3f(0, y, 0), new Quaternionf(), new Vector3f(scale), new Quaternionf());
    }

    private static void move(Display d, Transformation tf, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(tf);
    }

    // ------------------------------------------------------------------ one mark

    private final class Mark {
        final UUID caster;
        final String casterName;
        final Player target;
        final World world;
        final BlockDisplay[] halo = new BlockDisplay[SEGS];
        final List<Display> all = new ArrayList<>();
        BlockDisplay beam, core;
        ItemDisplay head;
        TextDisplay plaque;
        BukkitTask task;
        int t;
        float spin;

        Mark(Player caster, Player target) {
            this.caster = caster.getUniqueId();
            this.casterName = caster.getName();
            this.target = target;
            this.world = target.getWorld();
        }

        int left() { return DURATION_TICKS - t; }
        float haloY() { return (float) target.getHeight() + 0.45f; }
        Location base() { Location l = target.getLocation(); l.setYaw(0); l.setPitch(0); return l; }
        private void later(int ticks, Runnable r) { plugin.getServer().getScheduler().runTaskLater(plugin, r, Math.max(1, ticks)); }

        private <T extends Display> T track(T d) {
            d.setPersistent(false);
            d.setTeleportDuration(2);   // follows the target smoothly
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowRadius(0);
            all.add(d);
            return d;
        }

        private BlockDisplay block(BlockData data, Transformation tf, Color glow, float view) {
            return track(world.spawn(base(), BlockDisplay.class, d -> {
                d.setBlock(data);
                d.setTransformation(tf);
                d.setViewRange(view);
                if (glow != null) { d.setGlowing(true); d.setGlowColorOverride(glow); }
            }));
        }

        void start() {
            Player p = Bukkit.getPlayer(caster);
            float hy = haloY();
            // the halo: starts 30 blocks up, small, and drops onto the head
            for (int i = 0; i < SEGS; i++) halo[i] = block(haloBlock, segment(i * 2 * Math.PI / SEGS, hy + 30, 0.4f), GOLD, 4f);
            // the beam: a gold glass column with a glowing white core (seen through walls), unrolling into the sky
            beam = block(beamGlass, column(0.4f, 0.01f, hy + 0.15f), null, 8f);
            core = block(beamCore, column(0.12f, 0.01f, hy + 0.15f), GOLD, 8f);
            // the banner: their face, big, over a name plaque
            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            skull.editMeta(SkullMeta.class, m -> m.setPlayerProfile(target.getPlayerProfile()));
            head = track(world.spawn(base(), ItemDisplay.class, d -> {
                d.setItemStack(skull);
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setViewRange(6f);
                d.setTransformation(floating(BANNER_HEAD_Y, 0.01f));
            }));
            plaque = track(world.spawn(base(), TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setShadowed(true);
                d.setLineWidth(220);
                d.setViewRange(6f);
                d.setBackgroundColor(Color.fromARGB(190, 34, 24, 4));
                d.setTransformation(floating(BANNER_TEXT_Y, 0.01f));
            }));
            plaqueText();

            later(1, () -> {
                for (int i = 0; i < SEGS; i++) move(halo[i], segment(i * 2 * Math.PI / SEGS + 1.5, hy, 1f), DROP_TICKS);
                move(beam, column(0.4f, BEAM_HEIGHT, hy + 0.15f), DROP_TICKS + 4);
                move(core, column(0.12f, BEAM_HEIGHT, hy + 0.15f), DROP_TICKS + 4);
            });
            later(DROP_TICKS - 2, () -> {   // the banner snaps open just as the halo lands
                move(head, floating(BANNER_HEAD_Y, 2.4f), 4);
                move(plaque, floating(BANNER_TEXT_Y, 1.9f), 4);
                later(4, () -> { move(head, floating(BANNER_HEAD_Y, 2.0f), 4); move(plaque, floating(BANNER_TEXT_Y, 1.6f), 4); });
            });
            later(DROP_TICKS + 1, this::landed);
            spin = 1.5f;

            // the eye opens: a flash at the caster and a streak of light racing to the target
            World w = world;
            if (p != null) {
                Location eye = p.getEyeLocation();
                w.spawnParticle(Particle.END_ROD, eye.clone().add(eye.getDirection().multiply(0.6)), 20, 0.1, 0.1, 0.1, 0.12);
                w.playSound(eye, Sound.BLOCK_END_PORTAL_FRAME_FILL, SoundCategory.PLAYERS, 1f, 0.8f);
                w.playSound(eye, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1f, 1.4f);
                streak(eye, target.getLocation().add(0, 1.2, 0));
            }
            w.playSound(target.getLocation(), Sound.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 1.2f, 1.2f);
            target.playSound(target.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 0.5f, 1.4f);
            target.showTitle(Title.title(Ui.mm("<gradient:#FFF6C8:#FFD34D:#FFB000><bold>👁</bold></gradient>"),
                    Ui.mm("<gray>You've been seen by <white>" + casterName),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1500), Duration.ofMillis(500))));
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }

        /** A bright streak that races from the caster's eye to the target over a few ticks. */
        private void streak(Location from, Location to) {
            Vector d = to.toVector().subtract(from.toVector());
            double len = d.length();
            if (len < 0.5) return;
            Vector step = d.clone().multiply(1.0 / len);
            int ticks = 6;
            int[] n = {0};
            Particle.DustTransition dust = new Particle.DustTransition(WHITE, AMBER, 1.3f);
            plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
                int k = n[0]++;
                double a = len * k / ticks, b = Math.min(len, len * (k + 1) / ticks);
                for (double s = a; s < b; s += 0.4) {
                    Location at = from.clone().add(step.clone().multiply(s));
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0.02, 0.02, 0.02, 0, dust);
                    if (rnd.nextInt(4) == 0) world.spawnParticle(Particle.END_ROD, at, 1, 0, 0, 0, 0);
                }
                if (k + 1 >= ticks) task.cancel();
            }, 0L, 1L);
        }

        /** The halo touches down: a ring of light bursts round them and the bell rings. */
        private void landed() {
            if (!target.isValid()) return;
            Location l = target.getLocation().add(0, haloY(), 0);
            for (int i = 0; i < 32; i++) {
                double a = i * 2 * Math.PI / 32;
                world.spawnParticle(Particle.END_ROD, l, 0, Math.cos(a), 0.05, Math.sin(a), 0.18);
            }
            world.spawnParticle(Particle.FIREWORK, l, 20, 0.5, 0.2, 0.5, 0.05);
            world.playSound(l, Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1f, 1.6f);
            world.playSound(l, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1f, 1.8f);
        }

        void plaqueText() {
            if (plaque == null || !plaque.isValid()) return;
            Player p = Bukkit.getPlayer(caster);
            String dist = p != null && p.getWorld() == world ? String.format(Locale.ROOT, "%.0fm", p.getLocation().distance(target.getLocation())) : "?";
            String phase = String.format(Locale.ROOT, "%.2f", Math.sin(t / 7.0));
            plaque.text(Ui.mm("<gradient:#FFF6C8:#FFD34D:#FFB000:#FFD34D:" + phase + "><bold>" + target.getName() + "</bold></gradient>"
                    + "<newline><#FFD34D>👁 <gray>seen by <white>" + casterName + " <dark_gray>· <gray>" + dist));
        }

        void tick() {
            t++;
            Player p = Bukkit.getPlayer(caster);
            if (!target.isOnline() || target.isDead() || target.getWorld() != world) {
                if (p != null) p.sendActionBar(Ui.mm("<#FFD34D>👁 <gray>Lost sight of <white>" + target.getName()));
                end(false);
                return;
            }
            if (left() <= 0) { end(true); return; }

            Location base = base();
            for (Display d : all) if (d.isValid()) d.teleport(base);
            float hy = haloY();

            // the halo turns; after landing it bobs gently too
            if (t > DROP_TICKS + 1 && t % 4 == 0) {
                spin += 0.35f;
                float bob = (float) (Math.sin(t / 8.0) * 0.06);
                for (int i = 0; i < SEGS; i++) move(halo[i], segment(i * 2 * Math.PI / SEGS + spin, hy + bob, 1f), 4);
            }
            if (t % 4 == 0) plaqueText();

            // particles
            double hx = base.getX(), hz = base.getZ(), y0 = base.getY() + hy;
            if (t % 2 == 0) {
                for (int i = 0; i < 3; i++) {   // sparkles round the halo
                    double a = rnd.nextDouble() * Math.PI * 2;
                    world.spawnParticle(Particle.DUST, hx + Math.cos(a) * HALO_R, y0 + 0.05, hz + Math.sin(a) * HALO_R, 1, 0.02, 0.02, 0.02, 0,
                            new Particle.DustOptions(rnd.nextBoolean() ? LIGHT : GOLD, 1.1f));
                }
                for (int i = 0; i < 2; i++) {   // light climbing the beam
                    double up = rnd.nextDouble() * 40;
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, hx, y0 + 0.5 + up, hz, 1, 0.12, 0.3, 0.12, 0,
                            new Particle.DustTransition(WHITE, AMBER, 1.6f));
                }
                world.spawnParticle(Particle.END_ROD, hx, y0 + 0.3 + rnd.nextDouble() * 3, hz, 0, 0, 1, 0, 0.25);
            }
            if (t % 4 == 0) {   // golden drops falling from the halo, motes round the banner
                double a = rnd.nextDouble() * Math.PI * 2;
                world.spawnParticle(Particle.END_ROD, hx + Math.cos(a) * HALO_R, y0, hz + Math.sin(a) * HALO_R, 0, 0, -1, 0, 0.03);
                double b = t * 0.25;
                world.spawnParticle(Particle.WAX_ON, hx + Math.cos(b) * 1.6, base.getY() + BANNER_HEAD_Y + 0.4, hz + Math.sin(b) * 1.6, 1, 0, 0, 0, 0);
                world.spawnParticle(Particle.WAX_ON, hx - Math.cos(b) * 1.6, base.getY() + BANNER_HEAD_Y + 0.4, hz - Math.sin(b) * 1.6, 1, 0, 0, 0, 0);
            }
            if (t % 10 == 0) {   // a spotlight ring on the ground under them
                for (int i = 0; i < 24; i++) {
                    double a = i * 2 * Math.PI / 24;
                    world.spawnParticle(Particle.DUST, hx + Math.cos(a) * 1.1, base.getY() + 0.1, hz + Math.sin(a) * 1.1, 1, 0, 0, 0, 0,
                            new Particle.DustOptions(GOLD, 1.0f));
                }
            }
            if (t % 20 == 0) world.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.6f, 1.5f);

            // the caster's tracker: name, distance, which way to turn
            if (p != null && t % 4 == 0) {
                if (p.getWorld() == world) {
                    Location pl = p.getLocation(), tl = target.getLocation();
                    p.sendActionBar(Ui.mm("<#FFD34D>👁 <white>" + target.getName() + " <dark_gray>· <yellow>"
                            + String.format(Locale.ROOT, "%.0fm", pl.distance(tl)) + " <dark_gray>· <gold><bold>"
                            + Compass.arrow(pl.getYaw(), tl.getX() - pl.getX(), tl.getZ() - pl.getZ()) + "</bold> <dark_gray>· <gray>" + secs(left()) + "s"));
                } else p.sendActionBar(Ui.mm("<#FFD34D>👁 <gray>" + target.getName() + " is in another world"));
            }
        }

        /** Time's up: the halo lifts off into the sky, the beam thins away, the banner folds. {@code fancy} false: just vanish. */
        void end(boolean fancy) {
            if (task != null) task.cancel();
            marks.remove(caster);
            if (!fancy) { remove(); return; }
            float hy = haloY();
            for (int i = 0; i < SEGS; i++) move(halo[i], segment(i * 2 * Math.PI / SEGS + spin + 3, hy + 40, 0.3f), 14);
            move(beam, column(0.01f, BEAM_HEIGHT, hy + 0.15f), 10);
            move(core, column(0.01f, BEAM_HEIGHT, hy + 0.15f), 10);
            move(head, floating(BANNER_HEAD_Y + 1, 0.01f), 6);
            move(plaque, floating(BANNER_TEXT_Y + 1, 0.01f), 6);
            Location l = target.getLocation().add(0, hy, 0);
            world.spawnParticle(Particle.END_ROD, l, 25, 0.4, 0.2, 0.4, 0.08);
            world.playSound(l, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1f, 1.6f);
            target.sendActionBar(Ui.mm("<#FFD34D>👁 <gray>The eye closes."));
            // keep the pieces following while they fly off
            int[] n = {0};
            plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
                if (n[0]++ >= 14 || !target.isValid()) { task.cancel(); remove(); return; }
                Location b = base();
                for (Display d : all) if (d.isValid()) d.teleport(b);
            }, 1L, 1L);
        }

        void remove() {
            if (task != null) task.cancel();
            for (Display d : all) d.remove();
        }
    }
}
