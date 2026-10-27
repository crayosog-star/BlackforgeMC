package net.alloymc.core.arcade.games.skywars;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** One SkyWars game: cages, kits, chest loot and refills, a closing border, last player/team standing. */
final class SkyWarsMatch extends Match {
    private final List<Block> cages = new ArrayList<>();
    private final Map<UUID, Integer> kitChoice = new HashMap<>();
    private Loot island, center;
    private double border;
    private final double startBorder;
    private WorldBorder wall;   // the visible red border every player in this match sees (a per-player border)

    SkyWarsMatch(MatchContext c) {
        super(c);
        startBorder = Math.max(map.bounds.sizeX(), map.bounds.sizeZ()) / 2.0;
        border = startBorder;
    }

    @Override protected String objective() { return variant.isTeams() ? "Be the last team on the islands!" : "Be the last one on the islands!"; }
    @Override protected int startingSeconds() { return settings.i("cage-seconds", 5); }
    @Override protected boolean freezeWhileStarting() { return false; }   // the cage holds them
    @Override protected boolean hunger() { return true; }
    @Override protected boolean allowBreak(Player p, Block b) { return true; }
    @Override protected boolean allowPlace(Player p, Block b) { return true; }
    @Override protected boolean allowDrop(Player p, ItemStack it) { return true; }
    @Override protected boolean explodable(Block b) { return inBounds(b.getLocation()); }
    @Override protected boolean allowCraft() { return true; }

    private List<String> kits() { return settings.list("kits", List.of()); }

    @Override protected void setup(List<Player> ps) {
        island = new Loot(settings.list("loot.island", List.of("STONE_SWORD 1 1")));
        center = new Loot(settings.list("loot.center", List.of("IRON_SWORD 1 1")));
        List<Location> spawns = points("spawns");
        Collections.shuffle(spawns, rnd);
        int step = Math.max(1, spawns.size() / Math.max(1, teams.size()));
        for (int ti = 0; ti < teams.size(); ti++) {
            Team t = teams.get(ti);
            Location base = spawns.get((ti * step) % spawns.size());
            List<Player> members = online(t.members);
            for (int j = 0; j < members.size(); j++) {
                Location l = base.clone().add(j * 2, 0, 0);
                cage(l);
                members.get(j).teleport(l);
                members.get(j).getInventory().setItem(0, Ui.item(Material.CHEST, "<aqua><bold>Kit Selector</bold> <gray>(right-click)"));
            }
        }
    }

