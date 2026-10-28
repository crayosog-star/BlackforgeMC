package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.Worlds;
import net.alloymc.core.build.Canvas;
import net.alloymc.core.build.Paster;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.time.Duration;
import java.util.*;

/**
 * The Last Rite duels: a void world ("rite_chambers") with a sealed chamber every 1024 blocks ({@link RiteChamberBuilder}),
 * one duel per chamber. Both duellists are saved, healed and dropped at opposite ends behind cover walls that fall after
 * 3 s; a boss bar counts the duel down. Death inside is never real: the lethal hit is cancelled, the loser shatters into
 * souls, and the real death happens back at their saved spot (so their items drop in their own world, with kill credit
 * to the winner). The winner returns to their spot exactly as they were, with a moment of protection.
 *
 * Safety: snapshots are written to rites.yml when a duel starts, so a crash never strands anyone; on shutdown every duel
 * is undone (both returned, nobody dies); quitting mid-duel counts as a loss.
 */
public final class RiteChambers implements Listener {
    public static final String WORLD = "rite_chambers";
    private static final int SPACING = 1024, WALLS_DROP_TICKS = 60, END_PAUSE_TICKS = 40, GRACE_TICKS = 30, IMMUNE_MS = 30_000;
    private static final String MARKER = "rite-chambers-v1";

    public enum Timeout { BOTH_DIE, BOTH_WEAKENED }

    private record Saved(Location loc, double health, double absorption, int food, float saturation, int fire, Collection<PotionEffect> effects) {}

