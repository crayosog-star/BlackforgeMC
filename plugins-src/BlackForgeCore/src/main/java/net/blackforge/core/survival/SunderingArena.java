package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.arcade.core.Worlds;
import net.blackforge.core.build.Paster;
import org.bukkit.*;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

import static net.blackforge.core.survival.SunderingMath.*;

/**
 * The Sundering's world: builds the arena once, enforces its rules (no PvP, no building, keep your items) and keeps the five
 * chained bosses. Everything alive here is non-persistent and only exists while someone's in the world or an event is on,
 * so an empty arena costs nothing and a crash can't leave a broken chain behind.
 */
public final class SunderingArena implements Listener {
    public static final String WORLD = "sundering";
    private static final String MARKER = "sundering-built-1", CHAIN_TAG = "sundering_chain";
    private static final float CHAIN_WIDTH = 1.4f;

    /** One boss in its pit. */
    static final class Sleeper {
        final WorldBoss.Boss kind;
        final int pit;
        Mob host;
        BossModel model;
        final List<List<BlockDisplay>> chains = new ArrayList<>();
        Sleeper(WorldBoss.Boss kind, int pit) { this.kind = kind; this.pit = pit; }
    }

    private final BlackForgeCore plugin;
    private final double bossSize;
    private final Map<String, Sleeper> sleepers = new LinkedHashMap<>();
    private final Random rnd = new Random();
    private World world;
    private Containment contain;
    private boolean ready, up, eventOn;

    SunderingArena(BlackForgeCore plugin, double bossSize) {
        this.plugin = plugin;
        this.bossSize = bossSize;
        int i = 0;
        for (WorldBoss.Boss b : WorldBoss.BOSSES) sleepers.put(b.id(), new Sleeper(b, i++));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTask(plugin, this::load);
        Bukkit.getScheduler().runTaskTimer(plugin, this::sync, 40L, 20L);
    }

    private void load() {
        world = Bukkit.getWorld(WORLD);
        if (world == null) world = new WorldCreator(WORLD).generator(new Worlds.Void()).environment(World.Environment.NORMAL)
                .type(WorldType.FLAT).generateStructures(false).createWorld();
        net.blackforge.core.LobbyRules.setupWorld(plugin, world, 12700);   // forever dusk
        world.setDifficulty(Difficulty.NORMAL);
        world.setPVP(false);
        for (Entity e : world.getEntities()) if (!(e instanceof Player)) e.remove();
        // The arena itself is the Dragon's Graveyard, placed into this world by tools/graveyard (centre CX,CZ, floor y=Y).
        contain = new Containment(plugin, world);
        ready = true;
        plugin.getLogger().info("The Sundering: arena ready");
    }

    boolean ready() { return ready; }
    World world() { return world; }
    public Location plaza() { return new Location(world, CX + 0.5, Y + 1, CZ + 8.5, 180f, 0f); }
    /** Where /warp lands: the south end of the bridge, facing the arena. */
    public Location arrival() { return new Location(world, CX + 0.5, Y + 1, CZ + 154.5, 180f, 0f); }
    Location center() { return new Location(world, CX + 0.5, Y + 1, CZ + 0.5); }
    Sleeper sleeper(String id) { return sleepers.get(id); }
    Collection<Sleeper> sleepers() { return sleepers.values(); }

    private Location pitSpot(Sleeper s) {
        int[] c = pit(s.pit);
        Location l = new Location(world, c[0] + 0.5, Y, c[1] + 0.5);
        l.setDirection(center().toVector().subtract(l.toVector()).setY(0));
        return l;
    }

    Location home(Sleeper s) { return pitSpot(s); }

    // ------------------------------------------------------------------ waking the arena up, putting it to sleep

    /** Keep the bosses up during an event, even before anyone arrives. */
    void hold(boolean on) { eventOn = on; if (on) sync(); }

