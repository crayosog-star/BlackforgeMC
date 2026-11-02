package net.alloymc.core.arcade;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Hooks;
import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.Arcade;
import net.alloymc.core.arcade.core.ArcadeGame;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

/**
 * The living parts of the Arcade hub ({@link ArcadeHubBuilder} builds the stone): every game gets the next free pod
 * in its district ({@link #assign}) with its icon spinning above the join plate; free pods say "coming soon"; district
 * and bridge signs; the giant cabinet's screen cycles through featured games; kiosk NPCs (daily rewards, cosmetics,
 * store, your stats, guide); three hidden secrets that pay once; neon sparkles. Spawned non-persistent, removed on shutdown.
 */
final class ArcadeHubLife implements Listener {
    private static final int Y = ArcadeHubBuilder.Y;
    private static final int SECRET_SCRAP = 150;
    private final AlloyCore plugin;
    private final World w;
    private final Arcade arcade;
    private final NamespacedKey kioskKey;
    private final List<Entity> spawned = new ArrayList<>();
    private final List<ItemDisplay> icons = new ArrayList<>();
    private final File secretsFile;
    private TextDisplay screen;
    private int tick, featured;

    ArcadeHubLife(AlloyCore plugin, World world, Arcade arcade) {
        this.plugin = plugin;
        this.w = world;
        this.arcade = arcade;
        this.kioskKey = new NamespacedKey(plugin, "arcade_kiosk");
        this.secretsFile = new File(plugin.getDataFolder(), "arcade-secrets.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 100L, 2L);
    }

    /** Game id -> join plate {x, y, z}: each game on the next free pod of its district (or More Games). */
    static Map<String, int[]> assign(Collection<ArcadeGame> games) {
        Map<String, int[]> out = new LinkedHashMap<>();
        List<ArcadeHubBuilder.Pod> pods = ArcadeHubBuilder.pods();
        Set<ArcadeHubBuilder.Pod> used = new HashSet<>();
        for (ArcadeGame g : games) {
            String d = ArcadeHubBuilder.GAME_DISTRICT.getOrDefault(g.id, switch (g.category) {
                case COMBAT -> "pvp";
                case PARTY -> "party";
                case CASUAL -> "casual";
                default -> "more";
            });
            ArcadeHubBuilder.Pod pick = pods.stream().filter(p -> p.district().equals(d) && !used.contains(p)).findFirst()
                    .orElse(pods.stream().filter(p -> p.district().equals("more") && !used.contains(p)).findFirst().orElse(null));
            if (pick == null) continue;
            used.add(pick);
            out.put(g.id, new int[]{pick.x(), pick.y(), pick.z()});
        }
        return out;
    }

    void spawn(Map<String, int[]> booths) {
        remove();
        Set<String> taken = new HashSet<>();
        for (var en : booths.entrySet()) {
            ArcadeGame g = arcade.game(en.getKey());
            int[] b = en.getValue();
            taken.add(b[0] + "," + b[2]);
            if (g == null) continue;
            ItemDisplay icon = w.spawn(new Location(w, b[0] + 0.5, b[1] + 5.2, b[2] + 0.5), ItemDisplay.class, d -> {
                d.setItemStack(new ItemStack(g.icon));
                d.setPersistent(false);
                d.setBrightness(new Display.Brightness(15, 15));
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.6f), new Quaternionf()));
            });
            icons.add(icon);
            spawned.add(icon);
        }
        for (ArcadeHubBuilder.Pod p : ArcadeHubBuilder.pods())
            if (!taken.contains(p.x() + "," + p.z()))
                label(new Location(w, p.x() + 0.5, p.y() + 3.0, p.z() + 0.5), "<dark_gray><bold>COMING SOON</bold><newline><gray>More games on the way", 0.9f);
        // district names over each island, and signposts where the bridges leave the plaza
        for (ArcadeHubBuilder.District d : ArcadeHubBuilder.DISTRICTS) {
            List<String> names = new ArrayList<>();
            for (var en : booths.entrySet()) {
                ArcadeGame g = arcade.game(en.getKey());
                if (g != null && ArcadeHubBuilder.pods().stream().anyMatch(p -> p.district().equals(d.id()) && p.x() == en.getValue()[0] && p.z() == en.getValue()[2])) names.add(g.name);
            }
            label(new Location(w, d.x() + 0.5, Y + d.dy() + 15, d.z() + 0.5), d.color() + "<bold>" + d.label().toUpperCase() + "</bold><newline><gray>"
                    + (names.isEmpty() ? "Coming soon" : String.join(" · ", names)), 1.8f);
            double a = Math.atan2(d.z(), d.x());
            label(new Location(w, Math.cos(a) * 34 + 0.5, Y + 3.2, Math.sin(a) * 34 + 0.5), d.color() + "<bold>" + d.label() + " →", 1.2f);
        }
        // the cabinet's screen
        screen = Ui.board(new Location(w, 0.5, Y + 14.6, -0.6), "", 2.0f, 0f);
        spawned.add(screen);
        // kiosks
        String[][] info = {{"calendar", "<#7bd88f><bold>Daily Rewards</bold><newline><gray>Your Arcade calendar"}, {"cosmetics", "<light_purple><bold>Cosmetics</bold><newline><gray>Victory and kill effects"},
                {"store", "<gold><bold>Store</bold><newline><gray>Supporter ranks"}, {"stats", "<aqua><bold>Your Stats</bold><newline><gray>Wins, games, ratings"}, {"guide", "<#4fd1c5><bold>Guide</bold><newline><gray>How everything works"}};
        Villager.Profession[] jobs = {Villager.Profession.FARMER, Villager.Profession.LEATHERWORKER, Villager.Profession.CLERIC, Villager.Profession.CARTOGRAPHER, Villager.Profession.LIBRARIAN};
        for (int i = 0; i < ArcadeHubBuilder.KIOSKS.size(); i++) {
            ArcadeHubBuilder.Kiosk k = ArcadeHubBuilder.KIOSKS.get(i);
            Location l = new Location(w, k.x() + 0.5, Y + 1, k.z() + 0.5);
            l.setDirection(new org.bukkit.util.Vector(-l.getX(), 0, -l.getZ()));
            Villager.Profession job = jobs[i % jobs.length];
            String id = info[i][0];
            Villager v = w.spawn(l, Villager.class, e -> {
                e.setAI(false); e.setInvulnerable(true); e.setSilent(true); e.setCollidable(false); e.setPersistent(false);
                e.setProfession(job); e.setVillagerLevel(5);
                e.getPersistentDataContainer().set(kioskKey, PersistentDataType.STRING, id);
            });
            spawned.add(v);
            label(new Location(w, k.x() + 0.5, Y + 3.3, k.z() + 0.5), info[i][1] + "<newline><yellow>» Right-click", 0.9f);
        }
    }

    private void label(Location l, String text, float scale) { spawned.add(Ui.hologram(l, text, scale)); }

    private void animate() {
        tick++;
        if (tick % 20 == 0) for (int i = 0; i < icons.size(); i++) {   // icons: a slow spin and a bob
            ItemDisplay d = icons.get(i);
            if (!d.isValid()) continue;
            float a = tick * 0.05f + i;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(20);
            d.setTransformation(new Transformation(new Vector3f(0, (float) Math.sin(a) * 0.25f, 0), new Quaternionf(new AxisAngle4f(a, 0, 1, 0)), new Vector3f(1.6f), new Quaternionf()));
        }
        if (tick % 60 == 0 && screen != null && screen.isValid()) {   // the giant cabinet: featured game, every 6 s
            List<ArcadeGame> games = new ArrayList<>(arcade.games());
            if (!games.isEmpty()) {
                ArcadeGame g = games.get(featured++ % games.size());
                int playing = arcade.matches().playing(g);
                Ui.setText(screen, "<gray>★ FEATURED ★<newline>" + g.color + "<bold>" + g.name.toUpperCase() + "</bold><newline>"
                        + (playing > 0 ? "<green>" + playing + " playing now" : "<yellow>Be the first in!") + "<newline><dark_gray>INSERT COIN");
            }
        }
        if (w.getPlayers().isEmpty() || tick % 6 != 0) return;
        w.spawnParticle(Particle.END_ROD, 0.5, Y + 25, 3, 4, 5, 0.3, 0.3, 0.01);
        w.spawnParticle(Particle.ELECTRIC_SPARK, 0.5, Y + 15, 2.5, 6, 4, 4, 0.2, 0.05);
        for (ArcadeHubBuilder.District d : ArcadeHubBuilder.DISTRICTS)
            w.spawnParticle(Particle.FIREWORK, d.x() + 0.5, Y + d.dy() + 13, d.z() + 0.5, 2, 3, 1, 3, 0.02);
    }

    @EventHandler
    public void onKiosk(PlayerInteractEntityEvent e) {
        String id = e.getRightClicked().getPersistentDataContainer().get(kioskKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.1f);
        if (id.equals("stats")) arcade.command().run(p, new String[]{"stats"});
        else p.performCommand(id);
    }

    /** The three hidden secrets: right-click the block once for a reward. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSecret(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getClickedBlock().getWorld() != w) return;
        Block b = e.getClickedBlock();
        for (ArcadeHubBuilder.Secret s : ArcadeHubBuilder.SECRETS) {
            if (b.getX() != s.x() || b.getY() != s.y() || b.getZ() != s.z()) continue;
            e.setCancelled(true);
            Player p = e.getPlayer();
            YamlConfiguration y = YamlConfiguration.loadConfiguration(secretsFile);
            List<String> found = new ArrayList<>(y.getStringList(p.getUniqueId().toString()));
            if (found.contains(s.id())) { p.sendActionBar(Ui.mm("<gray>You've found this secret already (" + found.size() + "/" + ArcadeHubBuilder.SECRETS.size() + ")")); return; }
            found.add(s.id());
            y.set(p.getUniqueId().toString(), found);
            try { y.save(secretsFile); } catch (Exception ignored) { }
            plugin.economy().addScrap(p, SECRET_SCRAP);
            Hooks.stat(p, "arcade_secret", 1);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.4f);
            w.spawnParticle(Particle.TOTEM_OF_UNDYING, b.getLocation().add(0.5, 1, 0.5), 40, 0.4, 0.6, 0.4, 0.3);
            p.sendMessage(Ui.mm("<gold>✦ <white>Secret found! <gray>(" + found.size() + "/" + ArcadeHubBuilder.SECRETS.size() + ") <#c6a15b>+" + SECRET_SCRAP + " Scrap"));
            return;
        }
    }

    void remove() {
        spawned.forEach(Entity::remove);
        spawned.clear();
        icons.clear();
        screen = null;
    }
}
