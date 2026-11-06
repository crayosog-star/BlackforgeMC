package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

import static net.blackforge.core.survival.SunderingMath.*;

/**
 * The cages (and the Frost Titan's iceberg) around each boss pit. The blocks are built into the arena by tools/graveyard;
 * the first time the arena comes up they are recorded to a file, so they can be shattered (cage) or melted (iceberg) when
 * that boss wakes, and put back when it returns to its pit.
 */
final class Containment {
    private record Snap(int x, int y, int z, BlockData data) {}

    private static final int ICE_PIT = 3;
    private final BlackForgeCore plugin;
    private final World world;
    private final File file;
    private final List<List<Snap>> byPit = new ArrayList<>();
    private final int[] melted = new int[PITS];
    private final Random rnd = new Random();
    private boolean loaded;

    Containment(BlackForgeCore plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.file = new File(plugin.getDataFolder(), "sundering-containment.txt");
    }

    private static boolean isIce(Material m) { return m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE; }

    /** The blocks that make up each boss's prison: magma crust, ribcage, void shards, iceberg, storm cage. */
    private static boolean mine(int pit, Material m) {
        return switch (pit) {
            case 0 -> m == Material.MAGMA_BLOCK || m == Material.BASALT || m == Material.BLACKSTONE;
            case 1 -> m == Material.BONE_BLOCK || m == Material.CALCITE;
            case 2 -> m == Material.OBSIDIAN || m == Material.CRYING_OBSIDIAN || m == Material.PURPLE_STAINED_GLASS_PANE;
            case 3 -> isIce(m);
            default -> m == Material.IRON_BARS || m == Material.LIGHTNING_ROD || m == Material.COPPER_BLOCK;
        };
    }

    private void themeFx(int pit, Location l, boolean big) {
        int n = big ? 12 : 2;
        switch (pit) {
            case 0 -> { world.spawnParticle(Particle.FLAME, l, n, 0.4, 0.4, 0.4, 0.03); world.spawnParticle(Particle.LAVA, l, big ? 4 : 1, 0.3, 0.3, 0.3, 0); }
            case 1 -> world.spawnParticle(Particle.WHITE_ASH, l, n * 3, 0.5, 0.5, 0.5, 0.02);
            case 2 -> world.spawnParticle(Particle.REVERSE_PORTAL, l, n * 3, 0.5, 0.5, 0.5, 0.1);
            case 3 -> world.spawnParticle(Particle.SNOWFLAKE, l, n, 0.4, 0.4, 0.4, 0.03);
            default -> world.spawnParticle(Particle.ELECTRIC_SPARK, l, n * 2, 0.4, 0.4, 0.4, 0.2);
        }
    }

    private void themeSound(int pit, Location l, boolean big) {
        float v = big ? 8f : 6f;
        switch (pit) {
            case 0 -> { world.playSound(l, Sound.BLOCK_STONE_BREAK, v, 0.5f); world.playSound(l, Sound.BLOCK_LAVA_EXTINGUISH, v, 0.6f); }
            case 1 -> { world.playSound(l, Sound.BLOCK_BONE_BLOCK_BREAK, v, 0.5f); world.playSound(l, Sound.ENTITY_SKELETON_DEATH, v, 0.5f); }
            case 2 -> { world.playSound(l, Sound.BLOCK_END_PORTAL_FRAME_FILL, v, 0.5f); world.playSound(l, Sound.ENTITY_ENDERMAN_SCREAM, v * 0.5f, 0.5f); }
            case 3 -> world.playSound(l, Sound.BLOCK_GLASS_BREAK, v, 0.5f);
            default -> { world.strikeLightningEffect(l); world.playSound(l, Sound.ITEM_TRIDENT_THUNDER, v, 0.8f); }
        }
    }

