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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
 * Rimelock: a BlackForgeMC ability item. Right-click to leap up and slam down; a round cage of light-blue glass
 * ({@link CageShape#RADIUS} blocks across the middle) bursts up around where you land and holds for 7 seconds.
 *
 * <ul>
 *   <li>Anyone inside can't walk, get knocked, pearl or chorus out. That includes the caster.</li>
 *   <li>Mobs too: one that's in (or wanders in) is held at the glass until the cage drops.</li>
 *   <li>Anyone outside can see in and can walk in, but once they're in they're stuck too.</li>
 *   <li>No damage crosses the wall either way: hits are cancelled and projectiles bounce off the glass.</li>
 * </ul>
 *
 * Only where {@code allowedAt} says (the hub Arena for now). The item isn't used up; you can't drop a second cage
 * while your first is still standing. The geometry lives in {@link CageShape}.
 */
public final class Rimelock implements Listener {
    public static final String NAME = "<gradient:#BFF4FF:#3FA9FF><bold>Rimelock</bold></gradient>";
    public static final int DURATION_TICKS = 7 * 20;

    // palette: ice, sky, azure and a near-white for the bright edges
    static final Color ICE = Color.fromRGB(0xBFF4FF), SKY = Color.fromRGB(0x7FD8FF), AZURE = Color.fromRGB(0x3FA9FF), FROST = Color.fromRGB(0xF2FDFF);
    private static final int RISE_TICKS = 8, FALL_TICKS = 6;

    private final AlloyCore plugin;
    private final boolean enabled;
    private final Predicate<Location> allowedAt;
    private final NamespacedKey tagKey;
    private final List<Cage> cages = new ArrayList<>();
    private final Set<UUID> leaping = new HashSet<>();
    private final Map<UUID, Long> fxReadyAt = new HashMap<>();     // per player: bounce / blocked-hit effects at most every 200 ms
    private final Random rnd = new Random();
    private final BlockData glass = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
    private final BlockData frame = Material.LIGHT_BLUE_CONCRETE.createBlockData();

    public Rimelock(AlloyCore plugin, Predicate<Location> allowedAt) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        this.enabled = plugin.getConfig().getBoolean("rimelock.enabled", true);
        this.tagKey = new NamespacedKey(plugin, "rimelock");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------------ the item

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.HEART_OF_THE_SEA);
        it.editMeta(m -> {
            m.displayName(Ui.mm(NAME));
            m.lore(List.of(
                    Ui.mm("<gray>Right-click to leap and slam down a cage"),
                    Ui.mm("<gray>of light around you for <white>7s"),
                    Ui.mm("<gray>Nobody inside can leave, mobs included."),
                    Ui.mm("<gray>Anyone can walk in, but then they're stuck too"),
                    Ui.mm("<gray>No hits get through the wall, either way"),
                    Ui.mm(""),
                    Ui.mm("<dark_gray>BlackForgeMC ability")));
            m.setMaxStackSize(1);
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    public boolean isCage(ItemStack it) {
        return it != null && it.getType() == Material.HEART_OF_THE_SEA && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ casting

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isCage(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>Rimelock isn't switched on yet.")); return; }
        if (!allowedAt.test(p.getLocation())) { p.sendActionBar(Ui.mm("<gray>" + NAME + " <gray>only works in the <white>Arena <gray>for now.")); return; }
        if (leaping.contains(p.getUniqueId())) return;
        for (Cage c : cages) if (c.owner.equals(p.getUniqueId())) {
            p.sendActionBar(Ui.mm("<#7FD8FF>⛓ <gray>Your cage is still up: <white>" + secs(c.left()) + "s"));
            return;
        }
        leap(p);
    }

    /** A short hop up and forward; the cage drops when you land (or after 1.5 s in the air, whichever is first). */
    private void leap(Player p) {
        UUID id = p.getUniqueId();
        leaping.add(id);
        Vector fwd = p.getLocation().getDirection().setY(0);
        if (fwd.lengthSquared() > 1e-6) fwd.normalize().multiply(0.35);
        p.setVelocity(fwd.setY(0.85));
        World w = p.getWorld();
        Location feet = p.getLocation();
        w.playSound(feet, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 0.6f, 1.4f);
        w.playSound(feet, Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1f, 0.7f);
        w.spawnParticle(Particle.CLOUD, feet, 14, 0.4, 0.05, 0.4, 0.06);
        ring(w, feet.getX(), feet.getY() + 0.1, feet.getZ(), 1.2, 20, new Particle.DustOptions(SKY, 1.4f));
        int[] n = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            int k = n[0]++;
            if (!p.isOnline() || p.isDead()) { leaping.remove(id); task.cancel(); return; }
            Location at = p.getLocation().add(0, 0.9, 0);
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 4, 0.25, 0.4, 0.25, 0, new Particle.DustTransition(FROST, AZURE, 1.5f));
            if (k % 2 == 0) w.spawnParticle(Particle.END_ROD, at, 1, 0.2, 0.3, 0.2, 0.01);
            if ((k >= 4 && p.isOnGround()) || k >= 30 || !allowedAt.test(p.getLocation())) {
                task.cancel();
                if (allowedAt.test(p.getLocation())) slam(p);
                // keep them on the no-fall list a moment longer so the landing itself never hurts
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> leaping.remove(id), 10L);
            }
        }, 1L, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeapFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && leaping.contains(e.getEntity().getUniqueId())) e.setCancelled(true);
    }

    /** The landing: a heavy slam, a shockwave racing out to the wall line, and the cage bursting up behind it. */
    private void slam(Player p) {
        Location c = p.getLocation();
        World w = c.getWorld();
        Cage cage = new Cage(p.getUniqueId(), p.getName(), w, c.getX(), c.getY(), c.getZ());
        cages.add(cage);
        w.playSound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 1.2f, 0.8f);
        w.playSound(c, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.5f, 0.6f);
        w.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.4f, 1.5f);
        w.playSound(c, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1f, 0.8f);
        w.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.35f, 2f);
        w.spawnParticle(Particle.EXPLOSION, c, 1);
        BlockData under = c.clone().add(0, -0.5, 0).getBlock().getBlockData();
        if (!under.getMaterial().isAir()) w.spawnParticle(Particle.BLOCK, c, 70, 1.2, 0.1, 1.2, 0.2, under);
        // the shockwave: a bright ring that races out to the wall in 6 ticks, shedding frost
        int[] n = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            int k = n[0]++;
            double r = CageShape.RADIUS * (k + 1) / 6.0;
            ring(w, cage.cx, cage.y + 0.15, cage.cz, r, (int) (12 + r * 8), new Particle.DustOptions(k % 2 == 0 ? FROST : SKY, 1.8f));
            for (int i = 0; i < 10; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                w.spawnParticle(Particle.SNOWFLAKE, cage.cx + Math.cos(a) * r, cage.y + 0.3, cage.cz + Math.sin(a) * r, 0, Math.cos(a), 0.4, Math.sin(a), 0.15);
            }
            if (k >= 5) {
                task.cancel();
                for (int i = 0; i < CageShape.SEGMENTS; i++) {
                    double a = CageShape.edgeAngle(i);
                    w.spawnParticle(Particle.ELECTRIC_SPARK, cage.cx + Math.cos(a) * CageShape.RADIUS, cage.y + 0.4, cage.cz + Math.sin(a) * CageShape.RADIUS, 3, 0.1, 0.3, 0.1, 0.2);
                }
            }
        }, 0L, 1L);
        cage.build();
        // who got caught
        for (Player q : w.getPlayers()) {
            if (cage.inside(q.getLocation())) {
                cage.trapped.add(q.getUniqueId());
                boolean caster = q.equals(p);
                q.showTitle(Title.title(Ui.mm("<gradient:#F2FDFF:#7FD8FF:#3FA9FF><bold>CAGED</bold></gradient>"),
                        Ui.mm(caster ? "<gray>You and everyone in here. <white>7s<gray>. Fight." : "<gray>by <white>" + p.getName() + " <dark_gray>· <gray>no way out for <white>7s"),
                        Title.Times.times(Duration.ofMillis(120), Duration.ofMillis(1300), Duration.ofMillis(400))));
            } else if (q.getLocation().distanceSquared(c) < 22 * 22) {
                q.sendActionBar(Ui.mm("<#7FD8FF>⛓ <white>" + p.getName() + " <gray>dropped a cage. Walk in if you dare."));
            }
        }
    }

    // ------------------------------------------------------------------ the barrier

    /** Walking (or being knocked) out of a cage you're in: you stop at the glass and get nudged back in. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (cages.isEmpty()) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return;   // only horizontal moves can cross the wall
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        for (Cage c : cages) {
            if (c.world != from.getWorld() || !c.inside(from) || CageShape.insideXZ(c.cx, c.cz, to.getX(), to.getZ(), CageShape.RADIUS - CageShape.BODY)) continue;
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
            Vector in = new Vector(c.cx - from.getX(), 0, c.cz - from.getZ());
            if (in.lengthSquared() > 1e-6) p.setVelocity(in.normalize().multiply(0.3).setY(Math.min(0.1, p.getVelocity().getY())));
            if (fxReady(p)) {
                wallHit(c, from, 1.0);
                p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 0.5f, 1.6f);
                p.sendActionBar(Ui.mm("<#7FD8FF>⛓ <gray>The cage holds. <white>" + secs(c.left()) + "s <gray>left"));
            }
            return;
        }
    }

    /** Ender pearls and chorus fruit can't get you out either (getting in is fine). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (cages.isEmpty()) return;
        String cause = e.getCause().name();
        if (!cause.equals("ENDER_PEARL") && !cause.equals("CHORUS_FRUIT") && !cause.equals("CONSUMABLE_EFFECT")) return;
        for (Cage c : cages) {
            if (c.world != e.getFrom().getWorld() || !c.inside(e.getFrom()) || c.inside(e.getTo())) continue;
            e.setCancelled(true);
            wallHit(c, e.getFrom(), 1.4);
            e.getPlayer().sendActionBar(Ui.mm("<#7FD8FF>⛓ <gray>Nice try. The cage holds."));
            return;
        }
    }

    /**
     * No damage across the wall, either way. Runs last so it also wins over the Arena's "PvP is on here" rule, and the
     * Gale Bellows' own probe sees the cancel and skips anyone on the other side.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (cages.isEmpty() || !(e.getEntity() instanceof LivingEntity victim)) return;
        Entity src = e.getDamager();
        Entity by = src instanceof Projectile pr && pr.getShooter() instanceof Entity shooter ? shooter : src;
        for (Cage c : cages) {
            if (c.world != victim.getWorld() || c.inside(by.getLocation()) == c.inside(victim.getLocation())) continue;
            e.setCancelled(true);
            if (by instanceof Player p && fxReady(p)) {
                Location mid = by.getLocation().add(victim.getLocation()).multiply(0.5);
                wallHit(c, mid, 1.2);
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 1f, 1.3f);
                p.sendActionBar(Ui.mm("<#7FD8FF>⛓ <gray>The cage soaks up the hit"));
            }
            return;
        }
    }

    private boolean fxReady(Player p) {
        long now = System.currentTimeMillis();
        if (fxReadyAt.getOrDefault(p.getUniqueId(), 0L) > now) return false;
        fxReadyAt.put(p.getUniqueId(), now + 200);
        return true;
    }

    /** A crackle of light where something met the glass: sparks, a flash of frost, a glassy ping. */
    private void wallHit(Cage c, Location near, double size) {
        Vector out = new Vector(near.getX() - c.cx, 0, near.getZ() - c.cz);
        if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
        out.normalize();
        Location at = new Location(c.world, c.cx + out.getX() * CageShape.RADIUS, Math.max(c.y + 0.3, near.getY() + 1.0), c.cz + out.getZ() * CageShape.RADIUS);
        c.world.spawnParticle(Particle.ELECTRIC_SPARK, at, (int) (8 * size), 0.15, 0.35, 0.15, 0.15);
        c.world.spawnParticle(Particle.DUST, at, (int) (10 * size), 0.1, 0.45, 0.1, 0, new Particle.DustOptions(FROST, 1.4f));
        c.world.spawnParticle(Particle.END_ROD, at, 2, 0.1, 0.2, 0.1, 0.04);
        c.world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 0.9f, 1.5f);
    }

    // ------------------------------------------------------------------ housekeeping

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        leaping.remove(e.getPlayer().getUniqueId());
        fxReadyAt.remove(e.getPlayer().getUniqueId());
    }

    /** Plugin disable: take every cage down at once (displays aren't saved, but don't leave them for the rest of this run). */
    public void shutdown() {
        for (Cage c : new ArrayList<>(cages)) c.remove();
        cages.clear();
        leaping.clear();
    }

    private static String secs(int ticks) { return String.format(Locale.ROOT, "%.1f", Math.max(0, ticks) / 20.0); }

    private static void ring(World w, double x, double y, double z, double r, int pts, Particle.DustOptions dust) {
        for (int i = 0; i < pts; i++) {
            double a = i * 2 * Math.PI / pts;
            w.spawnParticle(Particle.DUST, x + Math.cos(a) * r, y, z + Math.sin(a) * r, 1, 0.02, 0.02, 0.02, 0, dust);
        }
    }

    // ------------------------------------------------------------------ one cage

    private final class Cage {
        final UUID owner;
        final String ownerName;
        final World world;
        final double cx, y, cz;
        final List<BlockDisplay> panels = new ArrayList<>(), rims = new ArrayList<>(), posts = new ArrayList<>();
        final Set<UUID> trapped = new HashSet<>();
        final Map<UUID, Location> shots = new HashMap<>();   // projectiles near the wall: where they were last tick
        final Map<UUID, Location> mobsIn = new HashMap<>();  // mobs in the cage: their last spot inside
        TextDisplay timer;
        BukkitTask task;
        int t;

        Cage(UUID owner, String ownerName, World world, double cx, double y, double cz) {
            this.owner = owner; this.ownerName = ownerName; this.world = world; this.cx = cx; this.y = y; this.cz = cz;
        }

        int left() { return DURATION_TICKS - t; }
        boolean inside(Location l) { return l.getWorld() == world && CageShape.inside(cx, y, cz, l.getX(), l.getY(), l.getZ()); }
        Location center() { return new Location(world, cx, y, cz); }

        // --- the build: every piece starts flat on the ground and rises, panel by panel round the circle

        void build() {
            float w = (float) CageShape.panelWidth(), th = 0.05f, h = (float) CageShape.WALL_HEIGHT;
            for (int i = 0; i < CageShape.SEGMENTS; i++) {
                double phi = CageShape.panelAngle(i);
                Quaternionf q = new Quaternionf().rotateY((float) CageShape.panelYaw(phi));
                Vector3f mid = new Vector3f((float) (Math.cos(phi) * CageShape.RADIUS), 0, (float) (Math.sin(phi) * CageShape.RADIUS));
                BlockDisplay pane = display(glass, panelTf(mid, q, w, 0.01f, th, 0), false);
                BlockDisplay rim = display(frame, panelTf(mid, q, w, 0.1f, 0.1f, 0), true);
                panels.add(pane);
                rims.add(rim);
                int delay = 2 + i / 4;   // a wave that runs round the circle in ~8 ticks
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    move(pane, panelTf(mid, q, w, h, th, 0), RISE_TICKS);
                    move(rim, panelTf(mid, q, w, 0.1f, 0.1f, h), RISE_TICKS);
                }, delay);
                if (i % 4 == 0) {   // a glowing post every 4 panels
                    double a = CageShape.edgeAngle(i);
                    Vector3f at = new Vector3f((float) (Math.cos(a) * CageShape.RADIUS), 0, (float) (Math.sin(a) * CageShape.RADIUS));
                    BlockDisplay post = display(frame, postTf(at, 0.01f), true);
                    posts.add(post);
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> move(post, postTf(at, h + 0.25f), RISE_TICKS), delay);
                }
            }
            timer = world.spawn(center().add(0, CageShape.WALL_HEIGHT + 1.0, 0), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(2.2f), new Quaternionf()));
            });
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }

        private BlockDisplay display(BlockData block, Transformation tf, boolean glow) {
            return world.spawn(center(), BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.setBlock(block);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(3f);
                d.setShadowRadius(0);
                d.setTransformation(tf);
                if (glow) { d.setGlowing(true); d.setGlowColorOverride(SKY); }
            });
        }

        private static void move(BlockDisplay d, Transformation tf, int ticks) {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(tf);
        }

        /** A w x h x th slab centred on {@code mid}, turned by {@code q}, its bottom at {@code lift} above the ground. */
        private static Transformation panelTf(Vector3f mid, Quaternionf q, float w, float h, float th, float lift) {
            Vector3f corner = q.transform(new Vector3f(-w / 2, 0, -th / 2)).add(mid).add(0, lift, 0);
            return new Transformation(corner, new Quaternionf(q), new Vector3f(w, h, th), new Quaternionf());
        }

        private static Transformation postTf(Vector3f at, float h) {
            float s = 0.16f;
            return new Transformation(new Vector3f(at).add(-s / 2, 0, -s / 2), new Quaternionf(), new Vector3f(s, h, s), new Quaternionf());
        }

        // --- every tick while it stands

        void tick() {
            t++;
            int left = left();
            if (left <= 0) { shatter(); return; }
            Particle.DustOptions ice = new Particle.DustOptions(ICE, 1.6f), azure = new Particle.DustOptions(AZURE, 1.3f);
            double R = CageShape.RADIUS, H = CageShape.WALL_HEIGHT;

            if (t % 2 == 0) {
                // two comets chase each other round the top rim; one sweeps the floor the other way
                for (int k = 0; k < 2; k++) {
                    double a = t * 0.16 + k * Math.PI;
                    world.spawnParticle(Particle.DUST, cx + Math.cos(a) * R, y + H + 0.05, cz + Math.sin(a) * R, 3, 0.05, 0.05, 0.05, 0, ice);
                    world.spawnParticle(Particle.END_ROD, cx + Math.cos(a) * R, y + H + 0.05, cz + Math.sin(a) * R, 1, 0, 0, 0, 0.01);
                }
                double b = -t * 0.12;
                world.spawnParticle(Particle.DUST, cx + Math.cos(b) * R, y + 0.15, cz + Math.sin(b) * R, 2, 0.05, 0.02, 0.05, 0, azure);
                // motes of light drifting up the glass (count 0: the offset is the direction)
                for (int i = 0; i < 4; i++) {
                    double a = rnd.nextDouble() * Math.PI * 2, hy = rnd.nextDouble() * H;
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, cx + Math.cos(a) * R, y + hy, cz + Math.sin(a) * R, 1, 0.03, 0.1, 0.03, 0,
                            new Particle.DustTransition(SKY, FROST, 1.1f));
                    if (i == 0) world.spawnParticle(Particle.END_ROD, cx + Math.cos(a) * (R - 0.1), y + hy, cz + Math.sin(a) * (R - 0.1), 0, 0, 1, 0, 0.04);
                }
            }
            if (t % 10 == 0) ring(world, cx, y + 0.1, cz, R - 0.05, 64, azure);   // a soft pulse round the base

            // the clock: big number over the cage, ticking chimes that climb as time runs out
            if (timer != null && timer.isValid() && t % 2 == 0) {
                boolean late = left <= 40;
                String col = late ? (t % 8 < 4 ? "<#F2FDFF>" : "<#3FA9FF>") : "<gradient:#F2FDFF:#7FD8FF>";
                timer.text(Ui.mm(col + "<bold>⛓ " + secs(left) + "</bold>" + (late ? "" : "</gradient>") + "<newline><gray><font:uniform>" + ownerName + "'s cage</font>"));
            }
            if (left % 20 == 0) {
                float pitch = 0.7f + (DURATION_TICKS - left) / 20f * 0.15f;
                world.playSound(center(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, pitch);
            } else if (left <= 40 && left % 5 == 0) world.playSound(center(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.6f, 1.6f);
            if (t % 40 == 1) world.playSound(center(), Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 1f, 1.4f);

            // last 1.5 s: the frame flickers - it's about to go
            if (left <= 30 && left % 3 == 0) {
                boolean on = (left / 3) % 2 == 0;
                for (BlockDisplay d : rims) if (d.isValid()) d.setGlowing(on);
                for (BlockDisplay d : posts) if (d.isValid()) d.setGlowing(on);
            }

            // anyone who walked in since: they're stuck now too
            if (t % 2 == 0) for (Player q : world.getPlayers()) {
                if (!inside(q.getLocation())) continue;
                if (trapped.add(q.getUniqueId())) {
                    q.showTitle(Title.title(Ui.mm("<gradient:#F2FDFF:#3FA9FF><bold>CAGED</bold></gradient>"), Ui.mm("<gray>You walked in. Now you can't leave."),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(300))));
                    q.playSound(q.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 1f, 0.6f);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, q.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.1);
                } else if (t % 4 == 0 && fxReadyAt.getOrDefault(q.getUniqueId(), 0L) < System.currentTimeMillis() - 600) {
                    q.sendActionBar(Ui.mm("<#7FD8FF>⛓ <bold>CAGED</bold> <dark_gray>· <white>" + secs(left) + "s"));
                }
            }
            bounceShots();
            holdMobs();
        }

        /**
         * Mobs don't fire move events, so each tick: any mob that was inside and has crossed the wall goes back to its
         * last spot inside and gets nudged towards the middle. A mob that walks in is held from then on. Dropping out
         * of the bottom (a hole, the void) lets it go, same as for players. Mounts drop their riders first, so a
         * player on a horse is held by the player rule instead.
         */
        private void holdMobs() {
            Set<UUID> seen = new HashSet<>();
            boolean fx = t % 4 == 0;
            for (Entity en : world.getNearbyEntities(center(), CageShape.RADIUS + 3, CageShape.ABOVE, CageShape.RADIUS + 3)) {
                if (!(en instanceof LivingEntity m) || en instanceof Player || m.isDead() || m.isInsideVehicle()) continue;
                Location at = m.getLocation();
                if (inside(at)) { mobsIn.put(m.getUniqueId(), at.clone()); seen.add(m.getUniqueId()); continue; }
                Location last = mobsIn.get(m.getUniqueId());
                if (last == null || at.getY() < y - CageShape.BELOW || at.getY() > y + CageShape.ABOVE) continue;   // never in, or fell out
                seen.add(m.getUniqueId());
                if (!m.getPassengers().isEmpty()) m.eject();
                Location back = last.clone();
                back.setYaw(at.getYaw());
                back.setPitch(at.getPitch());
                m.teleport(back);
                Vector in = new Vector(cx - back.getX(), 0, cz - back.getZ());
                if (in.lengthSquared() > 1e-6) m.setVelocity(in.normalize().multiply(0.25).setY(Math.min(0, m.getVelocity().getY())));
                if (fx) { wallHit(this, back, 0.7); fx = false; }
            }
            mobsIn.keySet().retainAll(seen);
        }

        /** Arrows, tridents, snowballs, pearls and potions bounce off the glass instead of crossing it. */
        private void bounceShots() {
            Set<UUID> seen = new HashSet<>();
            for (Entity en : world.getNearbyEntities(center(), CageShape.RADIUS + 4, CageShape.ABOVE, CageShape.RADIUS + 4)) {
                if (!(en instanceof Projectile pr)) continue;
                if (pr instanceof AbstractArrow ar && ar.isInBlock()) continue;
                Location now = pr.getLocation();
                seen.add(pr.getUniqueId());
                Location before = shots.put(pr.getUniqueId(), now.clone());
                if (before == null) continue;
                boolean wasIn = CageShape.projectileInside(cx, y, cz, before.getX(), before.getY(), before.getZ());
                boolean isIn = CageShape.projectileInside(cx, y, cz, now.getX(), now.getY(), now.getZ());
                if (wasIn == isIn) continue;
                Vector v = pr.getVelocity();
                pr.teleport(before);
                pr.setVelocity(v.multiply(-0.3));
                shots.put(pr.getUniqueId(), before);
                wallHit(this, before, 0.8);
            }
            shots.keySet().retainAll(seen);
        }

        // --- the end: the glass sinks, the frame drops and it all shatters into light

        void shatter() {
            if (task != null) task.cancel();
            float w = (float) CageShape.panelWidth(), h = (float) CageShape.WALL_HEIGHT;
            for (int i = 0; i < panels.size(); i++) {
                double phi = CageShape.panelAngle(i);
                Quaternionf q = new Quaternionf().rotateY((float) CageShape.panelYaw(phi));
                Vector3f mid = new Vector3f((float) (Math.cos(phi) * CageShape.RADIUS), 0, (float) (Math.sin(phi) * CageShape.RADIUS));
                move(panels.get(i), panelTf(mid, q, w, 0.01f, 0.05f, 0), FALL_TICKS);
                move(rims.get(i), panelTf(mid, q, w, 0.1f, 0.1f, 0), FALL_TICKS);
            }
            for (BlockDisplay d : posts) {
                Transformation tf = d.getTransformation();
                move(d, new Transformation(tf.getTranslation(), tf.getLeftRotation(), new Vector3f(tf.getScale().x(), 0.01f, tf.getScale().z()), tf.getRightRotation()), FALL_TICKS);
            }
            BlockData shard = glass;
            for (int i = 0; i < 48; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, hy = rnd.nextDouble() * h;
                double x = cx + Math.cos(a) * CageShape.RADIUS, z = cz + Math.sin(a) * CageShape.RADIUS;
                world.spawnParticle(Particle.BLOCK, x, y + hy, z, 4, 0.2, 0.2, 0.2, 0.1, shard);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y + hy, z, 1, 0.1, 0.1, 0.1, 0, new Particle.DustTransition(FROST, AZURE, 1.6f));
                if (i % 6 == 0) world.spawnParticle(Particle.END_ROD, x, y + hy, z, 0, Math.cos(a), 0.3, Math.sin(a), 0.12);
            }
            world.spawnParticle(Particle.FIREWORK, cx, y + h, cz, 30, 1.5, 0.3, 1.5, 0.08);
            world.playSound(center(), Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.4f, 0.8f);
            world.playSound(center(), Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0f, 1.3f);
            world.playSound(center(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.2f, 1.4f);
            for (Player q : world.getPlayers()) if (inside(q.getLocation())) q.sendActionBar(Ui.mm("<#BFF4FF>⛓ <gray>The cage shatters. You're free."));
            if (timer != null) timer.remove();
            cages.remove(this);
            plugin.getServer().getScheduler().runTaskLater(plugin, this::removeDisplays, FALL_TICKS + 1L);
        }

        void remove() {
            if (task != null) task.cancel();
            if (timer != null) timer.remove();
            removeDisplays();
        }

        private void removeDisplays() {
            panels.forEach(Entity::remove);
            rims.forEach(Entity::remove);
            posts.forEach(Entity::remove);
        }
    }
}
