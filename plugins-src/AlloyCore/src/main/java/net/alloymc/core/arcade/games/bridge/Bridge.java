package net.alloymc.core.arcade.games.bridge;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.*;

/** The Bridge: two teams, one bridge. Jump into the other team's goal to score; first to five wins. */
public final class Bridge extends ArcadeGame {
    public Bridge() {
        super("bridge", "The Bridge", "<#5f9dff>", Material.BLUE_TERRACOTTA, Category.COMBAT,
                "Fight across the bridge and jump into the enemy goal to score. Build, block and bow your way to five goals.");
        variants.add(new Variant("1v1", "1v1", 2, 1, 2, 2, false, ""));
        variants.add(new Variant("2v2", "2v2", 2, 2, 3, 4, false, ""));
        variants.add(new Variant("4v4", "4v4", 2, 4, 4, 8, false, ""));
    }

    @Override public int queueCountdown() { return 5; }

    @Override public MapRequirements requirements() {
        return new MapRequirements().teamPoint("spawn").check(m -> {
            List<String> o = new ArrayList<>();
            for (int t = 0; t < 2; t++) if (m.region("team." + t + ".goal") == null) o.add("missing region 'team." + t + ".goal'");
            return o;
        });
    }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 900);
        m.put("goals-to-win", 5);
        m.put("arrow-regen-seconds", 4);
        m.put("build-height-above-bridge", 8);
        return m;
    }

    @Override public Match newMatch(MatchContext c) { return new BridgeMatch(c); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(build("classic_span", "Classic Span", "A straight bridge between two stone bases.", 30, "stone_bricks", "polished_andesite", false, 81),
                       build("gilded_gorge", "Gilded Gorge", "Gold and emerald bases with side ledges.", 34, "gold_block", "quartz_bricks", true, 82),
                       build("iron_crossing", "Iron Crossing", "Iron bases over a deep void.", 26, "iron_block", "polished_deepslate", false, 83));
    }

    static BuiltMap build(String id, String name, String desc, int half, String base, String deck, boolean ledges, long seed) {
        MapKit k = new MapKit(seed);
        MapDef d = new MapDef("bridge", id);
        d.display = name; d.description = desc; d.teams = 2; d.minPlayers = 2; d.maxPlayers = 8;
        for (int t = 0; t < 2; t++) {
            int s = t == 0 ? -1 : 1;
            TeamColor col = TeamColor.of(t);
            int cz = s * half;
            // base: 15x11 platform with walls, the goal pit behind the spawn
            for (int x = -7; x <= 7; x++) for (int z = -5; z <= 5; z++) {
                k.set(x, 0, cz + z, (Math.abs(x) == 7 || Math.abs(z) == 5) ? col.block("terracotta") : base);
                for (int y = -6; y < 0; y++) if (Math.abs(x) < 6 && Math.abs(z) < 4) k.set(x, y, cz + z, deck);
            }
            for (int x = -3; x <= 3; x++) for (int z = 1; z <= 4; z++) {
                int gz = cz + s * z;
                k.set(x, 0, gz, (Math.abs(x) == 3 || z == 4) ? col.block("concrete") : "air");
                for (int y = -5; y < 0; y++) k.set(x, y, gz, (Math.abs(x) == 3 || z == 4) ? col.block("concrete") : "air");
            }
            for (int x = -2; x <= 2; x++) for (int z = 1; z <= 3; z++) k.set(x, -5, cz + s * z, "black_concrete");
            d.region("team." + t + ".goal", Box.of(-2, -5, Math.min(cz + s, cz + s * 3), 2, -1, Math.max(cz + s, cz + s * 3)));
            d.point("team." + t + ".spawn", Pt.of(0.5, 1, cz - s * 3 + 0.5).facing(0.5, 0.5));
            for (int y = 1; y <= 4; y++) for (int x : new int[]{-7, 7}) k.set(x, y, cz - s * 5, y == 4 ? "sea_lantern" : col.block("concrete"));
        }
        // the bridge: a 3-wide terracotta line with a small middle island
        for (int z = -half + 6; z <= half - 6; z++) for (int x = -1; x <= 1; x++) k.set(x, 0, z, z < 0 ? "red_terracotta" : z > 0 ? "blue_terracotta" : "white_terracotta");
        k.island(0, 0, 0, 4, deck, "stone", "stone");
        for (int x = -1; x <= 1; x++) k.set(x, 0, 0, "white_terracotta");
        if (ledges) for (int z = -half / 2; z <= half / 2; z += half / 2) if (z != 0) { k.island(7, -2, z, 3, base, "stone", "stone"); k.island(-7, -2, z, 3, base, "stone", "stone"); }
        d.bounds = Box.of(-22, -30, -half - 8, 22, 20, half + 8);
        d.voidY = -12;
        d.spectator = new Pt(14.5, 14, 0.5, 90, 30);
        d.set("bridge-y", 0);
        return new BuiltMap(d, k.c);
    }

    static final class BridgeMatch extends Match {
        private final Map<Team, Integer> goals = new HashMap<>();
        private int ticks, freezeUntil;

        BridgeMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "Jump into the enemy goal! First to " + settings.i("goals-to-win", 5) + "."; }
        @Override protected boolean supportsRejoin() { return true; }
        @Override protected boolean allowPlace(Player p, Block b) {
            if (b.getY() > oy + settings.i("bridge-y", 0) + settings.i("build-height-above-bridge", 8)) { p.sendMessage(Ui.mm("<red>Build height reached.")); return false; }
            for (Team t : teams) if (region("team." + t.index + ".goal").grow(1).containsBlock(b.getX(), b.getY(), b.getZ())) return false;
            return true;
        }
        @Override protected boolean allowBreak(Player p, Block b) { return placed.contains(key(b)) || b.getType().name().endsWith("_TERRACOTTA") && b.getY() == oy + settings.i("bridge-y", 0); }
        @Override protected boolean wantsFineMoves() { return true; }

        @Override protected void setup(List<Player> ps) { for (Team t : teams) for (Player p : online(t.members)) spawn(p); }

        void spawn(Player p) {
            Team t = team(p);
            resetPlayer(p, GameMode.SURVIVAL);
            List<Player> ms = online(t.members);
            p.teleport(teamPoint(t, "spawn").clone().add(ms.indexOf(p) * 1.5 - (ms.size() - 1) * 0.75, 0, 0));
            ItemStack sword = unbreakable(Material.IRON_SWORD);
            p.getInventory().setItem(0, sword);
            p.getInventory().setItem(1, unbreakable(Material.BOW));
            ItemStack pick = unbreakable(Material.DIAMOND_PICKAXE);
            pick.addUnsafeEnchantment(Enchantment.EFFICIENCY, 2);
            p.getInventory().setItem(2, pick);
            p.getInventory().setItem(3, new ItemStack(t.color.terracotta(), 64));
            p.getInventory().setItem(4, new ItemStack(t.color.terracotta(), 64));
            p.getInventory().setItem(5, new ItemStack(Material.GOLDEN_APPLE, 8));
            p.getInventory().setItem(8, new ItemStack(Material.ARROW));
            for (Material m : new Material[]{Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS}) {
                ItemStack a = new ItemStack(m);
                a.editMeta(LeatherArmorMeta.class, x -> { x.setColor(t.color.rgb); x.setUnbreakable(true); });
                switch (m) { case LEATHER_HELMET -> p.getInventory().setHelmet(a); case LEATHER_CHESTPLATE -> p.getInventory().setChestplate(a); case LEATHER_LEGGINGS -> p.getInventory().setLeggings(a); default -> p.getInventory().setBoots(a); }
            }
        }

        @Override protected void fastTick() {
            ticks++;
            int regen = settings.i("arrow-regen-seconds", 4) * 20;
            if (ticks % regen == 0) for (Player p : living()) if (!p.getInventory().contains(Material.ARROW)) p.getInventory().setItem(8, new ItemStack(Material.ARROW));
        }

        @Override protected void onMove(Player p, Location from, Location to) {
            if (ticks < freezeUntil && (from.getX() != to.getX() || from.getZ() != to.getZ())) { p.teleport(from.clone().setDirection(to.getDirection())); return; }
            Team mine = team(p);
            for (Team t : teams) {
                if (!region("team." + t.index + ".goal").contains(to.getX(), to.getY(), to.getZ())) continue;
                if (t == mine) { spawn(p); p.sendMessage(Ui.mm("<gray>That's your own goal!")); return; }
                score(p, mine);
                return;
            }
        }

        private void score(Player p, Team t) {
            int g = goals.merge(t, 1, Integer::sum);
            arcade.stats().add(p.getUniqueId(), game.id, "goals", 1);
            broadcast(prefix() + nameOf(p) + " <gray>scored for " + t.name() + "<gray>! " + score());
            title(t.color.mm + "<bold>" + p.getName() + " SCORED!", score());
            sound(Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
            if (g >= settings.i("goals-to-win", 5)) { finish(new ArrayList<>(t.members), null); return; }
            // round reset: clear every placed block, everyone back to spawn, short freeze
            for (long k : new ArrayList<>(placed)) world.getBlockAt((int) (k >> 38), (int) (k << 52 >> 52), (int) (k << 26 >> 38)).setType(Material.AIR, false);
            placed.clear();
            arcade.worlds().restore(map, slot, bd -> bd.getMaterial().name().endsWith("_TERRACOTTA"), () -> {});
            for (Player x : living()) spawn(x);
            freezeUntil = ticks + 60;
        }

        private String score() { return teams.get(0).name() + " " + goals.getOrDefault(teams.get(0), 0) + " <gray>- " + teams.get(1).name() + " " + goals.getOrDefault(teams.get(1), 0); }

        @Override protected void onDeath(Player p, Player killer, String how) {
            broadcast(prefix() + deathMessage(p, killer, how));
            spawn(p);
        }

        @Override protected void onRejoin(Player p) { spawn(p); }

        @Override protected List<UUID> checkWinners() {
            for (Team t : teams) if (t.members.stream().noneMatch(alive::contains)) {
                Team o = teams.stream().filter(x -> x != t).findFirst().orElse(null);
                return o == null ? List.of() : new ArrayList<>(o.members);
            }
            return null;
        }

        @Override protected void onTimeUp() {
            int a = goals.getOrDefault(teams.get(0), 0), b = goals.getOrDefault(teams.get(1), 0);
            if (a == b) finish(List.of(), "Time's up - it's a draw!");
            else finish(new ArrayList<>((a > b ? teams.get(0) : teams.get(1)).members), "Time's up!");
        }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = new ArrayList<>();
            l.add("<gray>Map: <white>" + map.display);
            l.add(score());
            l.add("<gray>Goals to win: <white>" + settings.i("goals-to-win", 5));
            l.add("<gray>Kills: <white>" + kills.getOrDefault(p.getUniqueId(), 0));
            l.add("<gray>Time: <white>" + clock(timeLimit() - elapsed));
            return l;
        }
    }
}
