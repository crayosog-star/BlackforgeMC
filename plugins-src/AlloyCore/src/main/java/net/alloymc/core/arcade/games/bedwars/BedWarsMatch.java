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

/** One BedWars game. Forge Wars runs on it too, with {@code arsenal} on: a third villager selling ability items. */
final class BedWarsMatch extends Match {
    private final Set<UUID> shopNpcs = new HashSet<>(), upgradeNpcs = new HashSet<>(), arsenalNpcs = new HashSet<>();
    private final boolean arsenal;
    // Black Market (Forge Wars only): the villager under the Core, its current deals and seconds to the next restock
    private UUID marketNpc;
    private List<BlackMarket.Deal> market = List.of();
    private int marketTimer;
    private final Random marketRnd = new Random();
    // Launch pad (Forge Wars only): the slime pad on the cave floor throws you up the shaft onto the plaza
    private Location pad, padTop;
    private final Map<UUID, Integer> riding = new HashMap<>(), padCooldown = new HashMap<>(), noFallUntil = new HashMap<>();
    // The Golem (Forge Wars only): the Undercroft's mini boss, when it next wakes (match second, -1 = not scheduled),
    // which teams hold the Golem's Heart until when, and who has a free premium Black Market deal until when
    static final int DIAMOND_II = 360;
    private Golem golem;
    private int golemWakeAt = -1;
    private final Map<Team, Integer> heartUntil = new HashMap<>();
    private final Map<UUID, Integer> voucherUntil = new HashMap<>();
    // easter eggs (Forge Wars only): the Smith's hammers, the pink ore veins, the cracked walls, the jukebox
    private ForgeEggs eggs;
    private final Map<UUID, Integer> armour = new HashMap<>(), pick = new HashMap<>(), axe = new HashMap<>(), respawnAt = new HashMap<>();
    private final Set<UUID> shears = new HashSet<>();
    private final List<TextDisplay> genLabels = new ArrayList<>();
    private int ticks, diamondLevel = 1, emeraldLevel = 1, diamondTimer, emeraldTimer;
    private boolean bedsGone;

    BedWarsMatch(MatchContext c) { this(c, false); }

    BedWarsMatch(MatchContext c, boolean arsenal) { super(c); this.arsenal = arsenal; }

    @Override protected String objective() { return arsenal ? "Protect your bed, arm up at the Arsenal, destroy the others!" : "Protect your bed and destroy the others!"; }
    @Override protected boolean freezeWhileStarting() { return true; }
    @Override protected int startingSeconds() { return 5; }
    @Override protected boolean allowDrop(Player p, ItemStack it) { return true; }
    @Override protected boolean supportsRejoin() { return true; }
    @Override protected boolean allowRejoin(Player p) { Team t = team(p); return t != null && bedAlive(t); }

    /** Forge Wars: everyone named in /arcade test plays together, and so does a party. */
    @Override protected List<List<Player>> splitTeams(List<Player> ps, int teamCount) {
        if (!arsenal) return super.splitTeams(ps, teamCount);
        List<List<Player>> groups = new ArrayList<>();
        if (isTest()) groups.add(ps);
        else {
            Map<String, List<Player>> byParty = new LinkedHashMap<>();
            for (Player p : ps) {
                String leader = arcade.plugin().social().partyOf(p.getUniqueId());
                byParty.computeIfAbsent(leader != null ? leader : p.getUniqueId().toString(), k -> new ArrayList<>()).add(p);
            }
            groups.addAll(byParty.values());
        }
        return TeamBalancer.together(groups, teamCount, variant.teamSize(), isTest() || isForced());
    }

    /** A Forge Wars match started with one team has nobody to beat: it runs until that team is out. */
    @Override protected List<UUID> checkWinners() {
        if (arsenal && teams.size() == 1 && teams.getFirst().members.stream().anyMatch(alive::contains)) return null;
        return super.checkWinners();
    }

    List<Integer> costs(String key, List<Integer> def) {
        List<String> l = settings.list(key, null);
        if (l == null) { Object o = settings.raw(key); return o instanceof Number n ? List.of(n.intValue()) : def; }
        List<Integer> out = new ArrayList<>();
        for (String s : l) out.add(Integer.parseInt(s.trim()));
        return out.isEmpty() ? def : out;
    }

