package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import net.blackforge.core.hub.DragonAnimator;
import net.blackforge.core.hub.DragonFlight;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static net.blackforge.core.survival.InfernalLayout.*;

/**
 * The living side of the Infernal Kingdom: four dragons on a flight path round the citadel, ambient fire / embers / sparks at
 * the plaza, the crate shrines, the rank displays and the trophies, quiet forge sounds in the districts, and the floating
 * labels over every crate, rank and leaderboard. Everything runs only while someone is in the kingdom's world, within 56
 * blocks of an emitter, with small particle counts, and the labels are removed when it stops.
 *
 * {@code ox, oz} is where the kingdom's origin sits in the world (0, 0 in the staging world).
 */
public final class KingdomLife {
    private record Emitter(double x, double y, double z, String kind) {}
    private static final String TAG = "kingdom_label";
    private static final double RANGE = 56;

    private final BlackForgeCore plugin;
    private final World world;
    private final int ox, oz;
    private final List<Emitter> emitters = new ArrayList<>();
    private final List<TextDisplay> labels = new ArrayList<>();
    private final Random rnd = new Random();
    private DragonAnimator dragons;
    private BukkitTask ambient, sounds;

    public KingdomLife(BlackForgeCore plugin, World world, int ox, int oz) {
        this.plugin = plugin;
        this.world = world;
        this.ox = ox;
        this.oz = oz;
        buildEmitters();
    }

    /** The dragons' sky: round the citadel, high over the towers, never through the keep. */
    static DragonFlight.Region region(int ox, int oz) {
        return new DragonFlight.Region(ox, oz - 20, Y + 75, 150, -1e9, -1e9, oz + 40, ox, oz - 115, 80);
    }

    public void start() {
        stop();
        for (Entity e : world.getEntitiesByClass(TextDisplay.class)) if (e.getScoreboardTags().contains(TAG)) e.remove();
        dragons = new DragonAnimator(plugin, world, region(ox, oz), 4);
        spawnLabels();
        ambient = Bukkit.getScheduler().runTaskTimer(plugin, this::ambientTick, 40L, 8L);
        sounds = Bukkit.getScheduler().runTaskTimer(plugin, this::soundTick, 100L, 120L);
    }

    public void stop() {
        if (ambient != null) ambient.cancel();
        if (sounds != null) sounds.cancel();
        ambient = sounds = null;
        if (dragons != null) { dragons.remove(); dragons = null; }
        labels.forEach(Entity::remove);
        labels.clear();
    }

    // ------------------------------------------------------------------ emitters

    private void add(double x, double y, double z, String kind) { emitters.add(new Emitter(ox + x + 0.5, Y + y, oz + z + 0.5, kind)); }

