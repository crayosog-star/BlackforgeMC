package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.blackforge.core.Ui;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Night Thieves (Survival). While a player is outside their own base at night, every minute there is a 5% chance a gang
 * of thieves comes for them. A lone player gets one thief wearing a copy of their own gear, 1.5x stronger. A group within
 * {@code party-radius} rolls once together and gets more thieves, and tougher ones, the more of them there are and the
 * better their gear. About a third of thieves are mages who throw Hex Bolts instead of fighting up close.
 *
 * <p>A thief that lands a hit may steal: half the time he rips the weapon from your hands and turns it on you, otherwise
 * he snatches your most valuable item and runs (shown floating over his head). Kill him before he gets away to get it
 * back. Wiping out the gang pays coins and a small chance at a crate key.
 *
 * <p>Looks: each thief is a Mannequin wearing the skin and a copy of the gear of the player he came for (a mage wears
 * the robed {@code blackforge:entity/thief_mage} skin from the pack). The real mob, a zombie or an illusioner, walks
 * and fights hidden and silent underneath it: the Mannequin follows it every tick, swings when it hits, and passes
 * any damage it takes on to the mob, so armour, knockback and kill credit all work as normal.
 *
 * <p>"Your base" is a claim you're trusted on. Thieves never spawn inside any claim or in the protected areas the
 * module passes in. Thieves are not persistent: a restart or a chunk unload can't leave one behind, and a shutdown hands
 * stolen items back. Staff: {@code /blackforge thieves spawn <player> | clear | status}.
 */
final class ThiefAmbush implements Listener {
    private static final int GIVE_UP_AWAY_TICKS = 200, PARTY_FOLLOW = 48;
    private static final Color HEX = Color.fromRGB(150, 60, 230), HEX_DARK = Color.fromRGB(60, 15, 110), ASH = Color.fromRGB(70, 70, 70);

    private final BlackForgeCore plugin;
    private final BiPredicate<Player, Location> outsideBase;   // the player is in a place thieves can come for them
    private final Predicate<Location> spawnable;                // a thief may appear here
    private final Crates crates;
    private final Random rnd = new Random();
    private final NamespacedKey powerKey, fleeKey;
    private Economy eco;

    private final List<Gang> gangs = new ArrayList<>();
    private final Map<UUID, Thief> thieves = new HashMap<>();   // by entity id
    private final Map<UUID, Gang> gangOf = new HashMap<>();     // by player id
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Thief> viewOf = new HashMap<>();     // by the Mannequin's id
    private final Set<UUID> dyingViews = new HashSet<>();        // Mannequins playing their death fall

    // config (read once; /blackforge reload restarts the server module anyway)
    private final boolean enabled;
    private final double chance, soloMult, perPlayer, perGear, maxMult, thievesPerGear, stealChance, weaponTurnChance;
    private final double baseSpeed, fleeBoost, hexDamage, rewardCoins, keyChance, rareKeyChance;
    private final int rollSeconds, cooldownMinutes, minPlaytimeMinutes, partyRadius, maxThieves, mageEvery;
    private final int escapeSeconds, escapeDistance, giveUpSeconds, castGapMin, castGapMax;
    private final String keyCrate, rareKeyCrate;

