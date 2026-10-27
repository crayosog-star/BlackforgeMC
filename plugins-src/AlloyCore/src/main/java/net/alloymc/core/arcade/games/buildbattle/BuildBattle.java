package net.alloymc.core.arcade.games.buildbattle;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Build Battle: everyone gets a plot and a theme, builds in creative, then rates every other build. */
public final class BuildBattle extends ArcadeGame {
    public BuildBattle() {
        super("buildbattle", "Build Battle", "<#ffb3e6>", Material.BRICKS, Category.CASUAL,
                "Get a theme, build it on your own plot in creative, then visit and rate everyone else's build.");
        variants.add(Variant.ffa("solo", "Solo", 2, 16));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().list("plots", MapRequirements.PER_PLAYER); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("build-seconds", 300);
        m.put("vote-seconds", 12);
        m.put("plot-size", 21);
        m.put("plot-height", 24);
        m.put("themes", List.of("Castle", "Pirate Ship", "Volcano", "Treehouse", "Robot", "Dragon", "Spaceship", "Lighthouse", "Ice Cream", "Haunted House",
                "Windmill", "Rocket", "Mushroom House", "Train", "Fountain", "Pyramid", "Submarine", "Tree", "Car", "Forge"));
        m.put("blocked-items", List.of("TNT", "LAVA_BUCKET", "END_CRYSTAL", "COMMAND_BLOCK", "STRUCTURE_BLOCK", "BARRIER", "RESPAWN_ANCHOR"));
        return m;
    }

    @Override public Match newMatch(MatchContext c) { return new BuildMatch(c); }

    @Override public List<BuiltMap> builtInMaps() { return List.of(classic(), sky()); }

