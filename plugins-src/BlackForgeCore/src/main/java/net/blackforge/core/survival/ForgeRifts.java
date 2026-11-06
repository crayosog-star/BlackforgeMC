package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Forge Rifts (Survival 2 experiment "forge-rifts"): every so often a rift tears open somewhere in the wild and its
 * location is announced. The first player to reach it starts three waves of mobs; clearing the third makes the rift
 * burst, throwing loot into the air for whoever is there (first come, first served) and paying everyone who fought.
 * A rift nobody clears closes by itself. Staff: /rift here | stop.
 */
final class ForgeRifts implements Listener {
    static final String NAME = "<gradient:#ff5a1a:#ffe08a><bold>Forge Rift</bold></gradient>";
    private static final String ID = "forge-rifts";
    private static final int OPEN_TICKS = 40, CLOSE_TICKS = 30, WAVES = 3;
    private static final Color HOT = Color.fromRGB(0xff5a1a), PALE = Color.fromRGB(0xffe08a);

    private enum Stage { WAITING, FIGHTING, BETWEEN, CLOSING }

    private final LabExperiments.Ctx c;
    private final NamespacedKey tag;
    private final long intervalMillis, lifeMillis;
    private final double reward;
    private Location rift;
    private Stage stage;
    private int wave, tick, stageTick;
    private long openedAt, nextAt;
    private final Set<UUID> mobs = new HashSet<>();
    private final Set<UUID> fighters = new HashSet<>();
    private TextDisplay label;
    private BukkitTask task;

    ForgeRifts(LabExperiments.Ctx c) {
        this.c = c;
        this.tag = new NamespacedKey(c.plugin(), "forge_rift");
        var cfg = c.plugin().getConfig();
        intervalMillis = cfg.getLong("lab-experiments.forge-rifts.every-minutes", 90) * 60_000L;
        lifeMillis = cfg.getLong("lab-experiments.forge-rifts.open-minutes", 20) * 60_000L;
        reward = cfg.getDouble("lab-experiments.forge-rifts.reward-money", 400);
        nextAt = System.currentTimeMillis() + cfg.getLong("lab-experiments.forge-rifts.first-after-minutes", 10) * 60_000L;
        Bukkit.getPluginManager().registerEvents(this, c.plugin());
        Bukkit.getScheduler().runTaskTimer(c.plugin(), this::schedule, 600L, 20L * 30);
        LabExperiments.command(c.plugin(), ID, "rift", "Where is the Forge Rift", List.of("rifts"), this::command);
    }

    // ------------------------------------------------------------------ opening