    ThiefAmbush(BlackForgeCore plugin, Crates crates, BiPredicate<Player, Location> outsideBase, Predicate<Location> spawnable) {
        this.plugin = plugin;
        this.crates = crates;
        this.outsideBase = outsideBase;
        this.spawnable = spawnable;
        powerKey = new NamespacedKey(plugin, "thief_power");
        fleeKey = new NamespacedKey(plugin, "thief_flee");
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("thief-ambush");
        if (c == null) c = plugin.getConfig().createSection("thief-ambush");
        enabled = c.getBoolean("enabled", true);
        rollSeconds = Math.max(5, c.getInt("roll-seconds", 60));
        chance = c.getDouble("chance", 0.05);
        cooldownMinutes = c.getInt("cooldown-minutes", 10);
        minPlaytimeMinutes = c.getInt("min-playtime-minutes", 60);
        partyRadius = c.getInt("party-radius", 24);
        maxThieves = Math.max(1, c.getInt("max-thieves", 8));
        thievesPerGear = c.getDouble("thieves-per-gear", 12);
        soloMult = c.getDouble("solo-multiplier", 1.5);
        perPlayer = c.getDouble("per-extra-player", 0.08);
        perGear = c.getDouble("per-gear-point", 0.015);
        maxMult = c.getDouble("max-multiplier", 2.5);
        mageEvery = Math.max(1, c.getInt("mage-every", 3));
        stealChance = c.getDouble("steal-chance", 0.25);
        weaponTurnChance = c.getDouble("weapon-turn-chance", 0.5);
        baseSpeed = c.getDouble("speed", 0.3);
        fleeBoost = c.getDouble("flee-speed-boost", 0.05);
        escapeSeconds = c.getInt("escape-seconds", 30);
        escapeDistance = c.getInt("escape-distance", 40);
        giveUpSeconds = c.getInt("give-up-seconds", 180);
        hexDamage = c.getDouble("hex-damage", 4.0);
        castGapMin = c.getInt("cast-gap-min-ticks", 60);
        castGapMax = Math.max(castGapMin, c.getInt("cast-gap-max-ticks", 90));
        rewardCoins = c.getDouble("reward-coins", 150);
        keyChance = c.getDouble("key-chance", 0.04);
        rareKeyChance = c.getDouble("rare-key-chance", 0.01);
        keyCrate = c.getString("key-crate", "iron");
        rareKeyCrate = c.getString("rare-key-crate", "gold");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::roll, rollSeconds * 20L, rollSeconds * 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void setEconomy(Economy eco) { this.eco = eco; }

    // ------------------------------------------------------------------ state

    private final class Thief {
        final Mob mob;             // hidden, silent: the AI, hitbox for mobs, armour and the real health
        Mannequin view;            // what players see and hit
        final boolean mage;
        final Gang gang;
        UUID mark;                 // the player this thief is after
        ItemStack loot;            // what he stole (null until he steals)
        UUID lootOwner;
        boolean turned;            // took your weapon and is fighting you with it
        int growTick, shrinkTick = -1, fleeTick = -1, castIn;
        boolean escaping;          // shrinking away with whatever he holds
        ItemDisplay lootShow;
        Bolt bolt;
        Thief(Mob mob, boolean mage, Gang gang, UUID mark) { this.mob = mob; this.mage = mage; this.gang = gang; this.mark = mark; }
        boolean fleeing() { return fleeTick >= 0; }
    }

    private final class Gang {
        final List<UUID> players;
        final List<Thief> members = new ArrayList<>();
        final double mult;
        final int size;
        final Map<UUID, Location> lastPos = new HashMap<>(), velocity = new HashMap<>();
        int age, away, killed;
        boolean over;
        Gang(List<UUID> players, double mult, int size) { this.players = new ArrayList<>(players); this.mult = mult; this.size = size; }
    }

    private final class Bolt {
        final Thief caster;
        final Player target;
        final ItemDisplay show;
        Location pos;
        Vector dir;
        int tick, flying;
        double traveled;
        Bolt(Thief caster, Player target, ItemDisplay show, Location pos) { this.caster = caster; this.target = target; this.show = show; this.pos = pos; }
    }

    // ------------------------------------------------------------------ the roll

    private boolean canBeAmbushed(Player p, boolean ignoreNight) {
        if (p.isDead() || !p.isValid()) return false;
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return false;
        if (p.hasMetadata("vanished") || gangOf.containsKey(p.getUniqueId())) return false;
        if (!ignoreNight) {
            if (!ThiefGang.isNight(p.getWorld().getTime())) return false;
            Long cd = cooldownUntil.get(p.getUniqueId());
            if (cd != null && cd > System.currentTimeMillis()) return false;
            if (p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60 < minPlaytimeMinutes) return false;
        }
        return outsideBase.test(p, p.getLocation());
    }

    private List<Player> party(Player p, boolean ignoreNight) {
        List<Player> out = new ArrayList<>();
        out.add(p);
        double r2 = (double) partyRadius * partyRadius;
        for (Player o : p.getWorld().getPlayers())
            if (o != p && o.getLocation().distanceSquared(p.getLocation()) <= r2 && canBeAmbushed(o, ignoreNight)) out.add(o);
        return out;
    }

    private void roll() {
        if (!enabled) return;
        Set<UUID> handled = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (handled.contains(p.getUniqueId()) || !canBeAmbushed(p, false)) continue;
            List<Player> group = party(p, false);   // a group rolls once, together
            group.forEach(o -> handled.add(o.getUniqueId()));
            if (ThiefGang.ambush(rnd.nextDouble(), chance)) warnThenStart(group, false);
        }
    }

