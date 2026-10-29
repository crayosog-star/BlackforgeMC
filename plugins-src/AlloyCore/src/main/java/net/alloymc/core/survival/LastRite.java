package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/**
 * Last Rite: BlackForgeMC's take on a "kill contract". Right-click a charged scroll: a ring of runes spins up at your
 * feet for a second, then a glowing wall of energy rolls 30 blocks forward through everything. The first enemy it
 * touches is chained to you, the bell tolls three times, lightning falls on you both, and you're taken to a sealed
 * chamber ({@link RiteChambers}) for a duel only one of you walks out of.
 *
 * Charging: the scroll needs {@code last-rite.charges-needed} player kills (default 3) before it works. It's used up
 * when it catches someone; if the wave catches nobody it fizzles and keeps one charge.
 * Catching: only players the normal PvP rules let you hit (a probe damage event, like the Gale Bellows), never someone
 * already in a rite, never creative/spectator, never right after they survived one (30 s immunity).
 * The wave maths is in {@link RiteSweep}; the duel, the chamber and the return are in {@link RiteChambers}.
 */
public final class LastRite implements Listener, Hackable {
    public static final String NAME = "<gradient:#3A0A52:#B66CFF:#3A0A52><bold>Last Rite</bold></gradient>";
    static final Color VIOLET = Color.fromRGB(0xB66CFF), DEEP = Color.fromRGB(0x5A1A8C), INK = Color.fromRGB(0x12061C),
            BONE = Color.fromRGB(0xEDE4F7), BLOOD = Color.fromRGB(0x7A0F2A);
    private static final String RUNES = "ᛚᚨᛋᛏᚱᛁᛏᛖ";   // L A S T R I T E, in runes
    private static final int WINDUP_TICKS = 20, SEAL_TICKS = 14;

    private final AlloyCore plugin;
    private final boolean enabled, testing;
    private final int chargesNeeded, cooldownSeconds;
    private final Predicate<Location> allowedAt;
    private final Predicate<Entity> practiceTarget;           // the hub Arena's test dummy: a solo practice rite
    private final NamespacedKey tagKey, chargeKey, modelKey;
    private final RiteChambers chambers;
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private final Map<UUID, Cast> casting = new HashMap<>();
    private final Set<UUID> sealing = new HashSet<>();
    private final Map<UUID, Location> waves = new HashMap<>();   // caster -> where their rolling wall is now
    private final Set<UUID> jammedWaves = new HashSet<>();       // walls an EMP caught: dissolve next tick
    private final Set<Display> live = new HashSet<>();          // every display the rite has out, removed on shutdown
    private final Random rnd = new Random();

    private final class Cast {
        final List<TextDisplay> runes = new ArrayList<>();
        Lane lane;
        double damageTaken;
        boolean cancelled;
    }

    /** One row of the highlighted lane: how far down the lane its centre is, and the ground block it sits on. */
    private record Row(double along, int y) {}

    /**
     * The highlighted path: a ribbon of thin strips turned to the lane's direction (so its edges are straight lines
     * at any angle), one per block along the lane, revealed row by row. Each strip is a glass middle and two amethyst
     * edges. No glow: glow draws through terrain, which made strips hidden behind grass show up as stray outlines.
     */
    private final class Lane {
        final List<Row> rows;
        final double ox, oz;
        final double[] f;
        final float yaw;
        final List<BlockDisplay[]> strips = new ArrayList<>();
        final Set<Integer> flared = new HashSet<>();
        final Set<Long> ghosted = new HashSet<>();
        Lane(List<Row> rows, double ox, double oz, double[] f, float yaw) { this.rows = rows; this.ox = ox; this.oz = oz; this.f = f; this.yaw = yaw; }
    }

