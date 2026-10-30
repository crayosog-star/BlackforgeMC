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
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Rimelock: a BlackForgeMC ability item. Right-click to leap up and slam down; a tall round cage of shimmering glass
 * ({@link CageShape#RADIUS} blocks across the middle, {@link CageShape#WALL_HEIGHT} high) bursts up around where you
 * land and holds for 10 seconds.
 *
 * <ul>
 *   <li>Anyone inside can't walk, get knocked, pearl or chorus out. Mobs inside are held at the glass too.</li>
 *   <li>Anyone outside can see in and can walk in, but once they're in they're stuck too.</li>
 *   <li>The caster (and their team, once teams exist - see {@code allies}) walks in and out freely.</li>
 *   <li>No damage crosses the wall either way, for anyone: hits are cancelled and projectiles bounce off the glass.</li>
 * </ul>
 *
 * Two colours: the caster and their team see the cage in ice blue, everyone else sees it in red. Each cage is built
 * twice (one set of displays per colour, each shown only to its side) and every particle is sent per viewer in that
 * viewer's colours.
 *
 * The look: panes that shoot up past full height and settle, a neon frame and floor ring, spinning crystals on the
 * posts, white glints with light streaks sweeping round the wall, a brightness ripple, an aurora over the rim, snow
 * inside, and a burst of shards when it breaks. Only where {@code allowedAt} says (the hub Arena for now). Never used
 * up; one cage per caster at a time. The geometry lives in {@link CageShape}.
 */
public final class Rimelock implements Listener, Hackable {
    public static final String NAME = "<gradient:#BFF4FF:#3FA9FF><bold>Rimelock</bold></gradient>";
    public static final int DURATION_TICKS = 10 * 20;
    /** Entities with this scoreboard tag are never held at a cage's glass (the Forge Wars Golem: the cold slows it instead). */
    public static final String NO_HOLD_TAG = "alloy.cage_no_hold";

    private static final int RISE_TICKS = 8, SETTLE_TICKS = 6, BREAK_TICKS = 10;
    private static final float OVERSHOOT = 1.1f;
    private static final double GLINT_SPEED = 0.11;   // radians per tick: each glint laps the wall in ~3 s
    private static final double VIEW = 96;            // particles go to players this close to the cage

    /** One side's colours: the caster's team sees BLUE, everyone else RED. */
    private record Palette(Color frost, Color ice, Color sky, Color azure, Color deep, BlockData glass, BlockData glint,
                           BlockData frame, Material gem, String gradient, String hex) {
        String title() { return "<gradient:" + gradient + "><bold>RIMELOCKED</bold></gradient>"; }
    }

    private final Palette blue = new Palette(Color.fromRGB(0xF2FDFF), Color.fromRGB(0xBFF4FF), Color.fromRGB(0x7FD8FF), Color.fromRGB(0x3FA9FF),
            Color.fromRGB(0x1E6FD9), Material.LIGHT_BLUE_STAINED_GLASS.createBlockData(), Material.WHITE_STAINED_GLASS.createBlockData(),
            Material.LIGHT_BLUE_CONCRETE.createBlockData(), Material.LIGHT_BLUE_STAINED_GLASS, "#F2FDFF:#7FD8FF:#3FA9FF", "#7FD8FF");
    private final Palette red = new Palette(Color.fromRGB(0xFFEDEA), Color.fromRGB(0xFFB0A8), Color.fromRGB(0xFF6B5E), Color.fromRGB(0xE5312B),
            Color.fromRGB(0x8E0F12), Material.RED_STAINED_GLASS.createBlockData(), Material.PINK_STAINED_GLASS.createBlockData(),
            Material.RED_CONCRETE.createBlockData(), Material.RED_STAINED_GLASS, "#FFEDEA:#FF6B5E:#E5312B", "#FF6B5E");

    private final AlloyCore plugin;
    private final boolean enabled;
    private final Predicate<Location> allowedAt;
    private final BiPredicate<UUID, Player> allies;   // (caster, player): player is on the caster's team
    private final NamespacedKey tagKey;
    private final List<Cage> cages = new ArrayList<>();
    private final Set<UUID> leaping = new HashSet<>();
    private final Set<UUID> jammed = new HashSet<>();   // mid-leap when an EMP hit: no cage when they land
    private final Map<UUID, Long> fxReadyAt = new HashMap<>();     // per player: bounce / blocked-hit effects at most every 200 ms
    private final Random rnd = new Random();

    public Rimelock(AlloyCore plugin, Predicate<Location> allowedAt) { this(plugin, allowedAt, (caster, p) -> false); }

    /** {@code allies}: true when {@code p} is on the caster's team (no teams yet, so the hub passes "nobody"). */
    public Rimelock(AlloyCore plugin, Predicate<Location> allowedAt, BiPredicate<UUID, Player> allies) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        this.allies = allies;
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
                    Ui.mm("<gray>Right-click to leap and slam down a tower"),
                    Ui.mm("<gray>of shimmering ice for <white>10s"),
                    Ui.mm("<gray>Nobody inside can leave, mobs included."),
                    Ui.mm("<gray>Anyone can walk in, but then they're stuck too"),
                    Ui.mm("<gray>You and your team come and go as you like"),
                    Ui.mm("<gray>No hits get through the wall, either way"),
                    Ui.mm("<gray>Your side sees it <#7FD8FF>blue<gray>, enemies see <#FF6B5E>red"),
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

    /** The caster and their team: not held by the cage, and they see it blue. */
    private boolean friendly(UUID caster, Player p) { return caster.equals(p.getUniqueId()) || allies.test(caster, p); }

    private Palette paletteFor(UUID caster, Player p) { return friendly(caster, p) ? blue : red; }

    /** Run a per-viewer effect for every player near {@code at}, with the colours that player sees. */
    private void fx(UUID caster, Location at, BiConsumer<Player, Palette> f) {
        for (Player p : at.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(at) < VIEW * VIEW) f.accept(p, paletteFor(caster, p));
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
        if (!allowedAt.test(p.getLocation())) { p.sendActionBar(Ui.mm("<gray>" + NAME + " <gray>only works in PvP zones (KOTH, Scrapyard) and the hub <white>Arena<gray>.")); return; }
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
        w.playSound(feet, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1f, 1.6f);
        fx(id, feet, (v, pal) -> {
            v.spawnParticle(Particle.CLOUD, feet, 14, 0.4, 0.05, 0.4, 0.06);
            ring(v, feet.getX(), feet.getY() + 0.1, feet.getZ(), 1.2, 24, new Particle.DustOptions(pal.sky(), 1.4f));
        });
        int[] n = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            int k = n[0]++;
            if (!p.isOnline() || p.isDead()) { leaping.remove(id); jammed.remove(id); task.cancel(); return; }
            if (jammed.remove(id)) {   // an EMP caught the leap: no cage
                task.cancel();
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> leaping.remove(id), 10L);
                return;
            }
            Location at = p.getLocation().add(0, 0.9, 0);
            fx(id, at, (v, pal) -> {   // a spiral of frost round the caster on the way up
                for (int s = 0; s < 2; s++) {
                    double a = k * 0.9 + s * Math.PI;
                    v.spawnParticle(Particle.DUST_COLOR_TRANSITION, at.getX() + Math.cos(a) * 0.6, at.getY() - 0.4, at.getZ() + Math.sin(a) * 0.6, 1, 0, 0, 0, 0,
                            new Particle.DustTransition(pal.frost(), pal.azure(), 1.4f));
                }
                v.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 3, 0.2, 0.4, 0.2, 0, new Particle.DustTransition(pal.frost(), pal.sky(), 1.2f));
                if (k % 2 == 0) v.spawnParticle(Particle.END_ROD, at, 1, 0.2, 0.3, 0.2, 0.01);
            });
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
        UUID id = p.getUniqueId();
        Cage cage = new Cage(id, p.getName(), w, c.getX(), c.getY(), c.getZ());
        cages.add(cage);
        w.playSound(c, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.PLAYERS, 1.2f, 0.8f);
        w.playSound(c, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.5f, 0.6f);
        w.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.4f, 1.5f);
        w.playSound(c, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1f, 0.8f);
        w.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.4f, 0.8f);
        w.playSound(c, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.35f, 2f);
        BlockData under = c.clone().add(0, -0.5, 0).getBlock().getBlockData();
        fx(id, c, (v, pal) -> {
            v.spawnParticle(Particle.EXPLOSION, c, 1);
            v.spawnParticle(Particle.END_ROD, c.clone().add(0, 0.3, 0), 40, 0.2, 0.1, 0.2, 0.35);
            if (!under.getMaterial().isAir()) v.spawnParticle(Particle.BLOCK, c, 70, 1.2, 0.1, 1.2, 0.2, under);
        });
        // the shockwave: a double ring that races out to the wall in 6 ticks, shedding frost
        int[] n = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            int k = n[0]++;
            double r = CageShape.RADIUS * (k + 1) / 6.0;
            double[] flakes = new double[12];
            for (int i = 0; i < flakes.length; i++) flakes[i] = rnd.nextDouble() * Math.PI * 2;
            fx(id, c, (v, pal) -> {
                ring(v, cage.cx, cage.y + 0.15, cage.cz, r, (int) (14 + r * 9), new Particle.DustOptions(pal.frost(), 2.0f));
                if (r > 0.6) ring(v, cage.cx, cage.y + 0.1, cage.cz, r - 0.5, (int) (10 + r * 7), new Particle.DustOptions(pal.azure(), 1.5f));
                for (double a : flakes)
                    v.spawnParticle(Particle.SNOWFLAKE, cage.cx + Math.cos(a) * r, cage.y + 0.3, cage.cz + Math.sin(a) * r, 0, Math.cos(a), 0.5, Math.sin(a), 0.15);
                if (k >= 5) for (int i = 0; i < CageShape.SEGMENTS; i++) {
                    double a = CageShape.edgeAngle(i);
                    v.spawnParticle(Particle.ELECTRIC_SPARK, cage.cx + Math.cos(a) * CageShape.RADIUS, cage.y + 0.4, cage.cz + Math.sin(a) * CageShape.RADIUS, 3, 0.1, 0.3, 0.1, 0.2);
                }
            });
            if (k >= 5) task.cancel();
        }, 0L, 1L);
        cage.build();
        // who got caught
        for (Player q : w.getPlayers()) {
            Palette pal = paletteFor(id, q);
            if (!cage.inside(q.getLocation())) {
                if (q.getLocation().distanceSquared(c) < 24 * 24)
                    q.sendActionBar(Ui.mm("<" + pal.hex() + ">⛓ <white>" + p.getName() + " <gray>locked a cage down. "
                            + (friendly(id, q) ? "You can come and go." : "Walk in if you dare.")));
                continue;
            }
            if (friendly(id, q)) {
                q.showTitle(Title.title(Ui.mm(pal.title()),
                        Ui.mm(q.equals(p) ? "<gray>Your cage. You come and go - they don't. <white>10s" : "<white>" + p.getName() + "<gray>'s cage. You can come and go."),
                        Title.Times.times(Duration.ofMillis(120), Duration.ofMillis(1300), Duration.ofMillis(400))));
                continue;
            }
            cage.trapped.add(q.getUniqueId());
            q.showTitle(Title.title(Ui.mm(pal.title()), Ui.mm("<gray>by <white>" + p.getName() + " <dark_gray>· <gray>no way out for <white>10s"),
                    Title.Times.times(Duration.ofMillis(120), Duration.ofMillis(1300), Duration.ofMillis(400))));
        }
    }

    // ------------------------------------------------------------------ the barrier

    /** Walking (or being knocked) out of a cage you're held by: you stop at the glass and get nudged back in. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (cages.isEmpty()) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return;   // only horizontal moves can cross the wall
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        for (Cage c : cages) {
            if (c.world != from.getWorld() || friendly(c.owner, p) || !c.inside(from)
                    || CageShape.insideXZ(c.cx, c.cz, to.getX(), to.getZ(), CageShape.RADIUS - CageShape.BODY)) continue;
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
            Vector in = new Vector(c.cx - from.getX(), 0, c.cz - from.getZ());
            if (in.lengthSquared() > 1e-6) p.setVelocity(in.normalize().multiply(0.3).setY(Math.min(0.1, p.getVelocity().getY())));
            if (fxReady(p)) {
                wallHit(c, from, 1.0);
                p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 0.5f, 1.6f);
                p.sendActionBar(Ui.mm("<#FF6B5E>⛓ <gray>The ice holds. <white>" + secs(c.left()) + "s <gray>left"));
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
            if (c.world != e.getFrom().getWorld() || friendly(c.owner, e.getPlayer()) || !c.inside(e.getFrom()) || c.inside(e.getTo())) continue;
            e.setCancelled(true);
            wallHit(c, e.getFrom(), 1.4);
            e.getPlayer().sendActionBar(Ui.mm("<#FF6B5E>⛓ <gray>Nice try. The ice holds."));
            return;
        }
    }

    /**
     * No damage across the wall, either way, for everyone (the caster too). Runs last so it also wins over the Arena's
     * "PvP is on here" rule, and the Gale Bellows' own probe sees the cancel and skips anyone on the other side.
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
                p.sendActionBar(Ui.mm("<" + paletteFor(c.owner, p).hex() + ">⛓ <gray>The ice soaks up the hit"));
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

    /** A crackle of light where something met the glass: sparks, a star of frost, a glassy ping, and that pane flares. */
    private void wallHit(Cage c, Location near, double size) {
        Vector out = new Vector(near.getX() - c.cx, 0, near.getZ() - c.cz);
        if (out.lengthSquared() < 1e-6) out = new Vector(1, 0, 0);
        out.normalize();
        Location at = new Location(c.world, c.cx + out.getX() * CageShape.RADIUS, Math.max(c.y + 0.3, near.getY() + 1.0), c.cz + out.getZ() * CageShape.RADIUS);
        fx(c.owner, at, (v, pal) -> {
            v.spawnParticle(Particle.ELECTRIC_SPARK, at, (int) (10 * size), 0.15, 0.35, 0.15, 0.18);
            v.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, (int) (12 * size), 0.1, 0.5, 0.1, 0, new Particle.DustTransition(pal.frost(), pal.azure(), 1.5f));
            v.spawnParticle(Particle.END_ROD, at, 3, 0.1, 0.2, 0.1, 0.05);
            v.spawnParticle(Particle.SNOWFLAKE, at, (int) (4 * size), 0.1, 0.3, 0.1, 0.04);
        });
        c.world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.PLAYERS, 0.9f, 1.5f);
        c.flash(out);
    }

    // ------------------------------------------------------------------ housekeeping

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        leaping.remove(e.getPlayer().getUniqueId());
        fxReadyAt.remove(e.getPlayer().getUniqueId());
    }

    /**
     * EMP: every cage the wave touches (its wall, not just its middle) shatters at once, and a Rimelock leap still in
     * the air fizzles with no cage. The caster's own cage is left alone.
     */
    @Override
    public int hack(Location center, double radius, Player caster) {
        int n = 0;
        for (Cage c : new ArrayList<>(cages)) {
            if (c.owner.equals(caster.getUniqueId()) || !EmpWave.reaches(center, c.center(), radius, CageShape.RADIUS)) continue;
            c.shatter();
            Player owner = Bukkit.getPlayer(c.owner);
            if (owner != null) owner.sendActionBar(Ui.mm("<#B6FF3B>⚡ <gray>Your " + NAME + " <gray>was <white>shut down <gray>by an EMP"));
            n++;
        }
        for (UUID id : new ArrayList<>(leaping)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || id.equals(caster.getUniqueId()) || jammed.contains(id) || !EmpWave.reaches(center, p.getLocation(), radius)) continue;
            jammed.add(id);
            p.sendActionBar(Ui.mm("<#B6FF3B>⚡ <gray>Your " + NAME + " <gray>was <white>shut down <gray>by an EMP"));
            n++;
        }
        return n;
    }

    /** True when a cage's wall reaches something {@code reach} blocks round {@code l} (its middle within the cage radius + reach). */
    public boolean touches(Location l, double reach) {
        for (Cage c : cages)
            if (c.world == l.getWorld() && CageShape.insideXZ(c.cx, c.cz, l.getX(), l.getZ(), CageShape.RADIUS + reach)
                    && l.getY() >= c.y - CageShape.BELOW && l.getY() <= c.y + CageShape.ABOVE) return true;
        return false;
    }

    /** Plugin disable: take every cage down at once (displays aren't saved, but don't leave them for the rest of this run). */
    public void shutdown() {
        for (Cage c : new ArrayList<>(cages)) c.remove();
        cages.clear();
        leaping.clear();
    }

    private static String secs(int ticks) { return String.format(Locale.ROOT, "%.1f", Math.max(0, ticks) / 20.0); }

    private static void ring(Player v, double x, double y, double z, double r, int pts, Particle.DustOptions dust) {
        for (int i = 0; i < pts; i++) {
            double a = i * 2 * Math.PI / pts;
            v.spawnParticle(Particle.DUST, x + Math.cos(a) * r, y, z + Math.sin(a) * r, 1, 0.02, 0.02, 0.02, 0, dust);
        }
    }

    /** Smallest angle between two directions, 0..pi. */
    private static double angleGap(double a, double b) {
        double d = Math.abs((a - b) % (2 * Math.PI));
        return d > Math.PI ? 2 * Math.PI - d : d;
    }

    // ------------------------------------------------------------------ geometry of the pieces (shared by both colours)

    /** A slab of w (along the wall) x h x th (across it), centred on the wall line at {@code angle}/{@code radius}. */
    private static Transformation slab(double angle, double radius, float w, float h, float th, float lift) {
        Quaternionf q = new Quaternionf().rotateY((float) CageShape.panelYaw(angle));
        Vector3f mid = new Vector3f((float) (Math.cos(angle) * radius), lift, (float) (Math.sin(angle) * radius));
        Vector3f corner = q.transform(new Vector3f(-w / 2, 0, -th / 2)).add(mid);
        return new Transformation(corner, q, new Vector3f(w, h, th), new Quaternionf());
    }

    private static Transformation post(double angle, float h, float lift) {
        float s = 0.2f;
        Vector3f at = new Vector3f((float) (Math.cos(angle) * CageShape.RADIUS) - s / 2, lift, (float) (Math.sin(angle) * CageShape.RADIUS) - s / 2);
        return new Transformation(at, new Quaternionf(), new Vector3f(s, h, s), new Quaternionf());
    }

    /** A glass cube stood on its corner like a gem, floating over a post. */
    private static Transformation crystal(double angle, float scale, float spin, float bob) {
        Vector3f at = new Vector3f((float) (Math.cos(angle) * CageShape.RADIUS), (float) CageShape.WALL_HEIGHT + 1.0f + bob, (float) (Math.sin(angle) * CageShape.RADIUS));
        return new Transformation(at, new Quaternionf().rotateY(spin).rotateX(0.7854f).rotateZ(0.6155f), new Vector3f(scale), new Quaternionf());
    }

    private static void move(Display d, Transformation tf, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(tf);
    }

    // ------------------------------------------------------------------ one cage

    private final class Cage {
        final UUID owner;
        final String ownerName;
        final World world;
        final double cx, y, cz;
        final Side friends, foes;                            // the blue set and the red set of displays
        final long[] flashUntil = new long[CageShape.SEGMENTS];   // per pane: flaring after a hit (tick)
        final Map<UUID, Boolean> viewers = new HashMap<>();  // who has been shown which set (true = blue)
        final Set<UUID> trapped = new HashSet<>();
        final Map<UUID, Location> shots = new HashMap<>();   // projectiles near the wall: where they were last tick
        final Map<UUID, Location> mobsIn = new HashMap<>();  // mobs in the cage: their last spot inside
        boolean standing;                                    // panes have finished rising: the shimmer may take over
        BukkitTask task;
        int t;

        /**
         * Per pane: how far below the caster's landing height the ground under it is (0..BELOW). The panes, the glowing
         * base line and the posts reach down that far, so the wall meets the ground on a slope instead of hovering
         * over it. The barrier already reaches BELOW down, so this only makes the glass match it.
         */
        final float[] drop = new float[CageShape.SEGMENTS];

        Cage(UUID owner, String ownerName, World world, double cx, double y, double cz) {
            this.owner = owner; this.ownerName = ownerName; this.world = world; this.cx = cx; this.y = y; this.cz = cz;
            for (int i = 0; i < CageShape.SEGMENTS; i++) drop[i] = groundDrop(CageShape.panelAngle(i));
            this.friends = new Side(blue);
            this.foes = new Side(red);
        }

        /** Lowest ground under a pane: samples its middle and both ends (just outside the glass), deepest wins. */
        private float groundDrop(double a) {
            double half = CageShape.panelWidth() / 2, r = CageShape.RADIUS + 0.2;
            double tx = -Math.sin(a), tz = Math.cos(a);   // along the wall
            float worst = 0;
            for (double s = -half; s <= half + 1e-6; s += half) {
                double x = cx + Math.cos(a) * r + tx * s, z = cz + Math.sin(a) * r + tz * s;
                worst = Math.max(worst, dropAt((int) Math.floor(x), (int) Math.floor(z)));
            }
            return worst;
        }

        /** How far the ground in this column sits below the cage's base (0 if it's level or higher, capped at BELOW). */
        private float dropAt(int bx, int bz) {
            int feet = (int) Math.floor(y + 0.01);
            if (world.getBlockAt(bx, feet, bz).isSolid()) return 0;           // ground at (or over) the base: no gap
            for (int by = feet - 1; by >= feet - (int) CageShape.BELOW; by--)
                if (world.getBlockAt(bx, by, bz).isSolid()) return (float) Math.max(0, y - (by + 1));
            return (float) CageShape.BELOW;                                    // a drop: go as deep as the barrier does
        }

        /** Drop at an edge between two panes (where a post stands): the deeper of the two. */
        private float edgeDrop(int i) { return Math.max(drop[Math.floorMod(i - 1, CageShape.SEGMENTS)], drop[i % CageShape.SEGMENTS]); }

        /** Drop under whichever pane covers this angle. */
        private float dropAtAngle(double a) { return drop[CageShape.panelIndex(a)]; }

        int left() { return DURATION_TICKS - t; }
        boolean inside(Location l) { return l.getWorld() == world && CageShape.inside(cx, y, cz, l.getX(), l.getY(), l.getZ()); }
        Location center() { return new Location(world, cx, y, cz); }
        private void later(int ticks, Runnable r) { plugin.getServer().getScheduler().runTaskLater(plugin, r, Math.max(1, ticks)); }

        /** One colour's full set of displays, visible only to the players on that side. */
        final class Side {
            final Palette pal;
            final BlockDisplay[] panes = new BlockDisplay[CageShape.SEGMENTS], tops = new BlockDisplay[CageShape.SEGMENTS],
                    floors = new BlockDisplay[CageShape.SEGMENTS];
            final boolean[] lit = new boolean[CageShape.SEGMENTS];   // which panes currently show the glint block
            final int[] bright = new int[CageShape.SEGMENTS];
            final List<BlockDisplay> posts = new ArrayList<>(), streaks = new ArrayList<>();
            final List<ItemDisplay> crystals = new ArrayList<>();
            final List<Entity> all = new ArrayList<>();
            TextDisplay timer;

            Side(Palette pal) { this.pal = pal; }

            BlockDisplay block(BlockData data, Transformation tf, Color glow, int light) {
                BlockDisplay b = world.spawn(center(), BlockDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setVisibleByDefault(false);
                    d.setBlock(data);
                    d.setBrightness(new Display.Brightness(light, 15));
                    d.setViewRange(4f);
                    d.setShadowRadius(0);
                    d.setTransformation(tf);
                    if (glow != null) { d.setGlowing(true); d.setGlowColorOverride(glow); }
                });
                all.add(b);
                return b;
            }

            // the build: panes shoot up white past full height, settle and cool to colour, in a wave round the circle
            void build() {
                int n = CageShape.SEGMENTS;
                float w = (float) CageShape.panelWidth(), h = (float) CageShape.WALL_HEIGHT;
                for (int i = 0; i < n; i++) {
                    double a = CageShape.panelAngle(i);
                    int at = 2 + i / 4;   // the wave runs round the circle in ~8 ticks
                    float dp = drop[i];    // pane, base line and post start at the ground under this pane
                    BlockDisplay pane = block(pal.glint(), slab(a, CageShape.RADIUS, w, 0.01f, 0.05f, -dp), null, 15);
                    BlockDisplay top = block(pal.frame(), slab(a, CageShape.RADIUS, w, 0.12f, 0.14f, -dp), pal.sky(), 15);
                    BlockDisplay floor = block(pal.glass(), slab(a, CageShape.RADIUS - 0.05, 0.01f, 0.03f, 0.8f, 0.01f - dp), pal.sky(), 15);
                    panes[i] = pane; tops[i] = top; floors[i] = floor; lit[i] = true; bright[i] = 15;
                    later(5, () -> move(floor, slab(a, CageShape.RADIUS - 0.05, w, 0.03f, 0.8f, 0.01f - dp), 4));   // unrolls as the shockwave arrives
                    later(at, () -> {
                        move(pane, slab(a, CageShape.RADIUS, w, h * OVERSHOOT + dp, 0.05f, -dp), RISE_TICKS);
                        move(top, slab(a, CageShape.RADIUS, w, 0.12f, 0.14f, h * OVERSHOOT), RISE_TICKS);
                    });
                    int idx = i;
                    later(at + RISE_TICKS, () -> {
                        move(pane, slab(a, CageShape.RADIUS, w, h + dp, 0.05f, -dp), SETTLE_TICKS);
                        move(top, slab(a, CageShape.RADIUS, w, 0.12f, 0.14f, h), SETTLE_TICKS);
                        if (pane.isValid()) pane.setBlock(pal.glass());
                        lit[idx] = false;
                    });
                    if (i % 4 == 0) {   // a glowing post every 4 panes, a spinning crystal over it
                        double e = CageShape.edgeAngle(i);
                        float pd = edgeDrop(i);
                        BlockDisplay post = block(pal.frame(), post(e, 0.01f, -pd), pal.sky(), 15);
                        posts.add(post);
                        later(at, () -> move(post, post(e, (h + 0.4f) * OVERSHOOT + pd, -pd), RISE_TICKS));
                        later(at + RISE_TICKS, () -> move(post, post(e, h + 0.4f + pd, -pd), SETTLE_TICKS));
                        ItemDisplay gem = world.spawn(center(), ItemDisplay.class, d -> {
                            d.setPersistent(false);
                            d.setVisibleByDefault(false);
                            d.setItemStack(new ItemStack(pal.gem()));
                            d.setBrightness(new Display.Brightness(15, 15));
                            d.setViewRange(4f);
                            d.setShadowRadius(0);
                            d.setGlowing(true);
                            d.setGlowColorOverride(pal.ice());
                            d.setTransformation(crystal(e, 0.01f, 0, 0));
                        });
                        crystals.add(gem);
                        all.add(gem);
                        later(at + RISE_TICKS + 2, () -> {
                            move(gem, crystal(e, 1.3f, (float) Math.PI, 0.15f), 5);                          // pops in big...
                            later(5, () -> move(gem, crystal(e, 0.9f, (float) Math.PI * 1.5f, 0), 4));      // ...and settles
                        });
                    }
                }
                // two light streaks that ride the glints round the wall (just inside the glass)
                for (int k = 0; k < 2; k++) streaks.add(block(pal.glint(), slab(k * Math.PI, CageShape.RADIUS - 0.09, 0.22f, 0.01f, 0.05f, 0), pal.frost(), 15));
                timer = world.spawn(center().add(0, 6.5, 0), TextDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setVisibleByDefault(false);
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setShadowed(true);
                    d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(2.4f), new Quaternionf()));
                });
                all.add(timer);
            }

            void show(Player p, boolean on) {
                for (Entity e : all) if (e.isValid()) { if (on) p.showEntity(plugin, e); else p.hideEntity(plugin, e); }
            }

            /**
             * The shimmer: two white glints sweep round the wall with a light streak riding each, and a brightness
             * ripple rolls round the glass. Panes that were hit flare. In the last 2 s the whole wall strobes. Only
             * changed values are sent.
             */
            void shimmer(double glintAt, boolean strobe) {
                for (int i = 0; i < CageShape.SEGMENTS; i++) {
                    BlockDisplay pane = panes[i];
                    if (pane == null || !pane.isValid()) continue;
                    double a = CageShape.panelAngle(i);
                    boolean w = strobe || flashUntil[i] > t || angleGap(a, glintAt) < 0.23 || angleGap(a, glintAt + Math.PI) < 0.23;
                    if (w != lit[i]) { pane.setBlock(w ? pal.glint() : pal.glass()); lit[i] = w; }
                    int b = w ? 15 : (int) Math.round(9 + 6 * (0.5 + 0.5 * Math.sin(t * 0.3 - a * 4)));
                    if (b != bright[i]) { pane.setBrightness(new Display.Brightness(b, 15)); bright[i] = b; }
                }
                float h = (float) CageShape.WALL_HEIGHT;
                for (int k = 0; k < streaks.size(); k++) {
                    double sa = glintAt + k * Math.PI + 0.12;
                    float sd = dropAtAngle(sa);
                    move(streaks.get(k), slab(sa, CageShape.RADIUS - 0.09, 0.22f, h + sd, 0.05f, -sd), 2);
                }
            }

            /** Each crystal turns a quarter and bobs, a beat behind its neighbour. */
            void spinCrystals() {
                for (int k = 0; k < crystals.size(); k++) {
                    double e = CageShape.edgeAngle(k * 4);
                    float spin = (float) ((t / 10 + k) % 4 * Math.PI / 2 + Math.PI / 2);
                    float bob = (float) (Math.sin((t / 10.0 + k) * 1.2) * 0.18);
                    move(crystals.get(k), crystal(e, 0.9f, spin, bob), 10);
                }
            }

            void frameGlow(boolean on) {
                for (BlockDisplay d : tops) if (d != null && d.isValid()) d.setGlowing(on);
                for (BlockDisplay d : posts) if (d.isValid()) d.setGlowing(on);
            }

            void timerText(int left, boolean ending) {
                if (timer == null || !timer.isValid()) return;
                String phase = String.format(Locale.ROOT, "%.2f", Math.sin(t / 6.0));
                String[] stops = pal.gradient().split(":");
                String num = ending
                        ? "<" + (t % 8 < 4 ? stops[0] : stops[2]) + "><bold>⛓ " + secs(left) + "</bold>"
                        : "<gradient:" + pal.gradient() + ":" + stops[1] + ":" + phase + "><bold>⛓ " + secs(left) + "</bold></gradient>";
                timer.text(Ui.mm(num + "<newline><gray><font:uniform>" + ownerName + "'s rimelock</font>"));
            }

            /** Everything flares, then bursts outward into shrinking shards. */
            void shatter() {
                float w = (float) CageShape.panelWidth(), h = (float) CageShape.WALL_HEIGHT;
                for (BlockDisplay d : panes) if (d != null && d.isValid()) { d.setBlock(pal.glint()); d.setBrightness(new Display.Brightness(15, 15)); }
                long seed = rnd.nextLong();
                later(2, () -> {
                    Random r = new Random(seed);   // both colours break the same way
                    for (int i = 0; i < CageShape.SEGMENTS; i++) {
                        double a = CageShape.panelAngle(i);
                        float out = 1.5f + r.nextFloat() * 2f, up = r.nextFloat() * 4f;
                        move(panes[i], slab(a, CageShape.RADIUS + out, w * 0.15f, h * 0.12f, 0.05f, up + h * 0.3f), BREAK_TICKS);
                        move(tops[i], slab(a, CageShape.RADIUS + out * 0.8, w * 0.2f, 0.05f, 0.05f, h + up), BREAK_TICKS);
                        move(floors[i], slab(a, CageShape.RADIUS - 0.05, 0.01f, 0.03f, 0.8f, 0.01f - drop[i]), BREAK_TICKS);
                    }
                    for (int k = 0; k < posts.size(); k++) move(posts.get(k), post(CageShape.edgeAngle(k * 4), 0.01f, -edgeDrop(k * 4)), BREAK_TICKS);
                    for (BlockDisplay s : streaks) s.remove();
                    for (int k = 0; k < crystals.size(); k++) move(crystals.get(k), crystal(CageShape.edgeAngle(k * 4), 0.01f, (float) Math.PI * 3, 1.5f), BREAK_TICKS);
                });
                if (timer != null) timer.remove();
            }

            void remove() { for (Entity e : all) e.remove(); }
        }

        // --- life cycle

        void build() {
            friends.build();
            foes.build();
            syncViewers();
            later(2 + CageShape.SEGMENTS / 4 + RISE_TICKS + SETTLE_TICKS, () -> standing = true);
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }

        /** Each player sees exactly one colour: blue for the caster's side, red for everyone else. Newcomers too. */
        void syncViewers() {
            Set<UUID> here = new HashSet<>();
            for (Player p : world.getPlayers()) {
                here.add(p.getUniqueId());
                boolean f = friendly(owner, p);
                Boolean was = viewers.get(p.getUniqueId());
                if (was != null && was == f) continue;
                if (was != null) (was ? friends : foes).show(p, false);
                (f ? friends : foes).show(p, true);
                viewers.put(p.getUniqueId(), f);
            }
            viewers.keySet().retainAll(here);
        }

        /** The pane facing {@code out} flares for half a second (blocked hits, bounces, shots). */
        void flash(Vector out) {
            double a = Math.atan2(out.getZ(), out.getX());
            if (a < 0) a += 2 * Math.PI;
            int i = (int) (a / (2 * Math.PI) * CageShape.SEGMENTS) % CageShape.SEGMENTS;
            flashUntil[i] = t + 10;
        }

        // --- every tick while it stands

        void tick() {
            t++;
            int left = left();
            if (left <= 0) { shatter(); return; }
            double R = CageShape.RADIUS, H = CageShape.WALL_HEIGHT;
            double glintAt = (t * GLINT_SPEED) % (2 * Math.PI);
            boolean ending = left <= 40;

            if (t % 10 == 0) syncViewers();
            if (standing && t % 2 == 0) {
                boolean strobe = ending && (left / 4) % 2 == 0;
                friends.shimmer(glintAt, strobe);
                foes.shimmer(glintAt, strobe);
            }
            if (t % 10 == 0) { friends.spinCrystals(); foes.spinCrystals(); }
            if (t % 2 == 0) { friends.timerText(left, ending); foes.timerText(left, ending); }
            if (left <= 30 && left % 3 == 0) { boolean on = (left / 3) % 2 == 0; friends.frameGlow(on); foes.frameGlow(on); }

            particles(R, H, glintAt);

            // sound: chimes that climb as time runs out, a hum, and the glass singing now and then
            if (left % 20 == 0) {
                float pitch = 0.6f + (DURATION_TICKS - left) / 20f * 0.11f;
                world.playSound(center(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.8f, pitch);
            } else if (ending && left % 5 == 0) world.playSound(center(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.6f, 1.6f);
            if (t % 40 == 1) world.playSound(center(), Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 1f, 1.4f);
            if (t % 7 == 0 && rnd.nextInt(3) == 0) {
                double a = rnd.nextDouble() * Math.PI * 2;
                world.playSound(new Location(world, cx + Math.cos(a) * R, y + 1.5, cz + Math.sin(a) * R), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.5f, 1.2f + rnd.nextFloat() * 0.6f);
            }

            // anyone who walked in since: they're stuck now too (unless they're on the caster's side)
            if (t % 2 == 0) for (Player q : world.getPlayers()) {
                if (!inside(q.getLocation())) continue;
                if (friendly(owner, q)) {
                    if (t % 4 == 0) q.sendActionBar(Ui.mm("<#7FD8FF>⛓ <gray>" + (q.getUniqueId().equals(owner) ? "Your" : ownerName + "'s")
                            + " rimelock <dark_gray>· <white>" + secs(left) + "s <dark_gray>· <gray>you can come and go"));
                    continue;
                }
                if (trapped.add(q.getUniqueId())) {
                    q.showTitle(Title.title(Ui.mm(red.title()), Ui.mm("<gray>You walked in. Now you can't leave."),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(300))));
                    q.playSound(q.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 1f, 0.6f);
                    q.playSound(q.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1f, 1.4f);
                    Location l = q.getLocation().add(0, 1, 0);
                    fx(owner, l, (v, pal) -> {
                        v.spawnParticle(Particle.ELECTRIC_SPARK, l, 14, 0.3, 0.5, 0.3, 0.1);
                        v.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 12, 0.3, 0.5, 0.3, 0, new Particle.DustTransition(pal.frost(), pal.azure(), 1.3f));
                    });
                } else if (t % 4 == 0 && fxReadyAt.getOrDefault(q.getUniqueId(), 0L) < System.currentTimeMillis() - 600) {
                    q.sendActionBar(Ui.mm("<#FF6B5E>⛓ <bold>RIMELOCKED</bold> <dark_gray>· <white>" + secs(left) + "s"));
                }
            }
            bounceShots();
            holdMobs();
        }

        /** Comets on the rim, motes up the glass, an aurora over the top, glint sparkles, snow inside, the floor pulse. */
        private void particles(double R, double H, double glintAt) {
            boolean even = t % 2 == 0, third = t % 3 == 0, sixth = t % 6 == 0, tenth = t % 10 == 0;
            if (!even && !third) return;
            // roll the random spots once so both colours show the same pattern
            double[] motes = new double[8], aurora = new double[6], sparks = new double[2];
            for (int i = 0; i < motes.length; i++) motes[i] = rnd.nextDouble() * (i % 2 == 0 ? Math.PI * 2 : H);
            for (int i = 0; i < aurora.length; i++) aurora[i] = rnd.nextDouble() * (i % 2 == 0 ? Math.PI * 2 : 2.0);
            for (int i = 0; i < sparks.length; i++) sparks[i] = rnd.nextDouble() * H;
            boolean[] flip = {rnd.nextBoolean(), rnd.nextBoolean(), rnd.nextBoolean()};
            fx(owner, center(), (v, pal) -> {
                Particle.DustOptions ice = new Particle.DustOptions(pal.ice(), 1.8f), az = new Particle.DustOptions(pal.azure(), 1.4f);
                if (even) {
                    for (int k = 0; k < 3; k++) {   // three comets chase round the top rim
                        double a = t * 0.16 + k * Math.PI * 2 / 3;
                        double x = cx + Math.cos(a) * R, z = cz + Math.sin(a) * R;
                        v.spawnParticle(Particle.DUST, x, y + H + 0.1, z, 3, 0.05, 0.05, 0.05, 0, ice);
                        v.spawnParticle(Particle.END_ROD, x, y + H + 0.1, z, 1, 0, 0, 0, 0.01);
                    }
                    double b = -t * 0.12;   // one sweeps the floor the other way
                    v.spawnParticle(Particle.DUST, cx + Math.cos(b) * R, y + 0.15, cz + Math.sin(b) * R, 2, 0.05, 0.02, 0.05, 0, az);
                    for (int i = 0; i < motes.length; i += 2) {   // motes of light drifting up the glass
                        double a = motes[i], hy = motes[i + 1];
                        v.spawnParticle(Particle.DUST_COLOR_TRANSITION, cx + Math.cos(a) * R, y + hy, cz + Math.sin(a) * R, 1, 0.03, 0.15, 0.03, 0,
                                new Particle.DustTransition(pal.sky(), pal.frost(), 1.2f));
                        if (i == 0) v.spawnParticle(Particle.END_ROD, cx + Math.cos(a) * (R - 0.1), y + hy, cz + Math.sin(a) * (R - 0.1), 0, 0, 1, 0, 0.05);
                    }
                    for (int i = 0; i < aurora.length; i += 2) {   // the aurora: curtains of colour lifting off the rim
                        double a = aurora[i], up = 0.2 + aurora[i + 1];
                        v.spawnParticle(Particle.DUST_COLOR_TRANSITION, cx + Math.cos(a) * R, y + H + up, cz + Math.sin(a) * R, 1, 0.05, 0.2, 0.05, 0,
                                new Particle.DustTransition(flip[i / 2] ? pal.ice() : pal.sky(), flip[i / 2] ? pal.azure() : pal.deep(), 2.0f));
                    }
                }
                if (third) for (int k = 0; k < 2; k++) {   // the glints throw sparkles off the glass where they pass
                    double a = glintAt + k * Math.PI;
                    v.spawnParticle(Particle.END_ROD, cx + Math.cos(a) * (R + 0.15), y + sparks[k], cz + Math.sin(a) * (R + 0.15), 0, Math.cos(a), 0.2, Math.sin(a), 0.05);
                    v.spawnParticle(Particle.WAX_OFF, cx + Math.cos(a) * R, y + sparks[1 - k], cz + Math.sin(a) * R, 1, 0.05, 0.5, 0.05, 0);
                }
                if (sixth) {   // snow drifting down inside, glow motes hanging in the air
                    v.spawnParticle(Particle.SNOWFLAKE, cx, y + 6, cz, 4, R * 0.55, 1.5, R * 0.55, 0.005);
                    v.spawnParticle(Particle.GLOW, cx, y + 2, cz, 1, R * 0.5, 1.2, R * 0.5, 0);
                }
                if (tenth) ring(v, cx, y + 0.1, cz, R - 0.05, 64, az);   // a soft pulse round the base
            });
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

        /**
         * Mobs don't fire move events, so each tick: any mob that was inside and has crossed the wall goes back to its
         * last spot inside and gets nudged towards the middle. A mob that walks in is held from then on. Dropping out
         * of the bottom (a hole, the void) lets it go, same as for players. Mounts drop their riders first, so a
         * player on a horse is held by the player rule instead.
         */
        private void holdMobs() {
            Set<UUID> seen = new HashSet<>();
            boolean fxOn = t % 4 == 0;
            for (Entity en : world.getNearbyEntities(center(), CageShape.RADIUS + 3, CageShape.ABOVE, CageShape.RADIUS + 3)) {
                if (!(en instanceof LivingEntity m) || en instanceof Player || m.isDead() || m.isInsideVehicle() || en.getScoreboardTags().contains(NO_HOLD_TAG)) continue;
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
                if (fxOn) { wallHit(this, back, 0.7); fxOn = false; }
            }
            mobsIn.keySet().retainAll(seen);
        }

        // --- the end

        void shatter() {
            if (task != null) task.cancel();
            cages.remove(this);
            friends.shatter();
            foes.shatter();
            world.playSound(center(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.4f, 2f);
            double h = CageShape.WALL_HEIGHT;
            later(2, () -> {
                double[] spots = new double[160];
                for (int i = 0; i < spots.length; i++) spots[i] = rnd.nextDouble() * (i % 2 == 0 ? Math.PI * 2 : h);
                fx(owner, center(), (v, pal) -> {
                    for (int i = 0; i < spots.length; i += 2) {
                        double a = spots[i], hy = spots[i + 1];
                        double x = cx + Math.cos(a) * CageShape.RADIUS, z = cz + Math.sin(a) * CageShape.RADIUS;
                        v.spawnParticle(Particle.BLOCK, x, y + hy, z, 3, 0.2, 0.2, 0.2, 0.1, pal.glass());
                        v.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y + hy, z, 1, 0.1, 0.1, 0.1, 0, new Particle.DustTransition(pal.frost(), pal.azure(), 1.8f));
                        if (i % 8 == 0) v.spawnParticle(Particle.END_ROD, x, y + hy, z, 0, Math.cos(a), 0.3, Math.sin(a), 0.18);
                        if (i % 6 == 0) v.spawnParticle(Particle.SNOWFLAKE, x, y + hy, z, 0, Math.cos(a), 0.2, Math.sin(a), 0.12);
                    }
                    v.spawnParticle(Particle.FIREWORK, cx, y + h, cz, 50, 2.0, 0.5, 2.0, 0.12);
                });
                world.playSound(center(), Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.4f, 0.8f);
                world.playSound(center(), Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0f, 1.3f);
                world.playSound(center(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.2f, 1.0f);
                world.playSound(center(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.2f, 1.4f);
                for (Player q : world.getPlayers()) if (inside(q.getLocation()))
                    q.sendActionBar(Ui.mm("<" + paletteFor(owner, q).hex() + ">⛓ <gray>The ice shatters. " + (friendly(owner, q) ? "Nice work." : "You're free.")));
            });
            later(2 + BREAK_TICKS + 1, () -> { friends.remove(); foes.remove(); });
        }

        void remove() {
            if (task != null) task.cancel();
            friends.remove();
            foes.remove();
        }
    }
}
