package net.alloymc.core.arcade.games.murder;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.RayTraceResult;

import java.util.*;

/**
 * Murder Mystery: one murderer, one detective, everyone else innocent. Innocents collect gold for a bow;
 * the detective's bow reloads; the murderer's knife kills in one hit and can be thrown. Name tags are hidden.
 */
public final class MurderMystery extends ArcadeGame {
    public MurderMystery() {
        super("murder", "Murder Mystery", "<#c94b4b>", Material.IRON_SWORD, Category.PARTY,
                "Find the murderer before they find you. Innocents collect gold to get a bow; the detective must protect them.");
        variants.add(Variant.ffa("classic", "Classic", 4, 16));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().list("spawns", 8).list("gold", 6); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 270);
        m.put("gold-interval-seconds", 6);
        m.put("max-gold-on-map", 12);
        m.put("gold-for-bow", 10);
        m.put("detective-reload-seconds", 5);
        m.put("knife-throw-cooldown-seconds", 6);
        m.put("second-detective-at-players", 12);
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("Innocents: survive and collect gold - 10 gold buys a bow with one arrow. Shooting an innocent kills you too.",
                "Detective: your bow reloads. Murderer: kill everyone; right-click your knife to throw it.");
    }

    @Override public Match newMatch(MatchContext c) { return new MurderMatch(c); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(build("old_manor", "Old Manor", "A creaky two-storey manor full of rooms.", 111, 4, 3, 2,
                        new Rooms.Theme("dark_oak_planks", "spruce_planks", "dark_oak_planks", "stripped_dark_oak_log", "lantern[hanging=true]", "glass_pane",
                                List.of("barrel", "crafting_table", "red_carpet", "bookshelf", "spruce_trapdoor", "flower_pot", "cauldron"), List.of("bookshelf", "dark_oak_log"))),
                       build("archive_library", "Archive Library", "Tall bookshelves, reading rooms and dusty halls.", 112, 5, 3, 1,
                        new Rooms.Theme("bookshelf", "birch_planks", "chiseled_bookshelf", "stripped_birch_log", "sea_lantern", "white_stained_glass_pane",
                                List.of("lectern", "chiseled_bookshelf", "white_carpet", "birch_trapdoor", "potted_fern"), List.of("bookshelf", "bookshelf", "birch_log"))),
                       build("frost_lodge", "Frost Lodge", "A snowed-in mountain lodge with cosy rooms.", 113, 3, 3, 2,
                        new Rooms.Theme("spruce_planks", "stripped_spruce_wood", "spruce_planks", "spruce_log", "lantern[hanging=true]", "glass_pane",
                                List.of("barrel", "smoker", "white_carpet", "spruce_trapdoor", "chest", "campfire[lit=false]"), List.of("spruce_log", "barrel"))));
    }

    static BuiltMap build(String id, String name, String desc, long seed, int rx, int rz, int floors, Rooms.Theme theme) {
        MapKit k = new MapKit(seed);
        Rooms r = Rooms.build(k, theme, rx, rz, 9, floors);
        // a garden around the house
        int gx = r.width / 2 + 6, gz = r.depth / 2 + 6;
        for (int x = -gx; x <= gx; x++) for (int z = -gz; z <= gz; z++) {
            if (k.isSet(x, 0, z)) continue;
            k.set(x, 0, z, id.equals("frost_lodge") ? "snow_block" : MapKit.noise(x / 4.0, z / 4.0, seed) > 0.6 ? "coarse_dirt" : "grass_block");
            k.set(x, -1, z, "dirt");
            if (Math.abs(x) == gx || Math.abs(z) == gz) for (int y = 1; y <= 2; y++) k.set(x, y, z, y == 2 ? "barrier" : "spruce_fence");
            else if (k.rnd.nextInt(60) == 0) k.tree(x, 1, z, id.equals("frost_lodge") ? "spruce_log" : "oak_log", id.equals("frost_lodge") ? "spruce_leaves[persistent=true]" : "oak_leaves[persistent=true]", 4);
        }
        MapDef d = new MapDef("murder", id);
        d.display = name; d.description = desc; d.minPlayers = 4; d.maxPlayers = 16;
        d.bounds = Box.of(-gx, -4, -gz, gx, r.height + 6, gz);
        d.voidY = -3;
        d.spectator = new Pt(0.5, r.height + 4, gz - 2 + 0.5, 180, 40);
        List<Pt> spots = new ArrayList<>(r.floorSpots);
        Collections.shuffle(spots, k.rnd);
        for (int i = 0; i < 16; i++) d.add("spawns", spots.get(i % spots.size()));
        for (int i = 0; i < Math.min(24, spots.size()); i++) d.add("gold", spots.get(spots.size() - 1 - i));
        return new BuiltMap(d, k.c);
    }

    // ------------------------------------------------------------------ match

    enum Role { INNOCENT, DETECTIVE, MURDERER }

    static final class MurderMatch extends Match {
        private static final NamespacedKey GOLD = new NamespacedKey("alloycore", "mm_gold"), DROPPED_BOW = new NamespacedKey("alloycore", "mm_bow");
        private final Map<UUID, Role> role = new HashMap<>();
        private final Map<UUID, Integer> gold = new HashMap<>();
        private final Map<UUID, Long> reloadAt = new HashMap<>(), throwReady = new HashMap<>();

        MurderMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "Find the murderer - or don't get caught!"; }
        @Override protected boolean freezeWhileStarting() { return false; }
        @Override protected int startingSeconds() { return 10; }

        @Override protected void setup(List<Player> ps) {
            List<Location> sp = points("spawns");
            Collections.shuffle(sp, rnd);
            for (int i = 0; i < ps.size(); i++) ps.get(i).teleport(sp.get(i % sp.size()));
            List<Player> shuffled = new ArrayList<>(ps);
            Collections.shuffle(shuffled, rnd);
            role.put(shuffled.get(0).getUniqueId(), Role.MURDERER);
            role.put(shuffled.get(1).getUniqueId(), Role.DETECTIVE);
            if (ps.size() >= settings.i("second-detective-at-players", 12)) role.put(shuffled.get(2).getUniqueId(), Role.DETECTIVE);
            for (Player p : ps) {
                role.putIfAbsent(p.getUniqueId(), Role.INNOCENT);
                Role r = role.get(p.getUniqueId());
                title(p, r == Role.MURDERER ? "<red><bold>MURDERER" : r == Role.DETECTIVE ? "<aqua><bold>DETECTIVE" : "<green><bold>INNOCENT",
                        r == Role.MURDERER ? "<gray>Kill everyone without getting caught" : r == Role.DETECTIVE ? "<gray>Find and shoot the murderer" : "<gray>Stay alive, collect gold");
                hideNames(p, ps);
            }
        }

        /** Everyone's name tag hidden for everyone in this match. */
        private void hideNames(Player viewer, List<Player> all) {
            Scoreboard sb = viewer.getScoreboard();
            if (sb == Bukkit.getScoreboardManager().getMainScoreboard()) { sb = Bukkit.getScoreboardManager().getNewScoreboard(); viewer.setScoreboard(sb); }
            org.bukkit.scoreboard.Team t = sb.getTeam("mm_hidden");
            if (t == null) t = sb.registerNewTeam("mm_hidden");
            t.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
            for (Player p : all) t.addEntry(p.getName());
        }

        @Override protected void onCleanup() {
            for (Player p : everyone()) { var t = p.getScoreboard().getTeam("mm_hidden"); if (t != null) t.unregister(); }
        }

        @Override protected void onStart() {
            for (Player p : living()) {
                Role r = role.get(p.getUniqueId());
                if (r == Role.MURDERER) p.getInventory().setItem(1, knife());
                if (r == Role.DETECTIVE) { p.getInventory().setItem(1, detectiveBow()); p.getInventory().setItem(8, new ItemStack(Material.ARROW)); }
            }
            broadcast(prefix() + "The murderer has their knife. Good luck...");
        }

        private static ItemStack knife() { ItemStack it = Ui.item(Material.IRON_SWORD, "<red><bold>Knife</bold> <gray>(right-click to throw)"); it.editMeta(m -> m.setUnbreakable(true)); return it; }
        private static ItemStack detectiveBow() { ItemStack it = Ui.item(Material.BOW, "<aqua><bold>Detective's Bow"); it.editMeta(m -> m.setUnbreakable(true)); return it; }

        @Override protected void secondTick() {
            // gold spawns
            int interval = settings.i("gold-interval-seconds", 6);
            if (elapsed % interval == 0) {
                long onMap = world.getNearbyEntities(at(map.bounds.center()), map.bounds.sizeX(), 40, map.bounds.sizeZ()).stream().filter(e -> e instanceof Item i && i.getPersistentDataContainer().has(GOLD)).count();
                if (onMap < settings.i("max-gold-on-map", 12)) {
                    List<Location> g = points("gold");
                    Item it = world.dropItem(g.get(rnd.nextInt(g.size())).add(0, 0.3, 0), new ItemStack(Material.GOLD_INGOT));
                    it.setVelocity(new org.bukkit.util.Vector());
                    it.getPersistentDataContainer().set(GOLD, PersistentDataType.BYTE, (byte) 1);
                }
            }
            // detective reload
            long now = System.currentTimeMillis();
            for (Player p : living()) {
                Long r = reloadAt.get(p.getUniqueId());
                if (r != null && r <= now) { reloadAt.remove(p.getUniqueId()); p.getInventory().setItem(8, new ItemStack(Material.ARROW)); }
            }
            int left = timeLimit() - elapsed;
            for (Player p : everyone()) {
                Role r = role.get(p.getUniqueId());
                p.sendActionBar(Ui.mm((r == Role.MURDERER ? "<red>Murderer" : r == Role.DETECTIVE ? "<aqua>Detective" : "<green>Innocent") + " <gray>• gold: <gold>" + gold.getOrDefault(p.getUniqueId(), 0) + " <gray>• " + clock(left)));
            }
        }

        @Override protected boolean allowPickup(Player p, ItemStack it) {
            if (it.getType() == Material.GOLD_INGOT) {
                for (Entity e : p.getNearbyEntities(1.5, 1.5, 1.5)) if (e instanceof Item i && i.getPersistentDataContainer().has(GOLD)) {
                    i.remove();
                    int g = gold.merge(p.getUniqueId(), 1, Integer::sum);
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
                    arcade.stats().add(p.getUniqueId(), game.id, "gold", 1);
                    int need = settings.i("gold-for-bow", 10);
                    if (g % need == 0 && role.get(p.getUniqueId()) == Role.INNOCENT) {
                        if (!p.getInventory().contains(Material.BOW)) p.getInventory().setItem(1, Ui.item(Material.BOW, "<green>Bow"));
                        p.getInventory().addItem(new ItemStack(Material.ARROW));
                        p.sendMessage(Ui.mm(prefix() + "<green>You got a bow and an arrow!"));
                    }
                    break;
                }
                return false;
            }
            if (it.getType() == Material.BOW) {
                // the fallen detective's bow: any innocent can pick it up and become the hero
                for (Entity e : p.getNearbyEntities(1.5, 1.5, 1.5)) if (e instanceof Item i && i.getPersistentDataContainer().has(DROPPED_BOW)) {
                    if (role.get(p.getUniqueId()) != Role.INNOCENT) return false;
                    i.remove();
                    role.put(p.getUniqueId(), Role.DETECTIVE);
                    p.getInventory().setItem(1, detectiveBow());
                    p.getInventory().setItem(8, new ItemStack(Material.ARROW));
                    broadcast(prefix() + "<aqua>Someone picked up the detective's bow!");
                    return false;
                }
            }
            return false;
        }

        @Override protected boolean onDamage(Player victim, Entity attacker, EntityDamageEvent e) {
            if (attacker instanceof Player a && role.get(a.getUniqueId()) == Role.MURDERER && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    && a.getInventory().getItemInMainHand().getType() == Material.IRON_SWORD) {
                killBy(victim, a, "was stabbed");
            }
            return false;   // nobody takes normal damage; every death is decided here
        }

        @Override protected void onProjectileHit(Player shooter, ProjectileHitEvent e) {
            if (!(e.getEntity() instanceof Arrow) || !(e.getHitEntity() instanceof Player victim) || !isAlive(victim) || victim.equals(shooter)) return;
            e.setCancelled(true);
            e.getEntity().remove();
            if (role.get(shooter.getUniqueId()) == Role.DETECTIVE) reloadAt.put(shooter.getUniqueId(), System.currentTimeMillis() + settings.i("detective-reload-seconds", 5) * 1000L);
            Role vr = role.get(victim.getUniqueId());
            killBy(victim, shooter, "was shot");
            if (vr != Role.MURDERER && role.get(shooter.getUniqueId()) != Role.MURDERER && isAlive(shooter)) {
                broadcast(prefix() + nameOf(shooter) + " <gray>shot an innocent and paid for it.");
                killBy(shooter, null, "shot an innocent");
            }
        }

        @Override protected void onInteract(Player p, PlayerInteractEvent e) {
            ItemStack it = e.getItem();
            if (it == null || it.getType() != Material.IRON_SWORD || role.get(p.getUniqueId()) != Role.MURDERER) return;
            if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
            long now = System.currentTimeMillis();
            if (throwReady.getOrDefault(p.getUniqueId(), 0L) > now) { p.sendActionBar(Ui.mm("<red>Knife not ready")); return; }
            throwReady.put(p.getUniqueId(), now + settings.i("knife-throw-cooldown-seconds", 6) * 1000L);
            Location eye = p.getEyeLocation();
            RayTraceResult hit = world.rayTrace(eye, eye.getDirection(), 24, FluidCollisionMode.NEVER, true, 0.4, en -> en instanceof Player o && !o.equals(p) && isAlive(o));
            double len = hit == null ? 24 : hit.getHitPosition().distance(eye.toVector());
            for (double d = 0.5; d < len; d += 0.6) world.spawnParticle(Particle.CRIT, eye.clone().add(eye.getDirection().multiply(d)), 1, 0, 0, 0, 0);
            world.playSound(eye, Sound.ITEM_TRIDENT_THROW, 1f, 1.4f);
            if (hit != null && hit.getHitEntity() instanceof Player v) killBy(v, p, "was hit by a thrown knife");
        }

        private void killBy(Player victim, Player killer, String how) {
            if (!isAlive(victim)) return;
            if (killer != null) recordDamage(victim, killer);
            Role r = role.get(victim.getUniqueId());
            if (r == Role.DETECTIVE) {
                Item bow = world.dropItem(victim.getLocation(), new ItemStack(Material.BOW));
                bow.getPersistentDataContainer().set(DROPPED_BOW, PersistentDataType.BYTE, (byte) 1);
                bow.setGlowing(true); bow.customName(Ui.mm("<aqua><bold>Detective's Bow")); bow.setCustomNameVisible(true);
                broadcast(prefix() + "<aqua>The detective has been killed! Their bow is on the ground...");
            }
            world.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
            killPlayer(victim, how);
        }

        @Override protected void onDeath(Player p, Player killer, String how) {
            eliminate(p, null);
            title(p, "<red><bold>YOU DIED", "<gray>" + how);
            if (role.get(p.getUniqueId()) == Role.MURDERER) broadcast(prefix() + "<green>The murderer <white>" + p.getName() + " <green>has been killed" + (killer != null ? " by <white>" + killer.getName() : "") + "!");
        }

        @Override protected void onVoid(Player p) { p.teleport(points("spawns").getFirst()); }

        @Override protected List<UUID> checkWinners() {
            boolean murdererAlive = alive.stream().anyMatch(u -> role.get(u) == Role.MURDERER);
            long others = alive.stream().filter(u -> role.get(u) != Role.MURDERER).count();
            if (!murdererAlive) return players.stream().filter(u -> role.get(u) != Role.MURDERER && alive.contains(u)).toList();
            if (others == 0) return players.stream().filter(u -> role.get(u) == Role.MURDERER).toList();
            return null;
        }

        @Override protected void onTimeUp() {
            finish(players.stream().filter(u -> role.get(u) != Role.MURDERER && alive.contains(u)).toList(), "Time's up - the innocents survived!");
        }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = new ArrayList<>();
            Role r = role.get(p.getUniqueId());
            l.add("<gray>Role: " + (r == Role.MURDERER ? "<red>Murderer" : r == Role.DETECTIVE ? "<aqua>Detective" : "<green>Innocent"));
            l.add("<gray>Innocents left: <white>" + alive.stream().filter(u -> role.get(u) != Role.MURDERER).count());
            l.add("<gray>Time left: <white>" + clock(timeLimit() - elapsed));
            l.add("<gray>Map: <white>" + map.display);
            return l;
        }
    }
}
