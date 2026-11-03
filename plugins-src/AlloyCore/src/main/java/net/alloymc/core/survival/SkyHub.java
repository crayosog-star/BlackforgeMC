package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.alloymc.core.build.Canvas;
import net.alloymc.core.build.Paster;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.util.*;

/**
 * The Skyblock Hub: everyone joining the Skyblock server lands here and picks a game through one of two portals:
 * <b>Skyblock</b> (the classic islands; its spawn is Sky Harbor) or <b>OneBlock</b> (its own spawn island, with its
 * own crates). The two games keep separate inventories, ender chests and auction houses ({@link InventoryGroups}),
 * so nothing carries over between them.
 *
 * Worlds: skyhub (this lobby) and oneblock_spawn, both empty void worlds made here; Sky Harbor stays in the main world.
 */
public final class SkyHub implements Listener {
    public static final String LOBBY = "lobby", SKYBLOCK = "skyblock", ONEBLOCK = "oneblock";
    public static final int Y = 100;
    /** Gate openings: x from gx-1 to gx+1, y from Y+1 to Y+4, z = gz. */
    private static final int HUB_GATE_Z = -9, OB_GATE_Z = -18, LEFT_X = -7, RIGHT_X = 7;
    /** The OneBlock spawn's own crates, on the west side of its island. */
    static final List<SurvivalLayout.CrateSpot> OB_CRATES = OneBlockSpawnBuilder.CRATES;

    private final AlloyCore plugin;
    private final World mainWorld, hub, obSpawn;
    private final Location harborSpawn;
    private final List<TextDisplay> holos = new ArrayList<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private InventoryGroups groups;
    private java.util.function.BiConsumer<Player, String> firstVisit = (p, g) -> {};
    private final File visits;
    private SkyHubLife life;

    /** An empty world: the islands are pasted in. */
    public static final class Void extends ChunkGenerator {}

