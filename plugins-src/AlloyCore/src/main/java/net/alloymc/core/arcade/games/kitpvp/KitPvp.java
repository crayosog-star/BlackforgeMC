package net.alloymc.core.arcade.games.kitpvp;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Arcade FFA arena (separate from survival PvP): pick a kit, fight, respawn. Spawn protection, killstreak
 * rewards, assists, power-ups and arena hazards. Join any time; first to the kill target (or most kills) wins.
 */
public final class KitPvp extends ArcadeGame {
    public KitPvp() {
        super("kitpvp", "FFA Arena", "<#ff7a5c>", Material.IRON_AXE, Category.COMBAT,
                "Free-for-all kit fights. Pick a kit, rack up killstreaks and grab power-ups. Join a running arena any time.");
        variants.add(Variant.ffa("ffa", "Free for All", 2, 16));
    }

    @Override public boolean lateJoin() { return true; }
    @Override public int queueCountdown() { return 5; }

    @Override public MapRequirements requirements() { return new MapRequirements().region("spawn_zone").list("spawns", 4).list("powerups", 1); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 600);
        m.put("kill-target", 25);
        m.put("respawn-protection-seconds", 3);
        m.put("powerup-interval-seconds", 30);
        m.put("kits", List.of(
            "Warrior: IRON_SWORD 1, IRON_HELMET 1, IRON_CHESTPLATE 1, IRON_LEGGINGS 1, IRON_BOOTS 1, COOKED_BEEF 8",
            "Archer: STONE_SWORD 1, BOW 1 power:2, ARROW 32, CHAINMAIL_HELMET 1, CHAINMAIL_CHESTPLATE 1, LEATHER_LEGGINGS 1, CHAINMAIL_BOOTS 1",
            "Tank: STONE_SWORD 1, DIAMOND_HELMET 1, DIAMOND_CHESTPLATE 1, DIAMOND_LEGGINGS 1, DIAMOND_BOOTS 1, SHIELD 1",
            "Ninja: GOLDEN_SWORD 1 sharpness:3, LEATHER_HELMET 1, LEATHER_CHESTPLATE 1, LEATHER_LEGGINGS 1, LEATHER_BOOTS 1 feather_falling:4, ENDER_PEARL 2"));
        return m;
    }

    @Override public Match newMatch(MatchContext c) { return new FfaMatch(c); }

    @Override public List<BuiltMap> builtInMaps() { return List.of(colosseum(), ironclad(), cliffs()); }

    private static MapDef base(String id, String name, String desc, int half) {
        MapDef d = new MapDef("kitpvp", id);
        d.display = name; d.description = desc; d.minPlayers = 2; d.maxPlayers = 16;
        d.bounds = Box.of(-half, -20, -half, half, 30, half);
        d.voidY = -12;
        d.spectator = new Pt(0.5, 22, 0.5, 0, 70);
        return d;
    }

    private static BuiltMap colosseum() {
        MapKit k = new MapKit(91);
        int R = 26;
        for (int x = -R - 3; x <= R + 3; x++) for (int z = -R - 3; z <= R + 3; z++) {
            double r = Math.hypot(x, z);
            if (r <= R) { k.set(x, 0, z, MapKit.noise(x / 3.0, z / 3.0, 9) > 0.66 ? "cracked_stone_bricks" : k.pick("stone_bricks", "stone_bricks", "andesite")); k.set(x, -1, z, "stone"); }
            else if (r <= R + 3) for (int y = 0; y <= 9; y++) k.set(x, y, z, y > 4 + (int) (MapKit.noise(x / 2.0, z / 2.0, 4) * 6) ? "air" : "stone_bricks");
        }
        // hazard: lava pit in the middle with a ring path
        k.disc(0, 0, 0, 4.5, "lava"); k.disc(0, -1, 0, 4.5, "magma_block"); k.ring(0, 0, 0, 4.5, 5.5, "polished_blackstone");
        for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; int x = (int) Math.round(Math.cos(a) * 14), z = (int) Math.round(Math.sin(a) * 14); for (int y = 1; y <= (i % 2 == 0 ? 5 : 2); y++) k.set(x, y, z, "chiseled_stone_bricks"); }
        // spawn balcony on the north side
        for (int x = -5; x <= 5; x++) for (int z = -R + 1; z <= -R + 5; z++) k.set(x, 4, z, "polished_andesite");
        for (int x = -5; x <= 5; x++) k.set(x, 5, -R + 5, "stone_brick_wall");
        k.set(0, 5, -R + 5, "air"); for (int z = -R + 6; z <= -R + 9; z++) k.set(0, 4 - (z - (-R + 5)), z, "stone_brick_stairs[facing=north]");
        MapDef d = base("ruined_colosseum", "Ruined Colosseum", "Crumbling arena around a lava pit.", R + 4);
        d.region("spawn_zone", Box.of(-5, 5, -R + 1, 5, 9, -R + 5));
        for (int i = 0; i < 8; i++) d.add("spawns", Pt.of(-4 + i + 0.5, 5, -R + 2.5, 0));
        for (int i = 0; i < 4; i++) { double a = i * Math.PI / 2 + 0.4; d.add("powerups", Pt.of(Math.round(Math.cos(a) * 10) + 0.5, 1, Math.round(Math.sin(a) * 10) + 0.5)); }
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap ironclad() {
        MapKit k = new MapKit(92);
        int H = 22;
        for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
            k.set(x, 0, z, ((x / 3 + z / 3) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles"); k.set(x, -1, z, "deepslate");
            if (Math.abs(x) == H || Math.abs(z) == H) for (int y = 1; y <= 8; y++) k.set(x, y, z, y == 8 ? "waxed_cut_copper" : "deepslate_bricks");
        }
        // crates and catwalks; fire vents as hazards
        for (int i = 0; i < 14; i++) { int x = k.rnd.nextInt(34) - 17, z = k.rnd.nextInt(30) - 13; for (int y = 1; y <= 1 + k.rnd.nextInt(2); y++) k.set(x, y, z, "barrel"); }
        for (int x = -12; x <= 12; x++) { k.set(x, 4, 6, "waxed_copper_grate"); k.set(x, 4, -6, "waxed_copper_grate"); }
        for (int[] v : new int[][]{{-8, 0}, {8, 0}, {0, 10}}) { k.set(v[0], 0, v[1], "magma_block"); k.late(v[0], 1, v[1], "fire"); }
        for (int x = -4; x <= 4; x++) for (int z = H - 5; z <= H - 1; z++) k.set(x, 0, z, "waxed_oxidized_cut_copper");
        MapDef d = base("ironclad_yard", "Ironclad Yard", "Crates, catwalks and fire vents in a foundry yard.", H + 1);
        d.region("spawn_zone", Box.of(-4, 1, H - 5, 4, 4, H - 1));
        for (int i = 0; i < 8; i++) d.add("spawns", Pt.of(-4 + i + 0.5, 1, H - 2.5, 180));
        d.add("powerups", Pt.of(0.5, 5, 6.5)); d.add("powerups", Pt.of(0.5, 5, -5.5)); d.add("powerups", Pt.of(-14.5, 1, 0.5)); d.add("powerups", Pt.of(14.5, 1, 0.5));
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap cliffs() {
        MapKit k = new MapKit(93);
        int R = 24;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            double r = Math.hypot(x, z) + MapKit.noise(x / 5.0, z / 5.0, 2) * 5;
            if (r > R) continue;
            int h = (int) (MapKit.fbm(x + 20, z, 93) * 6);
            for (int y = -8; y <= h; y++) k.set(x, y, z, y == h ? "grass_block" : y > h - 3 ? "dirt" : "stone");
            if (k.rnd.nextInt(90) == 0) k.tree(x, h + 1, z, "spruce_log", "spruce_leaves[persistent=true]", 5);
        }
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) { k.clear(x, 1, z, x, 12, z); k.set(x, 0, z, "polished_diorite"); for (int y = -8; y < 0; y++) k.set(x, y, z, "stone"); }
        MapDef d = base("windswept_cliffs", "Windswept Cliffs", "Grassy cliffs with a long drop off every edge.", R + 4);
        d.region("spawn_zone", Box.of(-3, 1, -3, 3, 4, 3));
        for (int i = 0; i < 8; i++) d.add("spawns", Pt.of(-3 + (i % 4) * 2 + 0.5, 1, -2.5 + (i / 4) * 4, 0));
        for (int i = 0; i < 4; i++) { double a = i * Math.PI / 2; d.add("powerups", Pt.of(Math.round(Math.cos(a) * 15) + 0.5, 8, Math.round(Math.sin(a) * 15) + 0.5)); }
        d.set("powerups-drop", true);
        return new BuiltMap(d, k.c);
    }

    static final class FfaMatch extends Match {
        private static final NamespacedKey POWER = new NamespacedKey("alloycore", "arcade_powerup");
        private final Map<UUID, Integer> kitOf = new HashMap<>();
        private final Map<UUID, Long> protectedUntil = new HashMap<>();
        private final Map<UUID, Integer> respawnAt = new HashMap<>();
        private int ticks;

        FfaMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "First to " + settings.i("kill-target", 25) + " kills wins!"; }
        @Override protected boolean freezeWhileStarting() { return false; }

        private List<String> kits() { return settings.list("kits", List.of("Warrior: IRON_SWORD 1")); }

        @Override protected void setup(List<Player> ps) { for (Player p : ps) respawn(p); }
        @Override protected void onLateJoin(Player p) { respawn(p); p.sendMessage(Ui.mm(prefix() + "Welcome to the arena! Right-click the chest to change kit.")); }

        private void respawn(Player p) {
            resetPlayer(p, GameMode.SURVIVAL);
            List<Location> sp = points("spawns");
            p.teleport(sp.get(rnd.nextInt(sp.size())));
            int k = kitOf.getOrDefault(p.getUniqueId(), 0);
            String[] parts = kits().get(Math.min(k, kits().size() - 1)).split(":", 2);
            for (String it : parts[1].split(",")) {
                try {
                    ItemStack x = Loot.item(it);
                    String n = x.getType().name();
                    if (n.endsWith("_HELMET")) p.getInventory().setHelmet(x); else if (n.endsWith("_CHESTPLATE")) p.getInventory().setChestplate(x);
                    else if (n.endsWith("_LEGGINGS")) p.getInventory().setLeggings(x); else if (n.endsWith("_BOOTS")) p.getInventory().setBoots(x);
                    else if (x.getType() == Material.SHIELD) p.getInventory().setItemInOffHand(x); else p.getInventory().addItem(x);
                } catch (Exception e) { arcade.plugin().getLogger().warning("[FFA] bad kit item " + it); }
            }
            p.getInventory().setItem(8, Ui.item(Material.CHEST, "<gold><bold>Change kit</bold> <gray>(in spawn)"));
            protectedUntil.put(p.getUniqueId(), System.currentTimeMillis() + settings.i("respawn-protection-seconds", 3) * 1000L);
        }

        private boolean inSpawn(Player p) { return region("spawn_zone").grow(1).contains(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ()); }
        private boolean protectedNow(Player p) { return inSpawn(p) || protectedUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis(); }

        @Override protected boolean onDamage(Player victim, Entity attacker, EntityDamageEvent e) {
            if (respawnAt.containsKey(victim.getUniqueId())) return false;
            if (protectedNow(victim)) return false;
            if (attacker instanceof Player a) {
                if (inSpawn(a)) { a.sendActionBar(Ui.mm("<red>You can't attack from spawn!")); return false; }
                protectedUntil.remove(a.getUniqueId());   // attacking ends your protection
            }
            return true;
        }

        @Override protected void onDeath(Player p, Player killer, String how) {
            broadcast(prefix() + deathMessage(p, killer, how) + (killer != null ? " <dark_gray>(" + streak.getOrDefault(killer.getUniqueId(), 0) + " streak)" : ""));
            if (killer != null) {
                killer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 1));
                int s = streak.getOrDefault(killer.getUniqueId(), 0);
                if (s == 3) { killer.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE)); killer.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, 0)); killer.sendMessage(Ui.mm("<gold>3 kill streak! <gray>Golden apple + Strength")); }
                if (s == 5) { killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 30, 1)); broadcast(prefix() + nameOf(killer) + " <gold>is on a 5 kill streak!"); }
                if (s == 10) { killer.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 60, 2)); broadcast(prefix() + nameOf(killer) + " <red><bold>is UNSTOPPABLE! <gray>(10 streak)"); }
                if (kills.getOrDefault(killer.getUniqueId(), 0) >= settings.i("kill-target", 25)) finish(List.of(killer.getUniqueId()), nameOf(killer) + " <yellow>reached the kill target!");
            }
            resetPlayer(p, GameMode.SPECTATOR);
            p.teleport(at(map.spectator));
            respawnAt.put(p.getUniqueId(), ticks + 40);
        }

        @Override protected void fastTick() {
            ticks++;
            for (var it = respawnAt.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() > ticks) continue;
                it.remove();
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null && isAlive(p)) respawn(p);
            }
        }

        @Override protected void secondTick() {
            int every = settings.i("powerup-interval-seconds", 30);
            if (elapsed % every == every / 2) {
                List<Location> pts = points("powerups");
                Location l = pts.get(rnd.nextInt(pts.size()));
                Material[] kinds = {Material.SUGAR, Material.BLAZE_POWDER, Material.GHAST_TEAR, Material.GOLDEN_CARROT};
                Material m = kinds[rnd.nextInt(kinds.length)];
                Item it = world.dropItem(l.clone().add(0, 0.5, 0), new ItemStack(m));
                it.setVelocity(new org.bukkit.util.Vector());
                it.getPersistentDataContainer().set(POWER, PersistentDataType.BYTE, (byte) 1);
                it.setGlowing(true);
                it.customName(Ui.mm("<gold><bold>POWER-UP")); it.setCustomNameVisible(true);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, l, 30, 0.4, 0.6, 0.4, 0.1);
            }
            for (Player p : living()) if (inSpawn(p)) p.sendActionBar(Ui.mm("<green>Spawn protection <gray>• leave spawn to fight"));
        }

        @Override protected boolean allowPickup(Player p, ItemStack it) {
            Material m = it.getType();
            PotionEffectType t = switch (m) { case SUGAR -> PotionEffectType.SPEED; case BLAZE_POWDER -> PotionEffectType.STRENGTH; case GHAST_TEAR -> PotionEffectType.REGENERATION; case GOLDEN_CARROT -> PotionEffectType.ABSORPTION; default -> null; };
            if (t == null) return true;
            for (Entity e : p.getNearbyEntities(2, 2, 2)) if (e instanceof Item i && i.getPersistentDataContainer().has(POWER) && i.getItemStack().getType() == m) {
                i.remove();
                p.addPotionEffect(new PotionEffect(t, 20 * 10, 1));
                p.sendMessage(Ui.mm("<gold>Power-up: <white>" + t.getKey().getKey().replace('_', ' ')));
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
                break;
            }
            return false;
        }

        private static final class KitMenu implements InventoryHolder { Inventory inv; public Inventory getInventory() { return inv; } }

        @Override protected void onInteract(Player p, PlayerInteractEvent e) {
            ItemStack it = e.getItem();
            if (it == null || it.getType() != Material.CHEST || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
            e.setCancelled(true);
            if (!inSpawn(p)) { p.sendMessage(Ui.mm("<red>Go back to spawn to change kit.")); return; }
            KitMenu m = new KitMenu();
            m.inv = Bukkit.createInventory(m, 9, Ui.mm("<dark_gray>Choose your kit"));
            List<String> ks = kits();
            for (int i = 0; i < ks.size() && i < 9; i++) {
                String[] parts = ks.get(i).split(":", 2);
                Material icon = Material.IRON_SWORD;
                try { icon = Loot.item(parts[1].split(",")[0]).getType(); } catch (Exception ignored) { }
                m.inv.setItem(i, Ui.item(icon, "<gold><bold>" + parts[0].trim(), "<gray>" + parts[1].trim(), "", "<yellow>Click to equip"));
            }
            p.openInventory(m.inv);
        }

        @Override protected void onInventoryClick(Player p, InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof KitMenu)) return;
            e.setCancelled(true);
            int s = e.getRawSlot();
            if (s < 0 || s >= kits().size()) return;
            kitOf.put(p.getUniqueId(), s);
            p.closeInventory();
            if (inSpawn(p)) { Location keep = p.getLocation(); respawn(p); p.teleport(keep); }
            p.sendMessage(Ui.mm(prefix() + "Kit: <white>" + kits().get(s).split(":")[0]));
        }

        @Override protected boolean allowBreak(Player p, Block b) { return false; }
        @Override protected List<UUID> checkWinners() { return alive.isEmpty() ? List.of() : null; }

        @Override protected void onTimeUp() {
            UUID best = kills.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            finish(best == null ? List.of() : List.of(best), "Time's up - most kills wins!");
        }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = new ArrayList<>();
            l.add("<gray>Arena: <white>" + map.display);
            l.add("<gray>Kills: <white>" + kills.getOrDefault(p.getUniqueId(), 0) + " <gray>/ " + settings.i("kill-target", 25));
            l.add("<gray>Streak: <white>" + streak.getOrDefault(p.getUniqueId(), 0));
            kills.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3)
                    .forEach(e -> l.add("<gold>" + Bukkit.getOfflinePlayer(e.getKey()).getName() + " <gray>" + e.getValue()));
            l.add("<gray>Time: <white>" + clock(timeLimit() - elapsed));
            return l;
        }
    }
}