    boolean bedAlive(Team t) { return t.get("bed", false); }

    int setting(String key, int def) { return settings.i(key, def); }

    void teamMessage(Team t, String mm) { for (Player p : online(t.members)) p.sendMessage(Ui.mm(mm)); }

    // ------------------------------------------------------------------ setup

    @Override protected void setup(List<Player> ps) {
        for (Team t : teams) {
            t.data.put("bed", true);
            for (Player p : online(t.members)) { spawn(p); }
            Location shop = teamPoint(t, "shop"), up = teamPoint(t, "upgrades");
            shopNpcs.add(npc(shop, "<green><bold>ITEM SHOP", Villager.Profession.WEAPONSMITH).getUniqueId());
            upgradeNpcs.add(npc(up, "<aqua><bold>TEAM UPGRADES", Villager.Profession.LIBRARIAN).getUniqueId());
            Location ars = arsenal ? teamPoint(t, "arsenal") : null;
            if (ars != null) arsenalNpcs.add(npc(ars, "<#ff6a00><bold>ARSENAL", Villager.Profession.ARMORER).getUniqueId());
        }
        pad = arsenal ? point("launch_pad") : null;
        padTop = pad != null ? point("core") : null;
        if (padTop == null) pad = null;
        if (pad != null && settings.i("golem-enabled", 1) != 0) golemWakeAt = DIAMOND_II;   // first wakes with diamond generators II
        Location bm = arsenal ? point("black_market") : null;
        if (bm != null) { marketNpc = npc(bm, "<dark_purple><bold>BLACK MARKET", Villager.Profession.CLERIC).getUniqueId(); restockMarket(false); }
        if (arsenal && settings.i("easter-eggs", 1) != 0 && !map.list("easter.hammer").isEmpty())
            eggs = new ForgeEggs(this, world, arcade.plugin(), points("easter.hammer"), points("easter.vein"), points("easter.crack"), point("easter.jukebox"));
        // starting emeralds (a Forge Wars testing setting; 0 = none). Only at the start, not on respawn.
        int em = settings.i("starting-emeralds", 0);
        boolean testStacks = arsenal && isTest();   // /arcade test: a full stack of every resource instead
        if (em > 0 && !testStacks) for (Team t : teams) for (Player p : online(t.members)) BedWarsShop.give(p, new ItemStack(Material.EMERALD, em));
        if (testStacks) for (Team t : teams) for (Player p : online(t.members))
            for (Material m : new Material[]{Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD}) BedWarsShop.give(p, new ItemStack(m, 64));
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
        if (heartUntil.getOrDefault(t, -1) > elapsed) p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 120, 1, false, true));   // Golem's Heart
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

    void giveSword(Player p, Material m) { giveSword(p, m, 0); }

    /** A sword with at least {@code sharpness} (the team upgrade still gives Sharpness I to plain swords). */
    void giveSword(Player p, Material m, int sharpness) {
        p.getInventory().remove(Material.WOODEN_SWORD);
        ItemStack s = unbreakable(m);
        int lv = Math.max(sharpness, team(p).getInt("sharpness") > 0 ? 1 : 0);
        if (lv > 0) s.addUnsafeEnchantment(Enchantment.SHARPNESS, lv);
        BedWarsShop.give(p, s);
    }

    private void restockMarket(boolean announce) {
        market = BlackMarket.roll(marketRnd, settings.i("black-market-slots", 5));
        marketTimer = Math.max(30, settings.i("black-market-restock-seconds", 180));
        if (announce) broadcast(prefix() + "<dark_purple>The Black Market under the Core has new stock!");
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
            if (sharp > 0) for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType().name().endsWith("_SWORD") && it.getEnchantmentLevel(Enchantment.SHARPNESS) < 1) it.addUnsafeEnchantment(Enchantment.SHARPNESS, 1);   // never lowers a Black Market Sharpness II
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
        if (pad != null) launchPad();
        if (golem != null) { golem.tick(); if (golem.removed()) golem = null; }
        if (eggs != null) eggs.tick();
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
        if (marketNpc != null && --marketTimer <= 0) restockMarket(true);
        if (--diamondTimer <= 0) { diamondTimer = Math.max(10, settings.i("diamond-interval-seconds", 30) - 7 * (diamondLevel - 1)); for (Location l : points("diamond_generators")) drop(l, Material.DIAMOND, 4); }
        if (--emeraldTimer <= 0) { emeraldTimer = Math.max(15, settings.i("emerald-interval-seconds", 60) - 15 * (emeraldLevel - 1)); for (Location l : points("emerald_generators")) drop(l, Material.EMERALD, 2); }
        int di = 0;
        for (TextDisplay td : genLabels) {
            boolean dia = di++ < map.list("diamond_generators").size();
            td.text(Ui.mm(dia ? "<aqua><bold>Diamond " + roman(diamondLevel) + "</bold><newline><gray>in <white>" + diamondTimer + "s" : "<green><bold>Emerald " + roman(emeraldLevel) + "</bold><newline><gray>in <white>" + emeraldTimer + "s"));
        }
        // timeline
        if (elapsed == DIAMOND_II) { diamondLevel = 2; broadcast(prefix() + "<aqua>Diamond generators are now level II!"); }
        golemSecond();
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
        if (eggs != null && eggs.isCrack(b)) return true;   // a secret room's cracked wall
        return placed.contains(key(b));
    }

    @Override protected void onBreak(Player p, BlockBreakEvent e) {
        Block b = e.getBlock();
        if (eggs != null && eggs.isCrack(b)) { eggs.onBreak(p, e); return; }
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
            Location ars = arsenal ? teamPoint(t, "arsenal") : null;
            if (ars != null && ars.getBlock().getLocation().distanceSquared(b.getLocation()) < 2) return false;
        }
        Location bm = marketNpc != null ? point("black_market") : null;
        if (bm != null && bm.getBlock().getLocation().distanceSquared(b.getLocation()) < 2) return false;
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
        if (eggs != null && eggs.onInteract(p, e)) { e.setCancelled(true); return; }
        ItemStack it = e.getItem();
        // right-clicking a bed with a block places the block (no "you can only sleep at night"), no sneaking needed
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getBlockData() instanceof Bed) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            if (it == null || !it.getType().isBlock()) e.setCancelled(true);
            else if (plainSolid(it.getType()) && e.getHand() != null) { e.setCancelled(true); placeOnBed(p, e); }   // do it ourselves: the vanilla placement on a bed wasn't happening
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

    /** A plain full block (wool, wood, stone, glass...) that we can place by hand without losing behaviour: not TNT, a bed, a container or anything you can use. */
    private static boolean plainSolid(Material m) {
        return m.isBlock() && m.isSolid() && !m.isInteractable() && m != Material.TNT && !Tag.BEDS.isTagged(m);
    }

    /** Right-click on a bed with a block: put the block on the face you clicked, with the same rules as any other placement. */
    private void placeOnBed(Player p, PlayerInteractEvent e) {
        EquipmentSlot hand = e.getHand();
        ItemStack held = p.getInventory().getItem(hand);
        if (held == null || !plainSolid(held.getType())) return;
        Block target = e.getClickedBlock().getRelative(e.getBlockFace());
        if (!target.isReplaceable() || !inBounds(target.getLocation().add(0.5, 0.5, 0.5)) || target.getY() >= oy + map.bounds.y2() || !allowPlace(p, target)) return;
        org.bukkit.util.BoundingBox box = org.bukkit.util.BoundingBox.of(target);
        for (Entity en : world.getNearbyEntities(box))
            if (en instanceof LivingEntity && !(en instanceof Player o && o.getGameMode() == GameMode.SPECTATOR)) return;   // someone is standing there
        target.setType(held.getType());
        placed.add(key(target));
        if (p.getGameMode() != GameMode.CREATIVE) {
            if (held.getAmount() <= 1) p.getInventory().setItem(hand, null);
            else { ItemStack rest = held.clone(); rest.setAmount(held.getAmount() - 1); p.getInventory().setItem(hand, rest); }
        }
        var sg = target.getBlockData().getSoundGroup();
        world.playSound(target.getLocation().add(0.5, 0.5, 0.5), sg.getPlaceSound(), SoundCategory.BLOCKS, (sg.getVolume() + 1f) / 2f, sg.getPitch() * 0.8f);
        p.swingHand(hand);
    }

    @Override protected void onInteractEntity(Player p, PlayerInteractEntityEvent e) {
        UUID id = e.getRightClicked().getUniqueId();
        if (golem != null && golem.owns(e.getRightClicked())) { e.setCancelled(true); return; }   // no iron-ingot "repairs"
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (eggs != null && eggs.onInteractEntity(p, e.getRightClicked())) { e.setCancelled(true); return; }
        if (shopNpcs.contains(id)) { e.setCancelled(true); BedWarsShop.openShop(this, p); }
        else if (upgradeNpcs.contains(id)) { e.setCancelled(true); BedWarsShop.openUpgrades(this, p); }
        else if (id.equals(marketNpc)) { e.setCancelled(true); BlackMarket.open(this, p, market, marketTimer); }
        else if (arsenalNpcs.contains(id)) {
            e.setCancelled(true);
            ForgeArsenal a = ForgeArsenal.get();
            if (a != null) a.open(this, p);
            else p.sendMessage(Ui.mm("<red>The Arsenal isn't open on this server."));
        }
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

    // ------------------------------------------------------------------ launch pad

    /**
     * Step onto the slime pad on the cave floor (not sneaking) and a gust carries you straight up the shaft and out onto
     * the plaza, with no fall damage for a few seconds after. Dropping in from the plaza is safe too: the slime catches
     * you, and the pad waits a moment before throwing you back up.
     */
    private void launchPad() {
        if (ticks % 10 == 0) world.spawnParticle(Particle.END_ROD, pad.clone().add(0, 0.3, 0), 2, 0.9, 0.05, 0.9, 0.01);
        for (Team t : teams) for (Player p : online(t.members)) {
            if (!isAlive(p) || p.getGameMode() == GameMode.SPECTATOR) { riding.remove(p.getUniqueId()); continue; }
            UUID id = p.getUniqueId();
            Location l = p.getLocation();
            double dx = l.getX() - pad.getX(), dz = l.getZ() - pad.getZ();
            boolean inShaft = Math.abs(dx) <= 1.7 && Math.abs(dz) <= 1.7;
            Integer ride = riding.get(id);
            if (ride != null) {
                if (l.getY() >= padTop.getY() + 0.5 || ride > 100) {   // out of the top: a little push onto the plaza
                    riding.remove(id);
                    org.bukkit.util.Vector out = l.getDirection().setY(0);
                    if (out.lengthSquared() < 1e-4) out = new org.bukkit.util.Vector(1, 0, 0);
                    p.setVelocity(out.normalize().multiply(0.65).setY(0.45));
                    p.setFallDistance(0);
                    noFallUntil.put(id, ticks + 80);
                    padCooldown.put(id, ticks + 40);
                    world.spawnParticle(Particle.GUST, l, 2, 0.5, 0.2, 0.5, 0);
                    world.playSound(l, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1f, 1.2f);
                    continue;
                }
                if (!inShaft) { riding.remove(id); noFallUntil.put(id, ticks + 60); continue; }   // knocked out of the column
                riding.put(id, ride + 1);
                p.setVelocity(new org.bukkit.util.Vector(-dx * 0.2, 1.0, -dz * 0.2));   // up, and drift back to the middle
                p.setFallDistance(0);
                if (ride % 2 == 0) world.spawnParticle(Particle.CLOUD, l, 3, 0.25, 0.1, 0.25, 0.02);
                continue;
            }
            if (Math.abs(dx) > 1.4 || Math.abs(dz) > 1.4) continue;
            double up = l.getY() - pad.getY();
            if (up < -0.3 || up > 1.0 || p.isSneaking()) continue;
            if (p.getFallDistance() > 3) { padCooldown.put(id, ticks + 30); continue; }   // just dropped in: land first
            if (padCooldown.getOrDefault(id, 0) > ticks) continue;
            riding.put(id, 0);
            noFallUntil.put(id, ticks + 200);
            world.playSound(l, Sound.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1f, 0.8f);
            world.spawnParticle(Particle.GUST, l, 3, 0.6, 0.1, 0.6, 0);
        }
    }

    @Override protected boolean onDamage(Player victim, Entity attacker, org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && noFallUntil.getOrDefault(victim.getUniqueId(), 0) > ticks) return false;
        return true;
    }

    int armourTier(Player p) { return armour.getOrDefault(p.getUniqueId(), 0); }

    // ------------------------------------------------------------------ the Golem

    /** Players fighting in the Undercroft: alive, in survival, on the hall floor, ramps or ledges (not up on the Core). */
    List<Player> undercroftPlayers(Location home) {
        List<Player> out = new ArrayList<>();
        for (Team t : teams) for (Player p : online(t.members)) {
            if (!isAlive(p) || p.getGameMode() != GameMode.SURVIVAL) continue;
            Location l = p.getLocation();
            if (l.getWorld() != home.getWorld() || l.getY() < home.getY() - 2 || l.getY() > home.getY() + 21) continue;
            if (Math.hypot(l.getX() - home.getX(), l.getZ() - home.getZ()) <= 17 * ForgeWarsMaps.HALL) out.add(p);
        }
        return out;
    }

    private void wakeGolem() {
        golemWakeAt = -1;
        golem = new Golem(this, world, pad.clone(), point("golem_breach"), undercroftPlayers(pad).size());
        broadcast(prefix() + "<#ff3fa4><bold>THE GOLEM IS BREAKING OUT</bold> <gray>of the Undercroft wall. The team that hurts it most wins the <#6cff7a>Golem's Heart<gray>.");
    }

    /** /arcade undercroft and /arcade golem, for staff in a test match. */
    @Override public String testAction(Player p, String action) {
        if (!arsenal || pad == null) return "<red>This map has no Undercroft.";
        switch (action) {
            case "undercroft" -> {
                Location to = null;
                search:
                for (int[] d : new int[][]{{6, 0}, {-6, 0}, {0, 6}, {0, -6}, {4, 4}, {-4, -4}, {4, -4}, {-4, 4}})
                    for (int dy : new int[]{0, 1, 2, -1}) {
                        Location c = pad.clone().add(d[0] + 0.0, dy, d[1] + 0.0);
                        Block feet = c.getBlock();
                        if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(0, 1, 0).isPassable() && !feet.getRelative(0, -1, 0).isPassable()) { to = c; break search; }
                    }
                if (to == null) to = pad.clone().add(6, 1, 0);
                to.add(0.5, 0, 0.5);
                double dx = pad.getX() - to.getX(), dz = pad.getZ() - to.getZ();
                to.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                to.setPitch(10f);
                p.setFallDistance(0);
                p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                p.teleport(to);
                return "<green>You're in the Undercroft.";
            }
            case "golem" -> {
                if (golem != null) return "<red>The Golem is already awake.";
                wakeGolem();
                return "<green>The Golem is waking.";
            }
            default -> { return null; }
        }
    }

    /** Once a second: warn 30 s before the Golem wakes, wake it, keep the Golem's Heart's Haste on its team. */
    private void golemSecond() {
        if (golemWakeAt > 0 && golem == null) {
            if (elapsed == golemWakeAt - 30) {
                broadcast(prefix() + "<#ff3fa4>The walls of the Undercroft shake... <white>the Golem wakes in 30 seconds.");
                world.playSound(pad, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 4f, 0.5f);
            }
            if (golemWakeAt - elapsed > 0 && golemWakeAt - elapsed < GolemFinale.STIR_SECONDS) stir(golemWakeAt - elapsed);
            if (elapsed >= golemWakeAt) wakeGolem();
        }
        for (var e : heartUntil.entrySet()) {
            if (e.getValue() <= elapsed) continue;
            for (Player p : online(e.getKey().members))
                if (isAlive(p) && p.getGameMode() == GameMode.SURVIVAL) p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 40, 0, false, false));
        }
    }

    /** The last 30 seconds before the Golem wakes: the hall rumbles more and more, grit falls from the ceiling, pink light seeps from the wall. */
    private void stir(int left) {
        double s = GolemFinale.stir(left);
        for (int i = 0, n = GolemFinale.dustPerSecond(left); i < n; i++) {
            double a = marketRnd.nextDouble() * Math.PI * 2, r = Math.sqrt(marketRnd.nextDouble()) * 12;
            world.spawnParticle(Particle.FALLING_DUST, pad.getX() + Math.cos(a) * r, pad.getY() + 18, pad.getZ() + Math.sin(a) * r, 1, 0, 0, 0, Bukkit.createBlockData(Material.TUFF));
        }
        Location b = point("golem_breach");
        if (b == null) return;
        if (left % GolemFinale.rumbleEvery(left) == 0) {
            world.playSound(b, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.HOSTILE, 1.5f + (float) s * 2f, 0.5f);
            world.playSound(pad, Sound.AMBIENT_CAVE, SoundCategory.HOSTILE, 1f + (float) s * 2f, 0.6f);
        }
        if (left <= 12)
            world.spawnParticle(Particle.DUST, b.getX(), b.getY() + 1 + marketRnd.nextDouble() * 5, b.getZ(), 3, 1.2, 0.4, 1.2, new Particle.DustOptions(Color.fromRGB(0xFF3FA4), 1.2f));
    }

    /** The Golem fell: reward the team that hurt it most, scatter its loot, and wake the next one later. */
    void golemDefeated(Team best, UUID topPlayer, Location at) {
        int heart = settings.i("golem-heart-seconds", 180);
        golemWakeAt = elapsed + settings.i("golem-respawn-seconds", 240);
        for (int i = 0; i < 12; i++) world.dropItemNaturally(at.clone().add(0, 1, 0), new ItemStack(Material.EMERALD));
        for (int i = 0; i < 6; i++) world.dropItemNaturally(at.clone().add(0, 1, 0), new ItemStack(Material.DIAMOND));
        if (best == null) { broadcast(prefix() + "<#ff3fa4>The Golem crumbles."); return; }
        heartUntil.put(best, elapsed + heart);
        for (Player p : online(best.members))
            if (isAlive(p) && p.getGameMode() == GameMode.SURVIVAL) p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 120, 1, false, true));
        Player top = topPlayer == null ? null : Bukkit.getPlayer(topPlayer);
        if (top != null) {
            voucherUntil.put(topPlayer, elapsed + 60);
            arcade.stats().add(topPlayer, game.id, "golem_kills", 1);
            top.sendMessage(Ui.mm("<#ff3fa4>You hit the Golem hardest! <white>Your next premium Black Market deal is free <gray>(60 s)."));
        }
        broadcast(prefix() + "<#ff3fa4><bold>THE GOLEM FALLS!</bold> " + best.name() + " <gray>team takes the <#6cff7a>Golem's Heart<gray>: +4 hearts on every respawn and Haste for " + heart / 60 + " minutes.");
        title("<#ff3fa4><bold>GOLEM DEFEATED", best.color.mm + best.color.label + " <gray>team wins the Golem's Heart");
    }

    /** A free premium Black Market deal, won by hitting the Golem hardest (good for 60 s). */
    boolean hasGolemVoucher(Player p) { return voucherUntil.getOrDefault(p.getUniqueId(), -1) > elapsed; }

    void spendGolemVoucher(Player p) { voucherUntil.remove(p.getUniqueId()); }

    @Override protected void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent e, Player attacker) {
        if (golem != null && golem.owns(e.getEntity())) golem.onDamaged(e, attacker);
        else e.setCancelled(true);
    }

    @Override protected void onCleanup() {
        if (golem != null) { golem.remove(); golem = null; }
        if (eggs != null) { eggs.remove(); eggs = null; }
    }

    // ------------------------------------------------------------------ easter egg hooks (ForgeEggs)

    List<Player> eggListeners() { return everyone(); }
    String eggPrefix() { return prefix(); }
    String eggName(Player p) { return nameOf(p); }
    void eggStat(UUID u, String stat) { arcade.stats().add(u, game.id, stat, 1); }
    boolean golemAwake() { return golem != null; }
    /** Seconds until the Golem next wakes, or -1 when it isn't scheduled. */
    int golemWakesIn() { return golemWakeAt > 0 ? Math.max(0, golemWakeAt - elapsed) : -1; }

    @Override protected void onDeath(Player p, Player killer, String how) {
        riding.remove(p.getUniqueId());
        endAbilities(p);
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

    /** Arcade deaths aren't real deaths, so stop whatever this player's Forge Wars abilities still have running. */
    private void endAbilities(Player p) {
        ForgeArsenal a = arsenal ? ForgeArsenal.get() : null;
        if (a != null) a.endFor(p);
    }

    @Override protected void onLeave(Player p) {
        endAbilities(p);
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