    private void sync() {
        if (!ready) return;
        boolean want = eventOn || !world.getPlayers().isEmpty();
        if (want == up) return;
        up = want;
        if (want) {
            int r = (RADIUS >> 4) + 1;
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) world.addPluginChunkTicket((CX >> 4) + x, (CZ >> 4) + z, plugin);
            for (Sleeper s : sleepers.values()) chain(s, false);
        } else {
            for (Sleeper s : sleepers.values()) clear(s);
            world.removePluginChunkTickets(plugin);
        }
    }

    private void clear(Sleeper s) {
        if (s.model != null) s.model.remove();
        if (s.host != null) s.host.remove();
        s.model = null;
        s.host = null;
        s.chains.forEach(c -> c.forEach(Entity::remove));
        s.chains.clear();
    }

    /** Put this boss back in its pit; {@code forming} fades the chains in. */
    void chain(Sleeper s, boolean forming) {
        clear(s);
        if (up && contain != null) contain.restore(s.pit);
        if (!up) return;
        Location at = pitSpot(s);
        s.host = WorldBoss.spawnHost(at, s.kind, bossSize);
        s.host.setAI(false);
        s.host.setInvulnerable(true);
        s.model = BossModel.of(s.kind.id());
        if (s.model != null) {
            s.model.setSize((float) bossSize);
            s.model.setSlump(1);
            s.model.fixYaw(at.getYaw());
            s.model.attach(plugin, s.host, "boss_part");
        }
        Location chest = at.clone().add(0, 5, 0);
        for (int j = 0; j < CHAINS; j++) s.chains.add(links(j, s.pit, chest, forming));
    }

    private List<BlockDisplay> links(int chain, int pit, Location to, boolean forming) {
        int[] a = anchor(pit, chain);
        Location from = new Location(world, a[0] + 0.5, Y + PILLAR_HEIGHT - 1, a[1] + 0.5);
        Vector along = to.toVector().subtract(from.toVector());
        double dist = along.length();
        int n = (int) Math.ceil(dist / 1.6);
        float len = (float) (dist / n);
        Vector dir = along.clone().normalize();
        Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ()));
        Transformation full = new Transformation(q.transform(new Vector3f(-CHAIN_WIDTH / 2, 0, -CHAIN_WIDTH / 2)), new Quaternionf(q), new Vector3f(CHAIN_WIDTH, len, CHAIN_WIDTH), new Quaternionf());
        List<BlockDisplay> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Location at = from.clone().add(dir.clone().multiply(len * i));
            at.setYaw(0);
            at.setPitch(0);
            BlockDisplay d = world.spawn(at, BlockDisplay.class, x -> {
                x.setBlock(Material.IRON_CHAIN.createBlockData());
                x.setPersistent(false);
                x.setViewRange(2f);
                x.setShadowRadius(0f);
                x.setBrightness(new Display.Brightness(12, 12));
                x.addScoreboardTag(CHAIN_TAG);
                x.setTransformation(forming ? new Transformation(full.getTranslation(), full.getLeftRotation(), new Vector3f(), new Quaternionf()) : full);
            });
            out.add(d);
        }
        if (forming) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (BlockDisplay d : out) if (d.isValid()) { d.setInterpolationDelay(0); d.setInterpolationDuration(20); d.setTransformation(full); }
        }, 2L);
        return out;
    }

    /** One chain breaks: its links scatter and shrink away. */
    void snap(Sleeper s, int chain) {
        if (contain != null) contain.snap(s.pit, chain);
        if (chain >= s.chains.size()) return;
        List<BlockDisplay> links = s.chains.get(chain);
        for (BlockDisplay d : links) {
            if (!d.isValid()) continue;
            Transformation now = d.getTransformation();
            Vector3f flung = new Vector3f(now.getTranslation()).add((float) (rnd.nextGaussian() * 2.5), (float) (rnd.nextDouble() * 3), (float) (rnd.nextGaussian() * 2.5));
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(14);
            d.setTransformation(new Transformation(flung, now.getLeftRotation(), new Vector3f(), new Quaternionf()));
            world.spawnParticle(Particle.ELECTRIC_SPARK, d.getLocation(), 3, 0.2, 0.2, 0.2, 0.05);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> links.forEach(Entity::remove), 16L);
    }

    /** The Frost Titan's iceberg melts as his wake-up runs. */
    void melt(Sleeper s, double f) { if (contain != null) contain.melt(s.pit, f); }

    /** The cage / iceberg goes when the boss bursts out. */
    void breakOut(Sleeper s) { if (contain != null) contain.breakOut(s.pit); }

    /** The woken boss is out of its pit: the fight owns its mob from here. */
    void release(Sleeper s) {
        s.host = null;
        s.model = null;
    }

    // ------------------------------------------------------------------ house rules

    private boolean here(World w) { return world != null && w == world; }
    private static boolean staff(Player p) { return p.hasPermission("blackforge.admin") && p.getGameMode() == GameMode.CREATIVE; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) { if (here(e.getBlock().getWorld()) && !staff(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) { if (here(e.getBlock().getWorld()) && !staff(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) { if (here(e.getEntity().getWorld())) e.blockList().clear(); }

    @EventHandler
    public void onIgnite(BlockIgniteEvent e) { if (here(e.getBlock().getWorld())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (!here(e.getEntity().getWorld())) return;
        e.setKeepInventory(true);
        e.setKeepLevel(true);
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    /** Die in the arena and you come back to the plaza, so you can get back in the fight. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) { if (here(e.getPlayer().getWorld())) e.setRespawnLocation(plaza()); }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!here(e.getTo().getWorld()) || e.getTo().getY() > Y - 30) return;
        Player p = e.getPlayer();
        p.setFallDistance(0);
        p.setVelocity(new Vector());
        p.teleportAsync(plaza());
    }
}