    void load() {
        if (loaded) return;
        loaded = true;
        for (int i = 0; i < PITS; i++) byPit.add(new ArrayList<>());
        try {
            if (file.exists()) {
                for (String line : Files.readAllLines(file.toPath())) {
                    String[] p = line.split(";", 5);
                    if (p.length < 5) continue;
                    byPit.get(Integer.parseInt(p[0])).add(new Snap(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), Bukkit.createBlockData(p[4])));
                }
                return;
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < PITS; i++) {
                int[] c = pit(i);
                for (int x = c[0] - 13; x <= c[0] + 13; x++) for (int z = c[1] - 13; z <= c[1] + 13; z++) {
                    if (Math.hypot(x - c[0], z - c[1]) > 12.9) continue;
                    for (int y = Y + 1; y <= Y + 22; y++) {
                        Block b = world.getBlockAt(x, y, z);
                        Material m = b.getType();
                        if (mine(i, m)) {
                            byPit.get(i).add(new Snap(x, y, z, b.getBlockData()));
                            out.append(i).append(';').append(x).append(';').append(y).append(';').append(z).append(';').append(b.getBlockData().getAsString()).append('\n');
                        }
                    }
                }
            }
            plugin.getDataFolder().mkdirs();
            Files.writeString(file.toPath(), out.toString());
            plugin.getLogger().info("The Sundering: recorded " + byPit.stream().mapToInt(List::size).sum() + " cage/iceberg blocks");
        } catch (Exception e) {
            plugin.getLogger().warning("The Sundering: containment snapshot failed: " + e.getMessage());
        }
    }

    /** Put a pit's cage / iceberg back exactly as built. */
    void restore(int pit) {
        load();
        melted[pit] = 0;
        if (pit == ICE_PIT) puddle(pit, false);
        for (Snap s : byPit.get(pit)) {
            Block b = world.getBlockAt(s.x, s.y, s.z);
            if (!b.getBlockData().equals(s.data)) b.setBlockData(s.data, false);
        }
    }

    /** The melted Frost Titan leaves a puddle in his pit; it is drained again when the iceberg re-forms. */
    private void puddle(int pit, boolean on) {
        int[] c = pit(pit);
        for (int x = c[0] - 12; x <= c[0] + 12; x++) for (int z = c[1] - 12; z <= c[1] + 12; z++) {
            double r = Math.hypot(x - c[0], z - c[1]);
            if (r > 12) continue;
            Block b = world.getBlockAt(x, Y, z);
            if (on && r <= 11.2 && b.getType() == Material.AIR) b.setType(Material.WATER, false);
            else if (!on && b.getType() == Material.WATER) b.setType(Material.AIR, false);
        }
    }

    private void remove(int pit, Snap s, boolean fx) {
        Block b = world.getBlockAt(s.x, s.y, s.z);
        if (b.getType() == Material.AIR) return;
        if (fx) { Location at = b.getLocation().add(0.5, 0.5, 0.5); world.spawnParticle(Particle.BLOCK, at, 5, 0.3, 0.3, 0.3, s.data); themeFx(pit, at, false); }
        b.setType(Material.AIR, false);
    }

    /** A chain snaps: the cage section on that side of the pit tears open, bars flying away bottom to top. */
    void snap(int pit, int sector) {
        load();
        int[] c = pit(pit);
        Location l = new Location(world, c[0] + 0.5, Y + 6, c[1] + 0.5);
        if (pit == ICE_PIT) {   // the iceberg only cracks here; the real work is the melt
            themeSound(pit, l, false);
            world.spawnParticle(Particle.SNOWFLAKE, l, 60, 5, 5, 5, 0.05);
            return;
        }
        List<Snap> out = new ArrayList<>();
        for (Snap s : byPit.get(pit)) {
            double a = Math.toDegrees(Math.atan2(s.z - c[1], s.x - c[0]));
            if (a < 0) a += 360;
            if ((int) (a / 90) % 4 == sector) out.add(s);
        }
        stagger(pit, out, 12, true);
        themeSound(pit, l, false);
    }

    /** The boss bursts out: whatever is left of the cage / iceberg goes at once. */
    void breakOut(int pit) {
        load();
        stagger(pit, new ArrayList<>(byPit.get(pit)), 6, true);
        int[] c = pit(pit);
        Location l = new Location(world, c[0] + 0.5, Y + 6, c[1] + 0.5);
        themeSound(pit, l, true);
        themeFx(pit, l, true);
        if (pit == ICE_PIT) Bukkit.getScheduler().runTaskLater(plugin, () -> puddle(pit, true), 10L);
    }

    /** The Frost Titan's iceberg melts from the top down as the wake-up runs (f = 0..1). */
    void melt(int pit, double f) {
        if (pit != ICE_PIT) return;
        load();
        List<Snap> all = byPit.get(pit);
        if (all.isEmpty()) return;
        if (melted[pit] == 0) {   // order once: top first, a little jumbled
            all.sort(Comparator.comparingDouble(s -> -(s.y + Math.abs(rnd.nextGaussian()) * 1.2)));
            melted[pit] = 1;
        }
        int target = (int) Math.min(all.size(), f * all.size());
        int done = melted[pit] - 1;
        for (int i = done; i < target; i++) {
            Snap s = all.get(i);
            remove(pit, s, false);
            if (i % 7 == 0) {
                Location l = new Location(world, s.x + 0.5, s.y + 0.5, s.z + 0.5);
                world.spawnParticle(Particle.FALLING_WATER, l, 3, 0.3, 0.1, 0.3, 0);
                world.spawnParticle(Particle.CLOUD, l, 1, 0.3, 0.2, 0.3, 0.01);
            }
        }
        melted[pit] = target + 1;
        // steam and meltwater all over the berg while it goes
        int[] c = pit(pit);
        for (int k = 0; k < 3; k++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = rnd.nextDouble() * 10;
            Location l = new Location(world, c[0] + 0.5 + Math.cos(a) * r, Y + 1 + rnd.nextDouble() * 12 * (1 - f * 0.6), c[1] + 0.5 + Math.sin(a) * r);
            world.spawnParticle(Particle.CLOUD, l, 4, 0.5, 0.4, 0.5, 0.02);
            world.spawnParticle(Particle.FALLING_WATER, l, 6, 0.6, 0.2, 0.6, 0);
        }
        if (target > done && rnd.nextInt(4) == 0) {
            Snap s = all.get(Math.max(0, target - 1));
            world.playSound(new Location(world, s.x, s.y, s.z), Sound.BLOCK_POWDER_SNOW_BREAK, 4f, 0.7f);
        }
    }

    private void stagger(int pit, List<Snap> list, int ticks, boolean fx) {
        Collections.shuffle(list, rnd);
        list.sort(Comparator.comparingInt(s -> s.y));
        int per = Math.max(1, (list.size() + ticks - 1) / ticks);
        int[] i = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            for (int n = 0; n < per && i[0] < list.size(); n++, i[0]++) remove(pit, list.get(i[0]), fx && i[0] % 3 == 0);
            if (i[0] >= list.size()) task.cancel();
        }, 0L, 1L);
    }
}