    SkyHub(AlloyCore plugin, World mainWorld, Location harborSpawn) {
        this.plugin = plugin;
        this.mainWorld = mainWorld;
        this.harborSpawn = harborSpawn;
        this.hub = voidWorld("skyhub");
        this.obSpawn = voidWorld("oneblock_spawn");
        this.visits = new File(plugin.getDataFolder(), "skyhub-visits.yml");
        hub.setSpawnLocation(hubSpawn());
        obSpawn.setSpawnLocation(oneblockSpawn());
        for (int cx = -3; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) obSpawn.addPluginChunkTicket(cx, cz, plugin);
        for (int cx = -8; cx <= 8; cx++) for (int cz = -4; cz <= 3; cz++) hub.addPluginChunkTicket(cx, cz, plugin);   // the three isles
        Bukkit.getPluginManager().registerEvents(this, plugin);
        build();
        Bukkit.getScheduler().runTaskLater(plugin, this::holograms, 60L);
        life = new SkyHubLife(plugin, hub);   // signs, leaderboards, kiosk NPCs, the giant OneBlock, crystals, particles
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (holos.stream().anyMatch(t -> !t.isValid())) holograms(); }, 200L, 200L);
    }

    private World voidWorld(String name) {
        World w = Bukkit.getWorld(name);
        if (w != null) return w;
        w = new WorldCreator(name).generator(new Void()).environment(World.Environment.NORMAL).generateStructures(false).createWorld();
        for (String[] r : new String[][]{{"spawn_mobs", "false"}, {"advance_weather", "false"}, {"advance_time", "false"}}) {
            @SuppressWarnings("deprecation") boolean ignored = w.setGameRuleValue(r[0], r[1]);
        }
        w.setTime(6000);
        return w;
    }

    void setGroups(InventoryGroups g) { this.groups = g; }
    void onFirstVisit(java.util.function.BiConsumer<Player, String> f) { this.firstVisit = f; }

    private void arrived(Player p, String game) {
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(visits);
        String key = p.getUniqueId() + "." + game;
        if (y.getBoolean(key)) return;
        y.set(key, true);
        try { y.save(visits); } catch (Exception ignored) { }
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) firstVisit.accept(p, game); }, 20L);
    }

    public Location hubSpawn() { return new Location(hub, SkyHubBuilder.SPAWN_X, Y + 1, SkyHubBuilder.SPAWN_Z, 180, 0); }
    public Location oneblockSpawn() { return new Location(obSpawn, 0.5, Y + 1, 14.5, 180, 0); }
    public World oneblockSpawnWorld() { return obSpawn; }
    public World hubWorld() { return hub; }

    /** Which game a place belongs to. */
    public String groupOf(Location l) {
        World w = l.getWorld();
        if (w == null) return SKYBLOCK;
        String n = w.getName().toLowerCase();
        if (w == hub) return LOBBY;
        if (w == obSpawn || n.startsWith("oneblock")) return ONEBLOCK;
        return SKYBLOCK;
    }

    /** /spawn takes you to the spawn of the game you're playing. */
    public Location spawnFor(Player p) {
        return switch (groupOf(p.getLocation())) {
            case LOBBY -> hubSpawn();
            case ONEBLOCK -> oneblockSpawn();
            default -> harborSpawn;
        };
    }

    // ------------------------------------------------------------------ joining and portals

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.teleport(hubSpawn());
            p.sendMessage(Ui.mm("<#4fd1c5>Welcome to the <bold>Skyblock Hub</bold>! <gray>Use your <gold>compass <gray>or walk through a portal to pick your game: "
                    + "<#4fd1c5>Skyblock <gray>or <#b06bff>OneBlock<gray>. Each keeps its own items."));
            giveCompass(p);
        }, 5L);
    }

    // ------------------------------------------------------------------ the game-picker compass

    private static final int COMPASS_SLOT = 4, MENU_SKYBLOCK = 11, MENU_ONEBLOCK = 15;

    private static final class PickerHolder implements org.bukkit.inventory.InventoryHolder {
        org.bukkit.inventory.Inventory inv;
        public org.bukkit.inventory.Inventory getInventory() { return inv; }
    }

    private boolean isCompass(org.bukkit.inventory.ItemStack it) {
        return it != null && it.getType() == Material.COMPASS && it.hasItemMeta() && it.getItemMeta().hasDisplayName();
    }

    /** The hub keeps its own inventory (InventoryGroups), so the compass lives in the middle of the hotbar. */
    private void giveCompass(Player p) {
        if (p.getWorld() != hub) return;
        for (var it : p.getInventory().getContents()) if (isCompass(it)) return;
        p.getInventory().setItem(COMPASS_SLOT, Ui.item(Material.COMPASS, "<gold><bold>Game Picker</bold> <gray>(right-click)",
                "<gray>Choose <#4fd1c5>Skyblock <gray>or <#b06bff>OneBlock"));
        p.getInventory().setHeldItemSlot(COMPASS_SLOT);
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) giveCompass(p); }, 10L);   // after InventoryGroups swaps the inventory
    }

    @EventHandler
    public void onCompassUse(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || e.getPlayer().getWorld() != hub || !isCompass(e.getItem())) return;
        e.setCancelled(true);
        PickerHolder h = new PickerHolder();
        var inv = Bukkit.createInventory(h, 27, Ui.mm("<dark_gray>Pick your game"));
        h.inv = inv;
        var pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        inv.setItem(MENU_SKYBLOCK, Ui.item(Material.GRASS_BLOCK, "<#4fd1c5><bold>Skyblock", "<gray>The classic islands.", "<gray>Spawn: Sky Harbor", "", "<yellow>» Click to play"));
        inv.setItem(MENU_ONEBLOCK, Ui.item(Material.CRYING_OBSIDIAN, "<#b06bff><bold>OneBlock", "<gray>One block, endless phases.", "<gray>Your own OneBlock island", "", "<yellow>» Click to play"));
        e.getPlayer().openInventory(inv);
        e.getPlayer().playSound(e.getPlayer().getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.5f, 1.4f);
    }

    @EventHandler
    public void onPickerClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof PickerHolder) {
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            boolean sky = e.getRawSlot() == MENU_SKYBLOCK, ob = e.getRawSlot() == MENU_ONEBLOCK;
            if (!sky && !ob) return;
            p.closeInventory();
            p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.3f, 1.6f);
            if (sky) { p.teleport(harborSpawn); p.sendMessage(Ui.mm("<#4fd1c5>Welcome to <bold>Sky Harbor</bold>! <gray>Your island: <white>/is <gray>or the Island Gate")); arrived(p, SKYBLOCK); }
            else { p.teleport(oneblockSpawn()); p.sendMessage(Ui.mm("<#b06bff>Welcome to <bold>OneBlock</bold>! <gray>Your island: <white>/ob <gray>or the portal ahead")); arrived(p, ONEBLOCK); }
        } else if (e.getWhoClicked().getWorld() == hub && (isCompass(e.getCurrentItem()) || isCompass(e.getCursor())
                || (e.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY && e.getHotbarButton() == COMPASS_SLOT))) {
            e.setCancelled(true);   // keep the picker in its slot
        }
    }

    @EventHandler
    public void onCompassDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        if (e.getPlayer().getWorld() == hub && isCompass(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onCompassSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent e) {
        if (e.getPlayer().getWorld() == hub) e.setCancelled(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Location to = e.getTo();
        Player p = e.getPlayer();
        World w = to.getWorld();
        if (w != hub && w != obSpawn) return;
        if (to.getY() < Y - 40) { p.teleport(w == hub ? hubSpawn() : oneblockSpawn()); return; }
        if (w == hub) {
            boolean vanilla = to.getBlockX() == SkyHubBuilder.VANILLA_GATE_X, oneblock = to.getBlockX() == SkyHubBuilder.ONEBLOCK_GATE_X;
            if ((!vanilla && !oneblock) || Math.abs(to.getBlockZ()) > 2 || to.getY() < Y + 1 || to.getY() > Y + 7) return;
            long now = System.currentTimeMillis();
            if (cooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
            cooldown.put(p.getUniqueId(), now + 2500);
            p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.3f, 1.6f);
            if (vanilla) { p.teleport(harborSpawn); p.sendMessage(Ui.mm("<#4fd1c5>Welcome to <bold>Sky Harbor</bold>! <gray>Your island: <white>/is <gray>or the Island Gate")); arrived(p, SKYBLOCK); }
            else { p.teleport(oneblockSpawn()); p.sendMessage(Ui.mm("<#b06bff>Welcome to <bold>OneBlock</bold>! <gray>Your island: <white>/ob <gray>or the portal ahead")); arrived(p, ONEBLOCK); }
            return;
        }
        int gz = OB_GATE_Z;
        if (Math.abs(to.getBlockZ() - gz) > 0 || to.getY() < Y + 1 || to.getY() > Y + 5) return;
        boolean left = Math.abs(to.getBlockX() - LEFT_X) <= 1, right = Math.abs(to.getBlockX() - RIGHT_X) <= 1;
        if (!left && !right) return;
        long now = System.currentTimeMillis();
        if (cooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        cooldown.put(p.getUniqueId(), now + 2500);
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.3f, 1.6f);
        if (w == hub) {
            if (left) { p.teleport(harborSpawn); p.sendMessage(Ui.mm("<#4fd1c5>Welcome to <bold>Sky Harbor</bold>! <gray>Your island: <white>/is <gray>or the Island Gate")); arrived(p, SKYBLOCK); }
            else { p.teleport(oneblockSpawn()); p.sendMessage(Ui.mm("<#b06bff>Welcome to <bold>OneBlock</bold>! <gray>Your island: <white>/ob <gray>or the portal ahead")); arrived(p, ONEBLOCK); }
        } else {
            if (left) p.performCommand("ob");
            else p.teleport(hubSpawn());
        }
    }

    /** The portals are only for show: no Nether trips out of the hub worlds. */
    @EventHandler
    public void onPortal(PlayerPortalEvent e) { if (e.getFrom().getWorld() == hub || e.getFrom().getWorld() == obSpawn) e.setCancelled(true); }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent e) { if (e.getFrom().getWorld() == hub || e.getFrom().getWorld() == obSpawn) e.setCancelled(true); }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && (e.getEntity().getWorld() == hub || e.getEntity().getWorld() == obSpawn)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ the islands

    private void build() {
        if (!new File(plugin.getDataFolder(), "skyhub-v4-built").exists()) {
            // the first hub was a small island here: clear it (only if this is the first three-isle hub), then paste
            if (!new File(plugin.getDataFolder(), "skyhub-v2-built").exists())
                for (int x = -24; x <= 24; x++) for (int z = -24; z <= 24; z++) for (int y = Y - 30; y <= Y + 12; y++) {
                    var b = hub.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            Paster.paste(plugin, hub, SkyHubBuilder.build(), 6000, () -> mark("skyhub-v4-built"));   // v3: a step ledge in the pool; v4: real chains (26.x: iron_chain)
        }
        if (!new File(plugin.getDataFolder(), "oneblock-spawn-v2-built").exists()) {
            // v2: the dark gothic keep. The old grass island is cleared one slice per tick (it is bigger than a single tick can take), then the new one pasted
            int[] x = {-36};
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                if (x[0] > 36) { task.cancel(); Paster.paste(plugin, obSpawn, oneblockIsland(), 4000, () -> mark("oneblock-spawn-v2-built")); return; }
                for (int z = -36; z <= 36; z++) for (int y = Y - 34; y <= Y + 30; y++) {
                    var b = obSpawn.getBlockAt(x[0], y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
                x[0]++;
            }, 40L, 1L);
        }
    }

    private void mark(String f) {
        try { new File(plugin.getDataFolder(), f).createNewFile(); } catch (Exception ignored) { }
        plugin.getLogger().info("Built " + f.replace("-built", ""));
    }

    private static void island(Canvas c, int r, String top, String edge, long seed) {
        Random rnd = new Random(seed);
        for (int x = -r - 2; x <= r + 2; x++)
            for (int z = -r - 2; z <= r + 2; z++) {
                double d = Math.hypot(x, z), R = r + 1.5 * Math.sin(Math.atan2(z, x) * 5 + seed);
                if (d > R) continue;
                c.set(x, Y, z, "minecraft:" + (R - d < 1.2 ? edge : top));
                int depth = (int) Math.round(Math.pow(1 - d / R, 0.8) * r * 0.9 + 2 + rnd.nextInt(2));
                for (int y = 1; y <= depth; y++) c.set(x, Y - y, z, "minecraft:" + (y <= 2 ? "dirt" : y < depth - 1 ? "stone" : "andesite"));
            }
    }

    private static void gate(Canvas c, int gx, int gz, String frame, String trim) {
        for (int x = gx - 2; x <= gx + 2; x++)
            for (int y = Y + 1; y <= Y + 6; y++) {
                boolean edge = x == gx - 2 || x == gx + 2 || y == Y + 6;
                c.set(x, y, gz, edge ? "minecraft:" + (y == Y + 6 || y == Y + 1 ? trim : frame) : "minecraft:nether_portal[axis=x]");
                if (y == Y + 5 && !edge) c.set(x, y, gz, "minecraft:" + frame);
            }
        for (int x = gx - 3; x <= gx + 3; x++) c.set(x, Y, gz + 1, "minecraft:polished_andesite");
        c.setLate(gx - 2, Y + 7, gz, "minecraft:lantern");
        c.setLate(gx + 2, Y + 7, gz, "minecraft:lantern");
    }

    private static void path(Canvas c, int x1, int z1, int x2, int z2) {
        int n = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
        for (int i = 0; i <= n; i++) {
            int x = x1 + (x2 - x1) * i / Math.max(1, n), z = z1 + (z2 - z1) * i / Math.max(1, n);
            for (int dx = -1; dx <= 1; dx++) c.set(x + dx, Y, z, "minecraft:" + ((x + z + dx) % 3 == 0 ? "gravel" : "dirt_path"));
        }
    }

    static Canvas hubIsland() {
        Canvas c = new Canvas();
        island(c, 16, "grass_block", "moss_block", 3);
        path(c, 0, 9, 0, -2);
        path(c, 0, -2, LEFT_X, HUB_GATE_Z + 1);
        path(c, 0, -2, RIGHT_X, HUB_GATE_Z + 1);
        gate(c, LEFT_X, HUB_GATE_Z, "prismarine_bricks", "dark_prismarine");
        gate(c, RIGHT_X, HUB_GATE_Z, "purpur_block", "purpur_pillar");
        // a little fountain in the middle
        c.ring(0, Y, 2, 1.5, 3.2, "minecraft:stone_bricks");
        c.disc(0, Y, 2, 1.6, "minecraft:water");
        for (int[] l : new int[][]{{-4, 10}, {4, 10}, {-11, 0}, {11, 0}}) { c.set(l[0], Y + 1, l[1], "minecraft:oak_fence"); c.setLate(l[0], Y + 2, l[1], "minecraft:lantern"); }
        return c;
    }

    static Canvas oneblockIsland() { return OneBlockSpawnBuilder.build(); }

    private void holograms() {
        holos.forEach(TextDisplay::remove);
        holos.clear();
        holos.add(Ui.hologram(new Location(obSpawn, LEFT_X + 0.5, Y + 8.5, OB_GATE_Z + 0.5), "<#b06bff><bold>YOUR ONEBLOCK</bold><newline><gray>Go to your island • <white>/ob", 1.5f));
        holos.add(Ui.hologram(new Location(obSpawn, RIGHT_X + 0.5, Y + 8.5, OB_GATE_Z + 0.5), "<#4fd1c5><bold>SKYBLOCK HUB</bold><newline><gray>Back to the game picker", 1.5f));
        holos.add(Ui.hologram(new Location(obSpawn, 0.5, Y + 5.2, 0.5), "<#b06bff><bold>ONEBLOCK</bold><newline><gray>Top islands: <white>/istop", 1.4f));
    }

    void shutdown() { holos.forEach(TextDisplay::remove); if (life != null) life.remove(); }
}
