package net.blackforge.core.abyss;

import net.blackforge.core.survival.SkyHubBuilder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.loot.LootTable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The Abyssal Sea: an endless black ocean (sea level 63). The floor is 15-45 blocks down with narrow trenches that drop much
 * further and rare rock islands that break the surface. Structures ({@link AbyssStructures}) are stamped in by a populator, the
 * same for the same seed, clipped to each chunk, so they can span many chunks.
 */
public final class AbyssGenerator extends ChunkGenerator {
    public static final int SEA = 63, MIN_Y = -64;

    /** y of the sea floor (or of an island's top) at x, z. A pure function of x and z, shared by the structure builders. */
    public static int floor(int x, int z) {
        double base = SEA - 17 - 26 * SkyHubBuilder.fbm(x * 0.006, z * 0.006, 101) - 6 * SkyHubBuilder.fbm(x * 0.03, z * 0.03, 102);
        double ridge = Math.abs(SkyHubBuilder.fbm(x * 0.0035 + 40, z * 0.0035 + 40, 103) - 0.5);
        if (ridge < 0.022) base -= 55 * Math.pow(1 - ridge / 0.022, 0.8);                 // a trench
        double isl = SkyHubBuilder.fbm(x * 0.011 + 9, z * 0.011 + 9, 104);
        if (isl > 0.78) base = Math.max(base, SEA - 4 + (isl - 0.78) * 70);               // a rock island
        // the dock: shallows and a rock islet round the origin
        double d = Math.hypot(x, z);
        if (d < 140) {
            double t = Math.max(0, Math.min(1, (d - 40) / 100.0));
            base = base * t + 48 * (1 - t);
            if (d < 15) base = Math.max(base, SEA + 3 - d * 0.2);
        }
        return (int) Math.round(base);
    }

    @Override
    public void generateNoise(WorldInfo info, Random rnd, int cx, int cz, ChunkData data) {
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int wx = cx * 16 + x, wz = cz * 16 + z, f = floor(wx, wz);
                data.setBlock(x, MIN_Y, z, Material.BEDROCK);
                for (int y = MIN_Y + 1; y <= f; y++) {
                    Material m;
                    if (y == f) {
                        int r = Math.floorMod(wx * 31 + wz * 17, 10);
                        m = f > SEA - 3 ? (r < 5 ? Material.BLACKSTONE : r < 8 ? Material.DEEPSLATE : Material.GRAVEL) : (r < 4 ? Material.GRAVEL : r < 7 ? Material.TUFF : r < 9 ? Material.DEEPSLATE : Material.BLACKSTONE);
                    } else m = y < 0 || f - y > 6 ? Material.DEEPSLATE : Material.TUFF;
                    data.setBlock(x, y, z, m);
                }
                for (int y = f + 1; y < SEA; y++) data.setBlock(x, y, z, Material.WATER);
            }
    }

    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public Location getFixedSpawnLocation(World w, Random r) { return new Location(w, 6.5, SEA + 2, 0.5); }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        return new BiomeProvider() {
            @Override public Biome getBiome(WorldInfo w, int x, int y, int z) { return Biome.DEEP_COLD_OCEAN; }
            @Override public List<Biome> getBiomes(WorldInfo w) { return List.of(Biome.DEEP_COLD_OCEAN); }
        };
    }

    @Override public List<BlockPopulator> getDefaultPopulators(World world) { return List.of(new Populator()); }

    private static BlockData parse(String s) {
        try { return org.bukkit.Bukkit.createBlockData(s); }
        catch (IllegalArgumentException e) { org.bukkit.Bukkit.getLogger().warning("[Abyss] bad block data: " + s); return org.bukkit.Bukkit.createBlockData("minecraft:stone"); }
    }

    /** Stamps every structure that touches this chunk, clipped to the chunk. */
    static final class Populator extends BlockPopulator {
        private final Map<String, BlockData> cache = new HashMap<>();

        @Override
        public void populate(WorldInfo info, Random rnd, int cx, int cz, LimitedRegion region) {
            int x0 = cx * 16, z0 = cz * 16;
            for (AbyssStructures.Instance in : AbyssStructures.touching(info.getSeed(), x0, z0)) {
                for (AbyssStructures.Block b : in.blocksIn(cx, cz)) {
                    BlockData bd;
                    synchronized (cache) { bd = cache.computeIfAbsent(b.data(), AbyssGenerator::parse); }
                    if (region.isInRegion(b.x(), b.y(), b.z())) region.setBlockData(b.x(), b.y(), b.z(), bd);
                }
                for (AbyssStructures.Chest c : in.chestsIn(cx, cz)) {
                    if (!region.isInRegion(c.x(), c.y(), c.z())) continue;
                    try {
                        BlockState st = region.getBlockState(c.x(), c.y(), c.z());
                        if (st instanceof Chest ch) {
                            LootTable lt = c.table().getLootTable();
                            if (lt != null) { ch.setLootTable(lt, Math.abs((long) c.x() * 31 + c.z() * 17 + c.y())); ch.update(true, false); }
                        }
                    } catch (Exception ignored) { }
                }
                World w = org.bukkit.Bukkit.getWorld(info.getUID());
                for (AbyssStructures.Spawn s : in.spawnsIn(cx, cz)) {
                    Location at = new Location(w, s.x() + 0.5, s.y(), s.z() + 0.5);
                    try {
                        if (s.type() == EntityType.ELDER_GUARDIAN) region.spawn(at, org.bukkit.entity.ElderGuardian.class, g -> g.setPersistent(true));
                        else region.spawn(at, org.bukkit.entity.Guardian.class, g -> g.setPersistent(true));
                    }
                    catch (Exception ignored) { }
                }
            }
        }
    }
}
