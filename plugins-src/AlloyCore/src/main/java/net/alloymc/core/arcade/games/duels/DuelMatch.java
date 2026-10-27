package net.alloymc.core.arcade.games.duels;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** One duel: rounds (best-of), kit-specific rules (sumo knock-off, boxing hit count, combo hit speed). */
final class DuelMatch extends Match {
    private final String kit;
    private final Map<Team, Integer> roundWins = new HashMap<>();
    private final Map<UUID, Integer> hits = new HashMap<>();
    private int round = 1;
    private boolean resetting;

    DuelMatch(MatchContext c) { super(c); kit = variant.key(); }

    private int needed() { int r = Math.max(1, settings.i("rounds." + kit, 1)); return r / 2 + 1; }

    @Override protected String objective() {
        return switch (kit) { case "sumo" -> "Knock your opponent off the platform!"; case "boxing" -> "First to " + settings.i("boxing-hits", 100) + " hits wins!"; default -> "Defeat your opponent!"; };
    }

    @Override protected int startingSeconds() { return 4; }
    @Override protected boolean naturalRegen() { return !kit.equals("builduhc"); }
    @Override protected boolean hunger() { return kit.equals("builduhc"); }
    @Override protected boolean allowPlace(Player p, Block b) { return kit.equals("builduhc"); }
    @Override protected boolean allowBreak(Player p, Block b) { return kit.equals("builduhc") && placed.contains(key(b)); }
    @Override protected boolean allowPickup(Player p, ItemStack it) { return !kit.equals("sumo") && !kit.equals("boxing"); }

    @Override protected void setup(List<Player> ps) { spawnAll(); }

    private void spawnAll() {
        for (Team t : teams) {
            List<Player> ms = online(t.members);
            for (int i = 0; i < ms.size(); i++) {
                Player p = ms.get(i);
                if (!isAlive(p)) continue;
                resetPlayer(p, GameMode.SURVIVAL);
                p.teleport(teamPoint(t, "spawn").clone().add(i * 1.5, 0, 0));
                giveKit(p);
            }
        }
    }

    private void giveKit(Player p) {
        for (String line : settings.list("kit." + kit, List.of())) {
            try {
                ItemStack it = Loot.item(line);
                String n = it.getType().name();
                if (n.endsWith("_HELMET")) p.getInventory().setHelmet(it);
                else if (n.endsWith("_CHESTPLATE")) p.getInventory().setChestplate(it);
                else if (n.endsWith("_LEGGINGS")) p.getInventory().setLeggings(it);
                else if (n.endsWith("_BOOTS")) p.getInventory().setBoots(it);
                else if (it.getType() == Material.SPLASH_POTION && it.getAmount() > 1) { int c = it.getAmount(); it.setAmount(1); for (int i = 0; i < c; i++) p.getInventory().addItem(it.clone()); }
                else p.getInventory().addItem(it);
            } catch (Exception e) { arcade.plugin().getLogger().warning("[Duels] bad kit line '" + line + "': " + e.getMessage()); }
        }
        if (kit.equals("combo")) p.setMaximumNoDamageTicks(3);
    }

