package net.blackforge.core.raft;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

import static net.blackforge.core.raft.RaftMover.TILE_Y;

/**
 * Raft: a survival game on the open sea. Each crew (a solo player or friends) gets its own ocean, a small raft, and a hook to
 * drag floating debris in. Build the raft out, purify water, farm, fight sharks, put up a sail and a motor, and follow the radio
 * towers to Utopia (and Bruce). See {@link RaftStructures} for the map, {@link RaftStations} for what you can build.
 *
 * Commands: /raft [help | home | info | invite &lt;player&gt; | accept | leave | kick &lt;player&gt;], staff: /blackforge raft ...
 * Config: raft.sharks (true).
 */
public final class RaftModule implements Listener {
    public static final String WORLD = "raft_ocean";
    private static final long INVITE_MS = 60_000;

    private final BlackForgeCore plugin;
    private World world;
    private final Map<UUID, RaftTeam> byOwner = new LinkedHashMap<>(), byMember = new HashMap<>();
    private final Set<UUID> forced = new HashSet<>();     // owners whose raft counts as active with nobody aboard (staff testing)
    private final Map<UUID, UUID> invites = new HashMap<>();   // invited player -> owner
    private final Map<UUID, Long> inviteAt = new HashMap<>();
    private final File file;
    private boolean dirty;
    private RaftDebris debris;
    private RaftSharks sharks;
    private RaftStory story;
    private RaftNeeds needs;

    public RaftModule(BlackForgeCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "raft-teams.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, this::init, 20L);
        register();
    }

    BlackForgeCore plugin() { return plugin; }
    World world() { return world; }
    RaftDebris debris() { return debris; }
    RaftSharks sharks() { return sharks; }
    RaftStory story() { return story; }
    Collection<RaftTeam> teams() { return byOwner.values(); }
    void markDirty() { dirty = true; }