    private void cage(Location l) {
        Material glass = Material.LIGHT_BLUE_STAINED_GLASS;
        Block feet = l.getBlock();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 3; dy++) {
            boolean shell = dy == -1 || dy == 3 || dx != 0 || dz != 0;
            if (!shell) continue;
            Block b = feet.getRelative(dx, dy, dz);
            if (b.getType().isAir()) { b.setType(glass, false); cages.add(b); }
        }
        for (int dy = 0; dy <= 2; dy++) { Block b = feet.getRelative(0, dy, 0); if (!b.getType().isAir()) b.setType(Material.AIR, false); }
    }

    @Override protected void onStart() {
        Pt c = map.bounds.center();
        wall = Bukkit.createWorldBorder();
        wall.setCenter(ox + c.x(), oz + c.z());
        wall.setSize(startBorder * 2);
        wall.setWarningDistance(8);
        wall.setWarningTimeTicks(0);
        for (Player p : everyone()) p.setWorldBorder(wall);
        spawnAnimals();
        for (Block b : cages) if (b.getType() == Material.LIGHT_BLUE_STAINED_GLASS) b.setType(Material.AIR, false);
        cages.clear();
        fillChests();
        for (Player p : living()) {
            p.setGameMode(GameMode.SURVIVAL);
            p.getInventory().clear();
            int k = kitChoice.getOrDefault(p.getUniqueId(), -1);
            if (k >= 0 && k < kits().size()) {
                String[] parts = kits().get(k).split(":", 2);
                for (String it : parts[1].split(",")) try { p.getInventory().addItem(Loot.item(it)); } catch (Exception ex) { arcade.plugin().getLogger().warning("[SkyWars] bad kit item " + it); }
                p.sendMessage(Ui.mm(prefix() + "Kit: <white>" + parts[0].trim()));
            }
        }
    }

    /** A few farm animals on the mid islands - life, and food for whoever gets there first. */
    private void spawnAnimals() {
        org.bukkit.entity.EntityType[] kinds = {org.bukkit.entity.EntityType.SHEEP, org.bukkit.entity.EntityType.COW, org.bukkit.entity.EntityType.CHICKEN, org.bukkit.entity.EntityType.PIG};
        for (Location l : points("animals")) {
            if (!l.getBlock().getRelative(0, -1, 0).getType().isSolid()) continue;
            for (int i = 0; i < 2; i++) world.spawnEntity(l.clone().add(i * 0.6, 0, 0), kinds[rnd.nextInt(kinds.length)]);
        }
    }

    private void fillChests() {
        int ni = settings.i("island-chest-items", 6), nc = settings.i("center-chest-items", 8);
        for (Location l : points("island_chests")) fill(l, island, ni);
        for (Location l : points("center_chests")) fill(l, center, nc);
    }

    private void fill(Location l, Loot loot, int n) {
        Block b = l.getBlock();
        if (b.getType() != Material.CHEST) b.setType(Material.CHEST, false);
        if (b.getState() instanceof Chest c) loot.fill(c.getBlockInventory(), n, rnd);
    }

    @Override protected void secondTick() {
        for (String s : settings.list("refill-at-seconds", List.of("180", "360")))
            if (Integer.parseInt(s.trim()) == elapsed) { fillChests(); title("", "<gold><bold>Chests refilled!"); sound(Sound.BLOCK_CHEST_OPEN, 1f); }
        int bs = settings.i("border-start-seconds", 300), be = settings.i("border-end-seconds", 660);
        double fin = settings.i("border-final-size", 16) / 2.0;
        if (elapsed == bs - 30) broadcast(prefix() + "<red>The border starts closing in <white>30 seconds<red>!");
        if (elapsed == bs) {
            title("", "<red><bold>The border is closing in!");
            sound(Sound.ENTITY_WITHER_SPAWN, 1.2f);
            if (wall != null) wall.changeSize(fin * 2, (long) (be - bs) * 20L);   // the client animates the wall smoothly
        }
        if (elapsed == be) broadcast(prefix() + "<red>The border has stopped at <white>" + (int) (fin * 2) + "x" + (int) (fin * 2) + "<red> - fight!");
        if (elapsed >= bs) border = elapsed >= be ? fin : startBorder - (startBorder - fin) * (elapsed - bs) / (double) (be - bs);
        Pt c = map.bounds.center();
        for (Player p : living()) {
            double dx = Math.abs(p.getLocation().getX() - (ox + c.x())), dz = Math.abs(p.getLocation().getZ() - (oz + c.z()));
            double out = Math.max(dx, dz) - border;
            if (out > 0) { p.damage(1 + Math.min(5, out / 4)); p.sendActionBar(Ui.mm("<red><bold>You're outside the border! <gray>Get back inside the red wall.")); }
            else if (elapsed >= bs - 30) p.sendActionBar(Ui.mm("<gray>Border: <white>" + (int) (border * 2) + "x" + (int) (border * 2) + (elapsed < bs ? " <gray>• closing in " + (bs - elapsed) + "s" : "")));
        }
    }

    @Override protected void onLeave(Player p) { p.setWorldBorder(null); }
    @Override protected void onLateJoin(Player p) { if (wall != null) p.setWorldBorder(wall); }
    @Override protected void onCleanup() { for (Player p : everyone()) p.setWorldBorder(null); wall = null; }

    @Override protected void onDeath(Player p, Player killer, String how) {
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() != Material.AIR) world.dropItemNaturally(p.getLocation(), it);
        p.getInventory().clear();
        super.onDeath(p, killer, how);
    }

    // ------------------------------------------------------------------ kit selector

    private static final class KitMenu implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    @Override protected void onInteract(Player p, PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        if (state() != MatchState.STARTING || it == null || it.getType() != Material.CHEST || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        e.setCancelled(true);
        KitMenu m = new KitMenu();
        m.inv = Bukkit.createInventory(m, 9, Ui.mm("<dark_gray>Choose a kit"));
        List<String> ks = kits();
        for (int i = 0; i < ks.size() && i < 9; i++) {
            String[] parts = ks.get(i).split(":", 2);
            Material icon = Material.CHEST;
            try { icon = Loot.item(parts[1].split(",")[0]).getType(); } catch (Exception ignored) { }
            m.inv.setItem(i, Ui.item(icon, "<aqua><bold>" + parts[0].trim(), "<gray>" + parts[1].trim(), "", kitChoice.getOrDefault(p.getUniqueId(), -1) == i ? "<green>Selected" : "<yellow>Click to pick"));
        }
        p.openInventory(m.inv);
    }

    @Override protected void onInventoryClick(Player p, InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof KitMenu)) return;
        e.setCancelled(true);
        int s = e.getRawSlot();
        if (s >= 0 && s < kits().size()) {
            kitChoice.put(p.getUniqueId(), s);
            p.sendMessage(Ui.mm(prefix() + "You'll get the <white>" + kits().get(s).split(":")[0].trim() + "<gray> kit when the cages open."));
            p.closeInventory();
        }
    }

    @Override protected List<String> sidebar(Player p) {
        List<String> l = super.sidebar(p);
        l.add("<gray>Kills: <white>" + kills.getOrDefault(p.getUniqueId(), 0));
        l.add("<gray>Border: <white>" + (int) (border * 2));
        return l;
    }
}
