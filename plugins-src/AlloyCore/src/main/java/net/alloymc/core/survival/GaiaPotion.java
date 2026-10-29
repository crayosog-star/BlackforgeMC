package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * Potion of Gaia: a pink splash potion. Where it lands, a mist grows slowly then faster, blooms, and rises as
 * an 8-block platform carrying whoever stands on it, then fades with Slow Falling for its riders.
 * Timing lives in {@link BloomCurve}; one running cloud is a {@link GaiaPlatform}.
 *
 * Safety: never in spawn or someone else's claim; the floor only fills air or plants (grass, flowers) and puts back
 * exactly what was there;
 * every barrier is logged to gaia-barriers.txt and removed on shutdown and again on the next start.
 */
public final class GaiaPotion implements Listener, Hackable {
    public static final String NAME = "<gradient:#FFB3DE:#B48CFF><bold>Potion of Gaia</bold></gradient>";
    public static final int COOLDOWN_SECONDS = 20;
    private static final int MAX_ACTIVE = 6;          // per server, so a mass-throw can't flood entities
    private static final double MIN_SPACING = 11;     // centre to centre: two clouds can't stack into a tower
    private static final int MIN_CELLS = 15;          // need a reasonably flat spot

    private final AlloyCore plugin;
    private final boolean enabled, customModel;
    private final NamespacedKey tagKey, cooldownGroup, modelKey;
    private final BiPredicate<Player, Location> mayUse;   // spawn + claims rules, supplied by the server module
    private final java.util.function.Predicate<Location> testArea;   // the hub Arena: refilled potions, new cloud replaces your old one
    private final List<GaiaPlatform> active = new ArrayList<>();
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private final File barrierLog;

    public GaiaPotion(AlloyCore plugin, BiPredicate<Player, Location> mayUse) { this(plugin, mayUse, l -> false); }

