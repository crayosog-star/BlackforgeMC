package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Life in the four districts beyond the Survival rampart (spawn upgrade phase 2): the Blacksmith (opens an anvil),
 * the Merchant (opens the auction house), signs of information in the Grand Hall and at the Wilds gate, and the
 * /warp points that bring players to each bridge head. Villagers and holograms are re-created whenever they go missing.
 */
final class DistrictLife implements Listener {
    private static final int Y = SurvivalLayout.Y;
    private static final int D = SpawnUpgradeBuilder.DIST;

    /** A district's warp point (on the platform, by the bridge head), facing into it. */
    record Spot(String id, String title, double x, double z, float yaw) {}
    static final List<Spot> SPOTS = List.of(
            new Spot("smithy", "the Smithy", D - 7 + 0.5, 0.5, -90f),
            new Spot("market", "the Merchant District", 0.5, D - 7 + 0.5, 0f),
            new Spot("wilds", "the Gate of the Wilds", -(D - 6) + 0.5, 0.5, 90f),
            new Spot("hall", "the Grand Hall", 0.5, -(D - 7) + 0.5, 180f));

    private final AlloyCore plugin;
    private final World world;
    private final NamespacedKey tag;
    private final List<TextDisplay> holos = new ArrayList<>();

    DistrictLife(AlloyCore plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.tag = new NamespacedKey(plugin, "district_npc");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Spot s : SPOTS) world.addPluginChunkTicket(((int) s.x()) >> 4, ((int) s.z()) >> 4, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ensure, 200L, 600L);
    }

    static Location warp(World world, String id) {
        for (Spot s : SPOTS) if (s.id().equals(id)) return new Location(world, s.x(), Y + 1, s.z(), s.yaw(), 0);
        return null;
    }

    private void ensure() {
        if (!new java.io.File(plugin.getDataFolder(), "spawn-upgrade-phase2").exists()) return;   // only once the districts are built
        if (holos.isEmpty() || holos.stream().anyMatch(t -> !t.isValid())) holograms();
        villager("blacksmith", "<#ff7a1a>Blacksmith", Villager.Profession.WEAPONSMITH, D + 1.5, 0.5, 90f);
        villager("merchant", "<gold>Merchant", Villager.Profession.LIBRARIAN, 0.5, D + 1.5, 180f);
    }

    private void villager(String id, String name, Villager.Profession prof, double x, double z, float yaw) {
        Location at = new Location(world, x, Y + 1, z, yaw, 0);
        Villager found = null;
        for (Entity e : world.getNearbyEntities(at, 4, 4, 4)) {
            if (!(e instanceof Villager v) || !id.equals(v.getPersistentDataContainer().get(tag, PersistentDataType.STRING))) continue;
            if (found == null) found = v; else v.remove();
        }
        if (found != null) return;
        world.spawn(at, Villager.class, v -> {
            v.getPersistentDataContainer().set(tag, PersistentDataType.STRING, id);
            v.setProfession(prof);
            v.setVillagerLevel(5);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCollidable(false);
            v.setSilent(true);
            v.customName(Ui.mm(name));
            v.setCustomNameVisible(true);
        });
    }

    private void holograms() {
        holos.forEach(TextDisplay::remove);
        holos.clear();
        holos.add(Ui.hologram(new Location(world, 0.5, Y + 6, -(D - 7) + 0.5),
                "<#ff6a00><bold>BLACKFORGEMC</bold><newline><gray>Welcome to the Grand Hall<newline><white>/warp <gray>• <white>/daily <gray>• <white>/vote <gray>• <white>/pv<newline><gray>Crates and the Hall of Fame are at spawn", 1.3f));
        holos.add(Ui.hologram(new Location(world, D + 1.5, Y + 3.2, 0.5), "<#ff7a1a><bold>BLACKSMITH</bold><newline><gray>Right-click to use the anvil", 1.0f));
        holos.add(Ui.hologram(new Location(world, 0.5, Y + 3.2, D + 1.5), "<gold><bold>MERCHANT</bold><newline><gray>Right-click to open the Auction House", 1.0f));
        holos.add(Ui.hologram(new Location(world, -(D - 6) + 0.5, Y + 4, 0.5), "<#e5484d><bold>THE WILDS</bold><newline><gray>Use <white>/warp wild <gray>to leave on an expedition", 1.2f));
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Villager v)) return;
        String id = v.getPersistentDataContainer().get(tag, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (id.equals("blacksmith")) p.openAnvil(null, true);
        else if (id.equals("merchant")) p.performCommand("ah");
    }

    void shutdown() { holos.forEach(TextDisplay::remove); }
}
