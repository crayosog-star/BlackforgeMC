package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.file.*;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Stream;

/**
 * The resource world (Survival): a normal, unclaimable world for mining and gathering, wiped and regenerated on the 1st
 * of every month so the ores and terrain stay fresh while everyone's main bases are untouched. /resource drops you
 * somewhere random and safe in it (within 3,000 blocks). Building there is pointless - it all goes at the reset.
 */
public final class ResourceWorld {
    public static final String NAME = "resource";
    private static final int RANGE = 3000;

    private final AlloyCore plugin;
    private final Random rnd = new Random();
    private World world;

    ResourceWorld(AlloyCore plugin) {
        this.plugin = plugin;
        File state = new File(plugin.getDataFolder(), "resource-world.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(state);
        String month = YearMonth.now().toString();
        World existing = Bukkit.getWorld(NAME);
        if (!month.equals(y.getString("month"))) {
            // a new month: throw the old world away before it's loaded
            File folder = existing != null ? existing.getWorldFolder() : null;
            if (existing != null) Bukkit.unloadWorld(existing, false);
            if (folder == null) folder = findFolder();
            if (folder != null && folder.exists()) deleteTree(folder.toPath());
            plugin.getLogger().info("Resource world reset for " + month);
            y.set("month", month);
            try { y.save(state); } catch (Exception ignored) { }
        }
        world = Bukkit.getWorld(NAME);
        if (world == null) world = new WorldCreator(NAME).environment(World.Environment.NORMAL).createWorld();
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(RANGE * 2 + 1000);
        Bukkit.getCommandMap().register("alloy", new Command("resource", "Go to the resource world", "/resource", List.of("resourceworld", "rw", "mine")) {
            @Override public boolean execute(CommandSender s, String l, String[] a) { if (s instanceof Player p) go(p); return true; }
        });
    }

    public World world() { return world; }

    private File findFolder() {
        File container = Bukkit.getWorldContainer();
        for (File f : new File[]{new File(container, NAME), new File(container, "world/dimensions/minecraft/" + NAME)}) if (f.exists()) return f;
        return null;
    }

    private static void deleteTree(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> { try { Files.delete(f); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }

    private void go(Player p) {
        p.sendMessage(Ui.mm("<green>⛏ <gray>Finding you a spot in the resource world... <dark_gray>(it resets on the 1st of every month)"));
        for (int tries = 0; tries < 20; tries++) {
            int x = rnd.nextInt(RANGE * 2) - RANGE, z = rnd.nextInt(RANGE * 2) - RANGE;
            int fx = x, fz = z;
            world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(c -> {
                if (!p.isOnline()) return;
                Location top = world.getHighestBlockAt(fx, fz).getLocation();
                Material m = top.getBlock().getType();
                if (m == Material.WATER || m == Material.LAVA || m.name().contains("LEAVES")) { p.performCommand("resource"); return; }
                p.teleportAsync(top.add(0.5, 1, 0.5));
            });
            return;
        }
    }
}
