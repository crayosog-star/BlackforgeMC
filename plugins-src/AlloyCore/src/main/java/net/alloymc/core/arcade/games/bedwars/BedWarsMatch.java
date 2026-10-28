package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** One BedWars game. */
final class BedWarsMatch extends Match {
    private final Set<UUID> shopNpcs = new HashSet<>(), upgradeNpcs = new HashSet<>();
    private final Map<UUID, Integer> armour = new HashMap<>(), pick = new HashMap<>(), axe = new HashMap<>(), respawnAt = new HashMap<>();
    private final Set<UUID> shears = new HashSet<>();
    private final List<TextDisplay> genLabels = new ArrayList<>();
    private int ticks, diamondLevel = 1, emeraldLevel = 1, diamondTimer, emeraldTimer;
    private boolean bedsGone;

    BedWarsMatch(MatchContext c) { super(c); }

    @Override protected String objective() { return "Protect your bed and destroy the others!"; }
    @Override protected boolean freezeWhileStarting() { return true; }
    @Override protected int startingSeconds() { return 5; }
    @Override protected boolean allowDrop(Player p, ItemStack it) { return true; }
    @Override protected boolean supportsRejoin() { return true; }
    @Override protected boolean allowRejoin(Player p) { Team t = team(p); return t != null && bedAlive(t); }

    List<Integer> costs(String key, List<Integer> def) {
        List<String> l = settings.list(key, null);
        if (l == null) { Object o = settings.raw(key); return o instanceof Number n ? List.of(n.intValue()) : def; }
        List<Integer> out = new ArrayList<>();
        for (String s : l) out.add(Integer.parseInt(s.trim()));
        return out.isEmpty() ? def : out;
    }

    boolean bedAlive(Team t) { return t.get("bed", false); }

    void teamMessage(Team t, String mm) { for (Player p : online(t.members)) p.sendMessage(Ui.mm(mm)); }

    // ------------------------------------------------------------------ setup

    @Override protected void setup(List<Player> ps) {
        for (Team t : teams) {
            t.data.put("bed", true);
            for (Player p : online(t.members)) { spawn(p); }
            Location shop = teamPoint(t, "shop"), up = teamPoint(t, "upgrades");
            shopNpcs.add(npc(shop, "<green><bold>ITEM SHOP", Villager.Profession.WEAPONSMITH).getUniqueId());
            upgradeNpcs.add(npc(up, "<aqua><bold>TEAM UPGRADES", Villager.Profession.LIBRARIAN).getUniqueId());
        }
        // bases nobody plays on lose their bed, so they can't be "defended" by nobody
        for (int i = teams.size(); i < map.teams; i++) {
            Location bed = point("team." + i + ".bed");
            if (bed != null) { removeBed(bed.getBlock()); }
        }
        for (Location l : points("diamond_generators")) genLabels.add(Ui.hologram(l.clone().add(0, 2.4, 0), "<aqua><bold>Diamond", 1f));
        for (Location l : points("emerald_generators")) genLabels.add(Ui.hologram(l.clone().add(0, 2.4, 0), "<green><bold>Emerald", 1f));
        diamondTimer = settings.i("diamond-interval-seconds", 30);
        emeraldTimer = settings.i("emerald-interval-seconds", 60);
    }

    private Villager npc(Location l, String name, Villager.Profession prof) {
        return world.spawn(l, Villager.class, v -> {
            v.setAI(false); v.setInvulnerable(true); v.setSilent(true); v.setPersistent(false); v.setCollidable(false);
            v.setProfession(prof); v.customName(Ui.mm(name)); v.setCustomNameVisible(true);
        });
    }

    private void spawn(Player p) {
        Team t = team(p);
        resetPlayer(p, GameMode.SURVIVAL);
        p.teleport(teamPoint(t, "spawn"));
        kit(p);
        applyUpgrades(t);
    }

