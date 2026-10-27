package net.alloymc.core.arcade.games.hideseek;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/**
 * Hide and Seek (prop hunt): hiders become blocks from the map's palette. Stand still to turn solid - a
 * real block in the world, hider out of sight - or keep moving as a floating disguise. Seekers hit blocks
 * to find hiders; found hiders join the seekers. Hiders win if anyone survives the clock.
 */
public final class HideAndSeek extends ArcadeGame {
    public HideAndSeek() {
        super("hideseek", "Hide and Seek", "<#ffd27a>", Material.BARREL, Category.PARTY,
                "Hiders disguise as blocks and freeze to blend in. Seekers hunt them down before time runs out.");
        variants.add(Variant.ffa("classic", "Classic", 3, 16));
    }

    @Override public MapRequirements requirements() {
        return new MapRequirements().point("seeker_spawn").list("spawns", 4).check(m -> {
            Object b = m.settings.get("disguises");
            return b instanceof List<?> l && !l.isEmpty() ? List.of() : List.of("needs a 'disguises' setting: a list of block ids hiders can become");
        });
    }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 300);
        m.put("hide-seconds", 30);
        m.put("solid-after-seconds", 3);
        m.put("seekers-per-8-players", 1);
        m.put("taunt-cooldown-seconds", 20);
        m.put("speed-cooldown-seconds", 30);
        m.put("hider-health", 8);
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("Hiders: pick a block, find a spot and stand still for 3 seconds to turn solid. Taunt for bonus points.",
                "Seekers: hit suspicious blocks. Found hiders become seekers. Hiders win if anyone is still hidden at the end.");
    }

    @Override public Match newMatch(MatchContext c) { return new HideMatch(c); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(
            build("village_market", "Village Market", "Stalls, barrels and hay bales in a sunny market.", 121,
                  new Rooms.Theme("oak_planks", "cobblestone", "oak_slab", "oak_log", "lantern[hanging=true]", "glass_pane",
                          List.of("barrel", "hay_block", "composter", "crafting_table", "pumpkin", "melon", "fletching_table"), List.of("hay_block", "barrel")),
                  List.of("barrel", "hay_block", "composter", "crafting_table", "pumpkin", "melon", "fletching_table", "oak_log", "cobblestone")),
            build("workshop", "Workshop", "A cluttered workshop of anvils, furnaces and crates.", 122,
                  new Rooms.Theme("bricks", "polished_andesite", "stone_bricks", "stripped_spruce_log", "lantern[hanging=true]", "glass_pane",
                          List.of("anvil", "furnace", "blast_furnace", "smithing_table", "barrel", "grindstone", "cartography_table", "loom"), List.of("barrel", "bricks")),
                  List.of("anvil", "furnace", "blast_furnace", "smithing_table", "barrel", "grindstone", "loom", "bricks", "polished_andesite")));
    }

    static BuiltMap build(String id, String name, String desc, long seed, Rooms.Theme theme, List<String> disguises) {
        MapKit k = new MapKit(seed);
        Rooms r = Rooms.build(k, theme, 4, 3, 9, 2);
        int gx = r.width / 2 + 8, gz = r.depth / 2 + 8;
        for (int x = -gx; x <= gx; x++) for (int z = -gz; z <= gz; z++) {
            if (k.isSet(x, 0, z)) continue;
            k.set(x, 0, z, MapKit.noise(x / 3.0, z / 3.0, seed) > 0.6 ? "dirt_path" : "grass_block");
            k.set(x, -1, z, "dirt");
            if (Math.abs(x) == gx || Math.abs(z) == gz) for (int y = 1; y <= 3; y++) k.set(x, y, z, y == 3 ? "barrier" : "oak_fence");
            else if (k.rnd.nextInt(25) == 0) k.set(x, 1, z, disguises.get(k.rnd.nextInt(disguises.size())));   // outdoor clutter to hide among
        }
        MapDef d = new MapDef("hideseek", id);
        d.display = name; d.description = desc; d.minPlayers = 3; d.maxPlayers = 16;
        d.bounds = Box.of(-gx, -4, -gz, gx, r.height + 6, gz);
        d.voidY = -3;
        d.spectator = new Pt(0.5, r.height + 5, gz - 2 + 0.5, 180, 40);
        d.point("seeker_spawn", Pt.of(0.5, 1, -gz + 3.5, 0));
        List<Pt> spots = new ArrayList<>(r.floorSpots);
        Collections.shuffle(spots, k.rnd);
        for (int i = 0; i < 16; i++) d.add("spawns", spots.get(i % spots.size()));
        d.set("disguises", disguises.stream().map(s -> s.toUpperCase()).toList());
        return new BuiltMap(d, k.c);
    }

    // ------------------------------------------------------------------ match

    static final class HideMatch extends Match {
        private final Set<UUID> seekers = new HashSet<>();
        private final Map<UUID, Material> disguise = new HashMap<>();
        private final Map<UUID, BlockDisplay> displays = new HashMap<>();
        private final Map<UUID, Block> solid = new HashMap<>();
        private final Map<UUID, Long> stillSince = new HashMap<>(), tauntReady = new HashMap<>(), speedReady = new HashMap<>();
        private final Map<UUID, Integer> points = new HashMap<>();
        private List<Material> palette;

        HideMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "Hide in plain sight - or seek them out!"; }
        @Override protected boolean freezeWhileStarting() { return false; }
        @Override protected boolean wantsFineMoves() { return true; }

        private boolean released() { return elapsed >= settings.i("hide-seconds", 30); }

        @Override protected void setup(List<Player> ps) {
            palette = new ArrayList<>();
            for (String s : settings.list("disguises", List.of("BARREL"))) { Material m = Material.matchMaterial(s); if (m != null && m.isBlock()) palette.add(m); }
            List<Player> shuffled = new ArrayList<>(ps);
            Collections.shuffle(shuffled, rnd);
            int n = Math.max(1, ps.size() / 8 * settings.i("seekers-per-8-players", 1));
            for (int i = 0; i < n; i++) seekers.add(shuffled.get(i).getUniqueId());
            List<Location> sp = points("spawns");
            for (int i = 0; i < ps.size(); i++) {
                Player p = ps.get(i);
                if (seekers.contains(p.getUniqueId())) { p.teleport(point("seeker_spawn")); title(p, "<red><bold>SEEKER", "<gray>You're released in " + settings.i("hide-seconds", 30) + "s"); }
                else { p.teleport(sp.get(i % sp.size())); becomeHider(p, palette.get(rnd.nextInt(palette.size()))); title(p, "<green><bold>HIDER", "<gray>Pick a block and hide!"); }
            }
        }

        private void becomeHider(Player p, Material m) {
            disguise.put(p.getUniqueId(), m);
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 3600, 0, false, false));
            var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max != null) { max.setBaseValue(settings.i("hider-health", 8)); p.setHealth(max.getBaseValue()); }
            BlockDisplay bd = displays.get(p.getUniqueId());
            if (bd == null || !bd.isValid()) {
                bd = world.spawn(p.getLocation(), BlockDisplay.class, x -> { x.setPersistent(false); x.setTeleportDuration(1); });
                displays.put(p.getUniqueId(), bd);
            }
            bd.setBlock(m.createBlockData());
            p.getInventory().clear();
            p.getInventory().setItem(0, Ui.item(m, "<green><bold>Change disguise</bold> <gray>(right-click, before release)"));
            p.getInventory().setItem(4, Ui.item(Material.FIREWORK_ROCKET, "<gold><bold>Taunt</bold> <gray>(+5 points)"));
            p.getInventory().setItem(5, Ui.item(Material.SUGAR, "<aqua><bold>Speed burst"));
        }

        private void becomeSeeker(Player p) {
            unsolid(p, false);
            BlockDisplay bd = displays.remove(p.getUniqueId());
            if (bd != null) bd.remove();
            disguise.remove(p.getUniqueId());
            seekers.add(p.getUniqueId());
            resetPlayer(p, GameMode.ADVENTURE);
            p.getInventory().setItem(0, unbreakable(Material.IRON_SWORD));
            p.teleport(point("seeker_spawn"));
        }

        @Override protected void onStart() { for (Player p : online(seekers)) { p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * settings.i("hide-seconds", 30), 0, false, false)); p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * settings.i("hide-seconds", 30), 10, false, false)); } }

        @Override protected void fastTick() {
            long now = System.currentTimeMillis();
            int solidAfter = settings.i("solid-after-seconds", 3) * 1000;
            for (Player p : living()) {
                UUID u = p.getUniqueId();
                if (seekers.contains(u)) continue;
                if (solid.containsKey(u)) continue;
                BlockDisplay bd = displays.get(u);
                Location base = p.getLocation().getBlock().getLocation();
                if (bd != null && bd.isValid()) bd.teleport(new Location(world, p.getLocation().getX() - 0.5, p.getLocation().getY(), p.getLocation().getZ() - 0.5));
                long since = stillSince.computeIfAbsent(u, k -> now);
                if (now - since >= solidAfter && p.isOnGround() && base.getBlock().getType().isAir()) makeSolid(p, base.getBlock());
                else if (now - since >= 1000 && !solid.containsKey(u)) p.sendActionBar(Ui.mm("<gray>Solid in <white>" + Math.max(0, (solidAfter - (now - since)) / 1000 + 1) + "s"));
            }
        }

        private void makeSolid(Player p, Block b) {
            UUID u = p.getUniqueId();
            solid.put(u, b);
            b.setType(disguise.get(u), false);
            BlockDisplay bd = displays.get(u);
            if (bd != null) bd.setViewRange(0f);
            p.setGameMode(GameMode.SPECTATOR);
            p.teleport(b.getLocation().add(0.5, 0, 0.5).setDirection(p.getLocation().getDirection()));
            p.sendActionBar(Ui.mm("<green><bold>SOLID</bold> <gray>- move to unlock"));
            p.playSound(p.getLocation(), Sound.BLOCK_STONE_PLACE, 0.6f, 1.4f);
        }

        private void unsolid(Player p, boolean keepHider) {
            Block b = solid.remove(p.getUniqueId());
            if (b == null) return;
            b.setType(Material.AIR, false);
            if (keepHider) {
                p.setGameMode(GameMode.ADVENTURE);
                p.teleport(b.getLocation().add(0.5, 0, 0.5).setDirection(p.getLocation().getDirection()));
                BlockDisplay bd = displays.get(p.getUniqueId());
                if (bd != null) bd.setViewRange(1f);
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 3600, 0, false, false));
            }
        }

        @Override protected void onMove(Player p, Location from, Location to) {
            UUID u = p.getUniqueId();
            if (seekers.contains(u)) { if (!released() && to.distanceSquared(point("seeker_spawn")) > 4) p.teleport(point("seeker_spawn")); return; }
            boolean moved = from.getX() != to.getX() || from.getZ() != to.getZ() || Math.abs(from.getY() - to.getY()) > 0.01;
            if (!moved) return;
            stillSince.put(u, System.currentTimeMillis());
            if (solid.containsKey(u)) unsolid(p, true);   // any movement from a solid block unlocks (and can't fly through walls)
        }

        /** Seekers hitting a block: is it a solid hider? */
        @Override protected void onInteract(Player p, PlayerInteractEvent e) {
            UUID u = p.getUniqueId();
            ItemStack it = e.getItem();
            if (!seekers.contains(u)) {
                if (it == null || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
                e.setCancelled(true);
                long now = System.currentTimeMillis();
                if (it.getType() == Material.FIREWORK_ROCKET) {
                    if (tauntReady.getOrDefault(u, 0L) > now) { p.sendActionBar(Ui.mm("<red>Taunt recharging")); return; }
                    tauntReady.put(u, now + settings.i("taunt-cooldown-seconds", 20) * 1000L);
                    world.playSound(p.getLocation(), Sound.ENTITY_CAT_AMBIENT, 1.5f, 1.2f);
                    firework(p.getLocation());
                    points.merge(u, 5, Integer::sum);
                    arcade.stats().add(u, game.id, "taunts", 1);
                } else if (it.getType() == Material.SUGAR) {
                    if (speedReady.getOrDefault(u, 0L) > now) { p.sendActionBar(Ui.mm("<red>Speed recharging")); return; }
                    speedReady.put(u, now + settings.i("speed-cooldown-seconds", 30) * 1000L);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 2, false, false));
                } else if (!released() && palette.contains(it.getType())) openPicker(p);
                return;
            }
            if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null || !released()) return;
            Block b = e.getClickedBlock();
            for (var en : new ArrayList<>(solid.entrySet())) if (en.getValue().equals(b)) {
                Player h = Bukkit.getPlayer(en.getKey());
                if (h != null) { unsolid(h, true); found(h, p); }
                return;
            }
            if (palette.contains(b.getType())) { p.damage(1); p.sendActionBar(Ui.mm("<red>Wrong block! <gray>(-½ heart)")); }
        }

        private void openPicker(Player p) {
            Picker m = new Picker();
            m.inv = Bukkit.createInventory(m, 18, Ui.mm("<dark_gray>Pick your disguise"));
            for (int i = 0; i < palette.size() && i < 18; i++) m.inv.setItem(i, new ItemStack(palette.get(i)));
            p.openInventory(m.inv);
        }

        private static final class Picker implements InventoryHolder { Inventory inv; public Inventory getInventory() { return inv; } }

        @Override protected void onInventoryClick(Player p, InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof Picker)) return;
            e.setCancelled(true);
            int s = e.getRawSlot();
            if (s < 0 || s >= palette.size() || released()) return;
            becomeHider(p, palette.get(s));
            p.closeInventory();
            p.sendMessage(Ui.mm(prefix() + "You are now a <white>" + palette.get(s).name().toLowerCase().replace('_', ' ')));
        }

        @Override protected boolean onDamage(Player victim, Entity attacker, EntityDamageEvent e) {
            if (!(attacker instanceof Player a)) return e.getCause() != EntityDamageEvent.DamageCause.FALL;
            if (!released()) return false;
            if (seekers.contains(a.getUniqueId()) == seekers.contains(victim.getUniqueId())) return false;   // only seekers hit hiders
            if (!seekers.contains(a.getUniqueId())) return false;
            if (e.getFinalDamage() >= victim.getHealth()) { e.setCancelled(true); found(victim, a); return false; }
            return true;
        }

        private void found(Player h, Player by) {
            if (seekers.contains(h.getUniqueId())) return;
            broadcast(prefix() + "<white>" + h.getName() + " <gray>was found" + (by != null ? " by <red>" + by.getName() : "") + "!");
            if (by != null) { points.merge(by.getUniqueId(), 10, Integer::sum); arcade.stats().add(by.getUniqueId(), game.id, "kills", 1); }
            world.playSound(h.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
            becomeSeeker(h);
        }

        @Override protected void secondTick() {
            int hs = settings.i("hide-seconds", 30);
            if (elapsed == hs) { title("<red><bold>SEEKERS RELEASED!", ""); sound(Sound.ENTITY_WOLF_ANGRY_GROWL, 1f); }
            if (elapsed > hs) for (UUID u : alive) if (!seekers.contains(u)) points.merge(u, 1, Integer::sum);
        }

        @Override protected void onVoid(Player p) { p.teleport(seekers.contains(p.getUniqueId()) ? point("seeker_spawn") : points("spawns").getFirst()); }

        @Override protected List<UUID> checkWinners() {
            if (!released()) return null;
            boolean hidersLeft = alive.stream().anyMatch(u -> !seekers.contains(u));
            if (!hidersLeft) return alive.stream().filter(seekers::contains).toList();
            if (alive.stream().noneMatch(seekers::contains)) return alive.stream().toList();
            return null;
        }

        @Override protected void onTimeUp() { finish(alive.stream().filter(u -> !seekers.contains(u)).toList(), "Time's up - the hiders survived!"); }

        @Override protected void onLeave(Player p) { unsolid(p, false); BlockDisplay bd = displays.remove(p.getUniqueId()); if (bd != null) bd.remove(); }

        @Override protected void onCleanup() { for (UUID u : new ArrayList<>(solid.keySet())) { Block b = solid.remove(u); if (b != null) b.setType(Material.AIR, false); } displays.values().forEach(Entity::remove); }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = new ArrayList<>();
            l.add("<gray>You: " + (seekers.contains(p.getUniqueId()) ? "<red>Seeker" : "<green>Hider"));
            l.add("<gray>Hiders left: <white>" + alive.stream().filter(u -> !seekers.contains(u)).count());
            l.add("<gray>Points: <gold>" + points.getOrDefault(p.getUniqueId(), 0));
            l.add("<gray>" + (released() ? "Time left: <white>" + clock(timeLimit() - elapsed) : "Release in: <white>" + (settings.i("hide-seconds", 30) - elapsed) + "s"));
            return l;
        }
    }
}
