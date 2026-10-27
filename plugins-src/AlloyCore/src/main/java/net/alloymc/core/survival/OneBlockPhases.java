package net.alloymc.core.survival;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/**
 * Reads AOneBlock's phase files (plugins/BentoBox/addons/AOneBlock/phases): the order and length of every
 * phase from phases_index.yml, and each phase's mobs. Used for the progress tag over the OneBlock and for
 * which spawner mob types an island has unlocked (every biome it has reached).
 */
public final class OneBlockPhases {
    public record Phase(String name, int start, int length, Set<EntityType> mobs) {}

    /** Mobs that don't belong in a spawner (bosses, raid mobs that drop totems, villagers, unspawnables). */
    static final Set<String> NOT_SPAWNABLE = Set.of("VILLAGER", "WARDEN", "ILLUSIONER", "EVOKER", "VEX", "ALLAY", "TADPOLE", "RAVAGER",
            "PIGLIN_BRUTE", "ZOMBIE_HORSE", "WITHER", "ENDER_DRAGON", "ELDER_GUARDIAN", "WANDERING_TRADER");

    private final List<Phase> phases = new ArrayList<>();
    private int cycle;

    public OneBlockPhases(File phasesDir, Logger log) {
        File index = new File(phasesDir, "phases_index.yml");
        if (!index.exists()) { log.warning("OneBlock phases index not found - progress tag and biome spawners disabled"); return; }
        List<Map<?, ?>> entries = YamlConfiguration.loadConfiguration(index).getMapList("phases");
        int start = 0;
        for (Map<?, ?> e : entries) {
            String file = String.valueOf(e.get("file")), name = String.valueOf(e.get("name"));
            int length = e.get("length") instanceof Number n ? n.intValue() : 0;
            if (file.contains("goto")) continue;
            Set<EntityType> mobs = new LinkedHashSet<>();
            File f = new File(phasesDir, file + ".yml");
            if (f.exists()) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                for (String top : y.getKeys(false)) {
                    ConfigurationSection m = y.getConfigurationSection(top + ".mobs");
                    if (m == null) continue;
                    for (String k : m.getKeys(false)) {
                        if (NOT_SPAWNABLE.contains(k.toUpperCase())) continue;
                        try { EntityType t = EntityType.valueOf(k.toUpperCase()); if (t.isSpawnable() && t.isAlive()) mobs.add(t); } catch (IllegalArgumentException ignored) { }
                    }
                }
            }
            phases.add(new Phase(name, start, length, mobs));
            start += length;
        }
        cycle = start;
        log.info("OneBlock phases: " + phases.size() + " (" + cycle + " blocks per cycle)");
    }

    public boolean loaded() { return !phases.isEmpty(); }
    public List<Phase> all() { return phases; }

    /** The phase a given block number (position in the cycle) falls in. */
    public Phase at(long blockNumber) {
        if (phases.isEmpty()) return null;
        long n = cycle > 0 ? Math.floorMod(blockNumber, cycle) : blockNumber;
        Phase cur = phases.getFirst();
        for (Phase p : phases) if (p.start() <= n) cur = p;
        return cur;
    }

    public Phase next(Phase p) {
        int i = phases.indexOf(p);
        return i < 0 || phases.isEmpty() ? null : phases.get((i + 1) % phases.size());
    }

    /** Every mob from every phase this island has reached (lifetime blocks broken). */
    public Set<EntityType> unlockedMobs(long lifetime) {
        Set<EntityType> out = new LinkedHashSet<>();
        for (Phase p : phases) if (lifetime >= p.start() || lifetime >= cycle) out.addAll(p.mobs());
        return out;
    }

    /** Which phase first offers this mob (for "reach the X phase to unlock"). */
    public Phase firstWith(EntityType t) {
        for (Phase p : phases) if (p.mobs().contains(t)) return p;
        return null;
    }
}
