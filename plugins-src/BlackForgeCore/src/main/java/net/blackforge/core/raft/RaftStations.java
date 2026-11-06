package net.blackforge.core.raft;

import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.*;

import static net.blackforge.core.raft.RaftMover.TILE_Y;

/**
 * Everything you build on a raft: foundations, the Workbench (research and crafting), purifiers, crop plots, sails, the Steering
 * Wheel and the Motor, and the sailing itself (the wind, the sail's and the motor's speed, running aground).
 */
final class RaftStations implements Listener {
    private static final Set<String> STATION_TYPES = Set.of("workbench", "purifier", "plot", "sail", "wheel", "motor");

    /** Marks our inventories. */
    private static final class Gui implements InventoryHolder {
        final String kind; final RaftTeam team; Inventory inv;
        Gui(String kind, RaftTeam team) { this.kind = kind; this.team = team; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final RaftModule mod;
    private final Random rnd = new Random();
    private final Map<String, Long> lastStep = new HashMap<>();

    RaftStations(RaftModule mod) {
        this.mod = mod;
        Bukkit.getPluginManager().registerEvents(this, mod.plugin());
        Bukkit.getScheduler().runTaskTimer(mod.plugin(), this::sailTick, 40L, 2L);
        Bukkit.getScheduler().runTaskTimer(mod.plugin(), this::plotTick, 100L, 400L);
    }

    // ------------------------------------------------------------------ placing and breaking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlock().getWorld() != mod.world()) return;
        Player p = e.getPlayer();
        String id = RaftItems.id(e.getItemInHand());
        if (id == null) return;
        if (!STATION_TYPES.contains(id)) { e.setCancelled(true); return; }   // foundations, hooks and cups are not blocks
        RaftTeam t = mod.teamOf(p);
        Block b = e.getBlock();
        if (t == null || !t.has(b.getX(), b.getZ()) || b.getY() <= TILE_Y) {
            e.setCancelled(true);
            p.sendActionBar(Ui.mm("<red>Build that on your raft."));
            return;
        }
        if (id.equals("plot")) {
            if (b.getY() != TILE_Y + 1) { e.setCancelled(true); p.sendActionBar(Ui.mm("<red>A crop plot sits right on the raft.")); return; }
            Bukkit.getScheduler().runTask(mod.plugin(), () -> {
                Farmland fd = (Farmland) Material.FARMLAND.createBlockData();
                fd.setMoisture(7);
                b.setBlockData(fd, false);
            });
        }
        if (id.equals("workbench") && t.hasStation("workbench") && t.stations.values().stream().filter(s -> s.type.equals("workbench")).count() >= 3) { e.setCancelled(true); p.sendActionBar(Ui.mm("<red>Three workbenches is plenty.")); return; }
        if (id.equals("wheel") && t.hasStation("wheel")) { e.setCancelled(true); p.sendActionBar(Ui.mm("<red>One Steering Wheel is enough.")); return; }
        if (id.equals("motor") && t.hasStation("motor")) { e.setCancelled(true); p.sendActionBar(Ui.mm("<red>One Motor per raft.")); return; }
        RaftTeam.Station s = new RaftTeam.Station(id, b.getX(), b.getY(), b.getZ());
        t.stations.put(s.key(), s);
        mod.markDirty();
        p.sendActionBar(Ui.mm("<green>" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Ui.mm(RaftItems.displayName(id))) + " <gray>is set."));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getWorld() != mod.world()) return;
        RaftTeam t = mod.teamOf(e.getPlayer());
        if (t == null) return;
        RaftTeam.Station s = t.stationAt(b.getX(), b.getY(), b.getZ());
        if (s != null) {
            t.stations.remove(s.key());
            e.setDropItems(false);
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), RaftItems.make(s.type));
            mod.markDirty();
            return;
        }
        if (b.getY() == TILE_Y && t.has(b.getX(), b.getZ())) {
            e.setDropItems(false);
            if (t.tiles.size() <= 4) { e.setCancelled(true); e.getPlayer().sendActionBar(Ui.mm("<red>A raft needs at least four tiles.")); return; }
            for (int y = TILE_Y + 1; y <= RaftMover.TOP_Y; y++) if (!b.getWorld().getBlockAt(b.getX(), y, b.getZ()).getType().isAir()) { e.setCancelled(true); e.getPlayer().sendActionBar(Ui.mm("<red>Clear the tile first.")); return; }
            t.tiles.remove(RaftTeam.pack(b.getX(), b.getZ()));
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 1.2, 0.5), RaftItems.make("foundation"));
            Bukkit.getScheduler().runTask(mod.plugin(), () -> b.setType(Material.WATER, false));
            mod.markDirty();
        }
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        if (e.getLocation().getWorld() == mod.world()) e.blockList().clear();
    }

    // ------------------------------------------------------------------ using things

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld() != mod.world() || e.getHand() != EquipmentSlot.HAND) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!right) return;
        RaftTeam t = mod.teamOf(p);
        if (t == null) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        String held = RaftItems.id(hand);
        Block clicked = e.getClickedBlock();
        // machines first
        if (clicked != null && !p.isSneaking()) {
            RaftTeam.Station s = t.stationAt(clicked.getX(), clicked.getY(), clicked.getZ());
            if (s != null && !s.type.equals("sail") && !s.type.equals("plot")) {
                e.setCancelled(true);
                use(p, t, s, hand);
                return;
            }
        }
        if (held == null) return;
        switch (held) {
            case "foundation" -> { e.setCancelled(true); addFoundation(p, t, hand); }
            case "hook" -> { e.setCancelled(true); mod.debris().cast(p, false); }
            case "adv_hook" -> { e.setCancelled(true); mod.debris().cast(p, true); }
            case "receiver" -> { e.setCancelled(true); mod.story().receiver(p, t); }
            case "salt", "fresh", "cup" -> { }
            default -> { if (STATION_TYPES.contains(held) && e.getClickedBlock() == null) e.setCancelled(true); }
        }
    }

    private void addFoundation(Player p, RaftTeam t, ItemStack hand) {
        RayTraceResult r = p.rayTraceBlocks(6, FluidCollisionMode.ALWAYS);
        if (r == null || r.getHitBlock() == null) return;
        Block hit = r.getHitBlock();
        int x = hit.getX(), z = hit.getZ();
        if (hit.getType() == Material.WATER && hit.getY() == TILE_Y) { /* aimed at the water itself */ }
        else if (t.has(x, z) && hit.getY() >= TILE_Y) {
            BlockFace f = r.getHitBlockFace();
            if (f == null || f == BlockFace.UP || f == BlockFace.DOWN) {
                Vector2 off = new Vector2(r.getHitPosition().getX() - (x + 0.5), r.getHitPosition().getZ() - (z + 0.5));
                if (Math.abs(off.x) > Math.abs(off.z)) x += off.x > 0 ? 1 : -1; else z += off.z > 0 ? 1 : -1;
            } else { x += f.getModX(); z += f.getModZ(); }
        } else return;
        if (!RaftMover.canAddTile(mod.world(), t, x, z)) { p.sendActionBar(Ui.mm("<red>A foundation goes on open water, right beside your raft.")); return; }
        for (Player o : mod.world().getPlayers()) if (!t.members.contains(o.getUniqueId()) && o.getLocation().distanceSquared(new Location(mod.world(), x + 0.5, TILE_Y, z + 0.5)) < 1) return;
        RaftMover.placeTile(mod.world(), t, x, z);
        hand.setAmount(hand.getAmount() - 1);
        mod.world().playSound(new Location(mod.world(), x + 0.5, TILE_Y, z + 0.5), Sound.BLOCK_WOOD_PLACE, 1f, 0.9f);
        mod.markDirty();
    }

    private record Vector2(double x, double z) {}

    private void use(Player p, RaftTeam t, RaftTeam.Station s, ItemStack hand) {
        switch (s.type) {
            case "workbench" -> openWorkbench(p, t);
            case "wheel" -> openWheel(p, t);
            case "purifier" -> {
                String held = RaftItems.id(hand);
                if ("salt".equals(held)) {
                    if (s.salt >= 12) { p.sendActionBar(Ui.mm("<red>The purifier is full.")); return; }
                    hand.setAmount(hand.getAmount() - 1); s.salt++;
                    p.getInventory().addItem(RaftItems.make("cup")).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
                    p.playSound(p.getLocation(), Sound.ITEM_BOTTLE_EMPTY, 1f, 1f);
                } else if (RaftRecipes.fuelValue(hand) > 0) {
                    int v = RaftRecipes.fuelValue(hand);
                    if (s.fuel >= 24) { p.sendActionBar(Ui.mm("<red>Fuel is full.")); return; }
                    hand.setAmount(hand.getAmount() - 1); s.fuel += v;
                    p.playSound(p.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 0.6f, 1.2f);
                } else if (s.fresh > 0) {
                    int n = s.fresh; s.fresh = 0;
                    p.getInventory().addItem(RaftItems.make("fresh", n)).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
                    p.playSound(p.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1.4f);
                }
                p.sendActionBar(Ui.mm("<#7fd6ff>Purifier <gray>salt <white>" + s.salt + " <gray>fuel <white>" + s.fuel + " <gray>fresh <white>" + s.fresh));
                mod.markDirty();
            }
            case "motor" -> {
                int v = RaftRecipes.fuelValue(hand);
                if (v > 0) {
                    if (s.fuel >= 40) { p.sendActionBar(Ui.mm("<red>The tank is full.")); return; }
                    hand.setAmount(hand.getAmount() - 1); s.fuel += v;
                    p.playSound(p.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 0.6f, 1.2f);
                }
                p.sendActionBar(Ui.mm("<#ff9f43>Motor <gray>fuel <white>" + s.fuel + " <gray>(about " + s.fuel * 20 + "s of running)"));
                mod.markDirty();
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ the Workbench

    private void openWorkbench(Player p, RaftTeam t) {
        Gui g = new Gui("bench", t);
        g.inv = Bukkit.createInventory(g, 36, Ui.mm("<#c8a165>Raft Workbench"));
        fillBench(g, p);
        p.openInventory(g.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_WOODEN_TRAPDOOR_OPEN, 1f, 1f);
    }

    private void fillBench(Gui g, Player p) {
        g.inv.clear();
        int slot = 10;
        for (RaftRecipes.Recipe r : RaftRecipes.ALL) {
            boolean known = g.team.unlocked.contains(r.id());
            ItemStack icon;
            if (known) {
                icon = RaftItems.make(r.result(), r.amount());
                boolean ok = RaftRecipes.canAfford(p, r.cost());
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>(icon.lore() == null ? List.of() : icon.lore());
                lore.add(Ui.mm(" "));
                lore.add(Ui.mm("<gray>" + r.blurb()));
                lore.add(Ui.mm("<gray>Cost: " + RaftRecipes.describe(r.cost())));
                lore.add(Ui.mm(ok ? "<green>Click to build" : "<red>You are missing something"));
                icon.lore(lore);
            } else {
                icon = new ItemStack(Material.GRAY_DYE);
                final boolean towersOk = g.team.towers >= r.needTowers();
                final boolean ok = towersOk && RaftRecipes.canAfford(p, r.research());
                icon.editMeta(m -> {
                    m.displayName(Ui.mm("<gray>Locked: <white>" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Ui.mm(RaftItems.displayName(r.result())))));
                    List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                    lore.add(Ui.mm("<gray>" + r.blurb()));
                    if (!towersOk) lore.add(Ui.mm("<red>Needs radio tower " + r.needTowers() + " switched on"));
                    else { lore.add(Ui.mm("<gray>Research: " + RaftRecipes.describe(r.research()))); lore.add(Ui.mm(ok ? "<green>Click to research" : "<red>You are missing something")); }
                    m.lore(lore);
                });
            }
            g.inv.setItem(slot, icon);
            slot++;
            if (slot % 9 == 8) slot += 2;
        }
        g.inv.setItem(31, Ui.item(Material.BOOK, "<#c8a165>Blueprints", "<gray>Known blueprints build right here.", "<gray>Locked ones are researched with materials."));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui g)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (g.kind.equals("bench")) {
            int idx = -1, s = 10;
            for (int i = 0; i < RaftRecipes.ALL.size(); i++) { if (s == slot) idx = i; s++; if (s % 9 == 8) s += 2; }
            if (idx < 0) return;
            RaftRecipes.Recipe r = RaftRecipes.ALL.get(idx);
            if (g.team.unlocked.contains(r.id())) {
                if (!RaftRecipes.canAfford(p, r.cost())) { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f); return; }
                RaftRecipes.take(p, r.cost());
                p.getInventory().addItem(RaftItems.make(r.result(), r.amount())).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
            } else {
                if (g.team.towers < r.needTowers() || !RaftRecipes.canAfford(p, r.research())) { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f); return; }
                RaftRecipes.take(p, r.research());
                g.team.unlocked.add(r.id());
                mod.markDirty();
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
                p.sendMessage(Ui.mm("<#c8a165>Blueprint learned: <white>" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Ui.mm(RaftItems.displayName(r.result())))));
            }
            fillBench(g, p);
        } else if (g.kind.equals("wheel")) {
            wheelClick(p, g.team, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true); }

    // ------------------------------------------------------------------ the Steering Wheel and sailing

    private void openWheel(Player p, RaftTeam t) {
        Gui g = new Gui("wheel", t);
        g.inv = Bukkit.createInventory(g, 27, Ui.mm("<#c8a165>Steering Wheel"));
        fillWheel(g);
        p.openInventory(g.inv);
    }

    private void fillWheel(Gui g) {
        RaftTeam t = g.team;
        g.inv.clear();
        g.inv.setItem(4, dirItem("north", 3, t));
        g.inv.setItem(12, dirItem("west", 2, t));
        g.inv.setItem(14, dirItem("east", 0, t));
        g.inv.setItem(22, dirItem("south", 1, t));
        g.inv.setItem(13, Ui.item(Material.RED_DYE, t.steer < 0 ? "<red><bold>Anchored" : "<red>Drop anchor", "<gray>Click to stop the raft."));
        boolean sail = t.hasStation("sail"), motor = t.hasStation("motor");
        g.inv.setItem(18, Ui.item(Material.WHITE_WOOL, t.sailing ? "<green><bold>Under sail" : "<white>Use the sail", "<gray>Follows the wind, any way but against it.", sail ? "<green>You have a sail." : "<red>Build and place a Sail."));
        g.inv.setItem(20, Ui.item(Material.BLAST_FURNACE, !t.sailing ? "<green><bold>Under motor" : "<white>Use the motor", "<gray>Any direction. Burns fuel.", motor ? "<green>You have a motor." : "<red>Research and place a Motor."));
        g.inv.setItem(26, Ui.item(Material.COMPASS, "<aqua>The wind blows <white>" + RaftTeam.DIR_NAMES[t.wind], "<gray>Debris drifts with it, and so does a sail."));
    }

    private ItemStack dirItem(String name, int dir, RaftTeam t) {
        boolean on = t.steer == dir;
        return Ui.item(on ? Material.LIME_DYE : Material.ARROW, (on ? "<green><bold>" : "<white>") + "Sail " + name, "<gray>Click to set course " + name + ".");
    }

    private void wheelClick(Player p, RaftTeam t, int slot) {
        int dir = switch (slot) { case 4 -> 3; case 12 -> 2; case 14 -> 0; case 22 -> 1; default -> -2; };
        if (slot == 13) { t.steer = -1; p.sendActionBar(Ui.mm("<red>Anchor down.")); }
        else if (slot == 18) { if (!t.hasStation("sail")) p.sendActionBar(Ui.mm("<red>You have no sail.")); else t.sailing = true; }
        else if (slot == 20) { if (!t.hasStation("motor")) p.sendActionBar(Ui.mm("<red>You have no motor.")); else t.sailing = false; }
        else if (dir >= 0) {
            if (t.sailing) {
                if (!t.hasStation("sail")) { p.sendActionBar(Ui.mm("<red>You have no sail. Switch to the motor or build one.")); return; }
                int[] w = RaftTeam.DIRS[t.wind], d = RaftTeam.DIRS[dir];
                if (w[0] * d[0] + w[1] * d[1] < 0) { p.sendActionBar(Ui.mm("<red>A sail cannot go against the wind (" + RaftTeam.DIR_NAMES[t.wind] + ").")); return; }
            } else {
                RaftTeam.Station m = t.stations.values().stream().filter(s -> s.type.equals("motor")).findFirst().orElse(null);
                if (m == null) { p.sendActionBar(Ui.mm("<red>You have no motor.")); return; }
                if (m.fuel <= 0) { p.sendActionBar(Ui.mm("<red>The motor is out of fuel.")); return; }
            }
            t.steer = dir;
            p.sendActionBar(Ui.mm("<green>Course set: <white>" + RaftTeam.DIR_NAMES[dir]));
        } else return;
        mod.markDirty();
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof Gui g) fillWheel(g);
    }

    private void sailTick() {
        World w = mod.world();
        if (w == null) return;
        long now = System.currentTimeMillis();
        for (RaftTeam t : mod.activeTeams()) {
            if (t.windChangeAt == 0) t.windChangeAt = now + 20 * 60_000L + rnd.nextInt(10 * 60_000);
            if (now >= t.windChangeAt) {
                t.wind = rnd.nextInt(4);
                t.windChangeAt = now + 20 * 60_000L + rnd.nextInt(10 * 60_000);
                mod.tell(t, "<aqua>The wind turns and blows <white>" + RaftTeam.DIR_NAMES[t.wind] + "<aqua>.");
                if (t.steer >= 0 && t.sailing) { int[] ww = RaftTeam.DIRS[t.wind], d = RaftTeam.DIRS[t.steer]; if (ww[0] * d[0] + ww[1] * d[1] < 0) { t.steer = -1; mod.tell(t, "<red>The wind is against you. The sail goes slack."); } }
            }
            if (t.steer < 0) continue;
            String key = t.owner.toString();
            long interval = t.sailing ? 400 : 200;         // milliseconds per block: 2.5 and 5 blocks a second
            RaftTeam.Station motor = t.sailing ? null : t.stations.values().stream().filter(s -> s.type.equals("motor")).findFirst().orElse(null);
            if (t.sailing ? !t.hasStation("sail") : motor == null) { t.steer = -1; continue; }
            if (now - lastStep.getOrDefault(key, 0L) < interval) continue;
            lastStep.put(key, now);
            if (motor != null) {
                if (motor.fuel <= 0) { t.steer = -1; mod.tell(t, "<red>The motor coughs and stops. Out of fuel."); continue; }
                if (++motor.progress >= 100) { motor.progress = 0; motor.fuel--; }     // one fuel per 20 seconds
            }
            int[] d = RaftTeam.DIRS[t.steer];
            RaftMover.Result res = RaftMover.step(w, t, d[0], d[1]);
            if (res != RaftMover.Result.OK) {
                t.steer = -1;
                mod.tell(t, res == RaftMover.Result.BLOCKED ? "<red>The raft scrapes something and stops. <gray>Land ahead? Walk ashore." : "<red>The sea ends here. <gray>Turn back.");
            }
            mod.markDirty();
        }
    }

    private void plotTick() {
        World w = mod.world();
        if (w == null) return;
        for (RaftTeam t : mod.activeTeams())
            for (RaftTeam.Station s : t.stations.values()) {
                if (!s.type.equals("plot")) continue;
                Block b = w.getBlockAt(s.x, s.y, s.z);
                if (b.getType() == Material.FARMLAND) { Farmland f = (Farmland) b.getBlockData(); if (f.getMoisture() < 7) { f.setMoisture(7); b.setBlockData(f, false); } }
            }
    }
}