    private void kit(Player p) {
        Team t = team(p);
        p.getInventory().addItem(unbreakable(Material.WOODEN_SWORD));
        int tier = armour.getOrDefault(p.getUniqueId(), 0);
        Material[] boots = {Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS, Material.IRON_BOOTS, Material.DIAMOND_BOOTS};
        Material[] legs = {Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS, Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS};
        p.getInventory().setHelmet(leather(Material.LEATHER_HELMET, t));
        p.getInventory().setChestplate(leather(Material.LEATHER_CHESTPLATE, t));
        p.getInventory().setLeggings(tier == 0 ? leather(legs[0], t) : unbreakable(legs[tier]));
        p.getInventory().setBoots(tier == 0 ? leather(boots[0], t) : unbreakable(boots[tier]));
        if (pick.getOrDefault(p.getUniqueId(), 0) > 0) p.getInventory().addItem(tool(true, pick.get(p.getUniqueId())));
        if (axe.getOrDefault(p.getUniqueId(), 0) > 0) p.getInventory().addItem(tool(false, axe.get(p.getUniqueId())));
        if (shears.contains(p.getUniqueId())) p.getInventory().addItem(unbreakable(Material.SHEARS));
    }

    private static ItemStack leather(Material m, Team t) {
        ItemStack it = new ItemStack(m);
        it.editMeta(LeatherArmorMeta.class, x -> { x.setColor(t.color.rgb); x.setUnbreakable(true); });
        return it;
    }

    private static ItemStack tool(boolean pickaxe, int tier) {
        Material[] p = {Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE};
        Material[] a = {Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE, Material.DIAMOND_AXE};
        ItemStack it = unbreakable((pickaxe ? p : a)[Math.max(0, Math.min(3, tier - 1))]);
        it.addUnsafeEnchantment(Enchantment.EFFICIENCY, Math.max(1, tier - 1));
        return it;
    }

    // ------------------------------------------------------------------ shop hooks

    void giveSword(Player p, Material m) {
        p.getInventory().remove(Material.WOODEN_SWORD);
        ItemStack s = unbreakable(m);
        if (team(p).getInt("sharpness") > 0) s.addEnchantment(Enchantment.SHARPNESS, 1);
        BedWarsShop.give(p, s);
    }

    void setArmour(Player p, int tier) {
        if (armour.getOrDefault(p.getUniqueId(), 0) >= tier) { p.sendMessage(Ui.mm("<gray>You already have that armour or better.")); return; }
        armour.put(p.getUniqueId(), tier);
        Material[] boots = {Material.LEATHER_BOOTS, Material.CHAINMAIL_BOOTS, Material.IRON_BOOTS, Material.DIAMOND_BOOTS};
        Material[] legs = {Material.LEATHER_LEGGINGS, Material.CHAINMAIL_LEGGINGS, Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS};
        p.getInventory().setBoots(unbreakable(boots[tier]));
        p.getInventory().setLeggings(unbreakable(legs[tier]));
        applyUpgrades(team(p));
    }

    void giveShears(Player p) { if (shears.add(p.getUniqueId())) BedWarsShop.give(p, unbreakable(Material.SHEARS)); }

    void upgradeTool(Player p, boolean pickaxe) {
        Map<UUID, Integer> m = pickaxe ? pick : axe;
        int lv = m.getOrDefault(p.getUniqueId(), 0);
        if (lv >= 4) { p.sendMessage(Ui.mm("<gray>Already the best " + (pickaxe ? "pickaxe" : "axe") + ".")); return; }
        Material[] old = pickaxe ? new Material[]{Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE}
                                 : new Material[]{Material.WOODEN_AXE, Material.STONE_AXE, Material.IRON_AXE, Material.DIAMOND_AXE};
        for (Material x : old) p.getInventory().remove(x);
        m.put(p.getUniqueId(), lv + 1);
        BedWarsShop.give(p, tool(pickaxe, lv + 1));
    }