    private void command(CommandSender s, String[] a) {
        if (a.length > 0 && s.hasPermission("blackforge.staff")) {
            if (a[0].equalsIgnoreCase("stop")) { if (rift != null) close(false); s.sendMessage(Ui.mm("<gray>Rift closed.")); return; }
            if (a[0].equalsIgnoreCase("here") && s instanceof Player p) {
                if (rift != null) close(false);
                Location l = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(8));
                open(l.getWorld().getHighestBlockAt(l).getLocation().add(0.5, 1, 0.5));
                return;
            }
        }
        if (rift == null) {
            s.sendMessage(Ui.mm(NAME + " <gray>None open. The next one tears open in about <white>" + Math.max(1, (nextAt - System.currentTimeMillis()) / 60_000) + " min<gray>."));
            return;
        }
        String where = rift.getBlockX() + " " + rift.getBlockY() + " " + rift.getBlockZ();
        String dist = s instanceof Player p && p.getWorld() == rift.getWorld() ? " <dark_gray>(" + Math.round(p.getLocation().distance(rift)) + " blocks)" : "";
        s.sendMessage(Ui.mm(NAME + " <gray>is open at <white>" + where + dist + "<gray>. " + status()));
    }

    private String status() {
        return switch (stage) {
            case WAITING -> "Nobody has reached it yet.";
            case FIGHTING, BETWEEN -> "Wave <white>" + wave + "/" + WAVES + "<gray> is underway.";
            case CLOSING -> "It's closing.";
        };
    }

    private void schedule() {
        if (!Lab.enabled(ID)) { if (rift != null) close(false); return; }
        if (rift != null || System.currentTimeMillis() < nextAt || c.world().getPlayers().isEmpty()) return;
        Location l = pick();
        if (l == null) { nextAt = System.currentTimeMillis() + 5 * 60_000L; return; }
        open(l);
    }

    /** A random spot 300-1500 blocks from spawn, on dry, unclaimed ground. */
    private Location pick() {
        World w = c.world();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 20; i++) {
            double ang = r.nextDouble(Math.PI * 2), dist = r.nextDouble(300, 1500);
            int x = (int) (Math.cos(ang) * dist), z = (int) (Math.sin(ang) * dist);
            Location l = w.getHighestBlockAt(x, z).getLocation();
            if (l.getBlock().isLiquid() || !l.getBlock().getType().isSolid()) continue;
            l.add(0.5, 1, 0.5);
            if (c.claimed().test(l) || c.protectedArea().test(l) || !w.getWorldBorder().isInside(l)) continue;
            return l;
        }
        return null;
    }

    private void open(Location l) {
        rift = l;
        stage = Stage.WAITING;
        wave = 0; tick = 0; stageTick = 0;
        openedAt = System.currentTimeMillis();
        mobs.clear(); fighters.clear();
        for (Player p : c.world().getPlayers())
            p.sendMessage(Ui.mm(NAME + " <gray>tore open at <white>" + l.getBlockX() + " " + l.getBlockZ()
                    + "<gray>. Clear it for loot. <u><click:run_command:'/rift'><#ffe08a>[Where?]</click></u>"));
        task = Bukkit.getScheduler().runTaskTimer(c.plugin(), this::tick, 1L, 1L);
    }

    // ------------------------------------------------------------------ every tick

    private void tick() {
        if (rift == null) return;
        tick++; stageTick++;
        World w = rift.getWorld();
        boolean loaded = w.isChunkLoaded(rift.getBlockX() >> 4, rift.getBlockZ() >> 4);
        if (stage == Stage.CLOSING && stageTick > CLOSE_TICKS) { finish(); return; }
        if (stage != Stage.CLOSING && System.currentTimeMillis() - openedAt > lifeMillis) { close(false); return; }
        if (!loaded) return;
        draw(w);
        if (stage == Stage.WAITING && tick % 10 == 0) {
            for (Player p : w.getPlayers())
                if (p.getGameMode() == GameMode.SURVIVAL && p.getLocation().distanceSquared(rift) < 100) { startWave(1); break; }
        } else if (stage == Stage.FIGHTING && tick % 10 == 0) {
            mobs.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || e.isDead() || !e.isValid(); });
            for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(rift) < 32 * 32) fighters.add(p.getUniqueId());
            if (mobs.isEmpty()) {
                if (wave >= WAVES) burst();
                else { stage = Stage.BETWEEN; stageTick = 0; w.playSound(rift, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 0.7f); }
            }
        } else if (stage == Stage.BETWEEN && stageTick >= 60) startWave(wave + 1);
    }

    /** The rift itself: a tall oval of hot dust that eases open and shut, and a label above it. */
    private void draw(World w) {
        double s = stage == Stage.CLOSING ? 1 - LabMath.riftScale(stageTick, CLOSE_TICKS) : LabMath.riftScale(tick, OPEN_TICKS);
        if (tick % 2 == 0) {
            Particle.DustTransition dust = new Particle.DustTransition(HOT, PALE, 1.3f);
            for (int i = 0; i < 16; i++) {
                double a = (i / 16.0) * Math.PI * 2 + tick * 0.05;
                Location at = rift.clone().add(Math.cos(a) * 1.1 * s, 1.6 + Math.sin(a) * 1.6 * s, 0);
                w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0, 0, 0, 0, dust);
            }
            if (s > 0.5) w.spawnParticle(Particle.LAVA, rift.clone().add(0, 1.6, 0), 1, 0.3, 0.8, 0.1, 0);
        }
        if (tick % 40 == 0) w.playSound(rift, Sound.BLOCK_PORTAL_AMBIENT, SoundCategory.HOSTILE, 0.5f, 0.6f);
        if (label == null || !label.isValid()) { label = Ui.hologram(rift.clone().add(0, 3.8, 0), "", 1f); label.setPersistent(false); }
        if (tick % 10 == 0) Ui.setText(label, NAME + "\n<gray>" + switch (stage) {
            case WAITING -> "Come closer to begin";
            case FIGHTING -> "Wave <white>" + wave + "/" + WAVES + " <dark_gray>· <white>" + mobs.size() + " <gray>left";
            case BETWEEN -> "Wave <white>" + (wave + 1) + "<gray> is coming...";
            case CLOSING -> "Closing";
        });
    }

    private void startWave(int n) {
        wave = n; stage = Stage.FIGHTING; stageTick = 0;
        World w = rift.getWorld();
        int players = (int) w.getPlayers().stream().filter(p -> p.getLocation().distanceSquared(rift) < 32 * 32).count();
        int count = LabMath.riftWave(n, Math.max(1, players));
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            Class<? extends Mob> type = switch (n) {
                case 1 -> r.nextBoolean() ? Zombie.class : Skeleton.class;
                case 2 -> r.nextInt(3) == 0 ? Spider.class : r.nextBoolean() ? Husk.class : Stray.class;
                default -> r.nextInt(3) == 0 ? Blaze.class : WitherSkeleton.class;
            };
            double a = r.nextDouble(Math.PI * 2), d = r.nextDouble(2, 5);
            Location at = w.getHighestBlockAt(rift.clone().add(Math.cos(a) * d, 0, Math.sin(a) * d)).getLocation().add(0.5, 1, 0.5);
            Mob m = w.spawn(at, type, e -> {
                e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
                e.setRemoveWhenFarAway(false);
                e.setPersistent(false);
            });
            mobs.add(m.getUniqueId());
            w.spawnParticle(Particle.FLAME, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.03);
        }
        w.playSound(rift, Sound.ENTITY_WITHER_SHOOT, 1f, 0.6f + 0.15f * n);
        for (Player p : w.getPlayers())
            if (p.getLocation().distanceSquared(rift) < 48 * 48)
                p.sendActionBar(Ui.mm(NAME + " <gray>· Wave <white>" + n + "/" + WAVES + " <gray>· " + count + " mobs"));
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        if (!e.getEntity().getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) return;
        mobs.remove(e.getEntity().getUniqueId());
        if (e.getEntity().getKiller() != null) fighters.add(e.getEntity().getKiller().getUniqueId());
    }

    // ------------------------------------------------------------------ the end

    private void burst() {
        World w = rift.getWorld();
        Location c0 = rift.clone().add(0, 1.6, 0);
        w.playSound(c0, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
        w.playSound(c0, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        w.spawnParticle(Particle.FLAME, c0, 120, 0.5, 0.8, 0.5, 0.15);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> loot = new ArrayList<>(List.of(
                new ItemStack(Material.DIAMOND, r.nextInt(2, 5)), new ItemStack(Material.GOLD_INGOT, r.nextInt(4, 9)),
                new ItemStack(Material.EMERALD, r.nextInt(2, 6)), new ItemStack(Material.EXPERIENCE_BOTTLE, r.nextInt(4, 9))));
        if (r.nextInt(5) == 0) loot.add(new ItemStack(Material.ANCIENT_DEBRIS));
        for (ItemStack s : loot) {
            Item it = w.dropItem(c0, s);
            it.setVelocity(new Vector(r.nextDouble(-0.25, 0.25), r.nextDouble(0.4, 0.6), r.nextDouble(-0.25, 0.25)));
        }
        for (UUID id : fighters) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            c.plugin().economy().deposit(p, reward);
            boolean key = r.nextInt(4) == 0;
            if (key) c.crates().give(p, "iron", 1);
            p.sendMessage(Ui.mm(NAME + " <gray>burst! <green>+" + NetEconomy.money(reward) + (key ? " <gray>and a " + c.crates().keyName("iron") : "") + "<gray>. Grab the loot!"));
        }
        nextAt = System.currentTimeMillis() + intervalMillis;
        close(true);
    }

    private void close(boolean cleared) {
        if (rift == null) return;
        if (!cleared) {
            for (UUID id : mobs) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
            for (Player p : rift.getWorld().getPlayers()) if (p.getLocation().distanceSquared(rift) < 64 * 64) p.sendMessage(Ui.mm(NAME + " <gray>closed."));
            nextAt = System.currentTimeMillis() + intervalMillis;
        }
        mobs.clear();
        stage = Stage.CLOSING; stageTick = 0;
    }

    private void finish() {
        if (task != null) task.cancel();
        task = null;
        if (label != null) label.remove();
        label = null;
        rift = null;
    }
}
