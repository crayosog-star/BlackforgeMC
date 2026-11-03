package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Life at the Survival spawn: the Legendary Forge flares every half hour (a minute of fire, a short buff and a Cinder Key
 * for everyone still at spawn), the kingdom hums with distant hammering and lava after dark, and /tour walks a new
 * player through every district (a Cinder Key at the end, once).
 */
final class SpawnLife implements Listener {
    private record Stop(String title, Supplier<Location> at, String text) {}

    private static final int Y = SurvivalLayout.Y;

    private final AlloyCore plugin;
    private final World world;
    private final Crates crates;
    private final File toured;
    private final Random rnd = new Random();
    private final Set<UUID> touring = new HashSet<>();
    private final List<Stop> stops;
    private boolean flaring;

    SpawnLife(AlloyCore plugin, World world, Crates crates, Supplier<Location> spawn) {
        this.plugin = plugin;
        this.world = world;
        this.crates = crates;
        this.toured = new File(plugin.getDataFolder(), "tour-done.yml");
        this.stops = List.of(
                new Stop("The Grand Hall", spawn, "Welcome to the BlackForge kingdom! This is the Grand Hall, where you arrive. Cross the bridge south to the fortress courtyard."),
                new Stop("The Courtyard", () -> new Location(world, SurvivalLayout.SPAWN_X, SurvivalLayout.SPAWN_Y, SurvivalLayout.SPAWN_Z, SurvivalLayout.SPAWN_YAW, 0),
                        "The fortress courtyard, with the fountain and the Legendary Forge hanging above. Everything else is a short walk from here."),
                new Stop("The Crates", () -> DistrictLife.cratesWarp(world),
                        "The crate vaults, right here in the Grand Hall. Left-click a crate to see its rewards and odds; keys come from voting, the daily reward and events."),
                new Stop("The Market", () -> DistrictLife.marketWarp(world),
                        "Ring the bell in the Merchant District to open the Auction House, or use /ah. Buy and sell with other players."),
                new Stop("The Smithy", () -> DistrictLife.warp(world, "smithy"), "The Blacksmith's anvil is free to use. Beyond this bridge, the forge never sleeps."),
                new Stop("The Merchant District", () -> DistrictLife.warp(world, "market"), "The Merchant opens the Auction House too. Warp back any time with /warp merchant."),
                new Stop("The Gate of the Wilds", () -> DistrictLife.warp(world, "wilds"), "Leave on an expedition with /warp wild. Claim your land with the golden shovel before you build."));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("tour", "A guided tour of the BlackForge kingdom", "/tour", List.of()) {
            @Override public boolean execute(CommandSender s, String label, String[] args) {
                if (s instanceof Player p) tour(p); else s.sendMessage("Players only.");
                return true;
            }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::flare, 20L * 60 * 30, 20L * 60 * 30);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ambience, 20L * 20, 20L * 25);
    }

    private boolean atSpawn(Player p) { return p.getWorld() == world && SurvivalLayout.inSpawn(p.getX(), p.getY(), p.getZ()); }

    // ------------------------------------------------------------------ the forge flares

    private void flare() {
        if (flaring || world.getPlayers().stream().noneMatch(this::atSpawn)) return;
        flaring = true;
        Bukkit.broadcast(Ui.mm("<#ff6a00><bold>🔥 The Legendary Forge flares!</bold> <gray>Stand at spawn for one minute to claim a <#e0895a>Cinder Key <gray>and a burst of speed."));
        Location forge = new Location(world, 0.5, SpawnUpgradeBuilder.FORGE_Y + 8, 0.5);
        int[] ticks = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            ticks[0] += 10;
            world.spawnParticle(Particle.FLAME, forge, 40, 6, 3, 6, 0.04);
            world.spawnParticle(Particle.LAVA, forge, 6, 5, 2, 5, 0);
            if (ticks[0] % 100 == 0) world.playSound(forge, Sound.BLOCK_ANVIL_USE, 4f, 0.6f);
            if (ticks[0] < 20 * 60) return;
            task.cancel();
            flaring = false;
            for (Player p : world.getPlayers()) {
                if (!atSpawn(p)) continue;
                crates.give(p, "iron", 1);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 60 * 5, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 20 * 60 * 5, 1));
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
            }
            Bukkit.broadcast(Ui.mm("<#ff6a00>The Forge settles. <gray>Everyone at spawn received a key. Next flare in 30 minutes."));
        }, 10L, 10L);
    }

    // ------------------------------------------------------------------ the kingdom after dark

    private void ambience() {
        long t = world.getTime();
        if (t < 13000 || t > 23000) return;
        Sound[] pool = {Sound.BLOCK_ANVIL_USE, Sound.AMBIENT_BASALT_DELTAS_MOOD, Sound.BLOCK_LAVA_AMBIENT, Sound.BLOCK_FIRE_AMBIENT};
        for (Player p : world.getPlayers()) {
            if (!atSpawn(p)) continue;
            Location at = p.getLocation().add(rnd.nextInt(41) - 20, 0, rnd.nextInt(41) - 20);
            p.playSound(at, pool[rnd.nextInt(pool.length)], 0.35f, 0.6f + rnd.nextFloat() * 0.3f);
        }
    }

    // ------------------------------------------------------------------ the tour

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPlayedBefore()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.sendMessage(Ui.mm("<#ff6a00>✦ <white>New here? <click:run_command:'/tour'><#ffb52e><u>Take the guided tour</u></click> <gray>(a free Cinder Key at the end)."));
        }, 100L);
    }

    private void tour(Player p) {
        if (p.getWorld() != world) { p.sendMessage(Ui.mm("<red>The tour runs on Survival.")); return; }
        if (!touring.add(p.getUniqueId())) { p.sendMessage(Ui.mm("<gray>You're already on the tour.")); return; }
        p.sendMessage(Ui.mm("<#ff6a00><bold>BlackForge tour</bold> <gray>- " + stops.size() + " stops, about a minute. Type /tour again after it ends to repeat."));
        stop(p, 0);
    }

    private void stop(Player p, int i) {
        if (!p.isOnline()) { touring.remove(p.getUniqueId()); return; }
        if (i >= stops.size()) { finish(p); return; }
        Stop s = stops.get(i);
        Location at = s.at().get();
        if (at != null) p.teleport(at);
        p.sendMessage(Ui.mm("<#ffb52e>" + (i + 1) + "/" + stops.size() + " <white><bold>" + s.title() + "</bold> <gray>- " + s.text()));
        p.showTitle(net.kyori.adventure.title.Title.title(Ui.mm("<#ff6a00>" + s.title()), Ui.mm("<gray>Stop " + (i + 1) + " of " + stops.size())));
        Bukkit.getScheduler().runTaskLater(plugin, () -> stop(p, i + 1), 20L * 9);
    }

    private void finish(Player p) {
        touring.remove(p.getUniqueId());
        p.teleport(stops.getFirst().at().get());
        YamlConfiguration y = YamlConfiguration.loadConfiguration(toured);
        if (y.getBoolean(p.getUniqueId().toString())) { p.sendMessage(Ui.mm("<#ff6a00>Tour complete. <gray>Enjoy the kingdom!")); return; }
        y.set(p.getUniqueId().toString(), true);
        try { y.save(toured); } catch (Exception ex) { plugin.getLogger().warning("tour save: " + ex); }
        crates.give(p, "iron", 1);
        p.sendMessage(Ui.mm("<#ff6a00>Tour complete! <gray>Here's a Cinder Key for the crates. Enjoy the kingdom!"));
    }
}
