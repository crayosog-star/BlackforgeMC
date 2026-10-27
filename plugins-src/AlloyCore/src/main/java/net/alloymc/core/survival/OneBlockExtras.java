package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

/**
 * OneBlock additions on top of AOneBlock (which already rolls biome-themed chests for every phase):
 *  - a progress tag floating over each OneBlock: phase, block count and how far to the next biome
 *  - rare Spawner Fragments from breaking the OneBlock and from its chests
 *  - spawner mob types limited to the biomes an island has reached
 * BentoBox/AOneBlock are reached by reflection, so AlloyCore has no compile dependency on them.
 */
public final class OneBlockExtras implements Listener {
    private final AlloyCore plugin;
    private final double fromBlock, fromChest;
    private final OneBlockPhases phases;
    private final Map<String, TextDisplay> tags = new HashMap<>();
    private final Map<String, Long> seen = new HashMap<>();
    private final Set<String> rolledChests = new HashSet<>();
    private final Random rnd = new Random();
    private Object islands, addon;
    private Method getIslandAt, getOneBlocksIsland, getCenter, getUniqueId, getBlockNumber, getLifetime, getPhaseName;

    OneBlockExtras(AlloyCore plugin, Spawners spawners) {
        this.plugin = plugin;
        this.fromBlock = plugin.getConfig().getDouble("oneblock.fragment-chance-block", 1 / 300.0);
        this.fromChest = plugin.getConfig().getDouble("oneblock.fragment-chance-chest", 1 / 12.0);
        this.phases = new OneBlockPhases(new File(plugin.getServer().getPluginsFolder(), "BentoBox/addons/AOneBlock/phases"), plugin.getLogger());
        spawners.setBiomeMobs(l -> {
            if (l.getWorld() == null || !l.getWorld().getName().startsWith("oneblock")) return null;   // normal spawner list elsewhere
            long life = lifetimeAt(l);
            return life < 0 ? Set.of() : phases.unlockedMobs(life);
        }, phases);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateTags, 60L, 20L);
    }

    public void shutdown() { tags.values().forEach(TextDisplay::remove); tags.clear(); }

    // ------------------------------------------------------------------ reflection

    private boolean hook() {
        if (getIslandAt != null) return true;
        try {
            Class<?> bb = Class.forName("world.bentobox.bentobox.BentoBox");
            Object inst = bb.getMethod("getInstance").invoke(null);
            islands = inst.getClass().getMethod("getIslands").invoke(inst);
            Object am = inst.getClass().getMethod("getAddonsManager").invoke(inst);
            Optional<?> a = (Optional<?>) am.getClass().getMethod("getAddonByName", String.class).invoke(am, "AOneBlock");
            if (a.isEmpty()) return false;
            addon = a.get();
            Class<?> island = Class.forName("world.bentobox.bentobox.database.objects.Island");
            getIslandAt = islands.getClass().getMethod("getIslandAt", Location.class);
            getOneBlocksIsland = addon.getClass().getMethod("getOneBlocksIsland", island);
            getCenter = island.getMethod("getCenter");
            getUniqueId = island.getMethod("getUniqueId");
            Class<?> obi = getOneBlocksIsland.getReturnType();
            getBlockNumber = obi.getMethod("getBlockNumber");
            getLifetime = obi.getMethod("getLifetime");
            getPhaseName = obi.getMethod("getPhaseName");
            return true;
        } catch (Exception e) { plugin.getLogger().fine("OneBlock hook not ready: " + e); return false; }
    }

    private record Info(String id, Location center, long block, long lifetime, String phase) {}

    private Info infoAt(Location l) {
        if (l.getWorld() == null || !l.getWorld().getName().startsWith("oneblock") || !hook()) return null;
        try {
            Optional<?> is = (Optional<?>) getIslandAt.invoke(islands, l);
            if (is.isEmpty()) return null;
            Object island = is.get();
            Object ob = getOneBlocksIsland.invoke(addon, island);
            long block = ((Number) getBlockNumber.invoke(ob)).longValue(), life = ((Number) getLifetime.invoke(ob)).longValue();
            return new Info((String) getUniqueId.invoke(island), (Location) getCenter.invoke(island), block, Math.max(life, block), (String) getPhaseName.invoke(ob));
        } catch (Exception e) { return null; }
    }

    private long lifetimeAt(Location l) { Info i = infoAt(l); return i == null ? -1 : i.lifetime(); }

    // ------------------------------------------------------------------ progress tag

    private void updateTags() {
        World w = Bukkit.getWorld("oneblock_world");
        long now = System.currentTimeMillis();
        if (w != null) for (Player p : w.getPlayers()) {
            Info i = infoAt(p.getLocation());
            if (i == null || i.center() == null || i.center().distanceSquared(p.getLocation()) > 64 * 64) continue;
            seen.put(i.id(), now);
            TextDisplay td = tags.get(i.id());
            Location at = i.center().getBlock().getLocation().add(0.5, 3.3, 0.5);
            if (td == null || !td.isValid()) { td = Ui.hologram(at, "", 1.0f); tags.put(i.id(), td); }
            else if (td.getLocation().distanceSquared(at) > 0.01) td.teleport(at);
            td.text(Ui.mm(text(i)));
        }
        // tags nobody is near any more
        for (Iterator<Map.Entry<String, TextDisplay>> it = tags.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - seen.getOrDefault(e.getKey(), 0L) > 15_000) { e.getValue().remove(); it.remove(); }
        }
    }

    private String text(Info i) {
        OneBlockPhases.Phase cur = phases.at(i.block());
        String name = cur != null ? cur.name() : i.phase() == null ? "OneBlock" : i.phase();
        StringBuilder sb = new StringBuilder("<gold><bold>" + name.toUpperCase() + "</bold><newline><gray>Block <white>" + String.format("%,d", i.block()) + " <dark_gray>• <gray>Total <white>" + String.format("%,d", i.lifetime()));
        if (cur != null && cur.length() > 0) {
            long into = Math.max(0, i.block() - cur.start()), pct = Math.min(100, 100 * into / cur.length());
            int filled = (int) (pct / 5);
            OneBlockPhases.Phase next = phases.next(cur);
            sb.append("<newline><green>").append("|".repeat(filled)).append("<dark_gray>").append("|".repeat(20 - filled)).append(" <white>").append(pct).append("%");
            if (next != null) sb.append("<newline><gray>Next: <white>").append(next.name()).append(" <gray>in <white>").append(String.format("%,d", Math.max(0, cur.length() - into))).append(" <gray>blocks");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ spawner fragments

    private boolean isMagicBlock(Block b, Info i) {
        return i != null && i.center() != null && b.getX() == i.center().getBlockX() && b.getY() == i.center().getBlockY() && b.getZ() == i.center().getBlockZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!b.getWorld().getName().startsWith("oneblock")) return;
        Info i = infoAt(b.getLocation());
        if (!isMagicBlock(b, i) || rnd.nextDouble() >= fromBlock) return;
        b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 1, 0.5), Spawners.fragment(1));
        Player p = e.getPlayer();
        p.sendMessage(Ui.mm("<#c6a15b><bold>RARE DROP!</bold> <gray>The OneBlock dropped a <#c6a15b>Spawner Fragment<gray>! <dark_gray>(9 = an Empty Spawner)"));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
        b.getWorld().spawnParticle(Particle.WAX_ON, b.getLocation().add(0.5, 1, 0.5), 30, 0.4, 0.4, 0.4);
    }

    /** OneBlock chests (the biome chests AOneBlock rolls) sometimes hold a fragment too - checked once per chest. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getInventory().getHolder() instanceof Chest chest)) return;
        Block b = chest.getBlock();
        if (!b.getWorld().getName().startsWith("oneblock")) return;
        Info i = infoAt(b.getLocation());
        if (!isMagicBlock(b, i)) return;
        String key = i.id() + "@" + i.block();
        if (!rolledChests.add(key)) return;
        if (rolledChests.size() > 5000) rolledChests.clear();
        if (rnd.nextDouble() >= fromChest) return;
        e.getInventory().addItem(Spawners.fragment(1));
        if (e.getPlayer() instanceof Player p) p.sendMessage(Ui.mm("<#c6a15b>This chest holds a Spawner Fragment!"));
    }

    /** For staff/testing: give fragments. */
    static ItemStack fragments(int n) { return Spawners.fragment(n); }
}