    /**
     * {@code testArea}: where potions are refilled for free (the hub Arena). There a failed throw isn't refunded (the
     * refill already replaced it, so a refund would duplicate it) and a new throw replaces your old cloud.
     */
    public GaiaPotion(AlloyCore plugin, BiPredicate<Player, Location> mayUse, java.util.function.Predicate<Location> testArea) {
        this.plugin = plugin;
        this.mayUse = mayUse;
        this.testArea = testArea;
        this.enabled = plugin.getConfig().getBoolean("gaia-potion.enabled", true);
        this.tagKey = new NamespacedKey(plugin, "gaia_potion");
        this.cooldownGroup = new NamespacedKey("blackforge", "gaia_potion");
        this.modelKey = new NamespacedKey("blackforge", "gaia_potion");   // the resource pack's item model
        // only with the resource pack: without it the model id renders as the purple/black missing model
        this.customModel = plugin.getConfig().getBoolean("gaia-potion.custom-model", plugin.getConfig().getBoolean("resource-pack-live", false));
        this.barrierLog = new File(plugin.getDataFolder(), "gaia-barriers.txt");
        cleanLeftovers();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    // ------------------------------------------------------------------ the item

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.SPLASH_POTION);
        it.editMeta(PotionMeta.class, m -> {
            m.setColor(GaiaPlatform.PETAL_PINK);   // pink bottle with no resource pack
            if (customModel) m.setItemModel(modelKey);   // pack texture, also used for the potion in flight
            m.displayName(Ui.mm(NAME));
            m.lore(List.of(
                    Ui.mm("<gray>Throw it to grow a cloud of mist"),
                    Ui.mm("<gray>It blooms, then rises <white>8 blocks"),
                    Ui.mm("<gray>carrying anyone standing on it"),
                    Ui.mm("<gray>and slowly <white>heals<gray> them while they ride"),
                    Ui.mm(""),
                    Ui.mm("<gray>Cooldown: <white>" + COOLDOWN_SECONDS + "s"),
                    Ui.mm("<dark_gray>BlackForgeMC item")));
            m.addItemFlags(ItemFlag.values());   // hide the vanilla "No Effects" line
            UseCooldownComponent cd = m.getUseCooldown();
            cd.setCooldownSeconds(COOLDOWN_SECONDS);
            cd.setCooldownGroup(cooldownGroup);   // greys out only this potion, not every splash potion
            m.setUseCooldown(cd);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    public boolean isGaia(ItemStack it) {
        return it != null && it.getType() == Material.SPLASH_POTION && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    public void give(Player p) {
        p.getInventory().addItem(item()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<#FFB3DE>✿ <gray>You received a " + NAME + "<gray>! Throw it and stand in the mist."));
    }

    // ------------------------------------------------------------------ throwing

    /** Stop the throw while it's on cooldown or switched off (the vanilla cooldown covers most cases; this is the backstop). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onThrow(PlayerInteractEvent e) {
        Action a = e.getAction();
        if ((a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) || !isGaia(e.getItem())) return;
        Player p = e.getPlayer();
        if (!enabled) { e.setCancelled(true); p.sendActionBar(Ui.mm("<gray>The Potion of Gaia isn't switched on yet.")); return; }
        long left = readyAt.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        if (left > 0) {
            e.setCancelled(true);
            p.sendActionBar(Ui.mm("<#FFB3DE>✿ <gray>Gaia is still gathering... <white>" + (left / 1000 + 1) + "s"));
        }
    }

    /** The cooldown starts when the potion actually leaves your hand (not on every right-click, e.g. opening a chest). */
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof ThrownPotion pot && isGaia(pot.getItem()) && pot.getShooter() instanceof Player p)
            readyAt.put(p.getUniqueId(), System.currentTimeMillis() + COOLDOWN_SECONDS * 1000L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        ThrownPotion pot = e.getPotion();
        if (!isGaia(pot.getItem())) return;
        for (var hit : e.getAffectedEntities()) e.setIntensity(hit, 0);   // it has no effects; make sure of it
        if (!(pot.getShooter() instanceof Player p)) return;
        Location at = e.getHitBlock() != null && e.getHitBlockFace() != null
                ? e.getHitBlock().getRelative(e.getHitBlockFace()).getLocation()
                : pot.getLocation();
        start(p, at);
    }

    private void start(Player p, Location at) {
        World w = at.getWorld();
        if (active.size() >= MAX_ACTIVE) { fizzle(p, at, "Too many clouds are up right now - try again in a moment."); return; }
        for (Iterator<GaiaPlatform> it = active.iterator(); it.hasNext(); ) {
            GaiaPlatform g = it.next();
            if (g.fading()) continue;   // shattering: already on its way out
            if (g.owner.equals(p.getUniqueId())) {
                if (testArea.test(at)) { g.remove(); it.remove(); saveLog(); continue; }   // Arena: the new cloud replaces your old one
                fizzle(p, at, "Your last cloud is still up."); return;
            }
            double dx = g.cx + 0.5 - at.getX(), dz = g.cz + 0.5 - at.getZ();
            if (g.world == w && dx * dx + dz * dz < MIN_SPACING * MIN_SPACING) {
                fizzle(p, at, "Too close to another cloud."); return;
            }
        }
        Integer ground = groundAt(w, at.getBlockX(), at.getBlockY(), at.getBlockZ());
        if (ground == null) { fizzle(p, at, "The mist needs solid ground to grow on."); return; }
        int cx = at.getBlockX(), cz = at.getBlockZ(), gy = ground;

        List<GaiaPlatform.Cell> cells = new ArrayList<>();
        double cap = BloomCurve.MAX_RISE;
        int span = (int) Math.ceil(BloomCurve.RADIUS);
        for (int dx = -span; dx <= span; dx++)
            for (int dz = -span; dz <= span; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > BloomCurve.RADIUS) continue;
                Block base = w.getBlockAt(cx + dx, gy, cz + dz);
                if (!GaiaPlatform.open(base)) continue;   // a bump in the terrain: not part of the cloud (grass and flowers count as open)
                if (!mayUse.test(p, base.getLocation())) { fizzle(p, at, "Gaia can't grow here: not in spawn, someone else's claim, or the hub outside the Arena."); return; }
                // headroom: the floor layer plus 2 blocks above the surface must be air
                for (int k = 1; k <= (int) BloomCurve.MAX_RISE + 2; k++)
                    if (!GaiaPlatform.open(w.getBlockAt(cx + dx, gy + k, cz + dz))) { cap = Math.min(cap, k - 2); break; }
                cells.add(new GaiaPlatform.Cell(dx, dz, dist));
            }
        if (cells.size() < MIN_CELLS) { fizzle(p, at, "Not enough flat ground here."); return; }
        if (cap < 2) { fizzle(p, at, "Not enough room above to rise."); return; }

        active.add(new GaiaPlatform(plugin, p.getUniqueId(), w, cx, gy, cz, cells, cap));
        Location c = new Location(w, cx + 0.5, gy + 0.2, cz + 0.5);
        w.spawnParticle(Particle.DUST, c, 25, 0.4, 0.2, 0.4, 0, new Particle.DustOptions(GaiaPlatform.PETAL_PINK, 1.4f));
        if (cap < BloomCurve.MAX_RISE) p.sendActionBar(Ui.mm("<#FFB3DE>✿ <gray>Low ceiling - the cloud will only rise <white>" + (int) cap + " <gray>blocks"));
    }

    /** First air layer with solid ground under it, searching a few blocks up and down from where it landed. */
    private static Integer groundAt(World w, int x, int y, int z) {
        for (int up = 0; up <= 3; up++) {
            Block b = w.getBlockAt(x, y + up, z);
            if (GaiaPlatform.open(b)) { y = y + up; break; }
            if (up == 3) return null;
        }
        for (int down = 0; down <= 6; down++) {
            Block b = w.getBlockAt(x, y - down, z), below = b.getRelative(0, -1, 0);
            if (GaiaPlatform.open(b) && below.getType().isSolid()) return y - down;
        }
        return null;
    }

    /**
     * It didn't take: pink smoke, a hiss, and the cooldown is cleared. The potion comes back only if the throw really
     * used it up: not in creative (throwing doesn't use it) and not in the Arena (refilled already) - either would dupe it.
     */
    private void fizzle(Player p, Location at, String why) {
        at.getWorld().spawnParticle(Particle.DUST, at.clone().add(0, 0.3, 0), 20, 0.4, 0.3, 0.4, 0, new Particle.DustOptions(GaiaPlatform.LAVENDER, 1.2f));
        at.getWorld().spawnParticle(Particle.SMOKE, at.clone().add(0, 0.3, 0), 10, 0.3, 0.2, 0.3, 0.01);
        at.getWorld().playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.7f, 1.6f);
        boolean refund = p.getGameMode() != GameMode.CREATIVE && !testArea.test(p.getLocation());
        p.sendMessage(Ui.mm("<#FFB3DE>✿ <gray>" + why + (refund ? " <dark_gray>(potion returned)" : "")));
        readyAt.remove(p.getUniqueId());
        p.setCooldown(item(), 0);
        if (refund) p.getInventory().addItem(item()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    // ------------------------------------------------------------------ running clouds

    private void tickAll() {
        if (active.isEmpty()) return;
        boolean changed = false;
        Iterator<GaiaPlatform> it = active.iterator();
        while (it.hasNext()) {
            GaiaPlatform g = it.next();
            try { g.tick(); }
            catch (Exception ex) { plugin.getLogger().warning("Gaia cloud failed, removing it: " + ex); g.remove(); }
            if (g.barriersChanged) { changed = true; g.barriersChanged = false; }
            if (g.done()) { it.remove(); changed = true; }
        }
        if (changed) saveLog();
    }

    /** Every barrier currently placed by a cloud, so a crash can't leave invisible blocks behind. */
    private void saveLog() {
        List<String> lines = new ArrayList<>();
        for (GaiaPlatform g : active)
            for (var e : g.barriers().entrySet()) {   // the block that was there goes in the log too, so a crash restores grass, not air
                Block b = e.getKey();
                lines.add(b.getWorld().getName() + " " + b.getX() + " " + b.getY() + " " + b.getZ() + " " + e.getValue().getAsString());
            }
        try {
            if (lines.isEmpty()) Files.deleteIfExists(barrierLog.toPath());
            else Files.write(barrierLog.toPath(), lines);
        } catch (Exception ex) { plugin.getLogger().warning("Couldn't save gaia-barriers.txt: " + ex.getMessage()); }
    }

    /** On start: remove any barriers a crash left behind (only if the block is still a barrier). */
    private void cleanLeftovers() {
        if (!barrierLog.exists()) return;
        int n = 0;
        try {
            for (String l : Files.readAllLines(barrierLog.toPath())) {
                String[] s = l.trim().split(" ");
                if (s.length < 4) continue;
                World w = Bukkit.getWorld(s[0]);
                if (w == null) continue;
                Block b = w.getBlockAt(Integer.parseInt(s[1]), Integer.parseInt(s[2]), Integer.parseInt(s[3]));
                if (b.getType() != Material.BARRIER) continue;
                org.bukkit.block.data.BlockData was = null;
                if (s.length >= 5) try { was = Bukkit.createBlockData(s[4]); } catch (IllegalArgumentException ignored) { }
                if (was != null) b.setBlockData(was, false); else b.setType(Material.AIR, false);
                n++;
            }
            Files.deleteIfExists(barrierLog.toPath());
        } catch (Exception ex) { plugin.getLogger().warning("gaia-barriers.txt cleanup failed: " + ex.getMessage()); }
        if (n > 0) plugin.getLogger().info("Removed " + n + " leftover Potion of Gaia barrier blocks");
    }

    /** EMP: any cloud the wave touches collapses at once (not the EMP caster's own). */
    @Override
    public int hack(Location center, double radius, Player caster) {
        int n = 0;
        for (GaiaPlatform g : new ArrayList<>(active)) {
            if (g.fading() || g.owner.equals(caster.getUniqueId()) || !EmpWave.reaches(center, g.centre(), radius, BloomCurve.RADIUS)) continue;
            g.collapse();
            Player owner = Bukkit.getPlayer(g.owner);
            if (owner != null) owner.sendActionBar(Ui.mm("<#B6FF3B>⚡ <gray>Your " + NAME + " <gray>was <white>shut down <gray>by an EMP"));
            n++;
        }
        return n;
    }

    /** Server stopping: take every cloud down now. */
    public void shutdown() {
        for (GaiaPlatform g : active) g.remove();
        active.clear();
        saveLog();
    }
}