    @Override protected boolean onDamage(Player victim, Entity attacker, EntityDamageEvent e) {
        if (kit.equals("sumo")) { if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) e.setDamage(0); else return false; return true; }
        if (kit.equals("boxing")) {
            if (attacker instanceof Player a && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                int h = hits.merge(a.getUniqueId(), 1, Integer::sum);
                e.setDamage(0);
                a.sendActionBar(Ui.mm(game.color + "Hits: <white>" + h + " <gray>/ " + settings.i("boxing-hits", 100)));
                if (h >= settings.i("boxing-hits", 100)) roundOver(team(a));
                return true;
            }
            return false;
        }
        return true;
    }

    @Override protected void onVoid(Player p) { killPlayer(p, "void"); }

    @Override protected void onDeath(Player p, Player killer, String how) {
        broadcast(prefix() + deathMessage(p, killer, how));
        alive.remove(p.getUniqueId());
        resetPlayer(p, GameMode.SPECTATOR);
        p.teleport(at(map.spectator));
        Team t = team(p);
        if (t.members.stream().noneMatch(alive::contains)) {
            Team other = teams.stream().filter(x -> x != t).findFirst().orElse(null);
            roundOver(other);
        }
    }

    private void roundOver(Team winner) {
        if (resetting || state() != MatchState.IN_PROGRESS) return;
        int w = winner == null ? 0 : roundWins.merge(winner, 1, Integer::sum);
        if (winner == null || w >= needed()) {
            finish(winner == null ? List.of() : new ArrayList<>(winner.members), null);
            return;
        }
        resetting = true;
        round++;
        broadcast(prefix() + winner.name() + " <gray>wins the round! <white>" + score());
        // put the arena back the way it was (BuildUHC blocks), then everyone in again
        for (long k : new ArrayList<>(placed)) world.getBlockAt((int) (k >> 38), (int) (k << 52 >> 52), (int) (k << 26 >> 38)).setType(Material.AIR, false);
        placed.clear();
        arcade.worlds().restore(map, slot, bd -> true, () -> later(20, () -> {
            for (UUID u : players) alive.add(u);
            hits.clear();
            spawnAll();
            title(game.color + "<bold>ROUND " + round, "<white>" + score());
            sound(Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
            resetting = false;
        }));
    }

    private String score() {
        if (teams.size() < 2) return "";
        return teams.get(0).name() + " " + roundWins.getOrDefault(teams.get(0), 0) + " <gray>- " + teams.get(1).name() + " " + roundWins.getOrDefault(teams.get(1), 0);
    }

    @Override protected List<UUID> checkWinners() { return null; }   // rounds decide

    @Override protected void onTimeUp() {
        if (kit.equals("boxing")) {
            // most hits wins
            UUID best = hits.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            if (best != null) { Player p = org.bukkit.Bukkit.getPlayer(best); if (p != null) { finish(new ArrayList<>(team(p).members), "Time's up - most hits wins!"); return; } }
        }
        finish(List.of(), "Time's up - it's a draw!");
    }

    @Override protected void onLeave(Player p) {
        if (state() == MatchState.IN_PROGRESS || state() == MatchState.STARTING) {
            Team t = team(p);
            if (t != null && t.members.stream().noneMatch(u -> alive.contains(u) && !u.equals(p.getUniqueId()))) {
                Team other = teams.stream().filter(x -> x != t).findFirst().orElse(null);
                later(1, () -> finish(other == null ? List.of() : new ArrayList<>(other.members), p.getName() + " left the duel."));
            }
        }
    }

    @Override protected void onCleanup() {
        for (Player p : everyone()) p.setMaximumNoDamageTicks(20);
        // offer a rematch to everyone who was in this duel
        List<UUID> ids = new ArrayList<>(players);
        for (Player p : everyone()) p.sendMessage(Ui.mm(MatchManager.TAG + "<gray>Good game! ").append(
                Ui.mm("<green><bold>[Rematch]").clickEvent(ClickEvent.runCommand("/arcade rematch"))));
        arcade.matches().offerRematch(game, variant, ids);
    }

    @Override protected List<String> sidebar(Player p) {
        List<String> l = new ArrayList<>();
        l.add("<gray>Kit: <white>" + variant.label());
        l.add("<gray>Arena: <white>" + map.display);
        if (needed() > 1) l.add("<gray>Round " + round + " • " + score());
        if (kit.equals("boxing")) for (Player x : everyone()) l.add("<white>" + x.getName() + " <gray>" + hits.getOrDefault(x.getUniqueId(), 0) + " hits");
        l.add("<gray>Time: <white>" + clock(timeLimit() - elapsed));
        return l;
    }
}
