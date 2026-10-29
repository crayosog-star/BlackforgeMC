package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/**
 * Ashen Bonfire: a BlackForgeMC ability item with a dark, ashen look. Right-click the ground and a sword falls from the
 * sky and plunges into a pile of ash, bones and a skull, kindling a fire. For {@code duration-seconds} it heals
 * everyone standing in its {@code radius}-block ring (yourself, and anyone else - a bonfire is neutral ground) by
 * {@code heal-hearts-per-second}. Anyone can snuff it out early with {@code hits} hits. The ring rules live in {@link HealAura}.
 *
 * Only where {@code allowedAt} says (the hub Arena for now). Never used up; one bonfire per player at a time.
 */
public final class AshenBonfire implements Listener, Hackable {
    public static final String NAME = "<gradient:#FFB347:#C2541B:#6E0F0F><bold>Ashen Bonfire</bold></gradient>";
    private static final int DROP_TICKS = 7;                 // the sword falls out of the sky
    private static final float SWORD_REST_Y = 0.95f, SWORD_START_Y = 7f;

    static final Color EMBER = Color.fromRGB(0xE0702A), GOLD = Color.fromRGB(0xFFC56B), ASH = Color.fromRGB(0x4A4542), FLAME = Color.fromRGB(0xFFB347);

    private final AlloyCore plugin;
    private final boolean enabled;
    private final int cooldownTicks, durationTicks, hits;
    private final double radius, healPerSecond;
    private final Predicate<Location> allowedAt;
    private final NamespacedKey tagKey;
    private final Map<UUID, Fire> fires = new HashMap<>();   // by owner
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private final Random rnd = new Random();