    private void buildEmitters() {
        for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4 + Math.PI / 8; add(Math.round(Math.cos(a) * 30), 4.3, PLAZA_Z + Math.round(Math.sin(a) * 30), "brazier"); }
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) add(sx * 11, 14.5, PLAZA_Z + sz * 11, "pillar");
        add(0, 19, PLAZA_Z, "anvil");
        for (Spot s : CRATES) add(s.x(), 4.5, s.z(), "crate_" + s.id());
        for (Spot s : RANKS) add(s.x(), TERRACE_Y + 6 + RANKS.indexOf(s) * 2, s.z(), "rank_" + s.id());
        for (Spot s : BOARDS) add(s.x(), TERRACE_Y + 12, s.z(), "trophy");
        add(0, 2, GATE_Z - 6, "gate");
    }

    private void ambientTick() {
        List<Player> ps = world.getPlayers();
        if (ps.isEmpty()) return;
        for (Player p : ps) {
            Location l = p.getLocation();
            for (Emitter e : emitters) {
                double dx = e.x - l.getX(), dy = e.y - l.getY(), dz = e.z - l.getZ();
                if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) continue;
                emit(p, e);
            }
        }
    }

    private void emit(Player p, Emitter e) {
        double x = e.x, y = e.y, z = e.z;
        switch (e.kind) {
            case "brazier" -> { p.spawnParticle(Particle.FLAME, x, y, z, 1, 0.15, 0.1, 0.15, 0.005); if (rnd.nextInt(3) == 0) p.spawnParticle(Particle.LAVA, x, y, z, 1, 0.2, 0.1, 0.2, 0); }
            case "pillar" -> { p.spawnParticle(Particle.FLAME, x, y, z, 3, 0.4, 0.3, 0.4, 0.02); if (rnd.nextInt(2) == 0) p.spawnParticle(Particle.SMALL_FLAME, x, y + 2, z, 2, 0.5, 1.5, 0.5, 0.01); }
            case "anvil" -> { for (int i = 0; i < 3; i++) p.spawnParticle(Particle.LAVA, x + rnd.nextGaussian() * 8, y, z + rnd.nextGaussian() * 2, 1, 0, 0, 0, 0); p.spawnParticle(Particle.FIREWORK, x + rnd.nextGaussian() * 6, y + 1, z + rnd.nextGaussian() * 2, 1, 0, 0.1, 0, 0.05); }
            case "crate_vote" -> p.spawnParticle(Particle.ELECTRIC_SPARK, x, y + 1, z, 3, 0.8, 0.8, 0.8, 0.3);
            case "crate_iron" -> { p.spawnParticle(Particle.FLAME, x, y, z, 2, 1.2, 0.2, 1.2, 0.01); p.spawnParticle(Particle.SMALL_FLAME, x, y + 1, z, 1, 1.5, 1, 1.5, 0.01); }
            case "crate_koth" -> { p.spawnParticle(Particle.DUST, x, y + 1.5, z, 3, 1, 1, 1, 0, new Particle.DustOptions(Color.fromRGB(229, 72, 77), 1.4f)); p.spawnParticle(Particle.CRIMSON_SPORE, x, y + 2, z, 2, 1.5, 1, 1.5, 0); }
            case "crate_gold" -> { p.spawnParticle(Particle.DUST, x, y + 1.5, z, 3, 1.2, 1, 1.2, 0, new Particle.DustOptions(Color.fromRGB(255, 181, 46), 1.5f)); p.spawnParticle(Particle.FALLING_LAVA, x, y + 8, z, 1, 3, 0.2, 3, 0); }
            case "crate_netherite" -> { p.spawnParticle(Particle.SOUL_FIRE_FLAME, x, y, z, 3, 3, 0.2, 3, 0.01); p.spawnParticle(Particle.REVERSE_PORTAL, x, y + 3, z, 6, 2, 3, 2, 0.1); p.spawnParticle(Particle.LAVA, x + rnd.nextGaussian() * 5, y + 1, z + rnd.nextGaussian() * 5, 1, 0, 0, 0, 0); }
            case "rank_copper" -> p.spawnParticle(Particle.SMALL_FLAME, x, y, z, 2, 1, 1, 1, 0.01);
            case "rank_iron" -> p.spawnParticle(Particle.WHITE_ASH, x, y, z, 6, 1.5, 2, 1.5, 0);
            case "rank_gold" -> p.spawnParticle(Particle.WAX_ON, x, y, z, 3, 1.5, 2, 1.5, 0);
            case "rank_diamond" -> p.spawnParticle(Particle.END_ROD, x, y, z, 2, 1.5, 2, 1.5, 0.02);
            case "rank_netherite" -> { p.spawnParticle(Particle.REVERSE_PORTAL, x, y, z, 8, 2, 3, 2, 0.15); p.spawnParticle(Particle.SOUL_FIRE_FLAME, x, y - 4, z, 2, 2, 0.5, 2, 0.01); }
            case "trophy" -> p.spawnParticle(Particle.END_ROD, x, y, z, 1, 0.3, 0.6, 0.3, 0.01);
            default -> p.spawnParticle(Particle.FLAME, x, y, z, 2, 2, 0.2, 2, 0.01);   // the gate: torchlight along the sill
        }
    }

    private void soundTick() {
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            double lx = l.getX() - ox, lz = l.getZ() - oz;
            if (Math.hypot(lx, lz - PLAZA_Z) < 60) p.playSound(l, rnd.nextBoolean() ? Sound.BLOCK_LAVA_AMBIENT : Sound.BLOCK_FIRE_AMBIENT, SoundCategory.AMBIENT, 0.4f, 0.8f);
            if (Math.hypot(lx - BLACKSMITH.x(), lz - BLACKSMITH.z()) < 60) p.playSound(l, rnd.nextInt(3) == 0 ? Sound.BLOCK_ANVIL_USE : Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE, SoundCategory.AMBIENT, 0.35f, 0.9f);
            if (lz < TREASURY_Z1 && lz > TREASURY_Z2 && Math.abs(lx) < 46 && l.getY() < Y + 20) p.playSound(l, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.AMBIENT, 0.3f, 0.6f);
        }
    }

    // ------------------------------------------------------------------ labels

    private void label(double x, double y, double z, String text, float scale) {
        TextDisplay t = Ui.hologram(new Location(world, ox + x + 0.5, Y + y, oz + z + 0.5), text, scale);
        t.addScoreboardTag(TAG);
        t.setPersistent(false);
        labels.add(t);
    }

    private void spawnLabels() {
        String[][] crate = {{"vote", "<#ffe45c><bold>Spark</bold>", "<gray>Vote crate"}, {"iron", "<#e0895a><bold>Cinder</bold>", "<gray>Cinder Key"}, {"koth", "<#e5484d><bold>KOTH</bold>", "<gray>King of the Hill"},
                {"gold", "<#ffb52e><bold>Molten</bold>", "<gray>Molten Key"}, {"netherite", "<#ff4422><bold>Forgeheart</bold>", "<gray>Forgeheart Key"}};
        for (Spot s : CRATES) for (String[] c : crate) if (c[0].equals(s.id())) label(s.x(), 6.2, s.z(), c[1] + "<newline>" + c[2] + "<newline><dark_gray>Right-click with a key", s.id().equals("netherite") ? 1.5f : 1.1f);
        for (Spot s : RANKS) {
            Ranks.Rank r = Ranks.byId(s.id());
            if (r == null) continue;
            int tier = RANKS.indexOf(s);
            label(s.x(), TERRACE_Y + 18 + tier, s.z(), r.color() + "<bold>" + r.name().toUpperCase() + "</bold><newline><gray>" + r.homes() + " homes • +" + r.bonusClaims() + " chunks<newline><gray>+" + Math.round(r.scrapBoost() * 100) + "% Scrap" + (r.fly() ? " • /fly" : "") + "<newline><dark_gray>/ranks", 1.2f + tier * 0.1f);
        }
        String[] boards = {"Richest", "Miners", "Slayers", "Veterans", "The Fallen"};
        for (int i = 0; i < BOARDS.size(); i++) label(BOARDS.get(i).x(), TERRACE_Y + 14, BOARDS.get(i).z(), "<gold><bold>" + boards[i] + "</bold><newline><dark_gray>(leaderboard)", 1.2f);
        label(0, 10, GATE_Z - 8, "<#ff6a00><bold>DRAGON GATE</bold><newline><gray>Walk through to enter the wilds", 1.6f);
        label(0, 26, PLAZA_Z + 20, "<#ff6a00><bold>BLACKFORGEMC</bold><newline><gray>The Infernal Kingdom", 2.2f);
        for (Spot s : SHOPS) {
            String name = switch (s.id()) { case "tradinghall" -> "Trading Hall"; case "auction" -> "Auction House"; case "dailyshop" -> "Daily Shop"; case "sell" -> "The Trader"; case "scrap" -> "Scrap Shop"; default -> "Chest Shops"; };
            label(s.x(), s.y() + 12, s.z(), "<gold><bold>" + name + "</bold>", 1.4f);
        }
        label(BLACKSMITH.x(), BLACKSMITH.y() + 22, BLACKSMITH.z(), "<#ff7a1a><bold>The Great Forge</bold><newline><gray>Blacksmith • anvil", 1.6f);
    }
}
