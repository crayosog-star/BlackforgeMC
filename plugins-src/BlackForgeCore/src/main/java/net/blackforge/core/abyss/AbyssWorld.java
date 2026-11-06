package net.blackforge.core.abyss;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.io.File;
import java.util.List;
import java.util.Random;

/**
 * The Abyssal Sea: a dark, endless ocean with ruins, rock islands, lighthouses, bone islands and Atlantis (see
 * {@link AbyssGenerator}, {@link AbyssStructures}), and Jormungandr, a world serpent that may rise to hunt anyone who sails far
 * from the dock ({@link Leviathan}). Very high risk, very high reward: death is a normal death (gear is lost at sea), the
 * serpent hits for half a health bar a lunge, and it pays out Forgeheart and Molten keys.
 *
 * Config (survival config.yml): abyss.public (false = staff only), abyss.serpent-chance-per-second (0.004),
 * abyss.serpent-cooldown-minutes (12), abyss.min-distance-from-dock (150).
 */
public final class AbyssWorld {
    public static final String WORLD = "abyssal_sea";
    private static final String BOAT_TAG = "abyss_dock_boat", HOLO_TAG = "abyss_holo";

    private final BlackForgeCore plugin;
    private final Random rnd = new Random();
    private World world;
    private Leviathan serpent;
    private long cooldownUntil;
    private TextDisplay holo;