    public AshenBonfire(AlloyCore plugin, Predicate<Location> allowedAt) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        var c = plugin.getConfig();
        this.enabled = c.getBoolean("ashen-bonfire.enabled", true);
        this.cooldownTicks = Math.max(0, (int) Math.round(c.getDouble("ashen-bonfire.cooldown-seconds", 20) * 20));
        this.durationTicks = Math.max(20, (int) Math.round(c.getDouble("ashen-bonfire.duration-seconds", 10) * 20));
        this.radius = Math.max(1, c.getDouble("ashen-bonfire.radius", 4));
        this.healPerSecond = Math.max(0, c.getDouble("ashen-bonfire.heal-hearts-per-second", 1)) * 2;   // hearts -> health
        this.hits = Math.max(1, c.getInt("ashen-bonfire.hits", 5));
        this.tagKey = new NamespacedKey(plugin, "ashen_bonfire");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------------ the item

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.CAMPFIRE);
        it.editMeta(m -> {
            m.displayName(Ui.mm(NAME));
            m.lore(List.of(
                    Ui.mm("<dark_gray><italic>Ash, bone, and a blade that will not cool."),
                    Ui.mm("<dark_gray><italic>Rest here, if you dare."),
                    Ui.mm(""),
                    Ui.mm("<gray>Right-click the ground to kindle a bonfire"),
                    Ui.mm("<gray>Heals everyone within <white>" + fmt(radius) + " blocks <gray>by <white>" + fmt(healPerSecond / 2) + "❤/s"),
                    Ui.mm("<gray>Burns for <white>" + fmt(durationTicks / 20.0) + "s <gray>· snuffed out after <white>" + hits + " hits"),
                    Ui.mm("<gray>Heals enemies too: <white>guard it"),
                    Ui.mm(cooldownTicks > 0 ? "<gray>Cooldown: <white>" + fmt(cooldownTicks / 20.0) + "s" : "<gray>Cooldown: <white>none"),
                    Ui.mm(""),
                    Ui.mm("<dark_gray>BlackForgeMC ability")));
            m.setMaxStackSize(1);
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    public boolean isBonfire(ItemStack it) {
        return it != null && it.getType() == Material.CAMPFIRE && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d); }

    // ------------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isBonfire(p.getInventory().getItemInMainHand())) return;
        Block clicked = e.getClickedBlock();
        if (a == Action.RIGHT_CLICK_BLOCK && clicked != null && clicked.getType().isInteractable() && !p.isSneaking()) return;
        e.setCancelled(true);   // never place a real campfire
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>The Ashen Bonfire isn't switched on yet.")); return; }
        if (!allowedAt.test(p.getLocation())) { p.sendActionBar(Ui.mm("<gray>The " + NAME + " <gray>only works in the <white>Arena <gray>for now.")); return; }
        long now = System.currentTimeMillis(), ready = readyAt.getOrDefault(p.getUniqueId(), 0L);
        if (ready > now) {
            p.sendActionBar(Ui.mm("<#FFB347>🔥 <gray>The ashes are still cold... <white>" + String.format(Locale.ROOT, "%.1f", (ready - now) / 1000.0) + "s"));
            return;
        }
        // where: on top of the clicked block, or at your feet when you click the air
        Block at = a == Action.RIGHT_CLICK_BLOCK && clicked != null && e.getBlockFace() == BlockFace.UP ? clicked.getRelative(BlockFace.UP)
                : a == Action.RIGHT_CLICK_AIR && p.isOnGround() ? p.getLocation().getBlock() : null;
        if (at == null || !fits(at)) {
            p.sendActionBar(Ui.mm("<#FFB347>🔥 <gray>Kindle it on <white>open, flat ground <gray>(look down and right-click)."));
            return;
        }
        Fire old = fires.remove(p.getUniqueId());
        if (old != null) old.end(false);
        if (cooldownTicks > 0) {
            readyAt.put(p.getUniqueId(), now + cooldownTicks * 50L);
            p.setCooldown(Material.CAMPFIRE, cooldownTicks);
        }
        p.swingMainHand();
        Fire f = new Fire(p, at.getLocation().add(0.5, 0, 0.5));
        fires.put(p.getUniqueId(), f);
        f.start();
    }

    /** Two blocks of open space on a solid floor. */
    private static boolean fits(Block b) {
        return b.isPassable() && !b.isLiquid() && b.getRelative(BlockFace.UP).isPassable() && b.getRelative(BlockFace.DOWN).getType().isSolid();
    }

    /** Backup: a bonfire item is never placed as a block, whatever cancelled the click first. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (isBonfire(e.getItemInHand())) e.setCancelled(true); }

    /** EMP: any bonfire whose ring the wave touches is snuffed out (not the EMP caster's own). */
    @Override
    public int hack(Location center, double r, Player caster) {
        int n = 0;
        for (Fire f : new ArrayList<>(fires.values())) {
            if (f.owner.equals(caster.getUniqueId()) || !EmpWave.reaches(center, f.base, r, radius)) continue;
            f.end(false);
            Player owner = Bukkit.getPlayer(f.owner);
            if (owner != null) owner.sendActionBar(Ui.mm("<#B6FF3B>⚡ <gray>Your " + NAME + " <gray>was <white>shut down <gray>by an EMP"));
            n++;
        }
        return n;
    }

    /** Plugin disable: put every fire out. */
    public void shutdown() {
        for (Fire f : new ArrayList<>(fires.values())) f.remove();
        fires.clear();
    }

    // ------------------------------------------------------------------ display helpers

    private static Transformation tf(float x, float y, float z, Quaternionf rot, float sx, float sy, float sz) {
        return new Transformation(new Vector3f(x, y, z), rot, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /** The sword, blade down (the item sprite points up and to the right, so turn it 135 degrees), leaning a little. */
    private static Transformation sword(float y, float scale) {
        Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(25)).rotateX((float) Math.toRadians(8)).rotateZ((float) Math.toRadians(-135));
        return tf(0, y, 0, rot, scale, scale, scale);
    }

    private static void move(Display d, Transformation t, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(t);
    }

    // ------------------------------------------------------------------ one bonfire

    private final class Fire {
        final UUID owner;
        final String ownerName;
        final Location base;
        final World world;
        final List<Display> parts = new ArrayList<>();
        BlockDisplay campfire;
        ItemDisplay sword;
        TextDisplay label;
        Interaction hitbox;
        BukkitTask task;
        int t, hitsLeft = hits;
        long lastHit;
        boolean lit;

        Fire(Player p, Location base) {
            this.owner = p.getUniqueId();
            this.ownerName = p.getName();
            this.base = base;
            this.world = base.getWorld();
        }

        private <T extends Display> T part(T d) {
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setShadowRadius(0);
            d.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
            parts.add(d);
            return d;
        }

        private BlockDisplay block(BlockData data, Transformation tr) {
            return part(world.spawn(base, BlockDisplay.class, d -> { d.setBlock(data); d.setTransformation(tr); }));
        }

        private ItemDisplay item(Material m, Transformation tr) {
            return part(world.spawn(base, ItemDisplay.class, d -> { d.setItemStack(new ItemStack(m)); d.setTransformation(tr); }));
        }

        void start() {
            Quaternionf none = new Quaternionf();
            // the pile: grey ash, scattered bones and a skull; the fire starts unlit until the sword lands
            block(Material.LIGHT_GRAY_CONCRETE_POWDER.createBlockData(), tf(-0.7f, 0, -0.7f, none, 1.4f, 0.1f, 1.4f));
            block(Material.GRAY_CONCRETE_POWDER.createBlockData(), tf(-0.45f, 0.05f, -0.45f, none, 0.9f, 0.1f, 0.9f));
            item(Material.BONE, tf(0.55f, 0.12f, 0.25f, new Quaternionf().rotateY(0.6f).rotateX((float) Math.toRadians(-90)), 0.55f, 0.55f, 0.55f));
            item(Material.BONE, tf(-0.5f, 0.12f, 0.45f, new Quaternionf().rotateY(-1.1f).rotateX((float) Math.toRadians(-90)), 0.5f, 0.5f, 0.5f));
            item(Material.SKELETON_SKULL, tf(-0.55f, 0.28f, -0.5f, new Quaternionf().rotateY(0.7f), 0.4f, 0.4f, 0.4f));
            BlockData unlit = Material.CAMPFIRE.createBlockData();
            if (unlit instanceof Lightable l) l.setLit(false);
            campfire = block(unlit, tf(-0.4f, 0.08f, -0.4f, none, 0.8f, 0.8f, 0.8f));
            sword = item(Material.IRON_SWORD, sword(SWORD_START_Y, 1.3f));
            label = part(world.spawn(base, TextDisplay.class, d -> {
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setBackgroundColor(Color.fromARGB(150, 20, 12, 8));
                d.setTransformation(tf(0, 2.2f, 0, new Quaternionf(), 0.9f, 0.9f, 0.9f));
            }));
            hitbox = world.spawn(base, Interaction.class, i -> {
                i.setInteractionWidth(1.3f);
                i.setInteractionHeight(1.6f);
                i.setResponsive(true);
                i.setPersistent(false);
                i.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
            });

            // the sword falls out of the sky with a rising shriek
            Bukkit.getScheduler().runTaskLater(plugin, () -> move(sword, sword(SWORD_REST_Y, 1.3f), DROP_TICKS), 1L);
            world.playSound(base, Sound.ITEM_TRIDENT_THROW, SoundCategory.PLAYERS, 1.0f, 0.5f);
            world.playSound(base, Sound.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 1.0f, 0.6f);
            Bukkit.getScheduler().runTaskLater(plugin, this::kindle, DROP_TICKS + 1L);
            label();
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }

        /** The sword hits the ash: the fire roars up, embers burst out and the ring flares. */
        private void kindle() {
            if (campfire == null || !campfire.isValid()) return;
            lit = true;
            campfire.setBlock(Material.CAMPFIRE.createBlockData());   // lit by default
            Location c = base.clone().add(0, 0.5, 0);
            world.spawnParticle(Particle.FLAME, c, 40, 0.2, 0.2, 0.2, 0.15);
            world.spawnParticle(Particle.LAVA, c, 10, 0.3, 0.2, 0.3, 0);
            world.spawnParticle(Particle.WHITE_ASH, c, 60, 0.8, 0.5, 0.8, 0.05);
            world.spawnParticle(Particle.LARGE_SMOKE, c, 8, 0.3, 0.3, 0.3, 0.03);
            for (int i = 0; i < 48; i++) {
                double a = i * 2 * Math.PI / 48;
                world.spawnParticle(Particle.FLAME, base.getX() + Math.cos(a) * 0.6, base.getY() + 0.15, base.getZ() + Math.sin(a) * 0.6,
                        0, Math.cos(a), 0.02, Math.sin(a), 0.25);   // a ring of fire racing out to the edge
            }
            world.playSound(c, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.6f, 0.7f);
            world.playSound(c, Sound.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 1.2f, 0.6f);
            world.playSound(c, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.0f, 0.7f);
            world.playSound(c, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.6f, 0.5f);
            Player p = Bukkit.getPlayer(owner);
            if (p != null) p.showTitle(Title.title(Ui.mm("<gradient:#FFC56B:#E0702A:#8A1A0A><bold>BONFIRE KINDLED</bold></gradient>"), Ui.mm(""),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1400), Duration.ofMillis(700))));
        }

        int left() { return durationTicks - t; }

        void label() {
            if (label == null || !label.isValid()) return;
            label.text(Ui.mm("<gradient:#FFC56B:#E0702A><bold>" + ownerName + "'s Bonfire</bold></gradient><newline>"
                    + HealAura.hitBar(hitsLeft, hits) + " <gray>" + String.format(Locale.ROOT, "%.0f", Math.ceil(Math.max(0, left()) / 20.0)) + "s"));
        }

        void tick() {
            t++;
            if (left() <= 0) { end(true); return; }
            checkHit();
            if (!lit) return;

            double bx = base.getX(), by = base.getY(), bz = base.getZ();
            // embers and ash rising off the fire, a curl of smoke
            if (t % 2 == 0) {
                world.spawnParticle(Particle.SMALL_FLAME, bx, by + 0.6, bz, 2, 0.15, 0.1, 0.15, 0.01);
                world.spawnParticle(Particle.WHITE_ASH, bx, by + 1.2, bz, 4, 0.4, 0.6, 0.4, 0.01);
                world.spawnParticle(Particle.FLAME, bx, by + 0.4, bz, 0, (rnd.nextDouble() - 0.5) * 0.1, 1, (rnd.nextDouble() - 0.5) * 0.1, 0.06);
            }
            if (t % 9 == 0) world.spawnParticle(Particle.LAVA, bx, by + 0.6, bz, 1, 0.1, 0.1, 0.1, 0);
            if (t % 25 == 0) world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, bx, by + 0.9, bz, 1, 0.05, 0.05, 0.05, 0.01);
            // the ring: ember dust slowly turning round the edge, so you can see where it heals
            if (t % 3 == 0) {
                double spin = t * 0.04;
                int n = (int) Math.max(24, radius * 10);
                for (int i = 0; i < n; i += 2) {
                    double a = spin + i * 2 * Math.PI / n;
                    world.spawnParticle(Particle.DUST, bx + Math.cos(a) * radius, by + 0.12, bz + Math.sin(a) * radius, 1, 0, 0, 0, 0,
                            new Particle.DustOptions(i % 4 == 0 ? FLAME : EMBER, 1.1f));
                }
            }
            if (t % 30 == 0) world.playSound(base, Sound.BLOCK_LAVA_POP, SoundCategory.PLAYERS, 0.6f, 0.6f + rnd.nextFloat() * 0.3f);

            if (t % 20 == 0) { pulse(); label(); }
        }

        /** Once a second: everyone inside the ring gets health back, with warm motes spiralling up them. */
        private void pulse() {
            for (Player q : world.getPlayers()) {
                if (q.isDead() || q.getGameMode() == GameMode.SPECTATOR) continue;
                Location l = q.getLocation();
                if (!HealAura.inRange(l.getX() - base.getX(), l.getY() - base.getY(), l.getZ() - base.getZ(), radius)) continue;
                AttributeInstance max = q.getAttribute(Attribute.MAX_HEALTH);
                double add = HealAura.heal(q.getHealth(), max == null ? 20 : max.getValue(), healPerSecond);
                if (add > 0) q.setHealth(q.getHealth() + add);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4 + t * 0.2, h = i * 0.25;
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, l.getX() + Math.cos(a) * 0.5, l.getY() + h, l.getZ() + Math.sin(a) * 0.5,
                            1, 0, 0, 0, 0, new Particle.DustTransition(EMBER, GOLD, 1.0f));
                }
                if (add > 0) {
                    world.spawnParticle(Particle.HEART, l.clone().add(0, 2.1, 0), 1, 0.2, 0.1, 0.2, 0);
                    q.playSound(q, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.5f, 0.6f);
                }
                q.sendActionBar(Ui.mm("<#FFB347>🔥 <gray>Resting at <white>" + (q.getUniqueId().equals(owner) ? "your" : ownerName + "'s") + " <gray>bonfire"
                        + (add > 0 ? " <green>+" + fmt(add / 2) + "❤" : "")));
            }
        }

        /** Anyone attacking the bonfire chips a flame off it; the last hit snuffs it out. */
        private void checkHit() {
            if (hitbox == null || !hitbox.isValid()) return;
            Interaction.PreviousInteraction last = hitbox.getLastAttack();
            if (last == null || last.getTimestamp() <= lastHit) return;
            lastHit = last.getTimestamp();
            hitsLeft--;
            Location c = base.clone().add(0, 0.6, 0);
            world.spawnParticle(Particle.BLOCK, c, 12, 0.3, 0.2, 0.3, 0.1, Material.CAMPFIRE.createBlockData());
            world.spawnParticle(Particle.WHITE_ASH, c, 20, 0.4, 0.3, 0.4, 0.05);
            world.spawnParticle(Particle.FLAME, c, 6, 0.2, 0.2, 0.2, 0.05);
            world.playSound(c, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.6f, 1.2f);
            world.playSound(c, Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.8f, 0.8f);
            // the sword rattles in the ash
            move(sword, sword(SWORD_REST_Y + 0.08f, 1.38f), 2);
            Bukkit.getScheduler().runTaskLater(plugin, () -> move(sword, sword(SWORD_REST_Y, 1.3f), 3), 3L);
            label();
            if (hitsLeft <= 0) {
                String by = last.getPlayer().getName();
                Player p = Bukkit.getPlayer(owner);
                if (p != null && !p.getUniqueId().equals(last.getPlayer().getUniqueId()))
                    p.sendActionBar(Ui.mm("<#FFB347>🔥 <gray>Your bonfire was snuffed out by <white>" + (by == null ? "someone" : by)));
                end(false);
            }
        }

        /**
         * {@code burnedOut}: time's up - the fire gutters out, the sword sinks into the ash and it all crumbles.
         * Otherwise (snuffed out or replaced): a burst of ash and it's gone.
         */
        void end(boolean burnedOut) {
            if (task != null) task.cancel();
            fires.remove(owner, this);
            if (hitbox != null) hitbox.remove();
            Location c = base.clone().add(0, 0.5, 0);
            world.spawnParticle(Particle.WHITE_ASH, c, 50, 0.7, 0.4, 0.7, 0.04);
            world.spawnParticle(Particle.LARGE_SMOKE, c, 10, 0.3, 0.3, 0.3, 0.03);
            world.playSound(c, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 1.0f, 0.6f);
            if (!burnedOut) {
                world.spawnParticle(Particle.BLOCK, c, 30, 0.4, 0.3, 0.4, 0.15, Material.CAMPFIRE.createBlockData());
                world.playSound(c, Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.8f, 0.6f);
                remove();
                return;
            }
            BlockData out = Material.CAMPFIRE.createBlockData();
            if (out instanceof Lightable l) l.setLit(false);
            if (campfire != null && campfire.isValid()) campfire.setBlock(out);
            move(sword, sword(0.2f, 1.3f), 16);
            world.playSound(c, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.6f, 0.5f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                world.spawnParticle(Particle.WHITE_ASH, c, 30, 0.6, 0.2, 0.6, 0.02);
                remove();
            }, 18L);
        }

        void remove() {
            if (task != null) task.cancel();
            if (hitbox != null) hitbox.remove();
            for (Display d : parts) d.remove();
        }
    }
}
