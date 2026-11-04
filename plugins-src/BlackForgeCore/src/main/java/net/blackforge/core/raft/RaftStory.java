package net.blackforge.core.raft;

import net.blackforge.core.Ui;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Dolphin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;

/**
 * The story. Three radio towers, each a jukebox at the top of a mast: switching one on tells a little more and gives the crew a
 * blueprint; the Receiver points the way to the next. The last tower gives away Utopia; its beacon wakes Bruce, the shark that guards
 * the moat. Kill Bruce and the crew has escaped.
 */
final class RaftStory implements Listener {
    private static final String[][] TOWER_TEXT = {
            {"<gray>The radio crackles and spits...", "<#7fff9f>\"...anyone... still out there...? ...is this thing...\"", "<#7fff9f>\"...if you can hear this, there are towers. Follow them. There is a place... Utopia.\"", "<gold>Blueprint learned: <white>Motor"},
            {"<gray>A steadier voice this time.", "<#7fff9f>\"...you made it to the second one. Good. The water gets worse near Utopia. Something old lives there.\"", "<#7fff9f>\"...we called it Bruce. Bring a good blade and a better plan.\"", "<gold>Blueprint learned: <white>Advanced Hook"},
            {"<gray>The mast hums. A map prints itself in the static.", "<#7fff9f>\"...last one. Utopia is the white city on the water, past the three towers. Light its beacon. Then run... or fight.\""}};

    private final RaftModule mod;
    private final java.util.Random rnd = new java.util.Random();
    private long bruceCooldown;

    RaftStory(RaftModule mod) {
        this.mod = mod;
        Bukkit.getPluginManager().registerEvents(this, mod.plugin());
    }