    /** A moment of warning, then they come. */
    private void warnThenStart(List<Player> group, boolean forced) {
        for (Player o : group) {
            o.sendActionBar(Ui.mm("<dark_gray><italic>You feel eyes on you in the dark..."));
            o.playSound(o.getLocation(), Sound.AMBIENT_CAVE, 0.8f, 0.6f);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            List<Player> still = new ArrayList<>();
            for (Player o : group) if (o.isOnline() && canBeAmbushed(o, forced)) still.add(o);
            if (!still.isEmpty()) start(still);
        }, 50L);
    }

    // ------------------------------------------------------------------ the gang arrives

    private boolean start(List<Player> group) {
        double total = 0;
        for (Player o : group) total += gear(o);
        double avg = total / group.size();
        int n = ThiefGang.gangSize(group.size(), avg, thievesPerGear, maxThieves);
        double mult = ThiefGang.multiplier(group.size(), avg, soloMult, perPlayer, perGear, maxMult);
        int mages = ThiefGang.mages(n, mageEvery, rnd.nextDouble());
        Gang g = new Gang(group.stream().map(Player::getUniqueId).toList(), mult, n);
        List<Boolean> roles = new ArrayList<>();
        for (int i = 0; i < n; i++) roles.add(i < mages);
        Collections.shuffle(roles, rnd);
        for (int i = 0; i < n; i++) {
            Player mark = group.get(i % group.size());
            Location at = findSpot(mark);
            if (at == null) continue;
            Mob mob = roles.get(i) ? spawnMage(at, mult) : spawnThief(at, mark, mult);
            Thief t = new Thief(mob, roles.get(i), g, mark.getUniqueId());
            t.castIn = ThiefGang.FIRE_DELAY + rnd.nextInt(40);
            g.members.add(t);
            thieves.put(mob.getUniqueId(), t);
            t.view = spawnView(at, roles.get(i) ? null : mark, mob);
            viewOf.put(t.view.getUniqueId(), t);
            mob.setTarget(mark);
            at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 18, 0.35, 0.6, 0.35, 0.01, null, true);
        }
        if (g.members.isEmpty()) return false;   // nowhere to stand: no ambush this time
        gangs.add(g);
        for (Player o : group) {
            gangOf.put(o.getUniqueId(), g);
            o.showTitle(net.kyori.adventure.title.Title.title(Ui.mm("<#8a8a8a><bold>Night Thieves"),
                    Ui.mm(group.size() == 1 ? "<gray>Someone's been following you." : "<gray>A gang of <white>" + g.members.size() + "<gray> closes in."),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(250), java.time.Duration.ofMillis(1800), java.time.Duration.ofMillis(600))));
            o.playSound(o.getLocation(), Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, 0.9f, 0.7f);
        }
        return true;
    }

    /** Somewhere 10-16 blocks off, behind the player if possible, standing on solid ground, outside every claim. */
    private Location findSpot(Player p) {
        Location base = p.getLocation();
        World w = base.getWorld();
        double behind = Math.toRadians(base.getYaw() + 180);
        for (int attempt = 0; attempt < 20; attempt++) {
            double spread = attempt < 10 ? 70 : 180;
            double a = behind + Math.toRadians((rnd.nextDouble() * 2 - 1) * spread);
            double d = 10 + rnd.nextDouble() * 6;
            int x = (int) Math.floor(base.getX() - Math.sin(a) * d), z = (int) Math.floor(base.getZ() + Math.cos(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = base.getBlockY() + 4; y >= base.getBlockY() - 8; y--) {
                Block feet = w.getBlockAt(x, y, z), head = feet.getRelative(0, 1, 0), below = feet.getRelative(0, -1, 0);
                if (!below.getType().isSolid() || !feet.isPassable() || !head.isPassable() || feet.isLiquid() || head.isLiquid()) continue;
                Location at = new Location(w, x + 0.5, y, z + 0.5);
                if (spawnable.test(at)) return at;
                break;
            }
        }
        return null;
    }

    private Zombie spawnThief(Location at, Player copy, double mult) {
        return at.getWorld().spawn(at, Zombie.class, z -> {
            z.setAdult();
            z.setShouldBurnInDay(false);
            z.setCanPickupItems(false);
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false);
            z.setVisibleByDefault(false);   // players see the Mannequin, never the zombie
            z.setSilent(true);
            EntityEquipment eq = z.getEquipment();
            PlayerInventory inv = copy.getInventory();
            eq.setHelmet(copyOf(inv.getHelmet()));
            eq.setChestplate(copyOf(inv.getChestplate()));
            eq.setLeggings(copyOf(inv.getLeggings()));
            eq.setBoots(copyOf(inv.getBoots()));
            ItemStack hand = inv.getItemInMainHand();
            eq.setItemInMainHand(ThiefGang.isWeapon(hand.getType().name()) ? copyOf(hand) : null);
            eq.setItemInOffHand(null);
            noDrops(eq);
            stats(z, mult, 1.0);
        });
    }

    private Illusioner spawnMage(Location at, double mult) {
        return at.getWorld().spawn(at, Illusioner.class, m -> {
            m.setCanJoinRaid(false);
            m.setPatrolLeader(false);
            m.setCanPickupItems(false);
            m.setPersistent(false);
            m.setRemoveWhenFarAway(false);
            m.setVisibleByDefault(false);
            m.setSilent(true);
            EntityEquipment eq = m.getEquipment();
            eq.setItemInMainHand(new ItemStack(Material.AMETHYST_SHARD));   // no bow: the Hex Bolt is its only attack
            noDrops(eq);
            stats(m, mult, 0.75);
        });
    }

    /** The player-shaped body: the marked player's own skin (or the mage robe), the driver's gear, its name tag. */
    private Mannequin spawnView(Location at, Player skinOf, Mob driver) {
        return at.getWorld().spawn(at, Mannequin.class, v -> {
            v.setPersistent(false);
            v.setRemoveWhenFarAway(false);
            v.setGravity(false);
            v.setImmovable(true);
            v.setCollidable(false);
            v.setSilent(true);
            v.setProfile(skinOf != null
                    ? ResolvableProfile.resolvableProfile(skinOf.getPlayerProfile())
                    : ResolvableProfile.resolvableProfile().skinPatch(sp -> sp.body(Key.key("blackforge", "entity/thief_mage")).model(PlayerTextures.SkinModel.CLASSIC)).build());
            v.customName(Ui.mm(skinOf != null ? "<#8a8a8a>Night Thief" : "<#9a5ee6>Night Thief Mage"));
            v.setCustomNameVisible(true);
            v.setDescription(Ui.mm(skinOf != null ? "<dark_gray>Wearing a face you know" : "<dark_gray>Hex-thrower"));
            EntityEquipment from = driver.getEquipment(), eq = v.getEquipment();
            eq.setHelmet(from.getHelmet());
            eq.setChestplate(from.getChestplate());
            eq.setLeggings(from.getLeggings());
            eq.setBoots(from.getBoots());
            eq.setItemInMainHand(from.getItemInMainHand());
            noDrops(eq);
            base(v, Attribute.SCALE, ThiefGang.growIn(0));
        });
    }

    /** Keep the Mannequin on the hidden mob: same spot, facing and size. Teleporting every tick lets the client blend it. */
    private void syncView(Thief t) {
        Mannequin v = t.view;
        if (v == null || !v.isValid()) return;
        v.teleport(t.mob.getLocation());
        v.setBodyYaw(t.mob.getBodyYaw());
        AttributeInstance s = t.mob.getAttribute(Attribute.SCALE);
        if (s != null) base(v, Attribute.SCALE, s.getValue());
    }

    private void stats(Mob m, double mult, double healthShare) {
        base(m, Attribute.MAX_HEALTH, 20 * mult * healthShare);
        m.setHealth(Math.max(1, 20 * mult * healthShare));
        base(m, Attribute.MOVEMENT_SPEED, baseSpeed);
        base(m, Attribute.FOLLOW_RANGE, 40);
        base(m, Attribute.SPAWN_REINFORCEMENTS, 0);
        base(m, Attribute.SCALE, ThiefGang.growIn(0));
        for (Attribute a : List.of(Attribute.ATTACK_DAMAGE, Attribute.ARMOR, Attribute.ARMOR_TOUGHNESS)) {
            AttributeInstance in = m.getAttribute(a);
            if (in != null) in.addModifier(new AttributeModifier(powerKey, mult - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        }
    }

    private static void base(LivingEntity e, Attribute a, double v) {
        AttributeInstance in = e.getAttribute(a);
        if (in != null) in.setBaseValue(v);
    }

    private static void noDrops(EntityEquipment eq) {
        eq.setHelmetDropChance(0); eq.setChestplateDropChance(0); eq.setLeggingsDropChance(0); eq.setBootsDropChance(0);
        eq.setItemInMainHandDropChance(0); eq.setItemInOffHandDropChance(0);
    }

    /** A copy of a player's item for a thief to wear: same look and enchants, but none of our tags (so no ability works on it). */
    private static ItemStack copyOf(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        ItemStack c = it.clone();
        c.setAmount(1);
        c.editMeta(m -> new ArrayList<>(m.getPersistentDataContainer().getKeys()).forEach(m.getPersistentDataContainer()::remove));
        return c;
    }

    /** Gear score for {@link ThiefGang#gearScore}. */
    private static double gear(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] armour = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        String[] names = new String[4];
        int levels = 0;
        for (int i = 0; i < 4; i++) {
            ItemStack a = armour[i];
            if (a == null || a.getType().isAir()) continue;
            names[i] = a.getType().name();
            levels += a.getEnchantments().values().stream().mapToInt(Integer::intValue).sum();
        }
        ItemStack hand = inv.getItemInMainHand();
        if (ThiefGang.isWeapon(hand.getType().name())) levels += hand.getEnchantments().values().stream().mapToInt(Integer::intValue).sum();
        return ThiefGang.gearScore(names, hand.getType().name(), levels);
    }

    // ------------------------------------------------------------------ every tick

    private void tick() {
        if (gangs.isEmpty()) return;
        for (Gang g : new ArrayList<>(gangs)) tickGang(g);
    }

    private void tickGang(Gang g) {
        g.age++;
        List<Player> live = new ArrayList<>();
        for (UUID id : g.players) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.isDead()) continue;
            live.add(p);
            Location now = p.getLocation(), last = g.lastPos.put(id, now);   // real movement, not the stale server velocity
            if (last != null && last.getWorld() == now.getWorld()) g.velocity.put(id, now.clone().subtract(last));
        }
        for (Thief t : new ArrayList<>(g.members)) {
            if (!t.mob.isValid() || t.view == null || !t.view.isValid()) {   // unloaded or removed by something else: he got away
                if (!t.mob.isDead()) lose(t, "<gray>The thief slipped away into the dark");
                dropThief(t);
                if (!t.mob.isDead()) t.mob.remove();
                continue;
            }
            tickThief(t, live);
        }
        if (g.over) { if (g.members.isEmpty()) gangs.remove(g); return; }   // the last ones have finished fading out
        if (g.members.isEmpty()) { end(g, g.killed > 0); return; }

        // give up when everyone has gone home, logged off or got well away, or the night has dragged on too long
        boolean anyone = false;
        for (Player p : live) {
            if (!outsideBase.test(p, p.getLocation())) continue;
            for (Thief t : g.members)
                if (t.mob.getWorld() == p.getWorld() && t.mob.getLocation().distanceSquared(p.getLocation()) < PARTY_FOLLOW * PARTY_FOLLOW) { anyone = true; break; }
            if (anyone) break;
        }
        g.away = anyone ? 0 : g.away + 1;
        if (g.away > GIVE_UP_AWAY_TICKS || g.age > giveUpSeconds * 20 || live.isEmpty()) retreat(g);
    }

    private void tickThief(Thief t, List<Player> live) {
        Mob m = t.mob;
        if (t.growTick < ThiefGang.GROW_TICKS) base(m, Attribute.SCALE, ThiefGang.growIn(++t.growTick));
        if (t.shrinkTick >= 0) {   // vanishing: shrink over a few ticks, then gone
            t.shrinkTick++;
            base(m, Attribute.SCALE, ThiefGang.growIn(Math.max(0, ThiefGang.GROW_TICKS - t.shrinkTick)));
            syncView(t);
            m.setAI(false);
            moveLootShow(t);
            if (t.shrinkTick >= ThiefGang.GROW_TICKS) {
                m.getWorld().spawnParticle(Particle.LARGE_SMOKE, m.getLocation().add(0, 1, 0), 14, 0.3, 0.5, 0.3, 0.02, null, true);
                m.getWorld().playSound(m.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 0.8f);
                dropThief(t);
                m.remove();
            }
            return;
        }
        syncView(t);
        Player mark = mark(t, live);
        if (mark == null) { lose(t, "<gray>The thief slipped away into the dark"); vanish(t); return; }

        if (t.fleeing()) { tickFlee(t, mark, live); return; }
        if (m.getTarget() != mark && m.getTicksLived() % 10 == 0) m.setTarget(mark);
        if (t.turned && m.getTicksLived() % 4 == 0)
            m.getWorld().spawnParticle(Particle.DUST, m.getLocation().add(0, 1.2, 0), 1, 0.25, 0.4, 0.25, 0, new Particle.DustOptions(Color.fromRGB(170, 20, 20), 1.0f), true);
        if (t.mage) tickMage(t, mark);
    }

    /** The thief's player, or the nearest other gang member still about if that one has gone. */
    private Player mark(Thief t, List<Player> live) {
        Player p = Bukkit.getPlayer(t.mark);
        if (p != null && !p.isDead() && p.getWorld() == t.mob.getWorld()) return p;
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player o : live) {
            if (o.getWorld() != t.mob.getWorld()) continue;
            double d = o.getLocation().distanceSquared(t.mob.getLocation());
            if (d < bd) { bd = d; best = o; }
        }
        if (best != null && !t.fleeing()) t.mark = best.getUniqueId();
        return best;
    }

    // ------------------------------------------------------------------ stealing and running

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThiefHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Thief t = thieves.get(e.getDamager().getUniqueId());
        if (t == null) return;
        if (t.view != null && t.view.isValid()) t.view.swingMainHand();
        if (t.mage || t.loot != null || t.fleeing() || t.shrinkTick >= 0) return;
        if (!t.gang.players.contains(p.getUniqueId())) return;
        if (rnd.nextDouble() >= stealChance) return;
        PlayerInventory inv = p.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (ThiefGang.isWeapon(hand.getType().name()) && rnd.nextDouble() < weaponTurnChance) {
            t.loot = hand.clone();
            t.lootOwner = p.getUniqueId();
            t.turned = true;
            inv.setItemInMainHand(null);
            t.mob.getEquipment().setItemInMainHand(copyOf(t.loot));
            t.mob.getEquipment().setItemInMainHandDropChance(0);
            if (t.view != null) t.view.getEquipment().setItemInMainHand(copyOf(t.loot));
            p.sendMessage(Ui.mm("<red>⚔ <gray>The thief rips <white>" + name(t.loot) + "<gray> out of your hands and turns it on you! <red>Kill him to get it back."));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.6f);
            p.getWorld().playSound(t.mob.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1f, 0.8f);
            return;
        }
        int slot = mostValuable(inv);
        if (slot < 0) return;
        ItemStack it = inv.getItem(slot);
        if (it == null) return;
        t.loot = it.clone();
        t.lootOwner = p.getUniqueId();
        inv.setItem(slot, null);
        startFlee(t);
        p.sendMessage(Ui.mm("<red>✦ <gray>A thief snatched <white>" + (t.loot.getAmount() > 1 ? t.loot.getAmount() + "x " : "") + name(t.loot)
                + "<gray>! Catch him within <white>" + escapeSeconds + "s<gray> or it's gone."));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.5f);
    }

    /** Storage slot (not armour, off hand or the held slot) holding the most valuable stack, or -1 if there is nothing. */
    private int mostValuable(PlayerInventory inv) {
        int best = -1;
        double bestScore = 0;
        int held = inv.getHeldItemSlot();
        for (int i = 0; i < 36; i++) {
            if (i == held) continue;
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            double s = value(it) + rnd.nextDouble() * 0.01;   // ties broken at random
            if (s > bestScore) { bestScore = s; best = i; }
        }
        return best;
    }

    private static double value(ItemStack it) {
        String n = it.getType().name();
        double v = 1 + ThiefGang.tier(n) * 2;
        if (n.contains("NETHERITE")) v += 30;
        else if (n.contains("DIAMOND")) v += 15;
        else if (n.contains("EMERALD") || n.equals("TOTEM_OF_UNDYING") || n.equals("ENCHANTED_GOLDEN_APPLE") || n.equals("ELYTRA")) v += 15;
        else if (n.contains("GOLD") || n.contains("IRON")) v += 4;
        if (!it.getEnchantments().isEmpty()) v += 8;
        if (it.hasItemMeta() && !it.getItemMeta().getPersistentDataContainer().isEmpty()) v += 10;   // keys and BlackForgeMC items
        return v * Math.min(4, Math.sqrt(it.getAmount()));
    }

    private void startFlee(Thief t) {
        t.fleeTick = 0;
        t.mob.setTarget(null);
        ItemStack show = t.loot.clone();
        show.setAmount(1);
        t.lootShow = t.mob.getWorld().spawn(t.mob.getEyeLocation().add(0, 0.7, 0), ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(show);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setTeleportDuration(1);
            d.setInterpolationDuration(4);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setTransformation(scaled(0.05f));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {   // scale in, don't pop
            if (t.lootShow != null && t.lootShow.isValid()) { t.lootShow.setInterpolationDelay(0); t.lootShow.setTransformation(scaled(0.5f)); }
        }, 1L);
    }

    private void tickFlee(Thief t, Player owner, List<Player> live) {
        Mob m = t.mob;
        t.fleeTick++;
        if (t.fleeTick <= ThiefGang.FLEE_RAMP + 1) {   // the burst of speed builds over a few ticks
            AttributeInstance sp = m.getAttribute(Attribute.MOVEMENT_SPEED);
            if (sp != null) {
                sp.removeModifier(fleeKey);
                sp.addModifier(new AttributeModifier(fleeKey, ThiefGang.fleeBoost(t.fleeTick, fleeBoost), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
            }
        }
        if (m.getTarget() != null) m.setTarget(null);
        if (t.fleeTick % 10 == 1) {   // run directly away from whoever is closest
            Player near = owner;
            double nd = Double.MAX_VALUE;
            for (Player p : live)
                if (p.getWorld() == m.getWorld()) { double d = p.getLocation().distanceSquared(m.getLocation()); if (d < nd) { nd = d; near = p; } }
            Vector away = m.getLocation().toVector().subtract(near.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(rnd.nextDouble() - 0.5, 0, rnd.nextDouble() - 0.5);
            m.getPathfinder().moveTo(m.getLocation().add(away.normalize().multiply(16)), 1.0);
        }
        moveLootShow(t);
        if (t.fleeTick % 3 == 0) m.getWorld().spawnParticle(Particle.SMOKE, m.getLocation().add(0, 0.2, 0), 1, 0.15, 0.05, 0.15, 0.005, null, true);

        boolean clear = true;
        for (Player p : live)
            if (p.getWorld() == m.getWorld() && p.getLocation().distanceSquared(m.getLocation()) < (double) escapeDistance * escapeDistance) { clear = false; break; }
        if (clear || t.fleeTick > escapeSeconds * 20) {
            lose(t, "<gray>The thief got away with");
            vanish(t);
        }
    }

    private void moveLootShow(Thief t) {
        if (t.lootShow == null || !t.lootShow.isValid()) return;
        double scale = t.mob.getAttribute(Attribute.SCALE) != null ? t.mob.getAttribute(Attribute.SCALE).getValue() : 1;
        t.lootShow.teleport(t.mob.getLocation().add(0, 2.4 * scale + 0.3, 0));
    }

    /** The owner is told their item is gone for good, and the thief keeps it. */
    private void lose(Thief t, String what) {
        if (t.loot == null) return;
        Player owner = t.lootOwner == null ? null : Bukkit.getPlayer(t.lootOwner);
        if (owner != null) owner.sendMessage(Ui.mm("<dark_gray>☠ " + what + (what.endsWith("with") ? " <white>" + name(t.loot) + "<gray>." : "<gray>, and your <white>" + name(t.loot) + "<gray> with him.")));
        t.loot = null;
    }

    // ------------------------------------------------------------------ the mage's Hex Bolt

    private void tickMage(Thief t, Player mark) {
        Mob m = t.mob;
        double d = m.getLocation().distance(mark.getLocation());
        if (m.getTicksLived() % 10 == 0) {   // keep to the middle distance: close enough to cast, too far to hit
            if (d > 14) m.getPathfinder().moveTo(mark.getLocation(), 1.0);
            else if (d < 7) {
                Vector away = m.getLocation().toVector().subtract(mark.getLocation().toVector()).setY(0);
                if (away.lengthSquared() > 0.01) m.getPathfinder().moveTo(m.getLocation().add(away.normalize().multiply(6)), 1.1);
            } else m.getPathfinder().stopPathfinding();
        }
        if (t.bolt != null) { tickBolt(t.bolt); return; }
        if (--t.castIn > 0 || d > ThiefGang.MAX_RANGE || !m.hasLineOfSight(mark)) return;
        t.castIn = ThiefGang.FIRE_DELAY + castGapMin + rnd.nextInt(castGapMax - castGapMin + 1);
        Location hand = handPoint(m);
        ItemDisplay show = m.getWorld().spawn(hand, ItemDisplay.class, dd -> {
            dd.setPersistent(false);
            dd.setItemStack(new ItemStack(Material.AMETHYST_SHARD));
            dd.setBillboard(Display.Billboard.CENTER);
            dd.setTeleportDuration(1);
            dd.setInterpolationDuration(1);
            dd.setBrightness(new Display.Brightness(15, 15));
            dd.setGlowing(true);
            dd.setTransformation(scaled(0.1f));
        });
        t.bolt = new Bolt(t, mark, show, hand);
        m.getWorld().playSound(hand, Sound.ENTITY_EVOKER_PREPARE_ATTACK, 0.9f, 1.4f);
    }

    private static Location handPoint(Mob m) {
        Location eye = m.getEyeLocation();
        Vector f = eye.getDirection().setY(0);
        if (f.lengthSquared() < 1e-4) f = new Vector(0, 0, 1);
        return eye.add(f.normalize().multiply(0.6)).add(0, -0.35, 0);
    }

    private void tickBolt(Bolt b) {
        Mob m = b.caster.mob;
        World w = m.getWorld();
        b.tick++;
        if (!b.target.isValid() || b.target.isDead() || b.target.getWorld() != w) { fizzle(b); return; }
        if (b.tick <= ThiefGang.CHARGE) {   // gathering: the telegraph
            m.lookAt(b.target);
            b.pos = handPoint(m);
            double c = ThiefGang.charge(b.tick);
            show(b, (float) (0.1 + 0.4 * c));
            double r = 1.3 * (1 - c) + 0.15;
            for (int i = 0; i < 2; i++) {
                double a = rnd.nextDouble() * Math.PI * 2, y = rnd.nextDouble() * 2 - 1;
                Location p = b.pos.clone().add(Math.cos(a) * r * Math.sqrt(1 - y * y), y * r, Math.sin(a) * r * Math.sqrt(1 - y * y));
                w.spawnParticle(Particle.WITCH, p, 1, 0, 0, 0, 0, null, true);
            }
            if (b.tick == ThiefGang.CHARGE) {   // lock on: from here it no longer tracks
                Location aim = b.target.getLocation().add(0, 1.0, 0);
                Location v = b.caster.gang.velocity.get(b.target.getUniqueId());
                if (v != null) {
                    double[] lead = ThiefGang.lead(v.getX(), v.getZ(), b.pos.distance(aim), 0.8);
                    aim.add(lead[0], 0, lead[1]);
                }
                Vector dir = aim.toVector().subtract(b.pos.toVector());
                b.dir = dir.lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : dir.normalize();
                w.playSound(b.pos, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.6f);
            }
            return;
        }
        if (b.tick <= ThiefGang.FIRE_DELAY) {   // drawn back like a bowstring: the dodge window
            double u = (b.tick - ThiefGang.CHARGE) / (double) ThiefGang.LOCK;
            b.pos = handPoint(m).subtract(b.dir.clone().multiply(0.35 * u * u));
            show(b, 0.5f);
            if (b.tick == ThiefGang.FIRE_DELAY) {
                w.playSound(b.pos, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1.3f);
                if (b.caster.view != null && b.caster.view.isValid()) b.caster.view.swingMainHand();
            }
            return;
        }
        double v = ThiefGang.boltSpeed(b.flying++);
        int steps = Math.max(1, (int) Math.ceil(v / 0.4));
        for (int i = 0; i < steps; i++) {
            b.pos.add(b.dir.clone().multiply(v / steps));
            Player hit = playerAt(b);
            if (hit != null) { impact(b, hit); return; }
            if (b.pos.getBlock().getType().isSolid()) { impact(b, null); return; }
        }
        b.traveled += v;
        show(b, 0.5f);
        w.spawnParticle(Particle.DUST, b.pos, 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(HEX, 1.1f), true);
        if (b.flying % 2 == 0) w.spawnParticle(Particle.DUST, b.pos, 1, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(HEX_DARK, 0.8f), true);
        if (b.traveled > ThiefGang.MAX_RANGE) fizzle(b);
    }

    private Player playerAt(Bolt b) {
        for (UUID id : b.caster.gang.players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld() == b.pos.getWorld() && p.getBoundingBox().expand(ThiefGang.HIT_RADIUS * 0.5).contains(b.pos.toVector())) return p;
        }
        return null;
    }

    private void show(Bolt b, float scale) {
        if (!b.show.isValid()) return;
        b.show.teleport(b.pos);
        b.show.setInterpolationDelay(0);
        b.show.setTransformation(scaled(scale));
    }

    private void impact(Bolt b, Player hit) {
        World w = b.pos.getWorld();
        w.spawnParticle(Particle.DUST, b.pos, 16, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(HEX, 1.4f), true);
        w.spawnParticle(Particle.WITCH, b.pos, 8, 0.25, 0.25, 0.25, 0.05, null, true);
        w.playSound(b.pos, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1f, 0.8f);
        if (hit != null) {
            DamageSource src = DamageSource.builder(DamageType.MAGIC).withCausingEntity(b.caster.mob).withDirectEntity(b.caster.mob).build();
            hit.damage(hexDamage * b.caster.gang.mult, src);
            hit.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20, 0, false, true, true));
        }
        fizzle(b);
    }

    /** End a bolt: the shard shrinks away over a few ticks instead of vanishing. */
    private void fizzle(Bolt b) {
        b.caster.bolt = null;
        ItemDisplay d = b.show;
        if (!d.isValid()) return;
        d.setInterpolationDuration(4);
        d.setInterpolationDelay(0);
        d.setTransformation(scaled(0f));
        Bukkit.getScheduler().runTaskLater(plugin, d::remove, 5L);
    }

    private static Transformation scaled(float s) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
    }

    // ------------------------------------------------------------------ ends

    private void vanish(Thief t) {
        if (t.shrinkTick < 0) t.shrinkTick = 0;
    }

    /** Remove a thief from the books (and its displays); he is no longer part of the fight. */
    private void dropThief(Thief t) {
        thieves.remove(t.mob.getUniqueId());
        t.gang.members.remove(t);
        if (t.lootShow != null) t.lootShow.remove();
        if (t.bolt != null) { ItemDisplay d = t.bolt.show; t.bolt = null; d.remove(); }
        Mannequin v = t.view;
        if (v == null) return;
        viewOf.remove(v.getUniqueId());
        t.view = null;
        if (!v.isValid()) return;
        if (t.mob.isDead()) { dyingViews.add(v.getUniqueId()); v.setHealth(0); }   // the death fall, not a pop
        else v.remove();
    }

    /** Everyone left fades into the night; anything stolen goes with them. */
    private void retreat(Gang g) {
        for (Thief t : new ArrayList<>(g.members)) {
            if (t.shrinkTick >= 0) continue;
            lose(t, "<gray>The thieves melted back into the night");
            vanish(t);
        }
        if (!g.over) {
            g.over = true;
            for (UUID id : g.players) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.sendActionBar(Ui.mm("<dark_gray>The thieves melt back into the night."));
            }
            finish(g);
        }
    }

    private void end(Gang g, boolean beaten) {
        if (g.over) return;
        g.over = true;
        if (beaten) reward(g);
        finish(g);
    }

    private void finish(Gang g) {
        long until = System.currentTimeMillis() + cooldownMinutes * 60_000L;
        for (UUID id : g.players) { gangOf.remove(id); cooldownUntil.put(id, until); }
        // tickGang drops the gang once its last thief has shrunk away
    }

    private void reward(Gang g) {
        List<Player> paid = new ArrayList<>();
        for (UUID id : g.players) { Player p = Bukkit.getPlayer(id); if (p != null && !p.isDead()) paid.add(p); }
        if (paid.isEmpty()) return;
        double coins = ThiefGang.rewardEach(rewardCoins, g.killed, g.mult, paid.size());   // thieves that got away pay nothing
        double kc = ThiefGang.keyChance(keyChance, g.mult, soloMult), rare = rareKeyChance * g.mult / soloMult;
        for (Player p : paid) {
            StringBuilder msg = new StringBuilder("<#8a8a8a><bold>Thieves beaten!</bold>");
            if (eco != null && coins > 0) { eco.depositPlayer(p, coins); msg.append(" <gray>+<gold>").append((long) coins).append(" coins"); }
            p.sendMessage(Ui.mm(msg.toString()));
            if (rnd.nextDouble() < rare) crates.give(p, rareKeyCrate, 1);
            else if (rnd.nextDouble() < kc) crates.give(p, keyCrate, 1);
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (dyingViews.remove(e.getEntity().getUniqueId())) { e.getDrops().clear(); e.setDroppedExp(0); return; }
        Thief t = thieves.get(e.getEntity().getUniqueId());
        if (t == null) return;
        e.getDrops().clear();
        e.getEntity().getWorld().playSound(e.getEntity().getLocation(), Sound.ENTITY_PLAYER_DEATH, 1f, 0.8f);
        e.setDroppedExp((int) Math.round(8 * t.gang.mult));
        Location at = e.getEntity().getLocation();
        if (t.loot != null) {   // whatever he stole falls where he dies, and only its owner can pick it up
            Item drop = at.getWorld().dropItem(at.clone().add(0, 0.5, 0), t.loot);
            if (t.lootOwner != null) drop.setOwner(t.lootOwner);
            drop.setGlowing(true);
            Player owner = t.lootOwner == null ? null : Bukkit.getPlayer(t.lootOwner);
            if (owner != null) owner.sendMessage(Ui.mm("<green>✦ <gray>You got your <white>" + name(t.loot) + "<gray> back."));
            t.loot = null;
        }
        at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0, new Particle.DustOptions(ASH, 1.2f), true);
        if (e.getEntity().getKiller() != null) t.gang.killed++;
        dropThief(t);
        // tickGang notices the gang is empty and pays out
    }

    /** Players hit the Mannequin; the hit lands on the hidden mob, with its armour, knockback and kill credit. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onViewHurt(EntityDamageEvent e) {
        Thief t = viewOf.get(e.getEntity().getUniqueId());
        if (t == null) return;
        e.setCancelled(true);   // the body itself never takes damage
        if (!(e instanceof EntityDamageByEntityEvent be) || !t.mob.isValid() || t.mob.isDead() || t.shrinkTick >= 0) return;
        Entity by = be.getDamager();
        if (by instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) by = shooter;
        if (thieves.containsKey(by.getUniqueId())) return;   // no thief hurts another
        t.mob.damage(e.getDamage(), by);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDriverHurt(EntityDamageEvent e) {
        Thief t = thieves.get(e.getEntity().getUniqueId());
        if (t == null || t.view == null || !t.view.isValid()) return;
        t.view.playHurtAnimation(t.view.getLocation().getYaw());
        t.mob.getWorld().playSound(t.mob.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.9f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        Thief t = thieves.get(e.getEntity().getUniqueId());
        if (t == null) return;
        if (t.fleeing() || t.shrinkTick >= 0) { if (e.getTarget() != null) e.setCancelled(true); return; }
        if (e.getTarget() instanceof Player p && !t.gang.players.contains(p.getUniqueId())) e.setCancelled(true);   // only their marks
    }

    /** The mage is an Illusioner: let it keep its mirror images, but not its blindness on top of a night ambush. */
    @EventHandler(ignoreCancelled = true)
    public void onBlind(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getNewEffect() == null || e.getNewEffect().getType() != PotionEffectType.BLINDNESS) return;
        if (e.getCause() != EntityPotionEffectEvent.Cause.ATTACK) return;
        Gang g = gangOf.get(p.getUniqueId());
        if (g == null) return;
        for (Thief t : g.members) if (t.mage && t.mob.getWorld() == p.getWorld() && t.mob.getLocation().distanceSquared(p.getLocation()) < 64 * 64) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (thieves.containsKey(e.getEntity().getUniqueId())) e.setCancelled(true); }   // no drowned thieves

    @EventHandler(ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) {
        if (thieves.containsKey(e.getEntity().getUniqueId()) && !(e instanceof EntityCombustByEntityEvent) && !(e instanceof EntityCombustByBlockEvent)) e.setCancelled(true);   // dawn doesn't burn them
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { leave(e.getPlayer()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { leave(e.getPlayer()); }

    /** A player who leaves the fight is out of it; if nobody is left, the thieves win and keep what they took. */
    private void leave(Player p) {
        Gang g = gangOf.remove(p.getUniqueId());
        if (g == null) return;
        g.players.remove(p.getUniqueId());
        cooldownUntil.put(p.getUniqueId(), System.currentTimeMillis() + cooldownMinutes * 60_000L);
        if (g.players.isEmpty()) retreat(g);
    }

    // ------------------------------------------------------------------ staff

    /** /blackforge thieves spawn <player> | clear | status. Returns false if this isn't a thieves command. */
    boolean admin(CommandSender s, String[] a) {
        if (a.length < 2 || !a[0].equalsIgnoreCase("thieves")) return false;
        switch (a[1].toLowerCase()) {
            case "spawn" -> {
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player p ? p : null;
                if (t == null) { s.sendMessage(Ui.mm("<red>Usage: /blackforge thieves spawn <player>")); return true; }
                if (gangOf.containsKey(t.getUniqueId())) { s.sendMessage(Ui.mm("<red>" + t.getName() + " is already being hunted.")); return true; }
                if (!outsideBase.test(t, t.getLocation())) { s.sendMessage(Ui.mm("<red>" + t.getName() + " is somewhere thieves can't come (their base, spawn or a protected area).")); return true; }
                List<Player> group = party(t, true);
                warnThenStart(group, true);
                s.sendMessage(Ui.mm("<gray>Sending thieves after <white>" + group.size() + "<gray> player(s) near " + t.getName() + " (night not required)."));
            }
            case "clear" -> {
                int n = 0;
                for (Gang g : new ArrayList<>(gangs)) { for (Thief t : new ArrayList<>(g.members)) { giveBack(t); dropThief(t); t.mob.remove(); n++; } end(g, false); gangs.remove(g); }
                s.sendMessage(Ui.mm("<gray>Removed <white>" + n + "<gray> thieves (stolen items returned)."));
            }
            case "status" -> s.sendMessage(Ui.mm("<gray>Thieves: <white>" + (enabled ? "on" : "off") + "<gray>, " + gangs.size() + " gang(s) out, "
                    + thieves.size() + " thieves, " + (chance * 100) + "% every " + rollSeconds + "s outside a base at night."));
            default -> s.sendMessage(Ui.mm("<red>/blackforge thieves spawn <player> | clear | status"));
        }
        return true;
    }

    /** Restart or staff clear: nobody loses an item to a thief that only vanished for technical reasons. */
    private void giveBack(Thief t) {
        if (t.loot == null) return;
        Player owner = t.lootOwner == null ? null : Bukkit.getPlayer(t.lootOwner);
        if (owner != null) owner.getInventory().addItem(t.loot).values().forEach(l -> owner.getWorld().dropItemNaturally(owner.getLocation(), l));
        else if (t.mob.isValid()) t.mob.getWorld().dropItemNaturally(t.mob.getLocation(), t.loot);
        t.loot = null;
    }

    void shutdown() {
        for (Gang g : new ArrayList<>(gangs))
            for (Thief t : new ArrayList<>(g.members)) { giveBack(t); dropThief(t); t.mob.remove(); }
        gangs.clear();
        thieves.clear();
        gangOf.clear();
        viewOf.clear();
    }

    private static String name(ItemStack it) {
        return MiniMessage.miniMessage().escapeTags(PlainTextComponentSerializer.plainText().serialize(it.effectiveName()));
    }
}