    private static BuiltMap classic() {
        MapKit k = new MapKit(131);
        int size = 21, gap = 5, n = 4, cell = size + gap, half = n * cell / 2;
        MapDef d = new MapDef("buildbattle", "classic_plots");
        d.display = "Classic Plots"; d.description = "Sixteen grass plots on a stone grid.";
        d.minPlayers = 2; d.maxPlayers = 16;
        for (int x = -half - 2; x <= half + 2; x++) for (int z = -half - 2; z <= half + 2; z++) { k.set(x, 0, z, "smooth_stone"); k.set(x, -1, z, "stone"); }
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            int cx = -half + gap / 2 + i * cell + size / 2, cz = -half + gap / 2 + j * cell + size / 2;
            for (int x = -size / 2; x <= size / 2; x++) for (int z = -size / 2; z <= size / 2; z++) k.set(cx + x, 0, cz + z, "grass_block");
            for (int x = -size / 2 - 1; x <= size / 2 + 1; x++) for (int z = -size / 2 - 1; z <= size / 2 + 1; z++)
                if (Math.abs(x) == size / 2 + 1 || Math.abs(z) == size / 2 + 1) k.set(cx + x, 0, cz + z, "polished_andesite");
            d.add("plots", Pt.of(cx + 0.5, 1, cz + 0.5));
        }
        d.bounds = Box.of(-half - 4, -6, -half - 4, half + 4, 40, half + 4);
        d.voidY = -5;
        d.spectator = new Pt(0.5, 30, 0.5, 0, 80);
        d.set("plot-size", size);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap sky() {
        MapKit k = new MapKit(132);
        int size = 23;
        MapDef d = new MapDef("buildbattle", "sky_plots");
        d.display = "Sky Plots"; d.description = "Twelve floating island plots in the clouds.";
        d.minPlayers = 2; d.maxPlayers = 12;
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI * 2 / 12;
            int cx = (int) Math.round(Math.cos(a) * 70), cz = (int) Math.round(Math.sin(a) * 70);
            k.island(cx, 0, cz, size / 2.0 + 3, "moss_block", "dirt", "stone");
            for (int x = -size / 2; x <= size / 2; x++) for (int z = -size / 2; z <= size / 2; z++) k.set(cx + x, 0, cz + z, "grass_block");
            d.add("plots", Pt.of(cx + 0.5, 1, cz + 0.5));
        }
        k.island(0, 0, 0, 8, "quartz_block", "calcite", "calcite");
        d.bounds = Box.of(-90, -20, -90, 90, 40, 90);
        d.voidY = -15;
        d.spectator = new Pt(0.5, 2, 0.5, 0, 0);
        d.set("plot-size", size);
        return new BuiltMap(d, k.c);
    }

    static final class BuildMatch extends Match {
        private static final String[] VOTE_NAMES = {"<dark_red>Super Poop", "<red>Poop", "<yellow>OK", "<green>Good", "<dark_purple>Epic", "<gold>Legendary"};
        private static final Material[] VOTE_ITEMS = {Material.BROWN_TERRACOTTA, Material.RED_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.LIME_TERRACOTTA, Material.PURPLE_TERRACOTTA, Material.ORANGE_TERRACOTTA};
        private final Map<UUID, Integer> plotOf = new HashMap<>();
        private final Map<UUID, Map<UUID, Integer>> votes = new HashMap<>();   // builder -> voter -> 1..6
        private final List<UUID> order = new ArrayList<>();
        private String theme;
        private int voteIndex = -1, voteEnd;
        private boolean voting;

        BuildMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "Build the theme: " + theme; }
        @Override protected int timeLimit() { return 0; }
        @Override protected boolean pvp() { return false; }
        @Override protected boolean onDamage(Player v, Entity a, EntityDamageEvent e) { return false; }
        @Override protected boolean freezeWhileStarting() { return false; }

        private int half() { return settings.i("plot-size", 21) / 2; }
        private Box plot(UUID u) {
            Location c = points("plots").get(plotOf.get(u));
            int h = half();
            return new Box(c.getBlockX() - h, c.getBlockY() - 1, c.getBlockZ() - h, c.getBlockX() + h, c.getBlockY() + settings.i("plot-height", 24), c.getBlockZ() + h);
        }

        @Override protected void setup(List<Player> ps) {
            List<String> themes = settings.list("themes", List.of("House"));
            theme = themes.get(rnd.nextInt(themes.size()));
            for (int i = 0; i < ps.size(); i++) {
                Player p = ps.get(i);
                plotOf.put(p.getUniqueId(), i);
                p.teleport(points("plots").get(i));
                p.setGameMode(GameMode.CREATIVE);
                p.setAllowFlight(true);
            }
        }

        @Override protected void onStart() {
            title("<light_purple><bold>" + theme.toUpperCase(), "<gray>You have " + clock(settings.i("build-seconds", 300)) + " to build it!");
            broadcast(prefix() + "Theme: <white><bold>" + theme + "</bold><gray> - build it on your plot!");
        }

        private boolean inOwnPlot(Player p, Block b) { return !voting && plotOf.containsKey(p.getUniqueId()) && plot(p.getUniqueId()).containsBlock(b.getX(), b.getY(), b.getZ()); }
        @Override protected boolean allowPlace(Player p, Block b) { return inOwnPlot(p, b); }
        @Override protected boolean allowBreak(Player p, Block b) { return inOwnPlot(p, b) && b.getY() >= plot(p.getUniqueId()).y1() + 1; }

        @Override protected boolean allowFlow(Block from, Block to) {
            for (UUID u : plotOf.keySet()) { Box b = plot(u); if (b.containsBlock(from.getX(), from.getY(), from.getZ())) return b.containsBlock(to.getX(), to.getY(), to.getZ()); }
            return false;
        }

        @Override protected void onInventoryClick(Player p, InventoryClickEvent e) {
            ItemStack it = e.getCursor() != null && e.getCursor().getType() != Material.AIR ? e.getCursor() : e.getCurrentItem();
            if (it == null) return;
            String n = it.getType().name();
            if (n.endsWith("_SPAWN_EGG") || settings.list("blocked-items", List.of()).contains(n)) { e.setCancelled(true); p.sendActionBar(Ui.mm("<red>That item isn't allowed here.")); }
        }

        @Override protected void onMove(Player p, Location from, Location to) {
            if (voting || !plotOf.containsKey(p.getUniqueId())) return;
            if (!plot(p.getUniqueId()).grow(2).contains(to.getX(), to.getY(), to.getZ())) { p.teleport(from); p.sendActionBar(Ui.mm("<red>Stay on your plot!")); }
        }

        @Override protected void secondTick() {
            int build = settings.i("build-seconds", 300);
            if (!voting) {
                int left = build - elapsed;
                actionBar("<light_purple>Theme: <white>" + theme + " <gray>• " + clock(left));
                if (left == 60 || left == 30 || left == 10) broadcast(prefix() + "<white>" + left + "s <gray>left to build!");
                if (left <= 0) startVoting();
                return;
            }
            if (elapsed >= voteEnd) nextPlot();
        }

        private void startVoting() {
            voting = true;
            order.addAll(plotOf.keySet().stream().filter(u -> Bukkit.getPlayer(u) != null).toList());
            Collections.shuffle(order, rnd);
            for (Player p : everyone()) {
                p.setGameMode(GameMode.ADVENTURE);
                p.setAllowFlight(true);
                p.getInventory().clear();
                for (int i = 0; i < 6; i++) p.getInventory().setItem(i + 1, Ui.item(VOTE_ITEMS[i], VOTE_NAMES[i] + " <gray>(" + (i + 1) + ")"));
            }
            title("<light_purple><bold>VOTING", "<gray>Rate each build with the hotbar items");
            nextPlot();
        }

        private void nextPlot() {
            voteIndex++;
            if (voteIndex >= order.size()) { results(); return; }
            UUID u = order.get(voteIndex);
            Location c = points("plots").get(plotOf.get(u));
            Location view = c.clone().add(0, 6, -half() - 4);
            view.setDirection(c.toVector().subtract(view.toVector()));
            for (Player p : everyone()) { p.setAllowFlight(true); p.teleport(view); p.setFlying(true); }
            lastView = view;
            voteEnd = elapsed + settings.i("vote-seconds", 12);
            broadcast(prefix() + "Now rating build <white>" + (voteIndex + 1) + "/" + order.size() + "<gray>. Who built it? You'll find out at the end!");
        }

        @Override protected void onInteract(Player p, PlayerInteractEvent e) {
            if (!voting || e.getItem() == null || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
            e.setCancelled(true);
            int score = -1;
            for (int i = 0; i < 6; i++) if (e.getItem().getType() == VOTE_ITEMS[i]) score = i + 1;
            if (score < 0 || voteIndex < 0 || voteIndex >= order.size()) return;
            UUID builder = order.get(voteIndex);
            if (builder.equals(p.getUniqueId())) { p.sendActionBar(Ui.mm("<red>You can't vote for your own build.")); return; }
            votes.computeIfAbsent(builder, k -> new HashMap<>()).put(p.getUniqueId(), score);
            p.sendActionBar(Ui.mm("<gray>You voted " + VOTE_NAMES[score - 1]));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.6f + score * 0.2f);
        }

        private int scoreOf(UUID u) { return votes.getOrDefault(u, Map.of()).values().stream().mapToInt(Integer::intValue).sum(); }

        private void results() {
            List<UUID> ranked = new ArrayList<>(order);
            ranked.sort((a, b) -> scoreOf(b) - scoreOf(a));
            broadcast("<dark_gray>" + "-".repeat(30));
            broadcast(prefix() + "Results for <white>" + theme + "<gray>:");
            for (int i = 0; i < Math.min(5, ranked.size()); i++)
                broadcast("<gray>  " + (i + 1) + ". <white>" + Bukkit.getOfflinePlayer(ranked.get(i)).getName() + " <gray>- <gold>" + scoreOf(ranked.get(i)) + " points");
            for (UUID u : order) arcade.stats().add(u, game.id, "score", scoreOf(u));
            if (ranked.isEmpty()) { finish(List.of(), null); return; }
            int top = scoreOf(ranked.getFirst());
            Location best = points("plots").get(plotOf.get(ranked.getFirst()));
            for (Player p : everyone()) p.teleport(best.clone().add(0, 4, -half() - 3).setDirection(best.toVector().subtract(p.getLocation().toVector())));
            finish(ranked.stream().filter(u -> scoreOf(u) == top).toList(), null);
        }

        private Location lastView;

        /** Nobody is ever knocked out of Build Battle: a fall just puts you back on your plot (or the build being rated). */
        @Override protected void onVoid(Player p) {
            p.setFallDistance(0);
            if (voting && lastView != null) { p.teleport(lastView); p.setAllowFlight(true); p.setFlying(true); }
            else if (plotOf.containsKey(p.getUniqueId())) p.teleport(points("plots").get(plotOf.get(p.getUniqueId())));
        }

        @Override protected List<UUID> checkWinners() { return everyone().isEmpty() ? List.of() : null; }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = new ArrayList<>();
            l.add("<gray>Theme: <white>" + theme);
            l.add(voting ? "<gray>Voting: <white>" + (voteIndex + 1) + "/" + order.size() : "<gray>Time: <white>" + clock(settings.i("build-seconds", 300) - elapsed));
            l.add("<gray>Builders: <white>" + plotOf.size());
            return l;
        }
    }
}
