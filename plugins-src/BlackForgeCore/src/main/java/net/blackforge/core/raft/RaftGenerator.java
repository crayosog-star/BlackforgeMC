package net.blackforge.core.raft;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static net.blackforge.core.raft.RaftStructures.SEA;

/** The Raft ocean: warm clear water over a sandy floor, to the horizon. Islands and wrecks come from {@link RaftStructures}. */
public final class RaftGenerator extends ChunkGenerator {
    public static final int MIN_Y = -64;

    @Override
    public void generateNoise(WorldInfo info, Random rnd, int cx, int cz, ChunkData data) {
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int wx = cx * 16 + x, wz = cz * 16 + z, f = RaftStructures.floor(wx, wz);
                data.setBlock(x, MIN_Y, z, Material.BEDROCK);
                for (int y = MIN_Y + 1; y <= f; y++) {
                    Material m;
                    if (y > f - 3) m = Math.floorMod(wx * 31 + wz * 17, 11) < 2 ? Material.GRAVEL : Material.SAND;
                    else m = y < 0 ? Material.DEEPSLATE : Material.STONE;
                    data.setBlock(x, y, z, m);
                }
                for (int y = f + 1; y < SEA; y++) data.setBlock(x, y, z, Material.WATER);
            }
    }

    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public Location getFixedSpawnLocation(World w, Random r) { return new Location(w, 0.5, SEA + 1, 0.5); }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        return new BiomeProvider() {
            @Override public Biome getBiome(WorldInfo w, int x, int y, int z) { return y < SEA - 12 ? Biome.DEEP_LUKEWARM_OCEAN : Biome.WARM_OCEAN; }
            @Override public List<Biome> getBiomes(WorldInfo w) { return List.of(Biome.WARM_OCEAN, Biome.DEEP_LUKEWARM_OCEAN); }
        };
    }

    @Override public List<BlockPopulator> getDefaultPopulators(World world) { return List.of(new Populator()); }

    private static BlockData parse(String s) {
        try { return org.bukkit.Bukkit.createBlockData(s); }
        catch (IllegalArgumentException e) { org.bukkit.Bukkit.getLogger().warning("[Raft] bad block data: " + s); return org.bukkit.Bukkit.createBlockData("minecraft:stone"); }
    }

    /** Stamps every structure that touches this chunk, clipped to the chunk, and fills its chests. */
    static final class Populator extends BlockPopulator {
        private final Map<String, BlockData> cache = new HashMap<>();

        @Override
        public void populate(WorldInfo info, Random rnd, int cx, int cz, LimitedRegion region) {
            int x0 = cx * 16, z0 = cz * 16;
            for (RaftStructures.Instance in : RaftStructures.touching(info.getSeed(), x0, z0)) {
                for (RaftStructures.Block b : in.blocksIn(cx, cz)) {
                    BlockData bd;
                    synchronized (cache) { bd = cache.computeIfAbsent(b.data(), RaftGenerator::parse); }
                    if (region.isInRegion(b.x(), b.y(), b.z())) region.setBlockData(b.x(), b.y(), b.z(), bd);
                }
                for (RaftStructures.Chest c : in.chestsIn(cx, cz)) {
                    if (!region.isInRegion(c.x(), c.y(), c.z())) continue;
                    try {
                        BlockState st = region.getBlockState(c.x(), c.y(), c.z());
                        if (st instanceof Chest ch) {
                            ch.getPersistentDataContainer().set(RaftLoot.KEY, org.bukkit.persistence.PersistentDataType.STRING, c.kind());   // filled when first opened
                            ch.update(true, false);
                        }
                    } catch (Exception ex) { org.bukkit.Bukkit.getLogger().warning("[Raft] chest fill failed at " + c.x() + "," + c.y() + "," + c.z() + ": " + ex); }
                }
            }
        }
    }
}