    /**
     * @param allowedAt      where the scroll can be read (the caster's spot), supplied by each server
     * @param testing        the hub Arena: always charged, never used up, no cooldown
     * @param practiceTarget entities the wave may also catch for a solo practice rite (null for none)
     * @param timeout        what a 40 s draw does on this server, unless last-rite.timeout in config.yml says otherwise
     */
    public LastRite(AlloyCore plugin, Predicate<Location> allowedAt, boolean testing, Predicate<Entity> practiceTarget, RiteChambers.Timeout timeout) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        this.testing = testing;
        this.practiceTarget = practiceTarget == null ? e -> false : practiceTarget;
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("last-rite.enabled", true);
        this.chargesNeeded = Math.max(0, cfg.getInt("last-rite.charges-needed", 3));
        this.cooldownSeconds = Math.max(0, cfg.getInt("last-rite.cooldown-seconds", 300));
        this.tagKey = new NamespacedKey(plugin, "last_rite");
        this.chargeKey = new NamespacedKey(plugin, "last_rite_charges");
        this.modelKey = new NamespacedKey("blackforge", "last_rite");
        RiteChambers.Timeout t = timeout;
        String ct = cfg.getString("last-rite.timeout", "");
        if (ct.equalsIgnoreCase("both-die")) t = RiteChambers.Timeout.BOTH_DIE;
        else if (ct.equalsIgnoreCase("both-weakened")) t = RiteChambers.Timeout.BOTH_WEAKENED;
        this.chambers = new RiteChambers(plugin, t, cfg.getBoolean("last-rite.allow-totems", true),
                cfg.getInt("last-rite.duel-seconds", 40), cfg.getInt("last-rite.chamber-slots", 8));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * EMP: a rite being read inside the wave breaks (no wave), and a wall already rolling dissolves the moment the
     * wave touches it. Not the EMP caster's own. Rites already sealed in a chamber are out of reach.
     */
    @Override
    public int hack(Location center, double radius, Player caster) {
        int n = 0;
        for (Map.Entry<UUID, Cast> e : new ArrayList<>(casting.entrySet())) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || e.getKey().equals(caster.getUniqueId()) || e.getValue().cancelled || !EmpWave.reaches(center, p.getLocation(), radius)) continue;
            e.getValue().cancelled = true;
            fizzleMsg(p, "An EMP broke your rite. It wasn't read.");
            n++;
        }
        for (Map.Entry<UUID, Location> e : new ArrayList<>(waves.entrySet())) {
            if (e.getKey().equals(caster.getUniqueId()) || jammedWaves.contains(e.getKey())
                    || !EmpWave.reaches(center, e.getValue(), radius, RiteSweep.WIDTH / 2)) continue;
            jammedWaves.add(e.getKey());
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) fizzleMsg(p, "An EMP shut down your rite.");
            n++;
        }
        return n;
    }

    public void shutdown() {
        waves.clear();
        jammedWaves.clear();
        casting.values().forEach(c -> c.runes.forEach(Entity::remove));
        casting.clear();
        live.forEach(Entity::remove);
        live.clear();
        chambers.shutdown();
    }

    public RiteChambers chambers() { return chambers; }

    // ------------------------------------------------------------------ the item

    /** A Last Rite scroll; in the Arena (testing) it comes fully charged. */
    public ItemStack item() { return item(testing ? chargesNeeded : 0); }

    public ItemStack item(int charges) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> {
            m.displayName(Ui.mm(NAME));
            m.setMaxStackSize(1);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
            m.getPersistentDataContainer().set(chargeKey, PersistentDataType.INTEGER, Math.min(charges, chargesNeeded));
        });
        relabel(it);
        return it;
    }

    public boolean isRite(ItemStack it) {
        return it != null && it.getType() == Material.PAPER && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    private int charges(ItemStack it) {
        Integer c = it.getItemMeta().getPersistentDataContainer().get(chargeKey, PersistentDataType.INTEGER);
        return c == null ? 0 : c;
    }

    private boolean charged(ItemStack it) { return charges(it) >= chargesNeeded; }

    /** Not fully charged: the Arena swaps it for a fresh, charged one. */
    public boolean isWorn(ItemStack it) { return isRite(it) && !charged(it); }

    private void setCharges(ItemStack it, int c) {
        it.editMeta(m -> m.getPersistentDataContainer().set(chargeKey, PersistentDataType.INTEGER, Math.max(0, Math.min(c, chargesNeeded))));
        relabel(it);
    }

    private void relabel(ItemStack it) {
        int c = charges(it);
        boolean ready = c >= chargesNeeded;
        StringBuilder pips = new StringBuilder();
        for (int i = 0; i < chargesNeeded; i++) pips.append(i < c ? "<#B66CFF>◆" : "<dark_gray>◇");
        it.editMeta(m -> {
            m.lore(List.of(
                    Ui.mm("<gray>Right-click: a wall of energy rolls <white>30 blocks<gray> forward."),
                    Ui.mm("<gray>The first enemy it touches is taken with you"),
                    Ui.mm("<gray>to a sealed chamber. <white>Only one walks out."),
                    Ui.mm(""),
                    Ui.mm(chargesNeeded == 0 ? "<#B66CFF>Ready" : "<gray>Charge " + pips + " <gray>" + (ready ? "<#B66CFF><bold>READY" : "<dark_gray>(player kills)")),
                    Ui.mm("<dark_gray>BlackForgeMC ability · used up when it catches someone")));
            m.setEnchantmentGlintOverride(ready);
        });
    }

    public void give(Player p) {
        p.getInventory().addItem(item()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<#B66CFF>✦ <gray>You received " + NAME + "<gray>. Charge it with kills, then right-click."));
    }

    /** /alloy rite give <player> (for crates). */
    public boolean admin(CommandSender s, String[] a) {
        if (a.length < 3 || !a[0].equalsIgnoreCase("rite") || !a[1].equalsIgnoreCase("give")) return false;
        if (!s.hasPermission("alloy.admin")) return true;
        Player t = Bukkit.getPlayerExact(a[2]);
        if (t == null) { s.sendMessage(Component.text("Player not found.")); return true; }
        give(t);
        s.sendMessage(Component.text("Gave Last Rite to " + t.getName()));
        return true;
    }

    // ------------------------------------------------------------------ charging with kills

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(PlayerDeathEvent e) {
        Player killer = e.getPlayer().getKiller();
        if (killer == null || killer == e.getPlayer() || testing || chargesNeeded == 0) return;
        for (ItemStack it : killer.getInventory().getContents()) {
            if (!isRite(it) || charged(it)) continue;
            setCharges(it, charges(it) + 1);
            int c = charges(it);
            if (c >= chargesNeeded) {
                killer.sendActionBar(Ui.mm("<#B66CFF>✦ " + NAME + " <gray>is <#B66CFF><bold>ready</bold><gray>."));
                killer.playSound(killer.getLocation(), Sound.BLOCK_BELL_RESONATE, 0.8f, 0.6f);
                killer.getWorld().spawnParticle(Particle.SCULK_SOUL, killer.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.03);
            } else {
                killer.sendActionBar(Ui.mm("<#B66CFF>✦ " + NAME + " <gray>charge <white>" + c + "<gray>/" + chargesNeeded));
                killer.playSound(killer.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 0.6f + 0.3f * c);
            }
            return;
        }
    }

    // ------------------------------------------------------------------ reading the rite

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isRite(e.getItem())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (casting.containsKey(id) || sealing.contains(id) || chambers.inRite(p)) return;
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>The Last Rite isn't switched on yet.")); return; }
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (chambers.isChamberWorld(p.getWorld()) || !allowedAt.test(p.getLocation())) { fizzleMsg(p, "The rite can't be read here."); return; }
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!testing && !charged(held)) { fizzleMsg(p, "Not charged yet: " + charges(held) + "/" + chargesNeeded + " player kills."); return; }
        long left = readyAt.getOrDefault(id, 0L) - System.currentTimeMillis();
        if (left > 0 && !testing) { fizzleMsg(p, "The ink is still drying... " + (left / 1000 + 1) + "s"); return; }
        if (!chambers.hasFreeSlot()) { fizzleMsg(p, "Every chamber is taken. Try again in a moment."); return; }
        windup(p);
    }

    private void fizzleMsg(Player p, String msg) {
        p.sendActionBar(Ui.mm("<#B66CFF>✦ <gray>" + msg));
        p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.4f, 1.6f);
    }

    /** Big hits during the windup break your concentration. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Cast c = casting.get(p.getUniqueId());
        if (c != null) c.damageTaken += e.getFinalDamage();
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { Cast c = casting.get(e.getPlayer().getUniqueId()); if (c != null) c.cancelled = true; }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { Cast c = casting.get(e.getPlayer().getUniqueId()); if (c != null) c.cancelled = true; }

    /**
     * 1 second: you're slowed, eight runes rise out of the ground and orbit your feet in a widening ring, violet souls
     * climb out of them and a resonance builds in pitch. At the end the ring snaps outward and the wave launches.
     */
    private void windup(Player p) {
        UUID id = p.getUniqueId();
        Cast c = new Cast();
        casting.put(id, c);
        World w = p.getWorld();
        // the aim is locked the moment the rite is read, so the highlighted lane is exactly where the wave goes
        Location origin = p.getLocation().clone();
        float yaw = origin.getYaw();
        double[] f = RiteSweep.facing(yaw);
        c.lane = new Lane(laneRows(w, origin.getX(), origin.getY(), origin.getZ(), f), origin.getX(), origin.getZ(), f, yaw);
        p.swingMainHand();
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, WINDUP_TICKS + 4, 4, false, false, false));
        w.playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        w.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.8f, 0.6f);
        for (int i = 0; i < RUNES.length(); i++) {
            String glyph = String.valueOf(RUNES.charAt(i));
            c.runes.add(w.spawn(p.getLocation().add(0, 0.05, 0), TextDisplay.class, d -> {
                d.text(Ui.mm("<#C58CFF>" + glyph));
                d.setBillboard(Display.Billboard.FIXED);
                d.setPersistent(false);
                d.setShadowed(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTeleportDuration(1);
                d.setGlowing(true);
                d.setGlowColorOverride(VIOLET);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) -Math.PI / 2, 1, 0, 0), new Vector3f(0.1f), new AxisAngle4f()));
            }));
        }
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = ++t[0];
            boolean broken = c.damageTaken >= 6;
            if (c.cancelled || broken || !p.isOnline() || p.isDead()) {
                task.cancel();
                c.runes.forEach(Entity::remove);
                fadeLane(c.lane, -1, true);
                casting.remove(id);
                if (p.isOnline() && broken) {
                    fizzleMsg(p, "Your concentration broke. The rite wasn't read.");
                    w.spawnParticle(Particle.SMOKE, p.getLocation().add(0, 0.2, 0), 30, 0.8, 0.1, 0.8, 0.02);
                }
                return;
            }
            double prog = n / (double) WINDUP_TICKS;
            hold(p, origin);   // rooted while the rite is read (looking around is free)
            Location base = origin;
            double radius = 0.6 + prog * 1.7, spin = n * 0.32;
            for (int i = 0; i < c.runes.size(); i++) {
                double ang = spin + i * Math.PI * 2 / c.runes.size();
                Location at = base.clone().add(radius * Math.cos(ang), 0.06 + prog * 0.25, radius * Math.sin(ang));
                at.setYaw((float) Math.toDegrees(-ang) - 90);
                at.setPitch(0);
                TextDisplay d = c.runes.get(i);
                d.teleport(at);
                if (n == 1 || n == 10) {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(9);
                    d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f((float) -Math.PI / 2, 1, 0, 0), new Vector3f(n == 1 ? 1.6f : 2.4f), new AxisAngle4f()));
                }
                if (n % 2 == 0) w.spawnParticle(Particle.SCULK_SOUL, at.clone().add(0, 0.2, 0), 1, 0.05, 0.05, 0.05, 0.02 + prog * 0.04);
            }
            int pts = 28;
            for (int i = 0; i < pts; i++) {
                double ang = -spin * 0.6 + i * Math.PI * 2 / pts;
                w.spawnParticle(Particle.DUST, base.getX() + (radius + 0.35) * Math.cos(ang), base.getY() + 0.08, base.getZ() + (radius + 0.35) * Math.sin(ang),
                        1, 0, 0, 0, 0, new Particle.DustOptions(i % 4 == 0 ? BONE : VIOLET, 0.9f));
            }
            w.spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0, 1, 0), 6, 0.3, 0.6, 0.3, 0.05);
            if (n % 4 == 0) w.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.9f, (float) (0.5 + prog * 1.3));
            revealLane(w, c.lane, n);
            if (n >= WINDUP_TICKS) {
                task.cancel();
                // the ring snaps outward and the runes burst
                for (TextDisplay d : c.runes) {
                    w.spawnParticle(Particle.DUST, d.getLocation(), 6, 0.15, 0.1, 0.15, 0, new Particle.DustOptions(VIOLET, 1.4f));
                    d.remove();
                }
                casting.remove(id);
                launch(p, origin, yaw, c.lane);
            }
        }, 1L, 1L);
    }

    // ------------------------------------------------------------------ the highlighted lane

    private static final double ROW = 1.0, EDGE = 0.35;           // strip depth along the lane, amethyst edge width
    // ground tops searched, relative to the caster's feet: one step up, three down. The wall stays at the caster's
    // height (it can't catch anyone standing lower than that), so the lane doesn't climb cliffs or follow drops either.
    private static final int[] GROUND_SEARCH = {-1, 0, -2, 1, -3};

    /**
     * One row per block down the lane. Each row samples ten points across the lane's width and sits on the most common
     * ground height among them, so a lone flower pot, stump or hole doesn't make the ribbon jump. Leaves are never
     * ground (no more strips floating in trees), and only ground the wall can actually catch someone on counts.
     * Rows with too little ground under them (a drop, the void) are left out; a single row that pokes up or down
     * between two level neighbours is flattened to match them.
     */
    private static List<Row> laneRows(World w, double ox, double oy, double oz, double[] f) {
        double[] side = {-f[1], f[0]};
        int feet = (int) Math.floor(oy + 0.01);
        List<Row> raw = new ArrayList<>();
        for (double a = RiteSweep.START + ROW / 2; a <= RiteSweep.START + RiteSweep.RANGE; a += ROW) {
            Map<Integer, Integer> votes = new HashMap<>();
            int found = 0;
            for (double s = -RiteSweep.WIDTH / 2 + 0.25; s < RiteSweep.WIDTH / 2; s += 0.5) {
                int x = (int) Math.floor(ox + f[0] * a + side[0] * s), z = (int) Math.floor(oz + f[1] * a + side[1] * s);
                int y = groundAt(w, x, z, feet);
                if (y == Integer.MIN_VALUE) continue;
                votes.merge(y, 1, Integer::sum);
                found++;
            }
            if (found < 4) { raw.add(null); continue; }
            int best = Integer.MIN_VALUE, bestVotes = -1;
            for (var e : votes.entrySet()) {   // most votes; a tie goes to the height nearest the caster's ground
                int y = e.getKey(), v = e.getValue();
                if (v > bestVotes || (v == bestVotes && Math.abs(y - (feet - 1)) < Math.abs(best - (feet - 1)))) { best = y; bestVotes = v; }
            }
            raw.add(new Row(a, best));
        }
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Row r = raw.get(i);
            if (r == null) continue;
            Row prev = i > 0 ? raw.get(i - 1) : null, next = i + 1 < raw.size() ? raw.get(i + 1) : null;
            if (prev != null && next != null && prev.y() == next.y() && r.y() != prev.y()) r = new Row(r.along(), prev.y());
            out.add(r);
        }
        return out;
    }

    /** The top of the ground in this column near the caster's feet, or MIN_VALUE if there's none in reach. */
    private static int groundAt(World w, int x, int z, int feet) {
        for (int off : GROUND_SEARCH) {
            int y = feet + off;
            var b = w.getBlockAt(x, y, z);
            if (b.isSolid() && !Tag.LEAVES.isTagged(b.getType()) && !w.getBlockAt(x, y + 1, z).isSolid()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** A thin strip in the lane's own frame: {@code width} across (centred on {@code x}), height h, one row deep. */
    private static Transformation strip(float width, float x, float h) {
        return new Transformation(new Vector3f(x - width / 2, 0.005f, (float) -ROW / 2), new AxisAngle4f(),
                new Vector3f(width, h, (float) ROW), new AxisAngle4f());
    }

    private static final float MID_W = (float) (RiteSweep.WIDTH - 2 * EDGE), EDGE_X = (float) (RiteSweep.WIDTH / 2 - EDGE / 2);

    /** The lane draws itself out from the caster's feet over the windup: each row unrolls sideways from the centre line. */
    private void revealLane(World w, Lane lane, int n) {
        if (lane == null) return;
        int span = WINDUP_TICKS - 5;
        int want = (int) Math.min(lane.rows.size(), Math.ceil(lane.rows.size() * Math.min(1.0, n / (double) span)));
        for (int i = lane.strips.size(); i < want; i++) {
            Row r = lane.rows.get(i);
            Location at = new Location(w, lane.ox + lane.f[0] * r.along(), r.y() + 1, lane.oz + lane.f[1] * r.along(), lane.yaw, 0);
            BlockDisplay mid = stripDisplay(w, at, Material.PURPLE_STAINED_GLASS, strip(0.2f, 0, 0.01f));
            BlockDisplay left = stripDisplay(w, at, Material.AMETHYST_BLOCK, strip(0.01f, 0, 0.01f));
            BlockDisplay right = stripDisplay(w, at, Material.AMETHYST_BLOCK, strip(0.01f, 0, 0.01f));
            lane.strips.add(new BlockDisplay[]{mid, left, right});
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                grow(mid, strip(MID_W, 0, 0.05f), 4);
                grow(left, strip((float) EDGE, -EDGE_X, 0.07f), 4);
                grow(right, strip((float) EDGE, EDGE_X, 0.07f), 4);
            }, 1L);
            if (i % 2 == 0) w.spawnParticle(Particle.DUST, at.clone().add(0, 0.15, 0), 3, 0.8, 0.05, 0.3, 0, new Particle.DustOptions(VIOLET, 1.0f));
            if (i % 5 == 0) w.playSound(at, Sound.BLOCK_SCULK_SPREAD, SoundCategory.PLAYERS, 1.0f, 0.7f);
        }
        // the lane's two long edges shimmer while it waits
        if (n > span && n % 2 == 0) {
            double[] side = {-lane.f[1], lane.f[0]};
            for (Row r : lane.rows)
                for (int e = -1; e <= 1; e += 2)
                    if (rnd.nextInt(4) == 0) {
                        double s = e * (RiteSweep.WIDTH / 2 - EDGE / 2);
                        w.spawnParticle(Particle.END_ROD, lane.ox + lane.f[0] * r.along() + side[0] * s, r.y() + 1.2,
                                lane.oz + lane.f[1] * r.along() + side[1] * s, 1, 0.05, 0.05, 0.3, 0.005);
                    }
        }
    }

    private BlockDisplay stripDisplay(World w, Location at, Material m, Transformation start) {
        BlockData look = m.createBlockData();
        BlockDisplay d = w.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(look);
            e.setPersistent(false);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(start);
        });
        live.add(d);
        return d;
    }

    private static void grow(Display d, Transformation to, int ticks) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(to);
    }

    /** Pulls a row's strips in to the centre line, then removes them. */
    private void closeRow(BlockDisplay[] row, long delay, int ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            grow(row[0], strip(0.02f, 0, 0.01f), ticks);
            grow(row[1], strip(0.01f, 0, 0.01f), ticks);
            grow(row[2], strip(0.01f, 0, 0.01f), ticks);
        }, delay);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { for (BlockDisplay d : row) { d.remove(); live.remove(d); } }, delay + ticks + 2);
    }

    /** As the wall reaches a row it flares white, then closes in to the centre line behind the wall. */
    private void flareLane(World w, Lane lane, double upTo) {
        if (lane == null) return;
        for (int i = 0; i < lane.strips.size(); i++) {
            if (lane.rows.get(i).along() > upTo || !lane.flared.add(i)) continue;
            BlockDisplay[] row = lane.strips.get(i);
            BlockDisplay mid = row[0];
            if (!mid.isValid()) continue;
            mid.setBlock(Material.WHITE_STAINED_GLASS.createBlockData());
            grow(mid, strip(MID_W, 0, 0.12f), 2);
            if (rnd.nextInt(3) == 0) w.spawnParticle(Particle.SCULK_SOUL, mid.getLocation().add(0, 0.2, 0), 1, 0.6, 0.05, 0.2, 0.02);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (mid.isValid()) mid.setBlock(Material.PURPLE_STAINED_GLASS.createBlockData()); }, 4L);
            closeRow(row, 4L, 10);
        }
    }

    /** Whatever is left of the lane past {@code from} closes up, the far end last (all of it when from < 0). */
    private void fadeLane(Lane lane, double from, boolean quick) {
        if (lane == null) return;
        for (int i = 0; i < lane.strips.size(); i++) {
            double a = lane.rows.get(i).along();
            if (lane.flared.contains(i) || a < from) continue;
            closeRow(lane.strips.get(i), quick ? 1 : 1 + (long) ((a - Math.max(0, from)) * 0.6), 6);
        }
    }

    /**
     * Through walls: every full, solid block (not leaves or see-through blocks) inside the wall's slab this tick is outlined by a violet-glowing ghost of itself
     * that swells and dissolves - you can watch the wave pass through a house from outside it.
     */
    private void ghostBlocks(World w, Lane lane, double ox, double oy, double oz, double[] f, double from, double to) {
        if (lane == null) return;
        double[] side = {-f[1], f[0]};
        int y0 = (int) Math.floor(oy + 0.01), y1 = (int) Math.floor(oy - RiteSweep.BELOW + RiteSweep.HEIGHT - 0.01);
        int made = 0;
        for (double a = from; a <= to && made < 40; a += 0.4)
            for (double s = -RiteSweep.WIDTH / 2; s <= RiteSweep.WIDTH / 2 && made < 40; s += 0.4) {
                int x = (int) Math.floor(ox + f[0] * a + side[0] * s), z = (int) Math.floor(oz + f[1] * a + side[1] * s);
                for (int y = y0; y <= y1 && made < 40; y++) {
                    long key = ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
                    if (lane.ghosted.contains(key)) continue;
                    var b = w.getBlockAt(x, y, z);
                    // only real walls: leaves, glass, fences, slabs and the like made big flat purple patches
                    if (!b.getType().isOccluding() || Tag.LEAVES.isTagged(b.getType())) continue;
                    lane.ghosted.add(key);
                    made++;
                    BlockData data = b.getBlockData().clone();
                    BlockDisplay g = w.spawn(b.getLocation(), BlockDisplay.class, d -> {
                        d.setBlock(data);
                        d.setPersistent(false);
                        d.setGlowing(true);
                        d.setGlowColorOverride(VIOLET);
                        d.setBrightness(new Display.Brightness(15, 15));
                        d.setTransformation(new Transformation(new Vector3f(-0.02f, -0.02f, -0.02f), new AxisAngle4f(), new Vector3f(1.04f), new AxisAngle4f()));
                    });
                    live.add(g);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> grow(g, new Transformation(new Vector3f(-0.1f, -0.1f, -0.1f), new AxisAngle4f(), new Vector3f(1.2f), new AxisAngle4f()), 3), 1L);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> grow(g, new Transformation(new Vector3f(0.5f, 0.5f, 0.5f), new AxisAngle4f(), new Vector3f(0.001f), new AxisAngle4f()), 8), 5L);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { g.remove(); live.remove(g); }, 14L);
                    w.spawnParticle(Particle.DUST, x + 0.5, y + 0.5, z + 0.5, 3, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(VIOLET, 1.2f));
                }
            }
        if (made > 0 && rnd.nextInt(3) == 0)
            w.playSound(new Location(w, ox + f[0] * to, oy + 1, oz + f[1] * to), Sound.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.PLAYERS, 0.8f, 0.5f);
    }

    // ------------------------------------------------------------------ the wave

    /**
     * The wall: one glass block display stretched to 5 x 4 and glowing violet (seen through walls), sliding forward
     * smoothly, with a crackling particle face, a scorched soul trail on the ground and a sound that rolls past
     * everyone it passes. Through blocks, 30 blocks in 1.5 s.
     */
    private void launch(Player p, Location origin, float yaw, Lane lane) {
        World w = origin.getWorld();
        double[] f = RiteSweep.facing(yaw);
        Vector fwd = new Vector(f[0], 0, f[1]), side = new Vector(-f[1], 0, f[0]);
        double ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        if (!testing) for (ItemStack it : p.getInventory().getContents()) if (isRite(it) && charged(it)) { setCharges(it, 0); break; }   // spent; refunded to 1 on a miss

        w.playSound(origin, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 1.4f, 0.6f);
        w.playSound(origin, Sound.ITEM_TRIDENT_RIPTIDE_3, SoundCategory.PLAYERS, 1.0f, 0.7f);
        w.spawnParticle(Particle.SONIC_BOOM, origin.clone().add(fwd.clone().multiply(1.2)).add(0, 1.2, 0), 1);
        w.spawnParticle(Particle.DUST, origin.clone().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(DEEP, 1.6f));

        BlockData glass = Material.PURPLE_STAINED_GLASS.createBlockData();
        Location start = wallAt(origin, fwd, RiteSweep.reach(0), yaw);
        BlockDisplay wall = w.spawn(start, BlockDisplay.class, d -> {
            live.add(d);
            d.setBlock(glass);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(VIOLET);
            d.setViewRange(2.5f);
            d.setTransformation(new Transformation(new Vector3f((float) -RiteSweep.WIDTH / 2, 0, -0.06f), new AxisAngle4f(),
                    new Vector3f(0.4f, 0.4f, 0.12f), new AxisAngle4f()));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {   // unfold to full size in 3 ticks
            if (!wall.isValid()) return;
            wall.setInterpolationDelay(0);
            wall.setInterpolationDuration(3);
            wall.setTransformation(new Transformation(new Vector3f((float) -RiteSweep.WIDTH / 2, 0, -0.06f), new AxisAngle4f(),
                    new Vector3f((float) RiteSweep.WIDTH, (float) RiteSweep.HEIGHT, 0.12f), new AxisAngle4f()));
        }, 1L);

        UUID casterId = p.getUniqueId();
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = ++t[0];
            Player caster = Bukkit.getPlayer(casterId);
            double from = RiteSweep.reach(n - 1), to = RiteSweep.reach(n);
            Location at = wallAt(origin, fwd, to, yaw);
            if (jammedWaves.remove(casterId)) { task.cancel(); waves.remove(casterId); dissolve(wall); fadeLane(lane, -1, true); return; }
            waves.put(casterId, at);
            if (wall.isValid()) wall.teleport(at);
            waveFx(w, at, fwd, side, n, oy);
            flareLane(w, lane, to);
            ghostBlocks(w, lane, ox, oy, oz, f, from, to);
            if (caster == null || !caster.isOnline() || caster.isDead() || caster.getWorld() != w) { task.cancel(); waves.remove(casterId); dissolve(wall); fadeLane(lane, -1, true); return; }
            LivingEntity hit = firstCaught(caster, w, ox, oy, oz, f, from, to);
            if (hit != null) {
                task.cancel();
                waves.remove(casterId);
                shatter(wall, at);
                fadeLane(lane, to, false);
                seal(caster, hit);
                return;
            }
            if (n >= RiteSweep.travelTicks()) {
                task.cancel();
                waves.remove(casterId);
                dissolve(wall);
                fadeLane(lane, -1, false);
                miss(caster);
            }
        }, 1L, 1L);
    }

    private static Location wallAt(Location origin, Vector fwd, double dist, float yaw) {
        Location l = origin.clone().add(fwd.clone().multiply(dist));
        l.setY(origin.getY() - RiteSweep.BELOW);
        l.setYaw(yaw);
        l.setPitch(0);
        return l;
    }

    private void waveFx(World w, Location at, Vector fwd, Vector side, int n, double oy) {
        // crackling face of the wall
        for (int i = 0; i < 22; i++) {
            double s = (rnd.nextDouble() - 0.5) * RiteSweep.WIDTH, h = rnd.nextDouble() * RiteSweep.HEIGHT;
            Location pt = at.clone().add(side.clone().multiply(s)).add(0, h, 0).add(fwd.clone().multiply(0.1));
            w.spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0, new Particle.DustOptions(i % 5 == 0 ? BONE : i % 2 == 0 ? VIOLET : DEEP, 1.3f));
        }
        // bright edges
        for (int e = -1; e <= 1; e += 2) {
            Location edge = at.clone().add(side.clone().multiply(e * RiteSweep.WIDTH / 2));
            for (int k = 0; k < 4; k++) w.spawnParticle(Particle.END_ROD, edge.clone().add(0, rnd.nextDouble() * RiteSweep.HEIGHT, 0), 1, 0, 0, 0, 0);
        }
        w.spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, RiteSweep.HEIGHT / 2, 0), 8, RiteSweep.WIDTH / 3, 1.2, 0.1, 0.02);
        if (n % 2 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0, RiteSweep.HEIGHT / 2, 0), 4, RiteSweep.WIDTH / 3, 1.2, 0.1, 0.1);
        // scorched soul trail along the ground
        Location ground = at.clone();
        ground.setY(oy + 0.1);
        for (int i = 0; i < 4; i++) w.spawnParticle(Particle.SOUL, ground.clone().add(side.clone().multiply((rnd.nextDouble() - 0.5) * RiteSweep.WIDTH)), 1, 0, 0.02, 0, 0.01);
        w.spawnParticle(Particle.SCULK_SOUL, ground, 2, RiteSweep.WIDTH / 3, 0.05, 0.1, 0.01);
        if (n % 4 == 1) w.playSound(at, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, SoundCategory.PLAYERS, 0.8f, 1.5f);
        if (n % 6 == 3) w.playSound(at, Sound.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 1.0f, 0.5f);
    }

    /** Nearest eligible player (or practice target) the wall swept over this tick. */
    private LivingEntity firstCaught(Player caster, World w, double ox, double oy, double oz, double[] f, double from, double to) {
        Location mid = new Location(w, ox + f[0] * to, oy + 1, oz + f[1] * to);
        LivingEntity best = null;
        double bestAlong = Double.MAX_VALUE;
        for (Entity e : w.getNearbyEntities(mid, RiteSweep.WIDTH / 2 + 1.5, RiteSweep.HEIGHT + 2, RiteSweep.WIDTH / 2 + 1.5)) {
            if (!(e instanceof LivingEntity le) || e == caster || e.isDead()) continue;
            Location l = e.getLocation();
            if (!RiteSweep.touches(ox, oy, oz, f, from, to, l.getX(), l.getY(), l.getZ())) continue;
            if (!eligible(caster, le)) continue;
            double a = RiteSweep.along(ox, oz, f, l.getX(), l.getZ());
            if (a < bestAlong) { bestAlong = a; best = le; }
        }
        return best;
    }

    private boolean eligible(Player caster, LivingEntity t) {
        if (practiceTarget.test(t)) return chambers.hasFreeSlot();
        if (!(t instanceof Player p)) return false;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return false;
        if (!caster.canSee(p) || chambers.inRite(p) || sealing.contains(p.getUniqueId()) || casting.containsKey(p.getUniqueId())) return false;
        if (chambers.immune(p)) return false;
        // the normal PvP rules decide: spawn, claims, PvP-off zones and allies cancel this probe like a real hit
        DamageSource src = DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(caster).withDirectEntity(caster).build();
        @SuppressWarnings("removal") EntityDamageByEntityEvent probe = new EntityDamageByEntityEvent(caster, p, EntityDamageEvent.DamageCause.ENTITY_ATTACK, src, 1.0);
        Bukkit.getPluginManager().callEvent(probe);
        return !probe.isCancelled();
    }

    private void dissolve(BlockDisplay wall) {
        if (!wall.isValid()) return;
        wall.setInterpolationDelay(0);
        wall.setInterpolationDuration(6);
        wall.setTransformation(new Transformation(new Vector3f(0, (float) RiteSweep.HEIGHT / 2, 0), new AxisAngle4f(), new Vector3f(0.01f, 0.01f, 0.01f), new AxisAngle4f()));
        wall.getWorld().spawnParticle(Particle.DUST, wall.getLocation().add(0, RiteSweep.HEIGHT / 2, 0), 40, RiteSweep.WIDTH / 3, 1.2, 0.2, 0, new Particle.DustOptions(DEEP, 1.2f));
        Bukkit.getScheduler().runTaskLater(plugin, () -> { wall.remove(); live.remove(wall); }, 7L);
    }

    private void shatter(BlockDisplay wall, Location at) {
        World w = at.getWorld();
        w.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.2f, 0.5f);
        w.spawnParticle(Particle.BLOCK, at.clone().add(0, RiteSweep.HEIGHT / 2, 0), 80, RiteSweep.WIDTH / 3, 1.2, 0.2, 0.2, Material.PURPLE_STAINED_GLASS.createBlockData());
        w.spawnParticle(Particle.DUST, at.clone().add(0, RiteSweep.HEIGHT / 2, 0), 60, RiteSweep.WIDTH / 3, 1.2, 0.3, 0, new Particle.DustOptions(VIOLET, 1.6f));
        wall.remove();
        live.remove(wall);
    }

    /** Nobody caught: the scroll keeps one charge and cools down for 30 s. */
    private void miss(Player p) {
        fizzleMsg(p, "The rite found no one. It keeps one charge.");
        if (testing) return;
        readyAt.put(p.getUniqueId(), System.currentTimeMillis() + 30_000L);
        for (ItemStack it : p.getInventory().getContents()) if (isRite(it) && charges(it) == 0) { setCharges(it, Math.min(1, chargesNeeded)); break; }
    }

    // ------------------------------------------------------------------ the seal

    /**
     * Caught: both are frozen, a violet chain snaps taut between them, the screen goes dark, the bell tolls three
     * times and an elder guardian's curse rings in the target's ears. Lightning falls on both, and they're gone.
     */
    private void seal(Player caster, LivingEntity foe) {
        World w = caster.getWorld();
        boolean practice = !(foe instanceof Player);
        Player target = practice ? null : (Player) foe;
        UUID cid = caster.getUniqueId();
        sealing.add(cid);
        if (target != null) sealing.add(target.getUniqueId());
        Location cHold = caster.getLocation(), tHold = foe.getLocation();
        Location castSpot = cHold.clone();

        Title.Times times = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1400), Duration.ofMillis(500));
        Component title = Ui.mm("<gradient:#7A0F2A:#B66CFF:#7A0F2A><bold>LAST RITE</bold></gradient>");
        caster.showTitle(Title.title(title, Ui.mm("<gray>Only one of you walks out."), times));
        caster.playSound(caster.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 0.7f, 0.8f);
        if (target != null) {
            target.showTitle(Title.title(title, Ui.mm("<white>" + caster.getName() + " <gray>reads your last rite."), times));
            target.playSound(target.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 1.0f, 0.7f);
            target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, false));
        }
        caster.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, false, false, false));
        if (foe instanceof Mob m) m.setAware(false);

        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = t[0]++;
            boolean casterOk = caster.isOnline() && !caster.isDead() && caster.getWorld() == w;
            boolean foeOk = foe.isValid() && !foe.isDead() && foe.getWorld() == w && (target == null || target.isOnline());
            if (!casterOk || !foeOk) {
                task.cancel();
                sealing.remove(cid);
                if (target != null) sealing.remove(target.getUniqueId());
                if (foe instanceof Mob m) m.setAware(true);
                return;
            }
            // frozen in place (looking is free)
            hold(caster, cHold);
            hold(foe, tHold);
            chain(w, caster.getLocation().add(0, 1.1, 0), foe.getLocation().add(0, 1.1, 0), n);
            if (n == 0 || n == 5 || n == 10) {   // three tolls
                w.playSound(castSpot, Sound.BLOCK_BELL_USE, SoundCategory.PLAYERS, 2.0f, 0.5f);
                w.playSound(castSpot, Sound.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 1.0f, 0.5f);
                for (LivingEntity e : List.of(caster, foe))
                    w.spawnParticle(Particle.SCULK_SOUL, e.getLocation().add(0, 1, 0), 12, 0.4, 0.7, 0.4, 0.04);
            }
            if (n == SEAL_TICKS - 2) {
                w.strikeLightningEffect(caster.getLocation());
                w.strikeLightningEffect(foe.getLocation());
                for (LivingEntity e : List.of(caster, foe)) {
                    w.spawnParticle(Particle.DUST, e.getLocation().add(0, 1, 0), 50, 0.5, 1, 0.5, 0, new Particle.DustOptions(INK, 2.0f));
                    w.spawnParticle(Particle.REVERSE_PORTAL, e.getLocation().add(0, 1, 0), 60, 0.4, 0.9, 0.4, 0.2);
                }
            }
            if (n >= SEAL_TICKS) {
                task.cancel();
                sealing.remove(cid);
                if (target != null) sealing.remove(target.getUniqueId());
                if (foe instanceof Mob m) m.setAware(true);
                if (!testing) {   // used up: take the scroll
                    ItemStack[] inv = caster.getInventory().getContents();
                    for (int i = 0; i < inv.length; i++) if (isRite(inv[i])) { caster.getInventory().setItem(i, null); break; }
                    readyAt.put(cid, System.currentTimeMillis() + cooldownSeconds * 1000L);
                }
                if (!chambers.begin(caster, target, castSpot, practice))
                    fizzleMsg(caster, "The chamber wouldn't open. The rite fades.");
            }
        }, 1L, 1L);
    }

    private static void hold(LivingEntity e, Location at) {
        Location l = at.clone();
        l.setYaw(e.getLocation().getYaw());
        l.setPitch(e.getLocation().getPitch());
        if (e.getLocation().distanceSquared(at) > 0.01) e.teleport(l);
        e.setVelocity(new Vector());
        e.setFallDistance(0);
    }

    /** A violet-and-black chain with a slow ripple, drawn link by link between two chests. */
    private void chain(World w, Location a, Location b, int n) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.1) return;
        Vector u = d.clone().normalize();
        Vector perp = u.clone().crossProduct(new Vector(0, 1, 0));
        if (perp.lengthSquared() < 1e-6) perp = new Vector(1, 0, 0);
        perp.normalize();
        int links = (int) Math.min(120, len * 4);
        for (int i = 0; i <= links; i++) {
            double s = i / (double) links;
            double wave = Math.sin(s * Math.PI * 6 - n * 0.9) * 0.18 * (1 - Math.abs(s - 0.5) * 2) * Math.max(0, 1 - n / 12.0);
            Location pt = a.clone().add(u.clone().multiply(s * len)).add(perp.clone().multiply(wave));
            w.spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0, new Particle.DustOptions((i + n) % 3 == 0 ? INK : VIOLET, 1.1f));
        }
    }
}