    public AbyssWorld(BlackForgeCore plugin) {
        this.plugin = plugin;
        // load an existing world after startup (the first /blackforge abyss stage creates it)
        File main = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0).getWorldFolder();
        if (main != null && (new File(main, "dimensions/minecraft/" + WORLD).isDirectory() || new File(Bukkit.getWorldContainer(), WORLD).isDirectory()))
            Bukkit.getScheduler().runTaskLater(plugin, this::load, 100L);
    }

    private World load() {
        if (world != null) return world;
        world = Bukkit.getWorld(WORLD);
        if (world == null) world = new WorldCreator(WORLD).generator(new AbyssGenerator()).environment(World.Environment.NORMAL).type(WorldType.NORMAL).generateStructures(false).createWorld();
        if (world == null) return null;
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setTime(18000);
        world.setStorm(true);
        world.setThundering(false);
        world.setDifficulty(Difficulty.NORMAL);
        world.setPVP(false);
        world.setSpawnLocation(6, AbyssGenerator.SEA + 2, 0);
        for (Entity e : world.getEntities()) if (e.getScoreboardTags().contains("leviathan_part")) e.remove();
        Bukkit.getScheduler().runTaskLater(plugin, this::dockLife, 60L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::encounterTick, 200L, 20L);
        new AbyssMobs(plugin, world);
        return world;
    }

    /** Boats at the pier and the warning sign: made once, repaired if missing. */
    private void dockLife() {
        if (world == null) return;
        world.getChunkAt(2, 0).load(true);
        world.getChunkAt(3, 0).load(true);
        boolean hasBoat = false;
        for (Entity e : world.getNearbyEntities(new Location(world, 46, AbyssGenerator.SEA, 0), 14, 6, 14)) if (e.getScoreboardTags().contains(BOAT_TAG)) hasBoat = true;
        if (!hasBoat) for (int z = -4; z <= 4; z += 4) {
            Entity b = world.spawnEntity(new Location(world, 49.5, AbyssGenerator.SEA, z + 0.5, 90f, 0f), EntityType.OAK_BOAT);
            b.addScoreboardTag(BOAT_TAG);
        }
        for (Entity e : world.getEntitiesByClass(TextDisplay.class)) if (e.getScoreboardTags().contains(HOLO_TAG)) e.remove();
        holo = Ui.hologram(new Location(world, 44.5, AbyssGenerator.SEA + 5, 0.5),
                "<dark_aqua><bold>THE ABYSSAL SEA</bold><newline><red>Here be monsters<newline><gray>Sail far enough and something will find you<newline><dark_gray>High risk • high reward<newline><dark_gray>/spawn to go home", 1.8f);
        holo.addScoreboardTag(HOLO_TAG);
        holo.setPersistent(false);
    }

    // ------------------------------------------------------------------ encounters

    private void encounterTick() {
        if (world == null) return;
        if (serpent != null && !serpent.alive()) serpent = null;
        if (serpent != null || System.currentTimeMillis() < cooldownUntil) return;
        double chance = plugin.getConfig().getDouble("abyss.serpent-chance-per-second", 0.004), minDist = plugin.getConfig().getDouble("abyss.min-distance-from-dock", 150);
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            Location l = p.getLocation();
            if (Math.hypot(l.getX(), l.getZ()) < minDist || l.getY() < AbyssGenerator.SEA - 40) continue;
            if (rnd.nextDouble() < chance) { summon(p); return; }
        }
    }

    private void summon(Player target) {
        if (world == null || serpent != null) return;
        serpent = new Leviathan(plugin, world, target, () -> {
            serpent = null;
            cooldownUntil = System.currentTimeMillis() + plugin.getConfig().getLong("abyss.serpent-cooldown-minutes", 12) * 60_000L;
        });
        plugin.getLogger().info("Jormungandr rises near " + target.getName());
    }

    // ------------------------------------------------------------------ entering and staff commands

    /** Whether this player may go there yet: everyone once abyss.public is true, staff before that. */
    public boolean open(Player p) { return plugin.getConfig().getBoolean("abyss.public", false) || p.hasPermission("blackforge.admin"); }

    /** Puts a player on the dock (Captain Salt-Eye's sailing, or /blackforge abyss tp for staff). There is no warp for players. */
    public void enter(Player p) {
        if (!open(p)) {
            p.sendMessage(Ui.mm("<dark_aqua>The Abyssal Sea is still being charted. <gray>Soon."));
            return;
        }
        World w = load();
        if (w == null) { p.sendMessage(Ui.mm("<red>The sea could not be reached right now.")); return; }
        p.sendMessage(Ui.mm("<dark_aqua><bold>The Abyssal Sea.</bold> <gray>Gear lost out there stays out there. Something very large lives past the lights. <white>/spawn <gray>brings you home."));
        p.teleport(new Location(w, 7.5, AbyssGenerator.SEA + 1, 0.5, 90f, 0f));
    }

    public boolean command(CommandSender s, String[] a) {
        if (a.length < 1 || !a[0].equalsIgnoreCase("abyss")) return false;
        if (!s.hasPermission("blackforge.admin")) { s.sendMessage(Ui.mm("<red>You need blackforge.admin for that.")); return true; }
        String sub = a.length > 1 ? a[1].toLowerCase() : "status";
        switch (sub) {
            case "stage" -> { World w = load(); s.sendMessage(Ui.mm(w == null ? "<red>Could not create the world." : "<green>The Abyssal Sea is up. <gray>/blackforge abyss tp")); }
            case "tp" -> {
                if (!(s instanceof Player p)) { s.sendMessage(Ui.mm("<red>Players only.")); return true; }
                World w = load();
                if (w == null) { s.sendMessage(Ui.mm("<red>Not available.")); return true; }
                if (a.length > 2 && a[2].equalsIgnoreCase("atlantis")) {
                    int[] at = AbyssStructures.nearestAtlantis(w.getSeed(), p.getWorld() == w ? p.getLocation().getBlockX() : 0, p.getWorld() == w ? p.getLocation().getBlockZ() : 0);
                    s.sendMessage(Ui.mm("<aqua>Atlantis at " + at[0] + ", " + at[1] + " <gray>(teleporting above it)"));
                    Location l = new Location(w, at[0] + 0.5, AbyssGenerator.SEA + 8, at[1] + 0.5);
                    w.getChunkAtAsync(l).thenAccept(c -> Bukkit.getScheduler().runTask(plugin, () -> p.teleport(l)));
                } else enter(p);
            }
            case "serpent" -> {
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player p ? p : null;
                if (t == null || t.getWorld() != world) s.sendMessage(Ui.mm("<red>Needs a player standing in the Abyssal Sea."));
                else if (serpent != null) s.sendMessage(Ui.mm("<yellow>It is already out there."));
                else { summon(t); s.sendMessage(Ui.mm("<dark_aqua>It rises.")); }
            }
            case "where" -> {   // the nearest Atlantis to you (or to the dock)
                World w = load();
                if (w == null) { s.sendMessage(Ui.mm("<red>Not available.")); return true; }
                int px = s instanceof Player p && p.getWorld() == w ? p.getLocation().getBlockX() : 0, pz = s instanceof Player p2 && p2.getWorld() == w ? p2.getLocation().getBlockZ() : 0;
                int[] at = AbyssStructures.nearestAtlantis(w.getSeed(), px, pz);
                s.sendMessage(Ui.mm("<aqua>Nearest Atlantis: " + at[0] + ", " + at[1]));
            }
            case "kill" -> { if (serpent != null) serpent.forceEnd(); serpent = null; s.sendMessage(Ui.mm("<gray>The sea is calm.")); }
            default -> s.sendMessage(Ui.mm("<dark_aqua>Abyssal Sea: <white>" + (world == null ? "not loaded" : world.getPlayers().size() + " in it") + "<gray>, serpent " + (serpent != null ? "<red>out" : "<green>resting")
                    + "<gray>, public " + plugin.getConfig().getBoolean("abyss.public", false) + "<newline><gray>/blackforge abyss stage | tp [atlantis] | serpent [player] | kill"));
        }
        return true;
    }

    public List<String> tabs(String[] a) {
        if (a.length == 2) return List.of("stage", "tp", "where", "serpent", "kill", "status");
        if (a.length == 3 && a[1].equalsIgnoreCase("tp")) return List.of("atlantis");
        return List.of();
    }

    public void shutdown() {
        if (serpent != null) serpent.forceEnd();
        serpent = null;
        if (holo != null) holo.remove();
    }
}
