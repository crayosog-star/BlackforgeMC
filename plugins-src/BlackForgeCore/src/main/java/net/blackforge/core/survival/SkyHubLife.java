package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

/**
 * The living parts of the Skyblock Hub ({@link SkyHubBuilder} builds the stone): signposts and portal signs, the three
 * leaderboards on their pedestals, kiosk NPCs that open the guide/events/cosmetics/store/achievements, the giant
 * OneBlock that turns slowly with ores orbiting it, floating crystals, and particles at the portals, waterfalls and arch.
 * Everything here is spawned non-persistent and removed on shutdown.
 */
final class SkyHubLife implements Listener {
    private static final int Y = SkyHubBuilder.Y;
    private final BlackForgeCore plugin;
    private final World w;
    private final NamespacedKey kioskKey;
    private final List<Entity> spawned = new ArrayList<>();
    private final List<TextDisplay> boards = new ArrayList<>();
    private BlockDisplay bigBlock;
    private final List<BlockDisplay> orbit = new ArrayList<>(), crystals = new ArrayList<>();
    private int tick;

    SkyHubLife(BlackForgeCore plugin, World hub) {
        this.plugin = plugin;
        this.w = hub;
        this.kioskKey = new NamespacedKey(BlackForgeCore.NS, "skyhub_kiosk");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnAll, 80L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 100L, 2L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateBoards, 120L, 1200L);
    }

    private Location at(double x, double y, double z) { return new Location(w, x, y, z); }

    private void spawnAll() {
        remove();
        // signs: the info centre, the two ways out, the portals and what each game is
        label(at(0.5, Y + 4.2, 12.5), "<white><bold>SKYBLOCK HUB</bold><newline><#7bd88f>← Vanilla Skyblock   <#c084fc>OneBlock →<newline><gray>Walk across a bridge and through the portal", 1.3f);
        label(at(-17.5, Y + 3.6, 12.5), "<#7bd88f><bold>← VANILLA SKYBLOCK", 1.3f);
        label(at(18.5, Y + 3.6, 12.5), "<#c084fc><bold>ONEBLOCK →", 1.3f);
        label(at(SkyHubBuilder.VANILLA_GATE_X + 3.5, Y + 10.5, 0.5), "<#7bd88f><bold>VANILLA SKYBLOCK</bold><newline><gray>Start on a tiny island and build your empire.<newline><gray>Classic Skyblock: Sky Harbor, island levels, /is", 1.4f);
        label(at(SkyHubBuilder.ONEBLOCK_GATE_X - 2.5, Y + 10.5, 0.5), "<#c084fc><bold>ONEBLOCK</bold><newline><gray>One block under your feet. Break it - it comes back<newline><gray>as something new, phase after phase. /ob", 1.4f);
        label(at(SkyHubBuilder.VANILLA_X + 0.5, Y + 6, 4.5), "<#7bd88f>Welcome to the <bold>Vanilla</bold> isle<newline><gray>The portal is straight ahead", 1.0f);
        label(at(SkyHubBuilder.ONEBLOCK_X + 0.5, Y + 4, 6.5), "<#c084fc>Welcome to the <bold>OneBlock</bold> isle<newline><gray>The portal is straight ahead", 1.0f);
        // leaderboards above the pedestals
        for (int px : SkyHubBuilder.PEDESTAL_X) {
            TextDisplay td = Ui.hologram(at(px + 0.5, Y + 4.4, SkyHubBuilder.PEDESTAL_Z + 0.5), "<gray>Loading...", 1.0f);
            boards.add(td);
            spawned.add(td);
        }
        updateBoards();
        // kiosk NPCs
        Villager.Profession[] jobs = {Villager.Profession.LIBRARIAN, Villager.Profession.CLERIC, Villager.Profession.LEATHERWORKER,
                Villager.Profession.CARTOGRAPHER, Villager.Profession.ARMORER, Villager.Profession.FARMER};
        String[][] info = {{"guide", "<#4fd1c5><bold>Guide</bold><newline><gray>How everything works"}, {"events", "<#ffb347><bold>Events</bold><newline><gray>What's on today"},
                {"cosmetics", "<light_purple><bold>Cosmetics</bold><newline><gray>Kill and victory effects"}, {"store", "<gold><bold>Store</bold><newline><gray>Supporter ranks"},
                {"achievements", "<gold><bold>Achievements</bold><newline><gray>Your goals"}, {"rewards", "<#7bd88f><bold>Daily Rewards</bold><newline><gray>Where to claim them"}};
        for (int i = 0; i < SkyHubBuilder.KIOSKS.size(); i++) {
            SkyHubBuilder.Kiosk k = SkyHubBuilder.KIOSKS.get(i);
            Location l = at(k.x() + 0.5, Y + 1, k.z() + 0.5);
            l.setDirection(new org.bukkit.util.Vector(0.5 - l.getX(), 0, 12.5 - l.getZ()));   // facing the plaza
            Villager.Profession job = jobs[i % jobs.length];
            Villager v = w.spawn(l, Villager.class, e -> {
                e.setAI(false); e.setInvulnerable(true); e.setSilent(true); e.setCollidable(false); e.setPersistent(false);
                e.setRemoveWhenFarAway(false); e.setProfession(job); e.setVillagerLevel(5);
                e.getPersistentDataContainer().set(kioskKey, PersistentDataType.STRING, k.id());
            });
            spawned.add(v);
            label(at(k.x() + 0.5, Y + 3.3, k.z() + 0.5), info[i][1] + "<newline><yellow>» Right-click", 0.9f);
        }
        // the giant OneBlock, slowly turning, with ores orbiting it
        bigBlock = w.spawn(at(SkyHubBuilder.BIG_BLOCK_X, SkyHubBuilder.BIG_BLOCK_Y, SkyHubBuilder.BIG_BLOCK_Z), BlockDisplay.class, d -> {
            d.setBlock(Material.GRASS_BLOCK.createBlockData());
            d.setPersistent(false);
            d.setTransformation(new Transformation(new Vector3f(-3.5f, -3.5f, -3.5f), new Quaternionf(), new Vector3f(7, 7, 7), new Quaternionf()));
            d.setBrightness(new Display.Brightness(15, 15));
        });
        spawned.add(bigBlock);
        Material[] ores = {Material.DIAMOND_ORE, Material.GOLD_ORE, Material.EMERALD_ORE, Material.IRON_ORE, Material.AMETHYST_BLOCK, Material.OAK_LOG};
        for (Material m : ores) {
            BlockDisplay d = w.spawn(at(SkyHubBuilder.BIG_BLOCK_X, SkyHubBuilder.BIG_BLOCK_Y, SkyHubBuilder.BIG_BLOCK_Z), BlockDisplay.class, e -> {
                e.setBlock(m.createBlockData());
                e.setPersistent(false);
                e.setTeleportDuration(2);
                e.setTransformation(new Transformation(new Vector3f(-0.5f), new Quaternionf(), new Vector3f(1), new Quaternionf()));
            });
            orbit.add(d);
            spawned.add(d);
        }
        // floating crystals around the hub
        double[][] spots = {{-22, Y + 14, -6}, {24, Y + 16, -4}, {-60, Y + 12, -20}, {62, Y + 18, 18}, {0, Y + 30, -40}, {108, Y + 12, -14}, {84, Y + 20, 14}};
        for (double[] s : spots) {
            BlockDisplay d = w.spawn(at(s[0] + 0.5, s[1], s[2] + 0.5), BlockDisplay.class, e -> {
                e.setBlock(Material.AMETHYST_BLOCK.createBlockData());
                e.setPersistent(false);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTransformation(new Transformation(new Vector3f(-0.75f), new Quaternionf().rotateXYZ(0.6f, 0, 0.6f), new Vector3f(1.5f), new Quaternionf()));
            });
            crystals.add(d);
            spawned.add(d);
        }
    }

    private void label(Location l, String text, float scale) { spawned.add(Ui.hologram(l, text, scale)); }

    private void animate() {
        tick++;
        if (bigBlock == null || !bigBlock.isValid()) return;
        // the big block: a quarter turn every 4 seconds, smoothly
        if (tick % 40 == 0) {
            float a = (float) (tick / 40 * Math.PI / 8);
            bigBlock.setInterpolationDelay(0);
            bigBlock.setInterpolationDuration(40);
            bigBlock.setTransformation(new Transformation(new Vector3f(-3.5f, -3.5f, -3.5f).rotate(new Quaternionf(new AxisAngle4f(a, 0, 1, 0))),
                    new Quaternionf(new AxisAngle4f(a, 0, 1, 0)), new Vector3f(7, 7, 7), new Quaternionf()));
        }
        for (int i = 0; i < orbit.size(); i++) {
            double a = tick * 0.02 + i * Math.PI * 2 / orbit.size();
            double bob = Math.sin(tick * 0.05 + i) * 0.8;
            orbit.get(i).teleport(at(SkyHubBuilder.BIG_BLOCK_X + Math.cos(a) * 7.5, SkyHubBuilder.BIG_BLOCK_Y + bob, SkyHubBuilder.BIG_BLOCK_Z + Math.sin(a) * 7.5));
        }
        if (tick % 20 == 0) for (int i = 0; i < crystals.size(); i++) {   // crystals: a slow spin and a gentle bob
            BlockDisplay d = crystals.get(i);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(20);
            float a = tick * 0.03f + i;
            d.setTransformation(new Transformation(new Vector3f(-0.75f, (float) Math.sin(a) * 0.4f - 0.75f, -0.75f), new Quaternionf().rotateXYZ(0.6f, a, 0.6f), new Vector3f(1.5f), new Quaternionf()));
        }
        if (w.getPlayers().isEmpty()) return;
        if (tick % 5 == 0) {
            // the Vanilla portal's shimmering curtain, the OneBlock portal and the big block's sparkles
            w.spawnParticle(Particle.HAPPY_VILLAGER, SkyHubBuilder.VANILLA_GATE_X + 0.5, Y + 3.5, 0.5, 8, 0.2, 1.4, 1.2, 0);
            w.spawnParticle(Particle.FALLING_WATER, SkyHubBuilder.VANILLA_GATE_X + 0.5, Y + 6, 0.5, 6, 0.1, 0.3, 1.4, 0);
            w.spawnParticle(Particle.PORTAL, SkyHubBuilder.ONEBLOCK_GATE_X + 0.5, Y + 3.5, 0.5, 20, 0.3, 1.4, 1.2, 0.4);
            w.spawnParticle(Particle.END_ROD, SkyHubBuilder.BIG_BLOCK_X, SkyHubBuilder.BIG_BLOCK_Y, SkyHubBuilder.BIG_BLOCK_Z, 3, 4, 4, 4, 0.01);
            w.spawnParticle(Particle.CHERRY_LEAVES, 0.5, Y + 16, -32, 4, 8, 3, 1, 0);   // drifting over the arch
            for (int wx : new int[]{-19, 19}) w.spawnParticle(Particle.SPLASH, wx + 0.5, Y + 0.5, -24.5, 10, 1, 0.2, 0.5, 0);
        }
    }

    /** Richest in Skyblock, richest in OneBlock and most time played on the network. */
    private void updateBoards() {
        if (boards.size() < 3) return;
        Ui.setText(boards.get(0), top("<#7bd88f><bold>RICHEST · SKYBLOCK", plugin.economy().top("skyblock", 5)));
        Ui.setText(boards.get(1), top("<#c084fc><bold>RICHEST · ONEBLOCK", plugin.economy().top("oneblock", 5)));
        List<Map.Entry<String, Double>> played = new ArrayList<>();
        File[] fs = new File(plugin.shared(), "journey").listFiles((d, n) -> n.endsWith(".yml"));
        if (fs != null) for (File f : fs) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (y.getString("name") != null) played.add(Map.entry(y.getString("name"), (double) y.getLong("minutes")));
        }
        played.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder("<gold><bold>MOST PLAYED</bold>");
        for (int i = 0; i < Math.min(5, played.size()); i++)
            sb.append("<newline><yellow>").append(i + 1).append(". <white>").append(played.get(i).getKey()).append(" <gray>").append((long) (played.get(i).getValue() / 60)).append("h");
        if (played.isEmpty()) sb.append("<newline><gray>Nobody yet!");
        Ui.setText(boards.get(2), sb.toString());
    }

    private static String top(String title, List<Map.Entry<String, Double>> rows) {
        StringBuilder sb = new StringBuilder(title + "</bold>");
        for (int i = 0; i < rows.size(); i++)
            sb.append("<newline><yellow>").append(i + 1).append(". <white>").append(rows.get(i).getKey()).append(" <green>").append(NetEconomy.money(rows.get(i).getValue()));
        if (rows.isEmpty()) sb.append("<newline><gray>Nobody yet - be first!");
        return sb.toString();
    }

    @EventHandler
    public void onKiosk(PlayerInteractEntityEvent e) {
        String id = e.getRightClicked().getPersistentDataContainer().get(kioskKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.6f, 1.1f);
        if (id.equals("rewards")) {
            p.sendMessage(Ui.mm("<#7bd88f>✦ <gray>Daily rewards are claimed inside each game: walk through a portal, then <white>/calendar<gray>."));
            p.sendMessage(Ui.mm("<gray>  Skyblock and OneBlock each have their own calendar and streak. Votes (<white>/vote<gray>) pay into the game you're playing."));
            return;
        }
        p.performCommand(id);
    }

    void remove() {
        spawned.forEach(Entity::remove);
        spawned.clear(); boards.clear(); orbit.clear(); crystals.clear();
        bigBlock = null;
    }
}