    private static String bearing(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dz, dx));                     // 0 = east, 90 = south
        String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        return names[(int) Math.floorMod(Math.round(a / 45.0), 8)];
    }

    /** Right-click a Receiver: where the next signal is. */
    void receiver(Player p, RaftTeam t) {
        World w = mod.world();
        int[] c = t.centre();
        if (t.won) { p.sendActionBar(Ui.mm("<#7fff9f>Static. <gray>Nothing left to find. You made it out.")); return; }
        int k = t.towers < 3 ? t.towers : 3;
        if (t.towers >= 3 && t.utopia) { p.sendActionBar(Ui.mm("<#7fff9f>Utopia's beacon is lit. <gray>Bruce is somewhere in the moat.")); return; }
        int[] at = RaftStructures.storyPos(w.getSeed(), t.cell, k);
        double dx = at[0] - c[0], dz = at[1] - c[1];
        String what = k < 3 ? "Radio tower " + (k + 1) : "Utopia";
        p.sendActionBar(Ui.mm("<#7fff9f>" + what + " <gray>is <white>" + (int) Math.hypot(dx, dz) + " blocks <gray>to the <white>" + bearing(dx, dz)));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 1.6f);
    }

    @EventHandler
    public void onRadio(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.JUKEBOX || b.getWorld() != mod.world()) return;
        Player p = e.getPlayer();
        RaftTeam t = mod.teamOf(p);
        if (t == null) return;
        long seed = b.getWorld().getSeed();
        int cell = RaftStructures.cellOf(b.getX());
        for (int k = 0; k < 4; k++) {
            int[] r = RaftStructures.radioAt(seed, cell, k);
            if (r[0] != b.getX() || r[1] != b.getY() || r[2] != b.getZ()) continue;
            e.setCancelled(true);
            if (cell != t.cell) { p.sendActionBar(Ui.mm("<gray>Static. This is someone else's signal.")); return; }
            if (k < 3) tower(p, t, k); else beacon(p, t);
            return;
        }
    }

    private void tower(Player p, RaftTeam t, int k) {
        if (k < t.towers) { p.sendActionBar(Ui.mm("<#7fff9f>The radio hums. <gray>You already woke this one.")); return; }
        if (k > t.towers) { p.sendActionBar(Ui.mm("<gray>Just static. <dark_gray>Find the earlier towers first. A Receiver points the way.")); return; }
        t.towers = k + 1;
        if (k == 0) t.unlocked.add("motor");
        if (k == 1) t.unlocked.add("adv_hook");
        mod.markDirty();
        World w = mod.world();
        Location top = new Location(w, p.getLocation().getX(), p.getLocation().getY() + 1, p.getLocation().getZ());
        w.strikeLightningEffect(top.clone().add(4, 4, 0));
        w.spawnParticle(Particle.FIREWORK, top, 80, 1.5, 1.5, 1.5, 0.1);
        for (Player m : mod.membersOnline(t)) {
            m.playSound(m.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
            m.showTitle(Title.title(Ui.mm("<#7fff9f><bold>TOWER " + (k + 1) + " ONLINE"), Ui.mm("<gray>" + (k < 2 ? "Follow the signal." : "Utopia is on the map.")), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
            for (String line : TOWER_TEXT[k]) m.sendMessage(Ui.mm(line));
        }
        int[] c = t.centre();
        int[] next = RaftStructures.storyPos(w.getSeed(), t.cell, k + 1);
        p.sendMessage(Ui.mm("<gray>Next: <white>" + (k < 2 ? "radio tower " + (k + 2) : "Utopia") + " <gray>to the <white>" + bearing(next[0] - c[0], next[1] - c[1]) + "<gray>. Build a Receiver to track it."));
    }

    private void beacon(Player p, RaftTeam t) {
        if (t.won) { p.sendActionBar(Ui.mm("<gold>Utopia's beacon burns steadily. <gray>You already escaped.")); return; }
        if (t.towers < 3) { p.sendActionBar(Ui.mm("<gray>The beacon is dark. <dark_gray>Switch on all three radio towers first.")); return; }
        long now = System.currentTimeMillis();
        for (LivingEntity le : mod.world().getLivingEntities()) if (le.getScoreboardTags().contains(RaftSharks.BOSS) && le.getLocation().distance(p.getLocation()) < 150) { p.sendActionBar(Ui.mm("<red>Bruce is already out there.")); return; }
        if (now < bruceCooldown) { p.sendActionBar(Ui.mm("<gray>The beacon is still glowing. Wait a moment.")); return; }
        bruceCooldown = now + 30_000;
        t.utopia = true;
        mod.markDirty();
        long seed = mod.world().getSeed();
        int[] u = RaftStructures.storyPos(seed, t.cell, 3);
        Location at = new Location(mod.world(), u[0] + 0.5, RaftStructures.SEA - 3, u[1] + 49.5);
        for (Player m : mod.membersOnline(t)) {
            m.playSound(m.getLocation(), Sound.ENTITY_WARDEN_EMERGE, 1f, 0.7f);
            m.showTitle(Title.title(Ui.mm("<red><bold>BRUCE"), Ui.mm("<gray>The water goes dark around Utopia."), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(1000))));
            m.sendMessage(Ui.mm("<gold>The beacon blazes. <gray>Something huge turns in the moat. <white>Kill Bruce and you are free."));
        }
        mod.world().strikeLightningEffect(at);
        mod.sharks().spawn(at, "<dark_red><bold>Bruce</bold> <gray>the Shark King", 5.0, 300, true);
    }

    void bruceDied(LivingEntity bruce) {
        World w = mod.world();
        RaftTeam found = null;
        for (RaftTeam t : mod.teams()) {
            if (t.won || RaftStructures.cellOf(bruce.getLocation().getBlockX()) != t.cell) continue;
            found = t;
        }
        if (found == null) return;
        final RaftTeam t = found;
        t.won = true;
        mod.markDirty();
        w.strikeLightningEffect(bruce.getLocation());
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, bruce.getLocation(), 200, 3, 2, 3, 0.4);
        List<Player> crew = mod.membersOnline(t);
        for (Player m : crew) {
            m.playSound(m.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            m.showTitle(Title.title(Ui.mm("<gold><bold>YOU ESCAPED"), Ui.mm("<gray>Utopia is yours."), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(2))));
            ItemStack trophy = Ui.item(Material.NETHER_STAR, "<gold><bold>Utopian Trophy", "<gray>Bruce, the Shark King, is gone.", "<gray>Raft: escaped from the sea.");
            m.getInventory().addItem(trophy, new ItemStack(Material.DIAMOND, 12), new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 2), new ItemStack(Material.TOTEM_OF_UNDYING)).values().forEach(l -> m.getWorld().dropItemNaturally(m.getLocation(), l));
            m.giveExp(1500);
        }
        String names = String.join(", ", crew.stream().map(Player::getName).toList());
        if (!crew.isEmpty()) mod.plugin().net().broadcastAll(crew.get(0), Ui.mm("<gold>✦ <white>" + names + " <gray>escaped the Raft ocean and slew <dark_red>Bruce<gray>!"));
    }
}