    void applyUpgrades(Team t) {
        int prot = t.getInt("protection"), sharp = t.getInt("sharpness"), haste = t.getInt("haste");
        for (Player p : online(t.members)) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            for (ItemStack a : p.getInventory().getArmorContents()) if (a != null && prot > 0) a.addUnsafeEnchantment(Enchantment.PROTECTION, prot);
            p.getInventory().setArmorContents(p.getInventory().getArmorContents());
            if (sharp > 0) for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType().name().endsWith("_SWORD")) it.addUnsafeEnchantment(Enchantment.SHARPNESS, 1);
            if (haste > 0) p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 20 * 3600, haste - 1, false, false));
        }
    }

    // ------------------------------------------------------------------ generators & events

    private void drop(Location at, Material m, int cap) {
        int near = 0;
        for (Entity e : world.getNearbyEntities(at, 1.5, 1.5, 1.5)) if (e instanceof Item i && i.getItemStack().getType() == m) near += i.getItemStack().getAmount();
        if (near >= cap) return;
        Item it = world.dropItem(at.clone().add(0, 0.3, 0), new ItemStack(m));
        it.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
    }

    @Override protected void fastTick() {
        ticks++;
        int cap = settings.i("generator-cap", 48);
        for (Team t : teams) {
            if (t.members.stream().noneMatch(alive::contains)) continue;
            Location g = teamPoint(t, "generator");
            double speed = 1 + 0.5 * Math.min(2, t.getInt("forge"));
            if (ticks % Math.max(1, (int) (settings.i("iron-interval-ticks", 20) / speed)) == 0) drop(g, Material.IRON_INGOT, cap);
            if (ticks % Math.max(1, (int) (settings.i("gold-interval-ticks", 140) / speed)) == 0) drop(g, Material.GOLD_INGOT, cap / 3);
            if (t.getInt("forge") >= 3 && ticks % 1200 == 0) drop(g, Material.EMERALD, 4);
        }
        // respawns
        for (var it = respawnAt.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue() > ticks) continue;
            it.remove();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null && isAlive(p)) { spawn(p); title(p, "<green><bold>RESPAWNED", ""); }
        }
    }

    @Override protected void secondTick() {
        if (--diamondTimer <= 0) { diamondTimer = Math.max(10, settings.i("diamond-interval-seconds", 30) - 7 * (diamondLevel - 1)); for (Location l : points("diamond_generators")) drop(l, Material.DIAMOND, 4); }
        if (--emeraldTimer <= 0) { emeraldTimer = Math.max(15, settings.i("emerald-interval-seconds", 60) - 15 * (emeraldLevel - 1)); for (Location l : points("emerald_generators")) drop(l, Material.EMERALD, 2); }
        int di = 0;
        for (TextDisplay td : genLabels) {
            boolean dia = di++ < map.list("diamond_generators").size();
            td.text(Ui.mm(dia ? "<aqua><bold>Diamond " + roman(diamondLevel) + "</bold><newline><gray>in <white>" + diamondTimer + "s" : "<green><bold>Emerald " + roman(emeraldLevel) + "</bold><newline><gray>in <white>" + emeraldTimer + "s"));
        }
        // timeline
        if (elapsed == 360) { diamondLevel = 2; broadcast(prefix() + "<aqua>Diamond generators are now level II!"); }
        if (elapsed == 720) { emeraldLevel = 2; broadcast(prefix() + "<green>Emerald generators are now level II!"); }
        if (elapsed == 1080) { diamondLevel = 3; broadcast(prefix() + "<aqua>Diamond generators are now level III!"); }
        if (elapsed == 1440) { emeraldLevel = 3; broadcast(prefix() + "<green>Emerald generators are now level III!"); }
        int gone = settings.i("beds-destroyed-at-seconds", 1800);
        if (!bedsGone && elapsed == gone - 60) broadcast(prefix() + "<red>All beds will be destroyed in 1 minute!");
        if (!bedsGone && elapsed >= gone) {
            bedsGone = true;
            for (Team t : teams) if (bedAlive(t)) { t.data.put("bed", false); removeBed(teamPoint(t, "bed").getBlock()); }
            title("<red><bold>BED DESTRUCTION", "<gray>All beds are gone - no more respawns!");
            sound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f);
        }
        // heal pool
        for (Team t : teams) if (t.getInt("healpool") > 0) {
            Location s = teamPoint(t, "spawn");
            for (Player p : online(t.members)) if (isAlive(p) && p.getGameMode() == GameMode.SURVIVAL && p.getLocation().distanceSquared(s) < 14 * 14)
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, false, false));
        }
    }

    private static String roman(int n) { return new String[]{"", "I", "II", "III"}[Math.min(3, n)]; }

    // ------------------------------------------------------------------ beds & blocks

    private Team bedOwner(Block b) {
        for (Team t : teams) {
            Location bed = teamPoint(t, "bed");
            if (bed != null && bed.getBlock().getLocation().distanceSquared(b.getLocation()) <= 2.1) return t;
        }
        for (int i = teams.size(); i < map.teams; i++) { Location bed = point("team." + i + ".bed"); if (bed != null && bed.getBlock().getLocation().distanceSquared(b.getLocation()) <= 2.1) return null; }
        return null;
    }

    private void removeBed(Block foot) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            Block b = foot.getRelative(dx, 0, dz);
            if (b.getBlockData() instanceof Bed) b.setType(Material.AIR, false);
        }
    }

    @Override protected boolean allowBreak(Player p, Block b) {
        if (b.getBlockData() instanceof Bed) {
            Team owner = bedOwner(b);
            if (owner == null) return false;
            if (owner == team(p)) { p.sendMessage(Ui.mm("<red>You can't break your own bed!")); return false; }
            return true;
        }
        return placed.contains(key(b));
    }

    @Override protected void onBreak(Player p, BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!(b.getBlockData() instanceof Bed)) return;
        e.setDropItems(false);
        Team owner = bedOwner(b);
        if (owner == null || !bedAlive(owner)) return;
        owner.data.put("bed", false);
        removeBed(teamPoint(owner, "bed").getBlock());
        arcade.stats().add(p.getUniqueId(), game.id, "beds_broken", 1);
        broadcast(prefix() + "<white><bold>BED DESTROYED!</bold> " + owner.name() + " <gray>bed was destroyed by " + nameOf(p) + "<gray>!");
        for (Player m : online(owner.members)) { title(m, "<red><bold>BED DESTROYED!", "<gray>You will no longer respawn"); m.playSound(m.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.6f, 1f); }
        sound(Sound.ENTITY_ENDER_DRAGON_GROWL, 1.4f);
    }

    @Override protected boolean allowPlace(Player p, Block b) {
        // keep generator pads and villagers clear (only the generator spot itself and the block above it: a wider
        // ring used to block the space right behind the bed's pillow, so beds couldn't be fully covered)
        for (Team t : teams) {
            Block g = teamPoint(t, "generator").getBlock();
            if (b.getX() == g.getX() && b.getZ() == g.getZ() && (b.getY() == g.getY() || b.getY() == g.getY() + 1)) return false;
            if (teamPoint(t, "shop").getBlock().getLocation().distanceSquared(b.getLocation()) < 2 || teamPoint(t, "upgrades").getBlock().getLocation().distanceSquared(b.getLocation()) < 2) return false;
        }
        return true;
    }

    @Override protected void onPlace(Player p, BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.TNT) return;
        e.setCancelled(true);
        ItemStack hand = e.getItemInHand();
        hand.setAmount(hand.getAmount() - 1);
        Location l = e.getBlockPlaced().getLocation().add(0.5, 0, 0.5);
        world.spawn(l, TNTPrimed.class, t -> { t.setFuseTicks(52); t.setSource(p); t.setYield(3f); });
    }

    @Override protected boolean explodable(Block b) {
        Material m = b.getType();
        return placed.contains(key(b)) && !m.name().endsWith("STAINED_GLASS") && m != Material.OBSIDIAN && m != Material.END_STONE;
    }

    @Override protected void onInteract(Player p, PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        // right-clicking a bed with a block places the block (no "you can only sleep at night"), no sneaking needed
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getBlockData() instanceof Bed) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            if (it == null || !it.getType().isBlock()) e.setCancelled(true);
        }
        if (it != null && it.getType() == Material.FIRE_CHARGE && (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            e.setCancelled(true);
            it.setAmount(it.getAmount() - 1);
            Fireball f = p.launchProjectile(Fireball.class);
            f.setYield(2.5f);
            f.setIsIncendiary(false);
            f.setVelocity(p.getLocation().getDirection().multiply(1.4));
        }
    }

    @Override protected void onInteractEntity(Player p, PlayerInteractEntityEvent e) {
        UUID id = e.getRightClicked().getUniqueId();
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (shopNpcs.contains(id)) { e.setCancelled(true); BedWarsShop.openShop(this, p); }
        else if (upgradeNpcs.contains(id)) { e.setCancelled(true); BedWarsShop.openUpgrades(this, p); }
    }

    @Override protected void onInventoryClick(Player p, InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof BedWarsShop.Holder h) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getInventory()) return;
            Runnable r = h.actions.get(e.getRawSlot());
            if (r != null) r.run();
            return;
        }
        if (e.getSlotType() == InventoryType.SlotType.ARMOR) e.setCancelled(true);   // permanent armour stays on
    }

    // ------------------------------------------------------------------ death

    @Override protected void onDeath(Player p, Player killer, String how) {
        Team t = team(p);
        boolean finalKill = !bedAlive(t);
        // the killer gets the victim's resources
        if (killer != null) for (Material m : new Material[]{Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD}) {
            int n = BedWarsShop.count(p, m);
            if (n > 0) { BedWarsShop.give(killer, new ItemStack(m, n)); killer.sendMessage(Ui.mm("<gray>+" + n + " " + BedWarsShop.cur(m))); }
        }
        if (killer != null && finalKill) arcade.stats().add(killer.getUniqueId(), game.id, "final_kills", 1);
        broadcast(prefix() + deathMessage(p, killer, how) + (finalKill ? " <aqua><bold>FINAL KILL!" : ""));
        // tools drop a tier on death
        pick.computeIfPresent(p.getUniqueId(), (k, v) -> Math.max(1, v - 1));
        axe.computeIfPresent(p.getUniqueId(), (k, v) -> Math.max(1, v - 1));
        if (finalKill) {
            eliminate(p, null);
            if (t.members.stream().noneMatch(alive::contains)) {
                t.eliminated = true;
                broadcast(prefix() + "<white><bold>TEAM ELIMINATED</bold> " + t.name() + " <gray>team is out!");
            }
            return;
        }
        resetPlayer(p, GameMode.SPECTATOR);
        p.teleport(at(map.spectator));
        int secs = settings.i("respawn-seconds", 5);
        title(p, "<red><bold>YOU DIED", "<gray>Respawning in <white>" + secs + "s");
        respawnAt.put(p.getUniqueId(), ticks + secs * 20);
    }

    @Override protected void onRejoin(Player p) { respawnAt.put(p.getUniqueId(), ticks + 40); resetPlayer(p, GameMode.SPECTATOR); p.teleport(at(map.spectator)); }

    @Override protected void onLeave(Player p) {
        Team t = team(p);
        if (t != null && !bedAlive(t) && t.members.stream().noneMatch(u -> alive.contains(u) && !u.equals(p.getUniqueId()))) t.eliminated = true;
    }

    @Override protected void onTimeUp() {
        Team best = null; int bestN = -1; boolean tie = false;
        for (Team t : teams) {
            int n = (int) t.members.stream().filter(alive::contains).count() + (bedAlive(t) ? 1 : 0);
            if (n > bestN) { best = t; bestN = n; tie = false; } else if (n == bestN) tie = true;
        }
        if (best == null || tie) finish(List.of(), "Time's up - it's a draw!");
        else finish(new ArrayList<>(best.members), "Time's up! " + best.name() + " <yellow>team had the most left.");
    }

    @Override protected List<String> sidebar(Player p) {
        List<String> l = new ArrayList<>();
        l.add("<gray>" + map.display + " • " + clock(elapsed));
        for (Team t : teams) {
            long n = t.members.stream().filter(alive::contains).count();
            String st = bedAlive(t) ? "<green>✔" : n > 0 ? "<yellow>" + n : "<red>✖";
            l.add(t.color.mm + t.color.label.charAt(0) + " <white>" + t.color.label + " " + st + (t == team(p) ? " <gray>(you)" : ""));
        }
        l.add("<gray>Kills: <white>" + kills.getOrDefault(p.getUniqueId(), 0));
        return l;
    }
}