    private final class Duel {
        final int slot;
        final Player caster;
        final LivingEntity foe;          // a Player, or the practice dummy
        final boolean practice;
        final Saved casterSave, foeSave; // foeSave null for practice
        final Location castSpot;
        final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_20);
        final List<BlockDisplay> wallSkins = new ArrayList<>();
        TextDisplay sign;
        int ticks;
        boolean wallsDown, over;
        Duel(int slot, Player caster, LivingEntity foe, boolean practice, Saved cs, Saved fs, Location castSpot) {
            this.slot = slot; this.caster = caster; this.foe = foe; this.practice = practice; this.casterSave = cs; this.foeSave = fs; this.castSpot = castSpot;
        }
        LivingEntity other(LivingEntity e) { return e.equals(caster) ? foe : caster; }
        boolean has(Entity e) { return e.equals(caster) || e.equals(foe); }
        String foeName() { return foe instanceof Player p ? p.getName() : "the Rite Dummy"; }
    }

    private final AlloyCore plugin;
    private final Timeout timeout;
    private final boolean allowTotems;
    private final int duelTicks, slots;
    private final NamespacedKey dummyKey;
    private final File store;
    private World world;
    private boolean ready;
    private final Duel[] duels;
    private final Map<UUID, Long> immuneUntil = new HashMap<>(), graceUntil = new HashMap<>();
    private final Set<UUID> executing = new HashSet<>();
    private final Random rnd = new Random();

    public RiteChambers(AlloyCore plugin, Timeout timeout, boolean allowTotems, int duelSeconds, int slots) {
        this.plugin = plugin;
        this.timeout = timeout;
        this.allowTotems = allowTotems;
        this.duelTicks = Math.max(10, duelSeconds) * 20;
        this.slots = Math.max(1, Math.min(32, slots));
        this.duels = new Duel[this.slots];
        this.dummyKey = new NamespacedKey(plugin, "rite_dummy");
        this.store = new File(plugin.getDataFolder(), "rites.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTask(plugin, this::load);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    // ------------------------------------------------------------------ the world

    private void load() {
        world = Bukkit.getWorld(WORLD);
        if (world == null) world = new WorldCreator(WORLD).generator(new Worlds.Void()).environment(World.Environment.NORMAL)
                .type(WorldType.FLAT).generateStructures(false).createWorld();
        net.alloymc.core.LobbyRules.setupWorld(plugin, world, 18000);   // forever midnight
        world.setDifficulty(Difficulty.NORMAL);
        world.setPVP(true);
        for (Entity e : world.getEntities()) if (!(e instanceof Player)) e.remove();   // nothing survives a restart
        for (int s = 0; s < slots; s++) for (int cx = 0; cx <= 1; cx++) for (int cz = -1; cz <= 0; cz++) world.addPluginChunkTicket(((s * SPACING) >> 4) + cx, cz, plugin);
        File marker = new File(plugin.getDataFolder(), MARKER + "-" + slots);
        if (marker.exists()) { ready = true; return; }
        Canvas one = RiteChamberBuilder.build(), all = new Canvas();
        for (int s = 0; s < slots; s++) {
            int ox = s * SPACING;
            for (var e : one.blocks().entrySet()) { long k = e.getKey(); all.set(Canvas.kx(k) + ox, Canvas.ky(k), Canvas.kz(k), e.getValue()); }
            for (var e : one.lateBlocks().entrySet()) { long k = e.getKey(); all.setLate(Canvas.kx(k) + ox, Canvas.ky(k), Canvas.kz(k), e.getValue()); }
        }
        for (int s = 0; s < slots; s++) for (int cx = -1; cx <= 2; cx++) for (int cz = -1; cz <= 1; cz++) world.getChunkAt(((s * SPACING) >> 4) + cx, cz).load();
        Paster.paste(plugin, world, all, 6000, () -> {
            try { marker.createNewFile(); } catch (Exception ignored) { }
            ready = true;
            plugin.getLogger().info("Last Rite: " + slots + " chambers built");
        });
    }

    public boolean isChamberWorld(World w) { return world != null && w == world; }

    public boolean hasFreeSlot() {
        if (!ready) return false;
        for (Duel d : duels) if (d == null) return true;
        return false;
    }

    public boolean inRite(Player p) { return duelOf(p) != null; }

    public boolean immune(Player p) { return immuneUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis(); }

    private Duel duelOf(Entity e) {
        for (Duel d : duels) if (d != null && !d.over && d.has(e)) return d;
        return null;
    }

    private Location at(int slot, double x, double y, double z, float yaw) {
        return new Location(world, slot * SPACING + x, y, z, yaw, 0);
    }

    // ------------------------------------------------------------------ start

    /** Opens a chamber for caster vs target (or vs a practice dummy when target is null). False if none is free. */
    public boolean begin(Player caster, Player target, Location castSpot, boolean practice) {
        int slot = -1;
        for (int i = 0; i < slots && slot < 0; i++) if (duels[i] == null) slot = i;
        if (!ready || slot < 0 || (!practice && target == null)) return false;
        Saved cs = save(caster), ts = practice ? null : save(target);
        Location cAt = at(slot, RiteChamberBuilder.CASTER_X, RiteChamberBuilder.Y + 1, RiteChamberBuilder.SPAWN_Z, RiteChamberBuilder.CASTER_YAW);
        Location tAt = at(slot, RiteChamberBuilder.TARGET_X, RiteChamberBuilder.Y + 1, RiteChamberBuilder.SPAWN_Z, RiteChamberBuilder.TARGET_YAW);
        LivingEntity foe = practice ? spawnDummy(tAt) : target;
        Duel d = new Duel(slot, caster, foe, practice, cs, ts, castSpot);
        duels[slot] = d;
        persist();
        raiseWalls(d);
        enter(caster, cAt);
        if (!practice) enter(target, tAt);
        d.sign = Ui.hologram(castSpot.clone().add(0, 2.6, 0), "", 1.2f);
        caster.showBossBar(d.bar);
        if (target != null) target.showBossBar(d.bar);
        updateBar(d);
        return true;
    }

    private Saved save(Player p) {
        return new Saved(p.getLocation().clone(), p.getHealth(), p.getAbsorptionAmount(), p.getFoodLevel(), p.getSaturation(), p.getFireTicks(),
                new ArrayList<>(p.getActivePotionEffects()));
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    /** Healed, cleansed, and set down in the chamber in a swirl of souls. */
    private void enter(Player p, Location to) {
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.teleport(to);
        p.setHealth(maxHealth(p));
        p.setAbsorptionAmount(0);
        p.setFoodLevel(20);
        p.setSaturation(10);
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 30, 0, false, false, false));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.5f, 0.6f);
        p.playSound(p.getLocation(), Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.AMBIENT, 1f, 0.7f);
        world.spawnParticle(Particle.SOUL, to.clone().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.05);
        world.spawnParticle(Particle.REVERSE_PORTAL, to.clone().add(0, 1, 0), 80, 0.4, 1, 0.4, 0.3);
    }

    private LivingEntity spawnDummy(Location at) {
        return world.spawn(at, Husk.class, false, h -> {
            h.getPersistentDataContainer().set(dummyKey, PersistentDataType.BYTE, (byte) 1);
            h.setPersistent(false);
            if (h instanceof Ageable a) a.setAdult();
            h.setRemoveWhenFarAway(false);
            h.setCanPickupItems(false);
            h.customName(Ui.mm("<#B66CFF><bold>Rite Dummy"));
            h.setCustomNameVisible(true);
            AttributeInstance hp = h.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(40);
            h.setHealth(40);
            var eq = h.getEquipment();
            eq.clear();
            eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            eq.setItemInMainHand(new ItemStack(Material.STONE_SWORD));
            eq.setHelmetDropChance(0f);
            eq.setItemInMainHandDropChance(0f);
        });
    }

    // ------------------------------------------------------------------ the cover walls

    private void raiseWalls(Duel d) {
        int ox = d.slot * SPACING, y0 = RiteChamberBuilder.Y + 1;
        for (int[] c : RiteChamberBuilder.COVER) {
            for (int z = c[1]; z <= c[2]; z++)
                for (int y = y0; y < y0 + RiteChamberBuilder.COVER_HEIGHT; y++) world.getBlockAt(ox + c[0], y, z).setType(Material.BARRIER, false);
            int len = c[2] - c[1] + 1;
            d.wallSkins.add(world.spawn(new Location(world, ox + c[0], y0, c[1]), BlockDisplay.class, b -> {
                b.setBlock(Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
                b.setPersistent(false);
                b.setGlowing(true);
                b.setGlowColorOverride(LastRite.DEEP);
                b.setTransformation(new Transformation(new Vector3f(0, -RiteChamberBuilder.COVER_HEIGHT, 0), new AxisAngle4f(),
                        new Vector3f(1, RiteChamberBuilder.COVER_HEIGHT, len), new AxisAngle4f()));
            }));
        }
        // they rise out of the floor
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int i = 0; i < d.wallSkins.size(); i++) {
                BlockDisplay b = d.wallSkins.get(i);
                if (!b.isValid()) continue;
                int len = RiteChamberBuilder.COVER.get(i)[2] - RiteChamberBuilder.COVER.get(i)[1] + 1;
                b.setInterpolationDelay(0);
                b.setInterpolationDuration(8);
                b.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1, RiteChamberBuilder.COVER_HEIGHT, len), new AxisAngle4f()));
            }
        }, 2L);
    }

    /** The walls crack, sink into the floor in a burst of stone and souls, and the fight is on. */
    private void dropWalls(Duel d) {
        d.wallsDown = true;
        int ox = d.slot * SPACING, y0 = RiteChamberBuilder.Y + 1;
        for (int i = 0; i < d.wallSkins.size(); i++) {
            BlockDisplay b = d.wallSkins.get(i);
            int[] c = RiteChamberBuilder.COVER.get(i);
            int len = c[2] - c[1] + 1;
            if (b.isValid()) {
                b.setInterpolationDelay(0);
                b.setInterpolationDuration(10);
                b.setTransformation(new Transformation(new Vector3f(0.1f, -0.2f, 0.1f), new AxisAngle4f(), new Vector3f(0.8f, 0.01f, len - 0.2f), new AxisAngle4f()));
            }
            for (int z = c[1]; z <= c[2]; z++) {
                for (int y = y0; y < y0 + RiteChamberBuilder.COVER_HEIGHT; y++) {
                    Block bl = world.getBlockAt(ox + c[0], y, z);
                    if (bl.getType() == Material.BARRIER) bl.setType(Material.AIR, false);
                }
                Location p = new Location(world, ox + c[0] + 0.5, y0 + 1, z + 0.5);
                world.spawnParticle(Particle.BLOCK, p, 18, 0.3, 0.8, 0.3, 0.1, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
                world.spawnParticle(Particle.SOUL, p, 3, 0.2, 0.6, 0.2, 0.03);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> d.wallSkins.forEach(Entity::remove), 12L);
        for (Player p : players(d)) {
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.PLAYERS, 0.8f, 0.6f);
            p.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 0.6f, 0.6f);
            p.showTitle(Title.title(Ui.mm("<gradient:#7A0F2A:#FF2E55><bold>FIGHT</bold></gradient>"), Ui.mm(""),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(600), Duration.ofMillis(300))));
        }
    }

    private List<Player> players(Duel d) {
        List<Player> out = new ArrayList<>();
        if (d.caster.isOnline()) out.add(d.caster);
        if (d.foe instanceof Player p && p.isOnline()) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ the duel clock

    private void tick() {
        for (Duel d : duels) {
            if (d == null || d.over) continue;
            d.ticks++;
            // validity: someone vanished without an event (should not happen) -> undo
            if (!d.caster.isOnline() || d.caster.getWorld() != world || !d.foe.isValid() || d.foe.getWorld() != world) {
                if (!d.caster.isOnline()) continue;   // onQuit handles it
                finish(d, null, null, true);
                continue;
            }
            if (d.ticks <= WALLS_DROP_TICKS && d.ticks % 20 == 1) {
                int s = 3 - d.ticks / 20;
                for (Player p : players(d)) {
                    p.showTitle(Title.title(Ui.mm("<#B66CFF><bold>" + s), Ui.mm("<gray>the walls will fall"),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(800), Duration.ofMillis(150))));
                    p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 0.8f, 0.5f + (3 - s) * 0.15f);
                }
            }
            if (!d.wallsDown && d.ticks >= WALLS_DROP_TICKS) dropWalls(d);
            if (d.ticks % 10 == 0) { updateBar(d); ambience(d); }
            int left = duelTicks - d.ticks;
            if (left > 0 && left <= 200 && left % 20 == 0)
                for (Player p : players(d)) {
                    p.playSound(p.getLocation(), left <= 100 ? Sound.ENTITY_WARDEN_HEARTBEAT : Sound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.PLAYERS, 1f, left <= 100 ? 1f : 0.6f);
                    if (left <= 100) p.sendActionBar(Ui.mm("<red><bold>" + left / 20 + "</bold> <gray>- if no one falls, " + (timeout == Timeout.BOTH_DIE ? "<red>you both die" : "<red>you both leave broken")));
                }
            if (left <= 0) finish(d, null, null, false);
        }
    }

    private void updateBar(Duel d) {
        int left = Math.max(0, duelTicks - d.ticks), secs = (left + 19) / 20;
        d.bar.progress(Math.max(0f, Math.min(1f, left / (float) duelTicks)));
        d.bar.color(secs <= 10 ? BossBar.Color.RED : BossBar.Color.PURPLE);
        String time = secs / 60 + ":" + String.format(Locale.ROOT, "%02d", secs % 60);
        d.bar.name(Ui.mm("<#B66CFF>⚔ <bold>LAST RITE</bold> <dark_gray>· <white>" + d.caster.getName() + " <gray>vs <white>" + d.foeName() + " <dark_gray>· " + (secs <= 10 ? "<red>" : "<white>") + time));
        if (d.sign != null && d.sign.isValid())
            d.sign.text(Ui.mm("<#B66CFF>⚔ <white>" + d.caster.getName() + " <gray>vs <white>" + d.foeName() + "<newline><dark_gray>Last Rite · " + time));
        // a faint soul column where they vanished
        Location c = d.castSpot;
        if (c.getWorld() != null && c.isChunkLoaded())
            for (int i = 0; i < 6; i++) c.getWorld().spawnParticle(Particle.DUST, c.clone().add(0, 0.3 + i * 0.45, 0), 1, 0.08, 0.1, 0.08, 0, new Particle.DustOptions(LastRite.VIOLET, 1.0f));
    }

    private void ambience(Duel d) {
        int ox = d.slot * SPACING;
        // ash drifting down, and the rune line pulsing
        world.spawnParticle(Particle.WHITE_ASH, ox + RiteChamberBuilder.LEN / 2.0, RiteChamberBuilder.Y + 6, RiteChamberBuilder.WID / 2.0, 12, 10, 1, 4, 0);
        double pulse = (d.ticks / 10) % RiteChamberBuilder.WID;
        world.spawnParticle(Particle.DUST, ox + RiteChamberBuilder.LEN / 2 + 0.5, RiteChamberBuilder.Y + 1.05, pulse + 0.5, 3, 0.2, 0, 0.2, 0, new Particle.DustOptions(LastRite.VIOLET, 1.4f));
        world.spawnParticle(Particle.SCULK_SOUL, ox + RiteChamberBuilder.LEN / 2 + 0.5, RiteChamberBuilder.Y + 1.1, pulse + 0.5, 1, 0.1, 0.05, 0.1, 0.01);
    }

    // ------------------------------------------------------------------ hits and the lethal blow

    /** Hits between the two duellists always land (whatever spawn/lobby rules say); a lethal one ends the rite instead. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        UUID vid = e.getEntity().getUniqueId();
        if (executing.contains(vid)) { e.setCancelled(false); return; }                       // the real death back home
        if (graceUntil.getOrDefault(vid, 0L) > System.currentTimeMillis()) { e.setCancelled(true); return; }   // just came back
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        Duel d = duelOf(victim);
        if (d == null) {
            if (world != null && victim.getWorld() == world && victim instanceof Player) e.setCancelled(true);   // nobody else gets hurt in here
            return;
        }
        if (d.over) { e.setCancelled(true); return; }
        if (e instanceof EntityDamageByEntityEvent ee) {
            Entity src = ee.getDamager();
            if (src instanceof Projectile pr && pr.getShooter() instanceof ProjectileSource ps && ps instanceof Entity se) src = se;
            if (!src.equals(d.other(victim))) { e.setCancelled(true); return; }
            e.setCancelled(false);
        }
        if (e.isCancelled()) return;
        double hp = victim.getHealth() + victim.getAbsorptionAmount();
        if (e.getFinalDamage() < hp) return;
        if (allowTotems && victim instanceof Player p && (p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING)) return;   // the totem saves them, the fight goes on
        e.setCancelled(true);
        finish(d, d.other(victim), victim, false);
    }

    // ------------------------------------------------------------------ inside the chamber: nothing else works

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (isChamberWorld(e.getBlock().getWorld()) && !staffBuilding(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (isChamberWorld(e.getBlock().getWorld()) && !staffBuilding(e.getPlayer())) e.setCancelled(true); }

    private static boolean staffBuilding(Player p) { return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("alloy.admin"); }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!isChamberWorld(e.getFrom().getWorld()) || duelOf(e.getPlayer()) == null) return;
        String c = e.getCause().name();   // by name: the chorus-fruit cause was renamed across versions
        if (c.equals("ENDER_PEARL") || c.equals("CHORUS_FRUIT") || c.equals("CONSUMABLE_EFFECT") || c.equals("COMMAND")) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Ui.mm("<#B66CFF>✦ <gray>There's no way out of a Last Rite but through."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (duelOf(e.getPlayer()) == null || e.getPlayer().hasPermission("alloy.admin")) return;
        String cmd = e.getMessage().toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/msg ") || cmd.startsWith("/r ") || cmd.startsWith("/tell ") || cmd.startsWith("/w ")) return;
        e.setCancelled(true);
        e.getPlayer().sendActionBar(Ui.mm("<#B66CFF>✦ <gray>There's no way out of a Last Rite but through."));
    }

    /** BlackForgeMC ability items (Gale Bellows, Gaia, Darkness, Surge, the Rite itself...) don't work in here. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAbility(PlayerInteractEvent e) {
        if (!isChamberWorld(e.getPlayer().getWorld()) || e.getItem() == null || !e.getItem().hasItemMeta()) return;
        String ns = plugin.getName().toLowerCase(Locale.ROOT);
        for (NamespacedKey k : e.getItem().getItemMeta().getPersistentDataContainer().getKeys())
            if (k.getNamespace().equals(ns)) {
                e.setCancelled(true);
                e.getPlayer().sendActionBar(Ui.mm("<#B66CFF>✦ <gray>Abilities are silent in a Last Rite. Fight."));
                return;
            }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (duelOf(e.getPlayer()) != null) e.setCancelled(true); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Duel d = duelOf(e.getPlayer());
        if (d != null) finish(d, d.other(e.getPlayer()), e.getPlayer(), true);   // leaving is losing
    }

    /** A death in the chamber that got past onDamage (e.g. /kill): keep their items, count it as a loss, respawn them home. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        Duel d = duelOf(p);
        if (d == null) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        Saved home = p.equals(d.caster) ? d.casterSave : d.foeSave;
        pendingRespawn.put(p.getUniqueId(), home.loc());
        finish(d, d.other(p), p, true);   // they're already dead, so nothing more happens to them at home
    }

    private final Map<UUID, Location> pendingRespawn = new HashMap<>();

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Location home = pendingRespawn.remove(e.getPlayer().getUniqueId());
        if (home != null) e.setRespawnLocation(safe(home));
    }

    /** Back from a crash: put them back where they were, as they were. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(store);
        String k = p.getUniqueId().toString();
        if (!y.contains(k)) return;
        Location loc = y.getLocation(k + ".loc");
        double hp = y.getDouble(k + ".health", 20);
        int food = y.getInt(k + ".food", 20);
        y.set(k, null);
        try { y.save(store); } catch (Exception ignored) { }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || loc == null || loc.getWorld() == null) return;
            p.teleport(safe(loc));
            p.setHealth(Math.max(1, Math.min(maxHealth(p), hp)));
            p.setFoodLevel(food);
            p.sendMessage(Ui.mm("<#B66CFF>✦ <gray>The server restarted during your Last Rite. Nobody won; you're back where you were."));
        }, 5L);
    }

    // ------------------------------------------------------------------ the end

    /**
     * Ends a duel. winner/loser null = timeout (or undo when {@code undo}). Not immediate: the loser shatters into souls
     * and both see the verdict for 2 s before they're sent home.
     */
    private void finish(Duel d, LivingEntity winner, LivingEntity loser, boolean immediate) {
        if (d.over) return;
        d.over = true;
        for (Player p : players(d)) p.hideBossBar(d.bar);   // walls still up (ended early) are cleared quietly in sendHome
        boolean undo = immediate && winner == null && loser == null;
        if (undo) { sendHome(d, null, null, true); return; }

        if (loser != null) {
            shatter(loser);
            if (winner instanceof Player w) verdict(w, true, d);
            if (loser instanceof Player l) verdict(l, false, d);
        } else {   // timeout
            for (Player p : players(d)) {
                p.showTitle(Title.title(Ui.mm("<dark_gray><bold>TIME</bold>"), Ui.mm(timeout == Timeout.BOTH_DIE ? "<red>The rite takes you both." : "<gray>Neither of you walks out whole."),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1600), Duration.ofMillis(400))));
                p.playSound(p.getLocation(), Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.PLAYERS, 1f, 0.6f);
                if (timeout == Timeout.BOTH_DIE) shatter(p);
            }
        }
        if (immediate) sendHome(d, winner, loser, false);
        else Bukkit.getScheduler().runTaskLater(plugin, () -> sendHome(d, winner, loser, false), END_PAUSE_TICKS);
    }

    private void verdict(Player p, boolean won, Duel d) {
        Title.Times times = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1700), Duration.ofMillis(400));
        if (won) {
            p.showTitle(Title.title(Ui.mm("<gradient:#FFD27A:#FF9A1A><bold>YOU WALK OUT</bold></gradient>"), Ui.mm("<gray>The rite is answered."), times));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 0.8f);
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1f, 1.2f);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.5, 1, 0.5, 0.4);
        } else {
            p.showTitle(Title.title(Ui.mm("<gradient:#7A0F2A:#2B0010><bold>YOUR RITE IS READ</bold></gradient>"), Ui.mm(""), times));
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_DEATH, SoundCategory.PLAYERS, 0.5f, 0.8f);
        }
    }

    /** The loser comes apart: frozen, a heartbeat, then a burst of souls and ink as they fade. */
    private void shatter(LivingEntity e) {
        Location c = e.getLocation().add(0, 1, 0);
        e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, END_PAUSE_TICKS, 9, false, false, false));
        if (e instanceof Player p) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, END_PAUSE_TICKS, 0, false, false, false));
        world.playSound(c, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.5f, 0.7f);
        world.playSound(c, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.PLAYERS, 1f, 0.5f);
        world.spawnParticle(Particle.SOUL, c, 70, 0.4, 0.9, 0.4, 0.06);
        world.spawnParticle(Particle.SCULK_SOUL, c, 40, 0.4, 0.9, 0.4, 0.05);
        world.spawnParticle(Particle.DUST, c, 90, 0.5, 1, 0.5, 0, new Particle.DustOptions(LastRite.INK, 2.0f));
        world.spawnParticle(Particle.DUST, c, 40, 0.5, 1, 0.5, 0, new Particle.DustOptions(LastRite.BLOOD, 1.6f));
        for (int i = 1; i <= 3; i++)   // the souls keep rising for a moment
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (e.isValid()) world.spawnParticle(Particle.SOUL, e.getLocation().add(0, 1.5, 0), 15, 0.3, 0.6, 0.3, 0.08); }, i * 8L);
    }

    private void sendHome(Duel d, LivingEntity winner, LivingEntity loser, boolean undo) {
        duels[d.slot] = null;
        unpersist(d);
        d.wallSkins.forEach(Entity::remove);
        clearBarriers(d.slot);
        if (d.practice && d.foe.isValid()) d.foe.remove();
        for (Entity e : world.getNearbyEntities(at(d.slot, RiteChamberBuilder.LEN / 2.0, RiteChamberBuilder.Y + 3, RiteChamberBuilder.WID / 2.0, 0), 20, 10, 10))
            if (e instanceof Item || e instanceof AbstractArrow) e.remove();   // nothing left lying about
        boolean timeout = winner == null && loser == null && !undo;
        String result;
        if (undo) result = null;
        else if (timeout) result = "<gray>Nobody walked out of the Last Rite between <white>" + d.caster.getName() + " <gray>and <white>" + d.foeName() + "<gray>.";
        else result = "<#B66CFF>⚔ <white>" + name(winner) + " <gray>survived the Last Rite against <white>" + name(loser) + "<gray>.";

        returnPlayer(d, d.caster, d.casterSave, winner, loser, undo, timeout);
        if (d.foe instanceof Player t) returnPlayer(d, t, d.foeSave, winner, loser, undo, timeout);

        if (d.sign != null && d.sign.isValid()) {
            if (result == null) d.sign.remove();
            else {
                d.sign.text(Ui.mm(timeout ? "<gray>The rite took no one... or everyone" : "<#B66CFF>⚔ <white>" + name(winner) + " <gray>survived the rite"));
                TextDisplay s = d.sign;
                Bukkit.getScheduler().runTaskLater(plugin, s::remove, 100L);
            }
        }
        if (result != null && d.castSpot.getWorld() != null) {
            Set<Player> tell = new HashSet<>(d.castSpot.getWorld().getPlayers().stream().filter(p -> p.getLocation().distanceSquared(d.castSpot) < 80 * 80).toList());
            if (d.caster.isOnline()) tell.add(d.caster);
            if (d.foe instanceof Player t && t.isOnline()) tell.add(t);
            Component msg = Ui.mm(result);
            tell.forEach(p -> p.sendMessage(msg));
        }
        if (!undo && !timeout && winner instanceof Player w && d.foe.equals(w) && !d.practice)
            immuneUntil.put(w.getUniqueId(), System.currentTimeMillis() + IMMUNE_MS);   // the target survived: no instant re-rite
    }

    private static String name(LivingEntity e) { return e instanceof Player p ? p.getName() : "the Rite Dummy"; }

    private void returnPlayer(Duel d, Player p, Saved s, LivingEntity winner, LivingEntity loser, boolean undo, boolean timeout) {
        if (s == null) return;
        boolean online = p.isOnline();
        Location home = safe(s.loc());
        if (online && !p.isDead()) {
            p.teleport(home);
            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            p.addPotionEffects(s.effects());
            p.setHealth(Math.max(0.5, Math.min(maxHealth(p), s.health())));
            p.setAbsorptionAmount(s.absorption());
            p.setFoodLevel(s.food());
            p.setSaturation(s.saturation());
            p.setFireTicks(s.fire());
            p.setFallDistance(0);
        } else if (!online) {
            p.teleport(home);   // still possible during the quit event: their items drop at home, not in the void
        }
        if (undo) return;
        boolean dies = p.equals(loser) || (timeout && this.timeout == Timeout.BOTH_DIE);
        if (dies) {
            home.getWorld().strikeLightningEffect(home);
            execute(p, winner instanceof Player w && !w.equals(p) ? w : null);
        } else if (timeout) {
            p.setHealth(Math.min(p.getHealth(), 4));   // both-weakened: two hearts
            p.sendMessage(Ui.mm("<#B66CFF>✦ <gray>You leave the rite broken: <red>2 hearts<gray>."));
        } else {
            graceUntil.put(p.getUniqueId(), System.currentTimeMillis() + GRACE_TICKS * 50L);
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, GRACE_TICKS, 0, false, false, false));
            home.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, home.clone().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
            home.getWorld().playSound(home, Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.5f, 1.4f);
        }
    }

    /** The real death, back home: a kill-type hit from the winner (kill credit, kill feed), whatever the local rules say. */
    private void execute(Player p, Player killer) {
        if (p.isDead()) return;
        DamageSource.Builder b = DamageSource.builder(DamageType.GENERIC_KILL);
        if (killer != null) b = b.withCausingEntity(killer).withDirectEntity(killer);
        executing.add(p.getUniqueId());
        try { p.damage(1_000_000, b.build()); }
        finally { executing.remove(p.getUniqueId()); }
        if (!p.isDead() && p.getHealth() > 0) p.setHealth(0);
    }

    /** A spot to stand: the saved one if it's still clear and has ground, else the nearest clear spot above, else spawn. */
    private static Location safe(Location l) {
        World w = l.getWorld();
        if (w == null) return Bukkit.getWorlds().getFirst().getSpawnLocation();
        for (int dy = 0; dy <= 12; dy++) {
            Location c = l.clone().add(0, dy, 0);
            Block feet = c.getBlock(), head = feet.getRelative(0, 1, 0), below = feet.getRelative(0, -1, 0);
            boolean clear = !feet.getType().isSolid() && !head.getType().isSolid() && feet.getType() != Material.LAVA && head.getType() != Material.LAVA;
            if (clear && (dy == 0 || below.getType().isSolid()) && below.getType() != Material.LAVA) return c;
        }
        return w.getSpawnLocation();
    }

    private void clearBarriers(int slot) {
        int ox = slot * SPACING, y0 = RiteChamberBuilder.Y + 1;
        for (int[] c : RiteChamberBuilder.COVER)
            for (int z = c[1]; z <= c[2]; z++)
                for (int y = y0; y < y0 + RiteChamberBuilder.COVER_HEIGHT; y++) {
                    Block b = world.getBlockAt(ox + c[0], y, z);
                    if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
                }
    }

    // ------------------------------------------------------------------ crash safety

    private void persist() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(store);
        for (Duel d : duels) {
            if (d == null) continue;
            write(y, d.caster, d.casterSave);
            if (d.foe instanceof Player t) write(y, t, d.foeSave);
        }
        try { y.save(store); } catch (Exception ex) { plugin.getLogger().warning("Could not save rites.yml: " + ex.getMessage()); }
    }

    private static void write(YamlConfiguration y, Player p, Saved s) {
        String k = p.getUniqueId().toString();
        y.set(k + ".loc", s.loc());
        y.set(k + ".health", s.health());
        y.set(k + ".food", s.food());
    }

    private void unpersist(Duel d) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(store);
        y.set(d.caster.getUniqueId().toString(), null);
        if (d.foe instanceof Player t) y.set(t.getUniqueId().toString(), null);
        try { y.save(store); } catch (Exception ignored) { }
    }

    /** Plugin stopping: every rite is undone, both sides go home as they were. */
    public void shutdown() {
        for (Duel d : duels) {
            if (d == null) continue;
            d.over = true;
            for (Player p : players(d)) p.hideBossBar(d.bar);
            sendHome(d, null, null, true);
        }
        if (world != null) for (Entity e : world.getEntities()) if (e.getPersistentDataContainer().has(dummyKey, PersistentDataType.BYTE)) e.remove();
    }
}