    private void init() {
        world = Bukkit.getWorld(WORLD);
        if (world == null) world = new WorldCreator(WORLD).generator(new RaftGenerator()).environment(World.Environment.NORMAL).type(WorldType.NORMAL).generateStructures(false).createWorld();
        if (world == null) { plugin.getLogger().severe("Raft: could not create the ocean world"); return; }
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setDifficulty(Difficulty.NORMAL);
        world.setPVP(false);
        world.setStorm(false);
        world.setSpawnLocation(0, RaftStructures.SEA + 1, 0);
        load();
        debris = new RaftDebris(this);
        debris.clearAll();
        sharks = new RaftSharks(this);
        story = new RaftStory(this);
        needs = new RaftNeeds(this);
        new RaftStations(this);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::sidebars, 60L, 40L);
        plugin.getLogger().info("Raft ocean ready: " + byOwner.size() + " raft(s)");
    }

    public void shutdown() {
        if (world != null) save();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("teams");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                RaftTeam t = RaftTeam.load(s.getConfigurationSection(k));
                byOwner.put(t.owner, t);
                for (UUID m : t.members) byMember.put(m, t);
            } catch (Exception ex) { plugin.getLogger().warning("Raft: skipped a bad team '" + k + "': " + ex); }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (RaftTeam t : byOwner.values()) t.save(y.createSection("teams." + t.owner));
        try { y.save(file); dirty = false; } catch (Exception e) { plugin.getLogger().warning("Raft: save failed: " + e); }
    }

    private void saveIfDirty() { if (dirty) save(); }

    // ------------------------------------------------------------------ lookups

    public RaftTeam teamOf(Player p) { return byMember.get(p.getUniqueId()); }

    List<RaftTeam> activeTeams() {
        List<RaftTeam> out = new ArrayList<>();
        if (world == null) return out;
        for (RaftTeam t : byOwner.values()) {
            if (forced.contains(t.owner)) { out.add(t); continue; }
            for (UUID m : t.members) { Player p = Bukkit.getPlayer(m); if (p != null && p.getWorld() == world) { out.add(t); break; } }
        }
        return out;
    }

    List<Player> membersOnline(RaftTeam t) {
        List<Player> out = new ArrayList<>();
        for (UUID m : t.members) { Player p = Bukkit.getPlayer(m); if (p != null && p.isOnline()) out.add(p); }
        return out;
    }

    void tell(RaftTeam t, String mm) { for (Player p : membersOnline(t)) p.sendMessage(Ui.mm(mm)); }

    RaftTeam teamNear(Location l, double radius) {
        RaftTeam best = null; double bd = radius;
        for (RaftTeam t : byOwner.values()) {
            int[] c = t.centre();
            double d = Math.hypot(c[0] - l.getX(), c[1] - l.getZ());
            if (d < bd) { bd = d; best = t; }
        }
        return best;
    }

    Location arrival(RaftTeam t) {
        if (world == null) return null;
        int[] c = t.centre();
        world.getChunkAt(c[0] >> 4, c[1] >> 4).load(true);
        return new Location(world, c[0] + 0.5, TILE_Y + 1, c[1] + 0.5, 0f, 0f);
    }

    // ------------------------------------------------------------------ creating a raft

    private RaftTeam create(UUID owner, String ownerName) {
        int cell = 0;
        for (RaftTeam t : byOwner.values()) cell = Math.max(cell, t.cell + 1);
        RaftTeam t = new RaftTeam(owner, ownerName + "'s raft", cell);
        t.wind = new Random().nextInt(4);
        t.sharkAt = 0;
        int ox = RaftStructures.originX(cell);
        world.getChunkAt(ox >> 4, 0).load(true);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            world.getChunkAt((ox + x) >> 4, z >> 4).load(true);
            RaftMover.placeTile(world, t, ox + x, z);
        }
        Block barrel = world.getBlockAt(ox + 1, TILE_Y + 1, 1);
        barrel.setType(Material.BARREL, false);
        if (barrel.getState() instanceof Barrel b) {
            b.getInventory().addItem(new ItemStack(Material.OAK_PLANKS, 6), RaftItems.make("thatch", 4), RaftItems.make("plastic", 3), new ItemStack(Material.BREAD, 3), RaftItems.make("fresh", 2));
        }
        byOwner.put(owner, t);
        byMember.put(owner, t);
        dirty = true;
        return t;
    }

    private void kit(Player p) {
        p.getInventory().addItem(RaftItems.make("hook"), RaftItems.make("cup", 2), RaftItems.make("workbench"), RaftItems.make("foundation", 4))
                .values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
    }

    void home(Player p) {
        if (world == null) { p.sendMessage(Ui.mm("<gray>The sea is still filling. Try again in a moment.")); return; }
        RaftTeam t = teamOf(p);
        boolean fresh = false;
        if (t == null) { t = create(p.getUniqueId(), p.getName()); fresh = true; }
        Location at = arrival(t);
        p.teleport(at);
        p.setGameMode(GameMode.SURVIVAL);
        if (fresh) {
            kit(p);
            p.sendMessage(Ui.mm(" "));
            p.sendMessage(Ui.mm("<#7fd6ff><bold>RAFT</bold> <gray>- You wake on a few planks in an endless sea."));
            p.sendMessage(Ui.mm("<gray>Right-click debris with your <#c8a165>Hook</#c8a165> to pull it in. Place the <#c8a165>Workbench</#c8a161> and research."));
            p.sendMessage(Ui.mm("<gray>Keep drinking: scoop water with a <aqua>Cup</aqua>, purify it. Build out with <#c8a165>Foundations</#c8a165>."));
            p.sendMessage(Ui.mm("<gray>Find the radio towers. Friends can join your raft: <white>/raft invite <name>"));
        } else p.sendActionBar(Ui.mm("<#7fd6ff>Back on the raft."));
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && (world == null || p.getWorld() != world)) home(p); }, 30L);
    }

    /** Island, wreck and tower chests are stocked the first time somebody opens them. */
    @EventHandler
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent e) {
        if (world == null || e.getInventory().getLocation() == null || e.getInventory().getLocation().getWorld() != world) return;
        List<Block> blocks = new ArrayList<>();
        org.bukkit.inventory.InventoryHolder h = e.getInventory().getHolder(false);
        if (h instanceof org.bukkit.block.DoubleChest dc) {
            if (dc.getLeftSide(false) instanceof org.bukkit.block.Chest l) blocks.add(l.getBlock());
            if (dc.getRightSide(false) instanceof org.bukkit.block.Chest r) blocks.add(r.getBlock());
        } else if (h instanceof org.bukkit.block.Chest c) blocks.add(c.getBlock());
        for (Block b : blocks) {
            String kind = RaftLoot.takeTag(b);
            if (kind != null) RaftLoot.fill(e.getInventory(), kind, new Random(Math.abs((long) b.getX() * 31 + b.getZ() * 17 + b.getY() * 7)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { if (dirty) save(); }

    @EventHandler
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getLocation().getWorld() == world && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) e.setCancelled(true);
    }

    @EventHandler
    public void onHanging(HangingPlaceEvent e) {
        if (e.getEntity().getWorld() == world && e.getPlayer() != null) { e.setCancelled(true); e.getPlayer().sendActionBar(Ui.mm("<red>Frames and paintings do not stay up on a moving raft.")); }
    }

    // ------------------------------------------------------------------ sidebar

    private void sidebars() {
        if (world == null) return;
        for (Player p : world.getPlayers()) {
            RaftTeam t = teamOf(p);
            if (t == null) continue;
            int th = needs.thirst(t, p.getUniqueId());
            Ui.sidebar(p, List.of(
                    "<dark_gray>" + "-".repeat(18),
                    "<#7fd6ff>■ <white>" + (t.name.length() > 22 ? t.name.substring(0, 22) : t.name),
                    "<gray>Crew: <white>" + t.members.size() + "   <gray>Tiles: <white>" + t.tiles.size(),
                    "<gray>Thirst: <white>" + th + "<gray>/20",
                    "<gray>Wind: <white>" + RaftTeam.DIR_NAMES[t.wind],
                    "<gray>Course: <white>" + (t.steer < 0 ? "anchored" : RaftTeam.DIR_NAMES[t.steer] + (t.sailing ? " (sail)" : " (motor)")),
                    "<gray>Radio: <white>" + t.towers + "<gray>/3 towers" + (t.won ? " <gold>★" : ""),
                    "<dark_gray>" + "-".repeat(18) + " ",
                    "<gold>" + plugin.getConfig().getString("address", "BlackForgeMC")));
        }
    }

    // ------------------------------------------------------------------ commands

    private void register() {
        Bukkit.getCommandMap().register("blackforge", new Command("raft", "Raft: your raft and crew", "/raft [home|info|invite|accept|leave|kick|help]", List.of("raftmode")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) command(p, a); else s.sendMessage("Players only.");
                return true;
            }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) {
                if (a.length == 1) return List.of("home", "info", "invite", "accept", "leave", "kick", "help");
                return null;
            }
        });
    }

    private void command(Player p, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "home";
        RaftTeam t = teamOf(p);
        switch (sub) {
            case "home", "" -> home(p);
            case "help" -> {
                p.sendMessage(Ui.mm("<#7fd6ff><bold>Raft</bold> <gray>- /raft home, info, invite <name>, accept, leave, kick <name>"));
                p.sendMessage(Ui.mm("<gray>Hook debris, research at a Workbench, purify water, build out, sail, follow the radio towers."));
            }
            case "info" -> {
                if (t == null) { p.sendMessage(Ui.mm("<gray>You have no raft yet. <white>/raft")); return; }
                p.sendMessage(Ui.mm("<#7fd6ff><bold>" + t.name + "</bold> <gray>- " + t.tiles.size() + " tiles, radio " + t.towers + "/3" + (t.won ? ", escaped" : "")));
                p.sendMessage(Ui.mm("<gray>Crew: <white>" + String.join(", ", t.members.stream().map(u -> { var op = Bukkit.getOfflinePlayer(u); return op.getName() == null ? "?" : op.getName(); }).toList())));
                p.sendMessage(Ui.mm("<gray>Blueprints: <white>" + String.join(", ", t.unlocked)));
            }
            case "invite" -> {
                if (t == null) { p.sendMessage(Ui.mm("<gray>You have no raft yet. <white>/raft")); return; }
                if (a.length < 2) { p.sendMessage(Ui.mm("<gray>/raft invite <player>")); return; }
                Player o = Bukkit.getPlayerExact(a[1]);
                if (o == null) { p.sendMessage(Ui.mm("<red>" + a[1] + " is not on this server. <gray>They need to be on Raft.")); return; }
                if (t.members.size() >= 6) { p.sendMessage(Ui.mm("<red>A raft holds six at most.")); return; }
                if (o == p || t.members.contains(o.getUniqueId())) { p.sendMessage(Ui.mm("<gray>They are already aboard.")); return; }
                invites.put(o.getUniqueId(), t.owner);
                inviteAt.put(o.getUniqueId(), System.currentTimeMillis());
                p.sendMessage(Ui.mm("<green>Invited <white>" + o.getName() + "<green>."));
                o.sendMessage(Ui.mm("<#7fd6ff>" + p.getName() + " <gray>invites you aboard their raft. <click:run_command:'/raft accept'><green><u>[Accept]</u></click> <gray>(60s)"));
            }
            case "accept" -> {
                UUID owner = invites.remove(p.getUniqueId());
                Long at = inviteAt.remove(p.getUniqueId());
                RaftTeam target = owner == null ? null : byOwner.get(owner);
                if (target == null || at == null || System.currentTimeMillis() - at > INVITE_MS) { p.sendMessage(Ui.mm("<gray>No invite to accept.")); return; }
                if (target.members.size() >= 6) { p.sendMessage(Ui.mm("<red>That raft is full.")); return; }
                if (t != null) leave(p, t, false);
                target.members.add(p.getUniqueId());
                byMember.put(p.getUniqueId(), target);
                dirty = true;
                tell(target, "<green>" + p.getName() + " <gray>joined the crew.");
                home(p);
            }
            case "leave" -> {
                if (t == null) { p.sendMessage(Ui.mm("<gray>You are not on a raft.")); return; }
                leave(p, t, true);
                home(p);   // a fresh raft of their own
            }
            case "kick" -> {
                if (t == null || !t.owner.equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>Only the raft's owner can do that.")); return; }
                if (a.length < 2) { p.sendMessage(Ui.mm("<gray>/raft kick <player>")); return; }
                Player o = Bukkit.getPlayerExact(a[1]);
                if (o == null || !t.members.contains(o.getUniqueId()) || o == p) { p.sendMessage(Ui.mm("<red>Nobody by that name is on your crew.")); return; }
                leave(o, t, true);
                o.sendMessage(Ui.mm("<red>You were put off the raft."));
                home(o);
            }
            default -> p.sendMessage(Ui.mm("<gray>/raft help"));
        }
    }

    private void leave(Player p, RaftTeam t, boolean say) {
        t.members.remove(p.getUniqueId());
        byMember.remove(p.getUniqueId());
        if (t.members.isEmpty()) { byOwner.remove(t.owner); forced.remove(t.owner); }
        else if (t.owner.equals(p.getUniqueId())) {
            RaftTeam moved = t;
            byOwner.remove(t.owner);
            moved.owner = moved.members.iterator().next();
            byOwner.put(moved.owner, moved);
        }
        dirty = true;
        if (say) tell(t, "<gray>" + p.getName() + " left the crew.");
    }

    // ------------------------------------------------------------------ staff

    /** /blackforge raft ... : newteam &lt;name&gt; | list | move &lt;name&gt; &lt;dir&gt; [n] | shark &lt;name&gt; | debris &lt;name&gt; | tp &lt;name&gt; | status */
    public boolean admin(CommandSender s, String[] a) {
        if (a.length < 1 || !a[0].equalsIgnoreCase("raft")) return false;
        if (!s.hasPermission("blackforge.admin")) { s.sendMessage(Ui.mm("<red>You need blackforge.admin for that.")); return true; }
        if (world == null) { s.sendMessage(Ui.mm("<red>The ocean is not up yet.")); return true; }
        String sub = a.length > 1 ? a[1].toLowerCase() : "list";
        switch (sub) {
            case "newteam" -> {
                if (a.length < 3) { s.sendMessage(Ui.mm("<gray>/blackforge raft newteam <name>  (a test raft nobody owns, kept active)")); return true; }
                UUID id = UUID.nameUUIDFromBytes(("raft-test-" + a[2]).getBytes());
                if (byOwner.containsKey(id)) { s.sendMessage(Ui.mm("<yellow>That test raft exists.")); return true; }
                RaftTeam t = create(id, a[2]);
                forced.add(id);
                s.sendMessage(Ui.mm("<green>Test raft '" + a[2] + "' in cell " + t.cell + " at " + RaftStructures.originX(t.cell) + ", 0, wind " + RaftTeam.DIR_NAMES[t.wind]));
            }
            case "list" -> {
                s.sendMessage(Ui.mm("<#7fd6ff>" + byOwner.size() + " raft(s)"));
                for (RaftTeam t : byOwner.values()) { int[] c = t.centre(); s.sendMessage(Ui.mm("<gray>" + t.name + " <dark_gray>cell " + t.cell + " at " + c[0] + "," + c[1] + " tiles " + t.tiles.size() + " crew " + t.members.size() + " towers " + t.towers + " steer " + t.steer)); }
            }
            case "move" -> {
                RaftTeam t = find(a.length > 2 ? a[2] : "");
                int dir = a.length > 3 ? Arrays.asList(RaftTeam.DIR_NAMES).indexOf(a[3].toLowerCase()) : -1;
                if (t == null || dir < 0) { s.sendMessage(Ui.mm("<gray>/blackforge raft move <name> <east|south|west|north> [blocks]")); return true; }
                int n = a.length > 4 ? Integer.parseInt(a[4]) : 1, done = 0;
                RaftMover.Result res = RaftMover.Result.OK;
                for (int i = 0; i < n && res == RaftMover.Result.OK; i++) { res = RaftMover.step(world, t, RaftTeam.DIRS[dir][0], RaftTeam.DIRS[dir][1]); if (res == RaftMover.Result.OK) done++; }
                markDirty();
                s.sendMessage(Ui.mm("<green>Moved " + done + " block(s) " + RaftTeam.DIR_NAMES[dir] + (res != RaftMover.Result.OK ? " <yellow>then " + res : "")));
            }
            case "shark" -> {
                RaftTeam t = find(a.length > 2 ? a[2] : "");
                if (t == null) { s.sendMessage(Ui.mm("<gray>/blackforge raft shark <name>")); return true; }
                int[] c = t.centre();
                sharks.spawn(new Location(world, c[0] + 14.5, TILE_Y - 1, c[1] + 0.5), "<red><bold>Shark", 2.0, 40, false);
                s.sendMessage(Ui.mm("<green>A shark."));
            }
            case "debris" -> {
                RaftTeam t = find(a.length > 2 ? a[2] : "");
                if (t == null) { s.sendMessage(Ui.mm("<gray>/blackforge raft debris <name>")); return true; }
                forced.add(t.owner);
                s.sendMessage(Ui.mm("<green>Debris keeps coming for " + t.name + " (active even with nobody aboard)."));
            }
            case "tp" -> {
                RaftTeam t = find(a.length > 2 ? a[2] : "");
                if (t == null || !(s instanceof Player p)) { s.sendMessage(Ui.mm("<gray>/blackforge raft tp <name>")); return true; }
                p.teleport(arrival(t));
            }
            case "story" -> {
                RaftTeam t = find(a.length > 2 ? a[2] : "");
                if (t == null) { s.sendMessage(Ui.mm("<gray>/blackforge raft story <name>")); return true; }
                int[] st = RaftStructures.starterPos(world.getSeed(), t.cell);
                s.sendMessage(Ui.mm("<gray>starter island " + st[0] + ", " + st[1]));
                for (int k = 0; k < 4; k++) { int[] p = RaftStructures.storyPos(world.getSeed(), t.cell, k), r = RaftStructures.radioAt(world.getSeed(), t.cell, k); s.sendMessage(Ui.mm("<gray>" + (k < 3 ? "tower " + (k + 1) : "utopia") + " " + p[0] + ", " + p[1] + "  radio " + r[0] + "," + r[1] + "," + r[2])); }
            }
            case "loot" -> {   // /blackforge raft loot <x> <y> <z>: stock a tagged chest now, as opening it would
                if (a.length < 5) { s.sendMessage(Ui.mm("<gray>/blackforge raft loot <x> <y> <z>")); return true; }
                Block b = world.getBlockAt(Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4]));
                String kind = RaftLoot.takeTag(b);
                if (kind == null || !(b.getState(false) instanceof org.bukkit.block.Chest c)) { s.sendMessage(Ui.mm("<yellow>No tagged chest there (" + b.getType() + ").")); return true; }
                RaftLoot.fill(c.getInventory(), kind, new Random(1));
                int n = 0; for (ItemStack it : c.getInventory().getContents()) if (it != null) n += it.getAmount();
                s.sendMessage(Ui.mm("<green>Stocked a '" + kind + "' chest: " + n + " items."));
            }
            case "station" -> {   // /blackforge raft station <name> <type> [dx dz]: put a machine on a tile (testing)
                RaftTeam t = find(a.length > 2 ? a[2] : "");
                String type = a.length > 3 ? a[3] : "";
                if (t == null || !RaftItems.exists(type)) { s.sendMessage(Ui.mm("<gray>/blackforge raft station <name> <workbench|purifier|plot|sail|wheel|motor> [dx dz]")); return true; }
                int[] c = t.centre();
                int x = c[0] + (a.length > 5 ? Integer.parseInt(a[4]) : 0), z = c[1] + (a.length > 5 ? Integer.parseInt(a[5]) : 0);
                Block b = world.getBlockAt(x, TILE_Y + 1, z);
                b.setType(type.equals("plot") ? Material.FARMLAND : new ItemStack(RaftItems.make(type).getType()).getType(), false);
                RaftTeam.Station st = new RaftTeam.Station(type, x, TILE_Y + 1, z);
                if (type.equals("motor")) st.fuel = 20;
                t.stations.put(st.key(), st);
                markDirty();
                s.sendMessage(Ui.mm("<green>" + type + " at " + x + "," + (TILE_Y + 1) + "," + z));
            }
            case "steer" -> {
                RaftTeam t = find(a.length > 2 ? a[2] : "");
                int dir = a.length > 3 ? Arrays.asList(RaftTeam.DIR_NAMES).indexOf(a[3].toLowerCase()) : -2;
                if (t == null || dir == -1 && !(a.length > 3 && a[3].equals("stop"))) { s.sendMessage(Ui.mm("<gray>/blackforge raft steer <name> <east|south|west|north|stop> [sail|motor]")); return true; }
                t.steer = a[3].equals("stop") ? -1 : dir;
                t.sailing = a.length > 4 && a[4].equalsIgnoreCase("sail");
                markDirty();
                s.sendMessage(Ui.mm("<green>Course " + (t.steer < 0 ? "stopped" : RaftTeam.DIR_NAMES[t.steer] + (t.sailing ? " under sail" : " under motor"))));
            }
            case "status" -> s.sendMessage(Ui.mm("<#7fd6ff>Raft: " + byOwner.size() + " rafts, " + world.getPlayers().size() + " in the ocean, " + world.getEntitiesByClass(org.bukkit.entity.Item.class).size() + " items afloat"));
            default -> s.sendMessage(Ui.mm("<gray>/blackforge raft newteam|list|move|shark|debris|tp|status"));
        }
        return true;
    }

    private RaftTeam find(String name) {
        for (RaftTeam t : byOwner.values()) if (t.name.toLowerCase().startsWith(name.toLowerCase()) || t.name.toLowerCase().contains(name.toLowerCase() + "'s")) return t;
        return null;
    }
}
