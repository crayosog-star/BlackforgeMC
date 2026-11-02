package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * The Forge Wars easter eggs in game (the map side is in {@link ForgeWarsMaps}, the song and story in {@link ForgeLore}):
 * <ul>
 *   <li>The Smith's three hammers float in hidden spots. Hit or right-click one to pick it up (each player finds their
 *   own; a found hammer disappears for you only). A faint chime plays when you're close to one you haven't found.
 *   The first player to find all three plays the Smith's Song for the whole match; later finders hear it themselves.</li>
 *   <li>Pink ore veins: touch one to read a line of the Golem's story. All four tell you when the Golem next wakes.</li>
 *   <li>Cracked walls: the only cracked bricks underground. Break one to open a secret room.</li>
 *   <li>The jukebox in the song vault plays the Smith's Song for whoever clicks it.</li>
 * </ul>
 * None of it changes the fight: no items, no buffs. Finds are counted in stats.
 */
final class ForgeEggs {
    private static final String PINK = "<#ff3fa4>";
    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(0xFF3FA4), 0.9f);

    private final BedWarsMatch m;
    private final World world;
    private final Plugin plugin;
    private final List<Hammer> hammers = new ArrayList<>();
    private final List<Location> veins;
    private final List<Location> cracks;
    private final Location jukebox;
    private final Map<UUID, Set<Integer>> hammersFound = new HashMap<>(), veinsFound = new HashMap<>();
    private final Set<Integer> opened = new HashSet<>();
    private final Map<UUID, Integer> jukeboxCooldown = new HashMap<>();
    private final List<Playback> playing = new ArrayList<>();
    private boolean songForAll;
    private int ticks, afterSong = -1;   // when to tell everyone how the Golem took the song

    private static final class Hammer {
        final int index;
        final Location at;
        ItemDisplay display;
        Interaction box;
        long lastAttack;
        Hammer(int index, Location at) { this.index = index; this.at = at; }
    }

    private static final class Playback {
        final List<UUID> who;
        final int start;
        int next;
        Playback(List<UUID> who, int start) { this.who = who; this.start = start; }
    }

    ForgeEggs(BedWarsMatch m, World world, Plugin plugin, List<Location> hammerSpots, List<Location> veins, List<Location> cracks, Location jukebox) {
        this.m = m; this.world = world; this.plugin = plugin;
        this.veins = veins; this.cracks = cracks; this.jukebox = jukebox;
        for (int i = 0; i < hammerSpots.size(); i++) spawn(new Hammer(i, hammerSpots.get(i)));
    }

    // ------------------------------------------------------------------ the hammers

    private void spawn(Hammer h) {
        hammers.add(h);
        Location base = h.at.clone();
        base.setYaw(0); base.setPitch(0);
        h.display = world.spawn(base, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.MACE));
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(12, 12));
            d.setTransformation(pose(0, 0));
        });
        h.box = world.spawn(base, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(1.3f);
            i.setResponsive(true);
            i.setPersistent(false);
        });
    }

    /** The hammer turning slowly in the air, head up, bobbing a little. */
    private static Transformation pose(double turn, double bob) {
        Quaternionf rot = new Quaternionf().rotateY((float) turn).rotateZ((float) Math.toRadians(-25));
        return new Transformation(new Vector3f(0, (float) (0.75 + bob), 0), rot, new Vector3f(0.75f), new Quaternionf());
    }

    private Hammer hammerOf(Entity e) {
        for (Hammer h : hammers) if (e.equals(h.box) || e.equals(h.display)) return h;
        return null;
    }

    private void find(Player p, Hammer h) {
        if (!m.isAlive(p) || p.getGameMode() != GameMode.SURVIVAL) return;
        Set<Integer> got = hammersFound.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        hide(p, h);
        if (!got.add(h.index)) return;
        Location c = h.at.clone().add(0, 1, 0);
        p.playSound(c, Sound.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.6f, 1.5f);
        p.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 0.8f);
        p.spawnParticle(Particle.DUST, c, 30, 0.3, 0.4, 0.3, 0, DUST);
        p.spawnParticle(Particle.ELECTRIC_SPARK, c, 12, 0.2, 0.3, 0.2, 0.05);
        p.sendActionBar(Ui.mm(PINK + "⚒ The Smith's Hammer <white>" + got.size() + "/" + hammers.size()));
        if (got.size() < hammers.size()) return;
        m.eggStat(p.getUniqueId(), "smith_hammers");
        if (!songForAll) {
            songForAll = true;
            play(m.eggListeners());
            m.broadcast(m.eggPrefix() + m.eggName(p) + " " + PINK + "brought the Smith's three hammers together. <gray><i>Listen...</i>");
            afterSong = ticks + ForgeLore.length() + 10;
        } else {
            play(List.of(p));
            p.sendMessage(Ui.mm(PINK + "You found all three of the Smith's hammers. <gray><i>The Smith's Song plays for you.</i>"));
        }
    }

    private void hide(Player p, Hammer h) {
        if (h.display != null && h.display.isValid()) p.hideEntity(plugin, h.display);
        if (h.box != null && h.box.isValid()) p.hideEntity(plugin, h.box);
    }

    // ------------------------------------------------------------------ hooks from the match

    /** Right-clicking an entity: true when it was one of the hammers (the click is used up). */
    boolean onInteractEntity(Player p, Entity e) {
        Hammer h = hammerOf(e);
        if (h == null) return false;
        find(p, h);
        return true;
    }

    /** Clicking a block: true when it was a vein or the jukebox (the click is used up). */
    boolean onInteract(Player p, PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getHand() != EquipmentSlot.HAND) return false;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK) return false;
        if (jukebox != null && same(b, jukebox)) {
            if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return false;
            if (jukeboxCooldown.getOrDefault(p.getUniqueId(), 0) > ticks) return true;
            jukeboxCooldown.put(p.getUniqueId(), ticks + ForgeLore.length() + 20);
            play(List.of(p));
            p.sendActionBar(Ui.mm(PINK + "♪ <white>The Smith's Song"));
            return true;
        }
        for (int i = 0; i < veins.size(); i++) {
            Location v = veins.get(i);
            if (Math.abs(b.getX() - v.getBlockX()) > 1 || Math.abs(b.getY() - v.getBlockY()) > 1 || Math.abs(b.getZ() - v.getBlockZ()) > 1) continue;
            touchVein(p, i, b.getLocation().add(0.5, 0.5, 0.5));
            return true;
        }
        return false;
    }

    private void touchVein(Player p, int i, Location at) {
        if (!m.isAlive(p) || p.getGameMode() != GameMode.SURVIVAL) return;
        Set<Integer> got = veinsFound.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        boolean fresh = got.add(i);
        p.spawnParticle(Particle.DUST, at, 20, 0.5, 0.5, 0.5, 0, DUST);
        p.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.BLOCKS, 1f, 0.7f);
        if (!fresh) return;
        p.playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.BLOCKS, 0.7f, 0.6f);
        String line = i < ForgeLore.VEIN_LINES.size() ? ForgeLore.VEIN_LINES.get(i) : "...";
        p.sendMessage(Ui.mm(PINK + "<i>" + line + "</i> <dark_gray>(" + got.size() + "/" + veins.size() + ")"));
        if (got.size() < veins.size()) return;
        m.eggStat(p.getUniqueId(), "golem_lore");
        int in = m.golemWakesIn();
        p.sendMessage(Ui.mm(PINK + "The ore hums under your hand. <white>" + (m.golemAwake() ? "The Golem is awake." : in >= 0 ? "The Golem wakes in " + (in / 60) + ":" + String.format("%02d", in % 60) + "." : "The Golem sleeps.")));
    }

    boolean isCrack(Block b) {
        for (Location c : cracks) if (b.getX() == c.getBlockX() && b.getZ() == c.getBlockZ() && (b.getY() == c.getBlockY() || b.getY() == c.getBlockY() + 1)) return true;
        return false;
    }

    /** A cracked wall broke: open the whole doorway, no drops. */
    void onBreak(Player p, BlockBreakEvent e) {
        Block b = e.getBlock();
        for (int i = 0; i < cracks.size(); i++) {
            Location c = cracks.get(i);
            if (b.getX() != c.getBlockX() || b.getZ() != c.getBlockZ() || (b.getY() != c.getBlockY() && b.getY() != c.getBlockY() + 1)) continue;
            e.setDropItems(false);
            for (int dy = 0; dy <= 1; dy++) {
                Block d = world.getBlockAt(c.getBlockX(), c.getBlockY() + dy, c.getBlockZ());
                if (!d.equals(b) && d.getType() == Material.CRACKED_STONE_BRICKS) d.setType(Material.AIR, false);
            }
            Location mid = c.clone().add(0.5, 1, 0.5);
            world.spawnParticle(Particle.BLOCK, mid, 40, 0.3, 0.6, 0.3, 0.1, Material.CRACKED_STONE_BRICKS.createBlockData());
            world.playSound(mid, Sound.BLOCK_DEEPSLATE_BRICKS_BREAK, SoundCategory.BLOCKS, 1f, 0.7f);
            if (opened.add(i)) {
                p.playSound(mid, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 1.2f);
                p.sendActionBar(Ui.mm(PINK + "You found a secret room."));
                m.eggStat(p.getUniqueId(), "secret_rooms");
            }
            return;
        }
    }

    // ------------------------------------------------------------------ every tick

    void tick() {
        ticks++;
        if (ticks == afterSong) m.broadcast(m.eggPrefix() + PINK + "<i>" + (m.golemAwake()
                ? "Far below, the Golem stops... and listens."
                : "Somewhere under the Core, something heavy turns over in its sleep.") + "</i>");
        // the song
        for (Iterator<Playback> it = playing.iterator(); it.hasNext(); ) {
            Playback pb = it.next();
            int t = ticks - pb.start;
            while (pb.next < ForgeLore.SONG.size() && ForgeLore.SONG.get(pb.next).tick() <= t) {
                ForgeLore.Note n = ForgeLore.SONG.get(pb.next++);
                for (UUID u : pb.who) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null && p.getWorld() == world) p.playSound(p.getLocation(), n.sound(), SoundCategory.RECORDS, n.volume(), n.pitch());
                }
            }
            if (pb.next >= ForgeLore.SONG.size()) it.remove();
        }
        for (Hammer h : hammers) {
            if (h.box == null || !h.box.isValid()) continue;
            // hits (left-clicks) on the hitbox
            Interaction.PreviousInteraction last = h.box.getLastAttack();
            if (last != null && last.getTimestamp() > h.lastAttack) {
                h.lastAttack = last.getTimestamp();
                Player p = Bukkit.getPlayer(last.getPlayer().getUniqueId());
                if (p != null) find(p, h);
            }
            // slow turn and bob
            if (ticks % 40 == 1 && h.display.isValid()) {
                h.display.setInterpolationDelay(0);
                h.display.setInterpolationDuration(40);
                h.display.setTransformation(pose((ticks / 40) * Math.PI / 2, (ticks / 40) % 2 == 0 ? 0.08 : -0.04));
            }
        }
        if (ticks % 30 == 0) hints();
    }

    /** A faint chime and a few pink specks near hammers you haven't found; dust trickling off unopened cracks. */
    private void hints() {
        for (Player p : m.eggListeners()) {
            if (!m.isAlive(p) || p.getGameMode() != GameMode.SURVIVAL) continue;
            Set<Integer> got = hammersFound.getOrDefault(p.getUniqueId(), Set.of());
            for (Hammer h : hammers) {
                if (got.contains(h.index)) { if (h.display.isValid() && p.canSee(h.display)) hide(p, h); continue; }   // after a rejoin
                if (p.getWorld() != world || p.getLocation().distanceSquared(h.at) > 7 * 7) continue;
                Location c = h.at.clone().add(0, 0.9, 0);
                p.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.AMBIENT, 0.35f, 0.6f);
                p.spawnParticle(Particle.DUST, c, 3, 0.3, 0.3, 0.3, 0, DUST);
            }
        }
        for (int i = 0; i < cracks.size(); i++) {
            if (opened.contains(i) || ticks % 60 != 0) continue;
            Location c = cracks.get(i).clone().add(0.5, 2, 0.5);
            world.spawnParticle(Particle.FALLING_DUST, c, 2, 0.3, 0.05, 0.3, 0, Material.GRAVEL.createBlockData());
        }
    }

    private void play(List<Player> to) {
        List<UUID> who = new ArrayList<>();
        for (Player p : to) who.add(p.getUniqueId());
        playing.add(new Playback(who, ticks));
    }

    private static boolean same(Block b, Location l) { return b.getX() == l.getBlockX() && b.getY() == l.getBlockY() && b.getZ() == l.getBlockZ(); }

    void remove() {
        for (Hammer h : hammers) {
            if (h.display != null) h.display.remove();
            if (h.box != null) h.box.remove();
        }
        hammers.clear();
        playing.clear();
    }
}
