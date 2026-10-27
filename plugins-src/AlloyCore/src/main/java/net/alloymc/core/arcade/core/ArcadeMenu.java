package net.alloymc.core.arcade.core;

import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * The arcade GUI: dark metallic panes with forge cyan accents. Main screen lists every game (filter by
 * category, paged), a game screen lists its variants with live queue/match status, its maps (information
 * only - maps are always picked at random) and the player's stats. Every click is checked server-side.
 */
public final class ArcadeMenu implements Listener {
    private static final int[] GAME_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private final Arcade arcade;

    ArcadeMenu(Arcade arcade) { this.arcade = arcade; }

    private static final class Screen implements InventoryHolder {
        final String kind; final String game; final ArcadeGame.Category cat; final int page;
        final Map<Integer, Runnable> actions = new HashMap<>();
        Inventory inv;
        Screen(String kind, String game, ArcadeGame.Category cat, int page) { this.kind = kind; this.game = game; this.cat = cat; this.page = page; }
        public Inventory getInventory() { return inv; }
    }

    private static ItemStack pane(Material m) { return Ui.item(m, " "); }

    private void frame(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));
        for (int i = 0; i < 9; i++) inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE));
        for (int i = inv.getSize() - 9; i < inv.getSize(); i++) inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE));
        inv.setItem(4, pane(Material.CYAN_STAINED_GLASS_PANE));
    }

    // ------------------------------------------------------------------ main screen

    public void openMain(Player p, ArcadeGame.Category cat, int page) {
        Screen s = new Screen("main", null, cat, page);
        s.inv = Bukkit.createInventory(s, 54, Ui.mm("<dark_gray>⚙ <aqua><bold>BlackForge Arcade</bold>" + (cat == null ? "" : " <dark_gray>» <gray>" + cat.label)));
        frame(s.inv);
        // category tabs
        s.inv.setItem(1, tab(null, cat));
        s.actions.put(1, () -> openMain(p, null, 0));
        int slot = 2;
        for (ArcadeGame.Category c : ArcadeGame.Category.values()) {
            s.inv.setItem(slot, tab(c, cat));
            final ArcadeGame.Category cc = c;
            s.actions.put(slot, () -> openMain(p, cc, 0));
            slot++;
            if (slot == 4) slot = 5;
        }
        int totalPlaying = 0;
        for (ArcadeGame g : arcade.games()) totalPlaying += arcade.matches().playing(g);
        s.inv.setItem(8, Ui.item(Material.COMPASS, "<aqua><bold>BlackForge Arcade", "<gray>Pick a game, pick a mode, play.", "<gray>Maps are chosen at random every match.", "", "<gray>Playing right now: <white>" + totalPlaying));
        List<ArcadeGame> list = arcade.games().stream().filter(g -> cat == null || g.category == cat).toList();
        int per = GAME_SLOTS.length, pages = Math.max(1, (list.size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < per && pg * per + i < list.size(); i++) {
            ArcadeGame g = list.get(pg * per + i);
            s.inv.setItem(GAME_SLOTS[i], gameIcon(p, g));
            s.actions.put(GAME_SLOTS[i], () -> openGame(p, g));
        }
        if (list.isEmpty()) s.inv.setItem(31, Ui.item(Material.BARRIER, "<gray>No games in this category yet."));
        if (pg > 0) { s.inv.setItem(45, Ui.item(Material.ARROW, "<white>« Previous page")); s.actions.put(45, () -> openMain(p, cat, pg - 1)); }
        if (pg < pages - 1) { s.inv.setItem(53, Ui.item(Material.ARROW, "<white>Next page »")); s.actions.put(53, () -> openMain(p, cat, pg + 1)); }
        MatchManager.Queue q = arcade.matches().queueOf(p);
        if (q != null) { s.inv.setItem(49, Ui.item(Material.RED_DYE, "<red><bold>Leave Queue", "<gray>Queued for " + q.game.color + q.game.name + " <gray>(" + q.variant.label() + ")")); s.actions.put(49, () -> { arcade.matches().leave(p); p.closeInventory(); }); }
        else { s.inv.setItem(49, Ui.item(Material.PLAYER_HEAD, "<aqua><bold>Your Arcade Stats", "<gray>Wins: <white>" + arcade.stats().total(p.getUniqueId(), "wins"), "<gray>Games played: <white>" + arcade.stats().total(p.getUniqueId(), "played"), "<gray>Kills: <white>" + arcade.stats().total(p.getUniqueId(), "kills"))); }
        p.openInventory(s.inv);
    }

    private ItemStack tab(ArcadeGame.Category c, ArcadeGame.Category current) {
        boolean on = c == current;
        ItemStack it = Ui.item(c == null ? Material.NETHER_STAR : c.icon, (on ? "<aqua><bold>" : "<white>") + (c == null ? "All Games" : c.label), on ? "<aqua>Showing" : "<gray>Click to filter");
        if (on) it.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return it;
    }

    private String status(ArcadeGame g) {
        int playing = 0, loading = 0, starting = 0, waiting = 0;
        for (Match m : arcade.matches().matchesOf(g)) { if (m.state() == MatchState.IN_PROGRESS) playing++; else if (m.state().live()) starting++; }
        for (Variant v : g.variants()) {
            MatchManager.Queue q = arcade.matches().queue(g, v);
            if (q.loading()) loading++;
            else if (q.countdown() >= 0) starting++;
            else if (q.size() > 0) waiting++;
        }
        boolean anyMap = g.variants().stream().anyMatch(v -> arcade.hasPlayableMap(g, v));
        if (!anyMap) return "<red>Unavailable <dark_gray>(maps loading or disabled)";
        if (arcade.matches().slots().used() >= arcade.matches().slots().max()) return "<red>Full <dark_gray>(all arenas busy)";
        if (loading > 0) return "<yellow>Loading arena...";
        if (starting > 0) return "<yellow>Starting soon";
        if (playing > 0) return "<gold>In progress <dark_gray>(" + playing + " match" + (playing > 1 ? "es" : "") + ")" + (waiting > 0 ? " <green>+ queue open" : "");
        return waiting > 0 ? "<green>Players waiting" : "<green>Available";
    }

    private ItemStack gameIcon(Player p, ArcadeGame g) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + g.category.label);
        for (String line : wrap(g.description, 34)) lore.add("<gray>" + line);
        lore.add("");
        lore.add("<gray>Status: " + status(g));
        lore.add("<gray>Playing: <white>" + arcade.matches().playing(g));
        long maps = arcade.maps().pool(g.id).stream().filter(m -> m.enabled && arcade.isValid(m)).count();
        lore.add("<gray>Maps: <white>" + maps + " <dark_gray>(random each match)");
        lore.add("<gray>Modes: <white>" + String.join(", ", g.variants().stream().map(Variant::label).toList()));
        lore.add("<gray>Your wins: <gold>" + arcade.stats().get(p.getUniqueId(), g.id, "wins"));
        lore.add("");
        lore.add("<aqua>» Click to play");
        return Ui.item(g.icon, g.color + "<bold>" + g.name, lore.toArray(String[]::new));
    }

    static List<String> wrap(String s, int w) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (line.length() + word.length() + 1 > w && !line.isEmpty()) { out.add(line.toString()); line.setLength(0); }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    // ------------------------------------------------------------------ game screen

    public void openGame(Player p, ArcadeGame g) {
        Screen s = new Screen("game", g.id, null, 0);
        s.inv = Bukkit.createInventory(s, 54, Ui.mm("<dark_gray>⚙ " + g.color + "<bold>" + g.name));
        frame(s.inv);
        List<String> how = new ArrayList<>();
        for (String l : g.howToPlay()) for (String w : wrap(l, 38)) how.add("<gray>" + w);
        s.inv.setItem(4, Ui.item(g.icon, g.color + "<bold>" + g.name, how.toArray(String[]::new)));
        List<Variant> vs = g.variants();
        int[] slots = vs.size() <= 4 ? new int[]{20, 22, 24, 31} : vs.size() <= 7 ? new int[]{19, 20, 21, 22, 23, 24, 25} : new int[]{19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        if (vs.size() == 1) slots = new int[]{22};
        else if (vs.size() == 2) slots = new int[]{21, 23};
        else if (vs.size() == 3) slots = new int[]{20, 22, 24};
        MatchManager.Queue mine = arcade.matches().queueOf(p);
        for (int i = 0; i < vs.size() && i < slots.length; i++) {
            Variant v = vs.get(i);
            MatchManager.Queue q = arcade.matches().queue(g, v);
            boolean playable = arcade.hasPlayableMap(g, v);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + (v.isTeams() ? v.teams() + " teams of " + v.teamSize() : "Free for all") + " <dark_gray>• <gray>" + v.minPlayers() + "-" + v.maxPlayers() + " players");
            if (v.ranked()) lore.add("<gold>Ranked <gray>- your rating: <white>" + Ratings.rating(arcade.stats(), p.getUniqueId(), g.id, "rating_" + v.id()));
            lore.add("");
            if (!playable) lore.add("<red>No map available right now");
            else if (q.loading()) lore.add("<yellow>Loading an arena...");
            else if (q.countdown() >= 0) lore.add("<yellow>Starting in " + q.countdown() + "s <gray>(" + q.size() + "/" + v.maxPlayers() + ")");
            else lore.add("<gray>In queue: <white>" + q.size() + " <dark_gray>/ " + v.minPlayers() + " needed");
            long running = arcade.matches().matchesOf(g).stream().filter(m -> m.variant.equals(v)).count();
            if (running > 0) lore.add("<gray>Matches running: <white>" + running + (g.lateJoin() ? " <green>(join in progress)" : ""));
            lore.add("");
            boolean queued = mine != null && mine.game == g && mine.variant.equals(v);
            lore.add(queued ? "<red>» Click to leave the queue" : playable ? "<aqua>» Click to join" : "<dark_gray>Unavailable");
            ItemStack it = Ui.item(variantIcon(g, v, i), (queued ? "<green>✔ " : "") + g.color + "<bold>" + v.label(), lore.toArray(String[]::new));
            it.setAmount(Math.max(1, Math.min(64, v.isTeams() ? v.teamSize() : 1)));
            if (queued) it.editMeta(m -> m.setEnchantmentGlintOverride(true));
            s.inv.setItem(slots[i], it);
            s.actions.put(slots[i], () -> {
                if (queued) { arcade.matches().leave(p); openGame(p, g); return; }
                if (!playable) { p.sendMessage(Ui.mm(MatchManager.TAG + "<red>" + g.name + " " + v.label() + " has no available maps right now.")); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f); return; }
                p.closeInventory();
                arcade.matches().join(p, g, v);
            });
        }
        // maps (information only; selection is automatic)
        List<String> ml = new ArrayList<>(List.of("<gray>A map is picked at random for every", "<gray>match - no voting, no waiting.", ""));
        for (MapDef m : arcade.maps().pool(g.id)) {
            boolean ok = m.enabled && arcade.isValid(m);
            ml.add((ok ? "<green>● " : "<dark_gray>● ") + "<white>" + m.display + " <dark_gray>" + (ok ? m.minPlayers + "-" + m.maxPlayers + "p" : m.enabled ? "(unavailable)" : "(disabled)"));
        }
        s.inv.setItem(47, Ui.item(Material.FILLED_MAP, "<aqua><bold>Maps", ml.toArray(String[]::new)));
        StatsService st = arcade.stats();
        UUID u = p.getUniqueId();
        List<String> sl = new ArrayList<>(List.of(
            "<gray>Played: <white>" + st.get(u, g.id, "played"), "<gray>Wins: <white>" + st.get(u, g.id, "wins"), "<gray>Losses: <white>" + st.get(u, g.id, "losses"),
            "<gray>Kills: <white>" + st.get(u, g.id, "kills") + " <gray>Deaths: <white>" + st.get(u, g.id, "deaths") + " <gray>Assists: <white>" + st.get(u, g.id, "assists"),
            "<gray>Best streak: <white>" + st.get(u, g.id, "best_streak")));
        if (st.has(u, g.id, "best_time")) sl.add("<gray>Best time: <white>" + String.format("%.2fs", st.get(u, g.id, "best_time") / 1000.0));
        sl.add("");
        sl.add("<aqua>» Click for the leaderboard");
        s.inv.setItem(51, Ui.item(Material.PLAYER_HEAD, "<aqua><bold>Your " + g.name + " Stats", sl.toArray(String[]::new)));
        s.actions.put(51, () -> { p.closeInventory(); arcade.command().top(p, g, null); });
        s.inv.setItem(49, Ui.item(Material.ARROW, "<white>« Back to Arcade"));
        s.actions.put(49, () -> openMain(p, null, 0));
        if (mine != null) { s.inv.setItem(45, Ui.item(Material.RED_DYE, "<red><bold>Leave Queue", "<gray>" + mine.game.name + " (" + mine.variant.label() + ")")); s.actions.put(45, () -> { arcade.matches().leave(p); openGame(p, g); }); }
        p.openInventory(s.inv);
    }

    private static Material variantIcon(ArcadeGame g, Variant v, int i) {
        if (v.ranked()) return Material.GOLDEN_SWORD;
        return switch (v.isTeams() ? Math.min(v.teamSize(), 4) : 0) {
            case 0 -> g.icon;
            case 1 -> Material.IRON_INGOT;
            case 2 -> Material.GOLD_INGOT;
            case 3 -> Material.DIAMOND;
            default -> Material.EMERALD;
        };
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Screen s)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        Runnable r = s.actions.get(e.getRawSlot());
        if (r != null) { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f); r.run(); }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Screen) e.setCancelled(true); }
}
