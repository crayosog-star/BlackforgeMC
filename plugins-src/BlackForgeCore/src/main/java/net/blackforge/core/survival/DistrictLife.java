package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
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

    /** The five crate vaults, in the Grand Hall's side aisles (iron, gold, netherite on the west; KOTH and vote on the east). Vault y = Y + 2. */
    static final List<SurvivalLayout.CrateSpot> CRATE_SPOTS = List.of(
            new SurvivalLayout.CrateSpot("iron", -5, -(D - 1)), new SurvivalLayout.CrateSpot("gold", -5, -(D + 3)), new SurvivalLayout.CrateSpot("netherite", -5, -(D + 7)),
            new SurvivalLayout.CrateSpot("koth", 5, -(D - 1)), new SurvivalLayout.CrateSpot("vote", 5, -(D + 3)));
    /** /warp crates and the tour: the middle of the hall, among the vaults. */
    static Location cratesWarp(World world) { return new Location(world, 0.5, Y + 1.07, -(D + 1) + 0.5, 180f, 0f); }
    /** The auction bell: on a gold-flanked pedestal at the front of the Merchant District (bell y = Y + 2). */
    static final int BELL_X = 0, BELL_Z = D - 3;
    static Location marketWarp(World world) { return new Location(world, 0.5, Y + 1, BELL_Z - 2.5, 0f, 0f); }

    /** /sell's landing point. */
    static Location groveWarp(World world) { return new Location(world, KingdomLayout.GROVE_WARP_X, KingdomLayout.GROVE_WARP_Y, KingdomLayout.GROVE_WARP_Z + 0.5, 0f, 0f); }
    static boolean groveBuilt(BlackForgeCore plugin) { return new java.io.File(plugin.getDataFolder(), "spawn-upgrade-phase4").exists(); }

    private final BlackForgeCore plugin;
    private final World world;
    private final NamespacedKey tag;
    private final List<TextDisplay> holos = new ArrayList<>();
    private GroveTrader trader;

    DistrictLife(BlackForgeCore plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.tag = new NamespacedKey(BlackForgeCore.NS, "district_npc");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Spot s : SPOTS) world.addPluginChunkTicket(((int) s.x()) >> 4, ((int) s.z()) >> 4, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ensure, 200L, 600L);
        Bukkit.getScheduler().runTaskLater(plugin, this::relocate, 160L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::relocate2, 240L, 600L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::gateGlow, 100L, 8L);
        world.addPluginChunkTicket(KingdomLayout.GROVE_X >> 4, KingdomLayout.GROVE_Z >> 4, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::groveAir, 120L, 10L);
    }

    /** Once: the crates move into the Grand Hall and the auction bell into the Merchant District; the old vaults and bell on the island are taken down. */
    private void relocate() {
        java.io.File marker = new java.io.File(plugin.getDataFolder(), "spawn-move-v1");
        if (marker.exists() || !new java.io.File(plugin.getDataFolder(), "spawn-upgrade-phase2").exists()) return;
        for (SurvivalLayout.CrateSpot s : CRATE_SPOTS) {
            world.getBlockAt(s.x(), Y + 1, s.z()).setType(org.bukkit.Material.GILDED_BLACKSTONE, false);
            world.getBlockAt(s.x(), Y + 2, s.z()).setBlockData(Bukkit.createBlockData("minecraft:vault[facing=" + (s.x() < 0 ? "east" : "west") + "]"), false);
        }
        for (SurvivalLayout.CrateSpot s : SurvivalLayout.CRATES) {   // the old pavilion's vaults become plain pedestals
            var b = world.getBlockAt(s.x(), Y + 2, s.z());
            if (b.getType() == org.bukkit.Material.VAULT) b.setType(org.bukkit.Material.CHISELED_POLISHED_BLACKSTONE, false);
        }
        world.getBlockAt(BELL_X, Y + 1, BELL_Z).setType(org.bukkit.Material.POLISHED_BLACKSTONE_BRICKS, false);
        world.getBlockAt(BELL_X - 1, Y + 1, BELL_Z).setType(org.bukkit.Material.GOLD_BLOCK, false);
        world.getBlockAt(BELL_X + 1, Y + 1, BELL_Z).setType(org.bukkit.Material.GOLD_BLOCK, false);
        world.getBlockAt(BELL_X, Y + 2, BELL_Z).setBlockData(Bukkit.createBlockData("minecraft:bell[attachment=floor,facing=north]"), false);
        var oldBell = world.getBlockAt(SurvivalLayout.BELL_X, Y + 2, SurvivalLayout.BELL_Z);
        if (oldBell.getType() == org.bukkit.Material.BELL) oldBell.setType(org.bukkit.Material.AIR, false);
        try { marker.createNewFile(); } catch (Exception ignored) { }
        plugin.getLogger().info("Spawn move v1: crates are in the Grand Hall, the auction bell is in the Merchant District");
    }

    /** The arrival point: inside the Grand Hall, on the carpet, facing the throne. */
    static Location arrival(World world) { return new Location(world, 0.5, Y + 1.07, -(D - 4) + 0.5 - 1, 180f, 0f); }

    static Location warp(World world, String id) {
        for (Spot s : SPOTS) if (s.id().equals(id)) return new Location(world, s.x(), Y + 1, s.z(), s.yaw(), 0);
        return null;
    }

    /** Once the annexes are built: the old Trading Hall villagers, drop shafts and Wild gate on the island are taken down, the new gate opened. */
    private void relocate2() {
        java.io.File marker = new java.io.File(plugin.getDataFolder(), "spawn-move-v2");
        if (marker.exists() || !new java.io.File(plugin.getDataFolder(), "spawn-upgrade-phase3").exists()) return;
        // the old villagers
        Location oldHall = new Location(world, 0, Y + 2, -69);
        for (Entity e : world.getNearbyEntities(oldHall, 20, 12, 14)) if (e instanceof Villager v && v.isInvulnerable() && !v.hasAI()) v.remove();
        // the old drop shafts (x -41..-39, z -6..-4 and 4..6): capped at floor level
        for (int z : new int[]{-5, 5}) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var b = world.getBlockAt(-40 + dx, Y, z + dz);
            if (b.getType().isAir()) b.setType(org.bukkit.Material.POLISHED_BLACKSTONE_BRICKS, false);
        }
        // the old Wild gate's portal blocks (they would send players to the Nether)
        for (int x = 40; x <= 44; x++) for (int z = -5; z <= 6; z++) for (int y = Y + 1; y <= Y + 11; y++) {
            var b = world.getBlockAt(x, y, z);
            if (b.getType() == org.bukkit.Material.NETHER_PORTAL) b.setType(org.bukkit.Material.AIR, false);
        }
        // the new gate: the glass curtain becomes an open doorway
        for (int z = -3; z <= 3; z++) for (int y = Y + 1; y <= Y + 8; y++) {
            var b = world.getBlockAt(-D - 5, y, z);
            if (b.getType() == org.bukkit.Material.ORANGE_STAINED_GLASS_PANE) b.setType(org.bukkit.Material.AIR, false);
        }
        try { marker.createNewFile(); } catch (Exception ignored) { }
        plugin.getLogger().info("Spawn move v2: Trading Hall, Hall of Fame, Wild gate and Drop Zone are in the kingdom");
    }

    /** The Wild gate shimmers while anyone is near. */
    private void gateGlow() {
        if (world.getPlayers().stream().noneMatch(p -> Math.abs(p.getX() + D) < 30 && Math.abs(p.getZ()) < 20)) return;
        world.spawnParticle(org.bukkit.Particle.PORTAL, -D - 4.5, Y + 4, 0.5, 18, 0.4, 2.6, 3.0, 0.3);
        world.spawnParticle(org.bukkit.Particle.REVERSE_PORTAL, -D - 4.5, Y + 3, 0.5, 6, 0.3, 2.0, 2.5, 0.05);
    }

    void setTrader(GroveTrader trader) { this.trader = trader; }

    /** Drifting spores and golden motes in the Grove while anyone is there. */
    private void groveAir() {
        for (org.bukkit.entity.Player p : world.getPlayers()) {
            if (!KingdomLayout.inGrove(p.getX(), p.getY(), p.getZ())) continue;
            world.spawnParticle(org.bukkit.Particle.SPORE_BLOSSOM_AIR, p.getX(), p.getY() + 4, p.getZ(), 6, 8, 3, 8, 0);
            world.spawnParticle(org.bukkit.Particle.WAX_ON, p.getX(), p.getY() + 2, p.getZ(), 2, 7, 2, 7, 0);
        }
    }

    private void ensure() {
        if (!new java.io.File(plugin.getDataFolder(), "spawn-upgrade-phase2").exists()) return;   // only once the districts are built
        if (holos.isEmpty() || holos.stream().anyMatch(t -> !t.isValid())) holograms();
        villager("blacksmith", "<#ff7a1a>Blacksmith", Villager.Profession.WEAPONSMITH, D + 1.5, 0.5, 90f);
        villager("merchant", "<gold>Merchant", Villager.Profession.LIBRARIAN, 0.5, D + 1.5, 180f);
        if (groveBuilt(plugin)) {
            if (trader != null) trader.ensureFrames(world);
            villager("grove_daily", "<gradient:#ffd27a:#5fd38a>Daily Shop</gradient>", Villager.Profession.CARTOGRAPHER, KingdomLayout.GROVE_X - 5.5, KingdomLayout.GROVE_Y + 1, KingdomLayout.GROVE_Z + 0.5, -90f);
            villager("grove_trader", "<gradient:#ffd27a:#5fd38a>Trader</gradient>", Villager.Profession.FARMER, KingdomLayout.GROVE_X + 0.5, KingdomLayout.GROVE_Y + 1, KingdomLayout.GROVE_Z + 6.5, 180f);
            villager("grove_auction", "<gradient:#ffd27a:#5fd38a>Auction House</gradient>", Villager.Profession.LIBRARIAN, KingdomLayout.GROVE_X + 6.5, KingdomLayout.GROVE_Y + 1, KingdomLayout.GROVE_Z + 0.5, 90f);
        }
    }

    private void villager(String id, String name, Villager.Profession prof, double x, double z, float yaw) {
        villager(id, name, prof, x, Y + 1, z, yaw);
    }

    private void villager(String id, String name, Villager.Profession prof, double x, double y, double z, float yaw) {
        Location at = new Location(world, x, y, z, yaw, 0);
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
        if (groveBuilt(plugin) && trader != null) trader.priceTags(world, holos);
        if (groveBuilt(plugin)) holos.add(Ui.hologram(new Location(world, KingdomLayout.GROVE_WARP_X, KingdomLayout.GROVE_Y + 5, KingdomLayout.GROVE_WARP_Z + 2.5),
                "<gradient:#ffd27a:#5fd38a><bold>THE GILDED GROVE</bold></gradient><newline><gray>A BlackForgeMC trading haven<newline><white>Sell your goods <gray>at the pavilion ahead", 1.0f));
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
        else if (id.equals("merchant") || id.equals("grove_auction")) p.performCommand("ah");
        else if (id.equals("grove_daily")) p.performCommand("dailyshop");
        else if (id.equals("grove_trader") && trader != null) trader.open(p);
    }

    void shutdown() { holos.forEach(TextDisplay::remove); }
}
