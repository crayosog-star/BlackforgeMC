package net.alloymc.core.hub;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.LobbyRules;
import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.Worlds;
import net.alloymc.core.build.Paster;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Switch;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.time.Duration;
import java.util.*;

/**
 * The Arena: an item testing ground in its own world ("arena") on the hub server. Players get a kit of vanilla
 * weapons, beat on a test dummy that reports every hit's damage and their DPS, and can grab the newest items from a
 * 5x5 showcase wall of glow item frames. Staff fill the wall by sneak-right-clicking a frame with an item
 * (sneak-right-click with an empty hand clears it); the wall is saved in arena-showcase.yml.
 *
 * Everything the plugin spawns here is non-persistent and re-created on start, so nothing duplicates across restarts.
 */
public final class TestArena implements Listener {
    public static final String WORLD = "arena";
    private static final String MARKER = "arena-built-v2";   // v2: armour lever post
    private static final int SLOTS = TestArenaBuilder.GRID * TestArenaBuilder.GRID;
    private static final double DUMMY_HEALTH = 1000;

    private static final String[] IDLE_QUIPS = {
        "Hit me. I dare you.", "I've been hit by worse. Probably.", "Stuffed with straw and spite.",
        "Go on, test that new toy.", "I don't feel pain. I feel numbers.", "Swing harder, I'm getting bored."};
    private static final String[] BIG_HIT_QUIPS = {
        "OKAY. Okay. That one hurt.", "My lawyer will hear about this.", "Who gave you THAT?", "I felt that in my stuffing.",
        "Was that legal??", "Please nerf."};

    private final AlloyCore plugin;
    private final World world;
    private final NamespacedKey tag, leaveKey;
    private final File showcaseFile;
    private final ItemStack[] showcase = new ItemStack[SLOTS];
    private final ItemFrame[] frames = new ItemFrame[SLOTS];
    private final Random rnd = new Random();
    private final Map<UUID, Long> grabCooldown = new HashMap<>();
    private final long[] showcaseTime = new long[SLOTS];                     // when each slot was filled (for the NEW badge)
    private final Map<UUID, TextDisplay> cards = new HashMap<>();             // the item card each player sees, only to them
    private final Map<UUID, Integer> cardSlot = new HashMap<>();

    // dummy armour, switched by the lever: none, leather, iron, diamond, netherite
    private static final String[] ARMOR_NAMES = {"None", "Leather", "Iron", "Diamond", "Netherite"};
    private static final String[] ARMOR_COLORS = {"<gray>", "<#c08a5b>", "<#d9dde3>", "<#4fe3e0>", "<#7d6e72>"};
    private int armorTier;
    private TextDisplay armorHolo, archeryHolo;

    // the dummy's wobble after a hit
    private double wobbleAmp;
    private int wobbleT;

    // archery: longest bullseye this session
    private double bestShot;
    private String bestShooter;

    private LivingEntity dummy;
    private TextDisplay dummyHolo, wallHolo, spawnHolo;
    private boolean ready;
    private long tick;
    private net.alloymc.core.survival.GaiaPotion gaia;                // a Potion of Gaia in every kit, if set
    private final Set<UUID> diedHere = new HashSet<>();                // respawn back in the Arena, not the hub

    /** Give every Arena kit a Potion of Gaia (its splash and platform are handled by {@code g}). */
    public void setGaia(net.alloymc.core.survival.GaiaPotion g) { this.gaia = g; }
    private net.alloymc.core.survival.DarknessPotion darkness;     // a Potion of Darkness in every kit, if set
    public void setDarkness(net.alloymc.core.survival.DarknessPotion d) { this.darkness = d; }
    // the current combo on the dummy: hits within 4 s of each other
    private final Deque<double[]> hits = new ArrayDeque<>();   // {time ms, damage}
    private double lastHit, comboTotal;
    private long comboStart, lastHitAt;
    private String quip = IDLE_QUIPS[0];
    private long quipUntil;

    public TestArena(AlloyCore plugin) {
        this.plugin = plugin;
        this.tag = new NamespacedKey(plugin, "arena_entity");
        this.leaveKey = new NamespacedKey(plugin, "arena_leave");
        this.showcaseFile = new File(plugin.getDataFolder(), "arena-showcase.yml");
        World w = Bukkit.getWorld(WORLD);
        if (w == null) w = new WorldCreator(WORLD).generator(new Worlds.Void()).environment(World.Environment.NORMAL)
                .type(WorldType.FLAT).generateStructures(false).createWorld();
        this.world = w;
        LobbyRules.setupWorld(plugin, world, 1000);                     // a bright, still morning
        world.setDifficulty(Difficulty.NORMAL);                         // the dummy is a husk: peaceful would delete it
        world.setSpawnFlags(false, false);
        world.setSpawnLocation(spawn());
        for (int cx = -3; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        loadShowcase();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (!new File(plugin.getDataFolder(), MARKER).exists()) build(Bukkit.getConsoleSender());
        else Bukkit.getScheduler().runTaskLater(plugin, this::spawnEntities, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::animate, 60L, 2L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanup, 200L, 200L);
    }

    public World world() { return world; }
    public boolean inArena(Player p) { return p.getWorld() == world; }

    public Location spawn() {
        return new Location(world, TestArenaBuilder.SPAWN_X, TestArenaBuilder.SPAWN_Y, TestArenaBuilder.SPAWN_Z, TestArenaBuilder.SPAWN_YAW, 0f);
    }

    public void shutdown() { removeEntities(); }

    // ------------------------------------------------------------------ build

    public void build(CommandSender who) {
        who.sendMessage(Component.text("Building the Arena..."));
        long t = System.currentTimeMillis();
        ready = false;
        removeEntities();
        for (Player p : world.getPlayers()) p.teleport(spawn().add(0, 30, 0));   // out of the way while it pastes
        // clear the old island first so nothing from a previous design is left floating
        var clear = new net.alloymc.core.build.Canvas();
        for (int x = -40; x < 40; x++) for (int z = -40; z < 40; z++) for (int y = TestArenaBuilder.Y - 40; y <= TestArenaBuilder.Y + 30; y++)
            if (!world.getBlockAt(x, y, z).getType().isAir()) clear.set(x, y, z, "minecraft:air");
        var canvas = TestArenaBuilder.build();
        Paster.paste(plugin, world, clear, 6000, () -> Paster.paste(plugin, world, canvas, 6000, () -> {
            paintBiome();
            try { new File(plugin.getDataFolder(), MARKER).createNewFile(); } catch (Exception ignored) { }
            who.sendMessage(Component.text("Arena built: " + canvas.size() + " blocks in " + (System.currentTimeMillis() - t) / 1000 + "s"));
            spawnEntities();
            for (Player p : world.getPlayers()) p.teleport(spawn());
        }));
    }

    private void paintBiome() {
        org.bukkit.block.Biome forest = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME).get(NamespacedKey.minecraft("forest"));
        if (forest == null) return;
        for (int x = -40; x < 40; x += 4) for (int z = -40; z < 40; z += 4)
            for (int y = TestArenaBuilder.Y - 48; y < TestArenaBuilder.Y + 40; y += 4) world.setBiome(x, y, z, forest);
        for (int cx = -3; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) world.refreshChunk(cx, cz);
    }

    // ------------------------------------------------------------------ entities

    private boolean ours(Entity e) { return e.getPersistentDataContainer().has(tag, PersistentDataType.BYTE); }
    private void mark(Entity e) { e.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1); e.setPersistent(false); }

    private void removeEntities() {
        for (Entity e : world.getEntities()) if (ours(e)) e.remove();
        dummy = null; dummyHolo = wallHolo = spawnHolo = armorHolo = archeryHolo = null;
        cards.clear(); cardSlot.clear();
        Arrays.fill(frames, null);
    }

    private void spawnEntities() {
        removeEntities();
        spawnDummy();
        for (int i = 0; i < SLOTS; i++) spawnFrame(i);
        dummyHolo = Ui.hologram(new Location(world, TestArenaBuilder.DUMMY_X, TestArenaBuilder.DUMMY_Y + 2.7, TestArenaBuilder.DUMMY_Z), "", 1.0f);
        wallHolo = Ui.hologram(new Location(world, TestArenaBuilder.WALL_X + 1.6, TestArenaBuilder.Y + 10.2, 0.5), "", 1.4f);
        spawnHolo = Ui.hologram(new Location(world, TestArenaBuilder.SPAWN_X, TestArenaBuilder.Y + 3.4, TestArenaBuilder.SPAWN_Z - 3),
                "<gradient:#7bd88f:#2e9e5b><bold>THE ARENA</bold></gradient><newline><gray>Test your gear on the <white>dummy"
                        + "<newline><gray>Grab new items from the <light_purple>showcase wall <gray>(west)<newline><gray>Flip the <white>lever <gray>to armour the dummy<newline><gray>/arena kit <dark_gray>· <gray>/hub to leave", 1.1f);
        armorHolo = Ui.hologram(new Location(world, TestArenaBuilder.LEVER_X + 0.5, TestArenaBuilder.LEVER_Y + 0.9, TestArenaBuilder.LEVER_Z + 0.5), "", 0.8f);
        archeryHolo = Ui.hologram(new Location(world, 0.5, TestArenaBuilder.Y + 4.4, TestArenaBuilder.TARGET_Z + 0.5), "", 1.1f);
        for (TextDisplay td : List.of(dummyHolo, wallHolo, spawnHolo, armorHolo, archeryHolo)) mark(td);
        updateArmorText();
        updateArcheryText();
        ready = true;
    }

    private void spawnDummy() {
        Location at = new Location(world, TestArenaBuilder.DUMMY_X, TestArenaBuilder.DUMMY_Y, TestArenaBuilder.DUMMY_Z, 0f, 0f);   // facing south, at spawn
        dummy = world.spawn(at, Husk.class, false, h -> {
            mark(h);
            if (h instanceof Ageable a) a.setAdult();
            h.setAI(false);
            h.setRemoveWhenFarAway(false);
            h.setCanPickupItems(false);
            h.customName(Ui.mm("<gold><bold>Test Dummy"));
            h.setCustomNameVisible(true);
            setBase(h, Attribute.MAX_HEALTH, DUMMY_HEALTH);
            setBase(h, Attribute.ARMOR, 0);                             // raw damage: no natural armour
            setBase(h, Attribute.KNOCKBACK_RESISTANCE, 1);
            h.setHealth(DUMMY_HEALTH);
        });
        applyArmor();
    }

    private void applyArmor() {
        if (dummy == null) return;
        var eq = dummy.getEquipment();
        if (eq == null) return;
        eq.clear();
        String m = switch (armorTier) { case 1 -> "LEATHER"; case 2 -> "IRON"; case 3 -> "DIAMOND"; case 4 -> "NETHERITE"; default -> null; };
        if (m == null) eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
        else {
            eq.setHelmet(new ItemStack(Material.valueOf(m + "_HELMET")));
            eq.setChestplate(new ItemStack(Material.valueOf(m + "_CHESTPLATE")));
            eq.setLeggings(new ItemStack(Material.valueOf(m + "_LEGGINGS")));
            eq.setBoots(new ItemStack(Material.valueOf(m + "_BOOTS")));
        }
        eq.setHelmetDropChance(0f); eq.setChestplateDropChance(0f); eq.setLeggingsDropChance(0f); eq.setBootsDropChance(0f);
    }

    private void updateArmorText() {
        if (armorHolo != null && armorHolo.isValid())
            armorHolo.text(Ui.mm("<gray>Dummy armour: " + ARMOR_COLORS[armorTier] + "<bold>" + ARMOR_NAMES[armorTier] + "</bold><newline><dark_gray>right-click the lever"));
    }

    private void updateArcheryText() {
        if (archeryHolo != null && archeryHolo.isValid())
            archeryHolo.text(Ui.mm("<gold><bold>◎ ARCHERY ◎</bold><newline><gray>Longest bullseye: "
                    + (bestShooter == null ? "<dark_gray>nobody yet" : "<white>" + bestShooter + " <yellow>" + fmt(bestShot) + "m")));
    }

    private static void setBase(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    /** Slot 0 is top-left as you face the wall (west); row by row. */
    private Location frameLoc(int slot) {
        int row = slot / TestArenaBuilder.GRID, col = slot % TestArenaBuilder.GRID;
        int y = TestArenaBuilder.GRID_Y0 + (TestArenaBuilder.GRID - 1 - row);
        int z = TestArenaBuilder.GRID_Z0 + (TestArenaBuilder.GRID - 1 - col);   // facing west, +z is on your left
        return new Location(world, TestArenaBuilder.WALL_X + 1, y, z);
    }

    private void spawnFrame(int slot) {
        try {
            frames[slot] = world.spawn(frameLoc(slot), GlowItemFrame.class, f -> {
                f.setFacingDirection(BlockFace.EAST, true);
                mark(f);
                f.setFixed(true);
                f.setItem(showcase[slot] == null ? null : showcase[slot].clone(), false);
            });
        } catch (IllegalArgumentException ex) {   // no wall to hang on (e.g. the build was edited by hand)
            plugin.getLogger().warning("Arena showcase frame " + slot + " could not be placed: " + ex.getMessage());
        }
    }

    private int slotOf(Entity e) {
        for (int i = 0; i < SLOTS; i++) if (frames[i] != null && frames[i].equals(e)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ showcase storage

    private void loadShowcase() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(showcaseFile);
        for (int i = 0; i < SLOTS; i++) { showcase[i] = y.getItemStack("slot." + i); showcaseTime[i] = y.getLong("time." + i, 0L); }
    }

    private void saveShowcase() {
        YamlConfiguration y = new YamlConfiguration();
        for (int i = 0; i < SLOTS; i++) if (showcase[i] != null) { y.set("slot." + i, showcase[i]); y.set("time." + i, showcaseTime[i]); }
        try { y.save(showcaseFile); } catch (Exception ex) { plugin.getLogger().warning("Could not save arena-showcase.yml: " + ex.getMessage()); }
    }

    private int filled() { int n = 0; for (ItemStack s : showcase) if (s != null) n++; return n; }

    // ------------------------------------------------------------------ players

    /** Send a hub player into the Arena. */
    public void enter(Player p) {
        if (!ready) { p.sendActionBar(Ui.mm("<gray>The Arena is still being built - try again in a moment.")); return; }
        p.teleport(spawn());
        p.setFallDistance(0);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld() != world) { removeCard(p); return; }
        if (!p.hasPermission("alloy.admin") || p.getGameMode() != GameMode.CREATIVE) p.setGameMode(GameMode.ADVENTURE);
        p.setFlying(false);
        p.setAllowFlight(p.getGameMode() == GameMode.CREATIVE);
        kit(p);
        p.showTitle(Title.title(Ui.mm("<gradient:#7bd88f:#2e9e5b><bold>THE ARENA</bold></gradient>"), Ui.mm("<gray>Break things. Responsibly."),
                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(600))));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
    }

    public void kit(Player p) {
        var inv = p.getInventory();
        inv.clear();
        inv.setItem(0, new ItemStack(Material.NETHERITE_SWORD));
        inv.setItem(1, new ItemStack(Material.NETHERITE_AXE));
        inv.setItem(2, new ItemStack(Material.MACE));
        ItemStack bow = new ItemStack(Material.BOW);
        bow.addEnchantment(Enchantment.INFINITY, 1);
        inv.setItem(3, bow);
        inv.setItem(13, new ItemStack(Material.CROSSBOW));   // hotbar slot 5 holds the Potion of Darkness
        if (darkness != null) inv.setItem(4, darkness.item());
        ItemStack trident = new ItemStack(Material.TRIDENT);
        trident.addEnchantment(Enchantment.LOYALTY, 3);
        inv.setItem(5, trident);
        inv.setItem(6, new ItemStack(Material.WIND_CHARGE, 64));
        if (gaia != null) inv.setItem(7, gaia.item());
        ItemStack leave = Ui.item(Material.RED_BED, "<red><bold>Back to the Hub</bold> <gray>(right-click)");
        leave.editMeta(m -> m.getPersistentDataContainer().set(leaveKey, PersistentDataType.BYTE, (byte) 1));
        inv.setItem(8, leave);
        inv.setItem(17, new ItemStack(Material.ARROW, 64));
        inv.setHeldItemSlot(0);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta() || !it.getItemMeta().getPersistentDataContainer().has(leaveKey, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        p.performCommand("hub");
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo().getWorld() != world || e.getTo().getY() >= TestArenaBuilder.VOID_Y) return;
        Player p = e.getPlayer();
        p.teleport(spawn());
        p.setFallDistance(0);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { grabCooldown.remove(e.getPlayer().getUniqueId()); removeCard(e.getPlayer()); diedHere.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ PvP

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /**
     * PvP is on in the Arena. The hub's lobby rules cancel every hit on a player (NORMAL priority); this runs after
     * them and lets player-on-player hits through, but only when both players are in the Arena. Falls, the void
     * and everything else stay harmless.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim) || victim.getWorld() != world) return;
        Player by = attacker(e.getDamager());
        if (by == null || by == victim || by.getWorld() != world) return;
        if (by.getGameMode() == GameMode.CREATIVE || by.getGameMode() == GameMode.SPECTATOR) return;
        e.setCancelled(false);
    }

    /** Dying in the Arena costs nothing: keep the kit, drop nothing, and come straight back. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onArenaDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld() != world) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        diedHere.add(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onArenaRespawn(PlayerRespawnEvent e) {
        if (diedHere.remove(e.getPlayer().getUniqueId())) e.setRespawnLocation(spawn());
    }

    // ------------------------------------------------------------------ showcase wall

    @EventHandler(priority = EventPriority.HIGH)
    public void onFrameClick(PlayerInteractEntityEvent e) {
        if (!ours(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        int slot = slotOf(e.getRightClicked());
        if (slot < 0) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (p.isSneaking() && p.hasPermission("alloy.admin")) {        // staff: put the held item on show, or clear the frame
            boolean clear = hand.getType().isAir();
            showcase[slot] = clear ? null : hand.asOne();
            showcaseTime[slot] = clear ? 0 : System.currentTimeMillis();
            frames[slot].setItem(clear ? null : showcase[slot].clone(), false);
            saveShowcase();
            cardSlot.clear();   // cards re-read the slot
            if (!clear) newArrival(slot);
            p.sendActionBar(Ui.mm(clear ? "<gray>Showcase slot " + (slot + 1) + " cleared." : "<green>Showcase slot " + (slot + 1) + " set."));
            p.playSound(p.getLocation(), clear ? Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM : Sound.ENTITY_ITEM_FRAME_ADD_ITEM, 1f, 1f);
            return;
        }
        ItemStack item = showcase[slot];
        if (item == null) { p.sendActionBar(Ui.mm("<gray>Nothing here yet... <i>soon™")); return; }
        long now = System.currentTimeMillis();
        if (grabCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        grabCooldown.put(p.getUniqueId(), now + 750);
        ItemStack give = item.clone();
        give.setAmount(Math.min(16, give.getMaxStackSize()));
        p.getInventory().addItem(give).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
        Location fl = frames[slot].getLocation();
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, fl, 25, 0.3, 0.3, 0.3, 0.25);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.8f + rnd.nextFloat() * 0.6f);
        p.sendActionBar(Ui.mm("<green>+ <white>").append(give.effectiveName()));
        spinBurst(slot);
    }

    /** A quick little spin when someone takes an item. */
    private void spinBurst(int slot) {
        ItemFrame f = frames[slot];
        for (int i = 1; i <= 8; i++)
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (f.isValid()) f.setRotation(f.getRotation().rotateClockwise()); }, i);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameBreak(HangingBreakEvent e) { if (ours(e.getEntity())) e.setCancelled(true); }

    // ------------------------------------------------------------------ the dummy

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFrameHit(EntityDamageByEntityEvent e) { if (e.getEntity() instanceof ItemFrame && ours(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDummyHit(EntityDamageEvent e) {
        if (dummy == null || !e.getEntity().equals(dummy)) return;
        double dmg = e.getFinalDamage();
        long now = System.currentTimeMillis();
        if (now - lastHitAt > 4000) { hits.clear(); comboTotal = 0; comboStart = now; }
        hits.add(new double[]{now, dmg});
        lastHit = dmg;
        lastHitAt = now;
        comboTotal += dmg;
        if (dmg >= 20 && now > quipUntil) { quip = BIG_HIT_QUIPS[rnd.nextInt(BIG_HIT_QUIPS.length)]; quipUntil = now + 2500; }
        Player by = null;
        if (e instanceof EntityDamageByEntityEvent ee) {
            if (ee.getDamager() instanceof Player p) by = p;
            else if (ee.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) by = p;
        }
        // what kind of hit was it?
        String hitTag = null;
        if (e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) hitTag = "<aqua>SWEEP";
        else if (e instanceof EntityDamageByEntityEvent ee) {
            if (ee.getDamager() instanceof Player p && p.getInventory().getItemInMainHand().getType() == Material.MACE && p.getFallDistance() > 1.5f) hitTag = "<light_purple>SMASH!";
            else if (ee.getDamager() instanceof Projectile pr && pr.getLocation().getY() >= dummy.getLocation().getY() + 1.45) hitTag = "<red>HEADSHOT";
            else if (ee.isCritical()) hitTag = "<gold>CRIT";
        }
        if (by != null) by.sendActionBar(Ui.mm("<gold>" + fmt(dmg) + " damage <gray>(" + fmt(dmg / 2) + "❤)  <dark_gray>|  <yellow>DPS " + fmt(dps(now))
                + (armorTier > 0 ? "  <dark_gray>|  " + ARMOR_COLORS[armorTier] + ARMOR_NAMES[armorTier] + " armour" : "")));
        Location head = dummy.getLocation().add(0, 2.1, 0);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, head, (int) Math.min(12, 1 + dmg / 3), 0.25, 0.2, 0.25, 0.05);
        // floating number, colour by size; a tag under it for crits, sweeps, smashes and headshots
        String col = dmg >= 20 ? "<red><bold>" : hitTag != null && hitTag.contains("CRIT") ? "<gold>" : "<white>";
        float size = (float) Math.min(2.2, 0.9 + dmg / 25);
        Location pop = dummy.getLocation().add((rnd.nextDouble() - 0.5) * 1.1, 1.3 + rnd.nextDouble() * 0.5, (rnd.nextDouble() - 0.5) * 1.1);
        popup(pop, col + fmt(dmg) + (hitTag != null ? "<newline><reset><bold>" + hitTag : ""), size, 22);
        // the dummy rocks on its post and sheds some straw
        wobbleAmp = Math.min(38, Math.max(wobbleAmp, 7 + dmg * 1.3));
        wobbleT = 0;
        world.spawnParticle(Particle.BLOCK, dummy.getLocation().add(0, 1.1, 0), (int) Math.min(30, 4 + dmg), 0.25, 0.4, 0.25, 0.1, Material.HAY_BLOCK.createBlockData());
        // full health again next tick, so it never dies (unless something does over 1000 in one go - see onDummyDeath)
        Bukkit.getScheduler().runTask(plugin, () -> { if (dummy != null && dummy.isValid()) dummy.setHealth(DUMMY_HEALTH); });
        updateDummyText();
    }

    @EventHandler
    public void onDummyDeath(EntityDeathEvent e) {
        if (dummy == null || !e.getEntity().equals(dummy)) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        dummy = null;
        Location at = e.getEntity().getLocation();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        for (Player p : world.getPlayers())
            p.sendMessage(Ui.mm("<gold>✦ <gray>Someone actually <red><bold>killed</bold></red> the Test Dummy. It'll be back. It's always back."));
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (ready) { spawnDummy(); quip = "...I'm back. Did you miss me?"; quipUntil = System.currentTimeMillis() + 4000; } }, 60L);
    }

    private double dps(long now) {
        if (hits.isEmpty()) return 0;
        double secs = Math.max(1.0, (now - comboStart) / 1000.0);
        return comboTotal / secs;
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.1f", d); }

    private void updateDummyText() {
        if (dummyHolo == null || !dummyHolo.isValid()) return;
        long now = System.currentTimeMillis();
        boolean active = now - lastHitAt <= 4000 && !hits.isEmpty();
        if (!active && now > quipUntil && tick % 300 == 0) { quip = IDLE_QUIPS[rnd.nextInt(IDLE_QUIPS.length)]; quipUntil = now + 6000; }
        String stats = active
                ? "<gray>Last hit <gold>" + fmt(lastHit) + " <gray>(" + fmt(lastHit / 2) + "❤)<newline><gray>DPS <yellow>" + fmt(dps(now))
                    + "  <dark_gray>|  <gray>Combo <white>" + fmt(comboTotal)
                : "<dark_gray>Hit me to see your damage";
        dummyHolo.text(Ui.mm("<italic><gray>\"" + quip + "\"</italic><newline>" + stats));
    }

    // ------------------------------------------------------------------ armour lever + archery

    @EventHandler(priority = EventPriority.HIGH)
    public void onLever(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getWorld() != world || b.getX() != TestArenaBuilder.LEVER_X || b.getY() != TestArenaBuilder.LEVER_Y || b.getZ() != TestArenaBuilder.LEVER_Z) return;
        e.setCancelled(true);
        armorTier = (armorTier + 1) % ARMOR_NAMES.length;
        if (b.getBlockData() instanceof Switch sw) { sw.setPowered(!sw.isPowered()); b.setBlockData(sw, false); }
        Player p = e.getPlayer();
        p.playSound(b.getLocation(), Sound.BLOCK_LEVER_CLICK, 0.8f, leverPitch(armorTier));
        applyArmor();
        updateArmorText();
        if (dummy != null) {
            Location c = dummy.getLocation().add(0, 1, 0);
            world.spawnParticle(Particle.ENCHANTED_HIT, c, 30, 0.35, 0.6, 0.35, 0.2);
            Sound equip = switch (armorTier) {
                case 1 -> Sound.ITEM_ARMOR_EQUIP_LEATHER; case 2 -> Sound.ITEM_ARMOR_EQUIP_IRON;
                case 3 -> Sound.ITEM_ARMOR_EQUIP_DIAMOND; case 4 -> Sound.ITEM_ARMOR_EQUIP_NETHERITE; default -> Sound.ITEM_ARMOR_EQUIP_GENERIC; };
            world.playSound(c, equip, 1f, 1f);
            popup(c.clone().add(0, 1.2, 0), ARMOR_COLORS[armorTier] + "<bold>" + ARMOR_NAMES[armorTier] + (armorTier == 0 ? "" : " armour"), 1.2f, 26);
        }
        hits.clear(); comboTotal = 0; lastHitAt = 0;   // fresh numbers for the new armour
    }

    private static float leverPitch(int tier) { return 0.8f + tier * 0.12f; }

    /** Arrows into the targets: a ring for every hit, a bell and a record for bullseyes. */
    @EventHandler
    public void onTarget(io.papermc.paper.event.block.TargetHitEvent e) {
        if (e.getEntity().getWorld() != world || !(e.getEntity().getShooter() instanceof Player p) || e.getHitBlock() == null) return;
        int signal = e.getSignalStrength();
        Location c = e.getHitBlock().getLocation().add(0.5, 0.5, 0.5);
        double dist = p.getLocation().distance(c);
        boolean bull = signal >= 13;
        world.spawnParticle(bull ? Particle.TOTEM_OF_UNDYING : Particle.CRIT, c.clone().add(0, 0, 0.55), bull ? 30 : 8, 0.25, 0.25, 0.05, bull ? 0.3 : 0.1);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, bull ? 1f : 0.5f, 0.5f + signal / 15f * 1.5f);
        popup(c.clone().add(0, 1.0, 1.0), (bull ? "<gold><bold>BULLSEYE!" : "<white>" + signal + "<gray>/15") + "<newline><reset><gray>" + fmt(dist) + "m", bull ? 1.4f : 1.0f, 28);
        if (bull && dist > bestShot) {
            boolean beatSomeone = bestShooter != null;
            bestShot = dist;
            bestShooter = p.getName();
            updateArcheryText();
            for (Player o : world.getPlayers()) {
                o.sendMessage(Ui.mm("<gold>◎ <white>" + p.getName() + " <gray>hit the longest bullseye" + (beatSomeone ? " yet" : "") + ": <yellow>" + fmt(dist) + "m"));
                o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.4f, 1.4f);
            }
            world.spawnParticle(Particle.FIREWORK, c, 40, 0.4, 0.6, 0.4, 0.15);
        }
    }

    // ------------------------------------------------------------------ pop-ups, cards, new arrivals

    /** A bit of floating text that pops in, drifts up and fades: damage numbers, armour changes, archery scores. */
    private void popup(Location at, String text, float scale, int life) {
        TextDisplay td = world.spawn(at, TextDisplay.class, d -> {
            mark(d);
            d.text(Ui.mm(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale * 0.3f), new AxisAngle4f()));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> grow(td, 0.25f, scale * 1.2f, 3), 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> grow(td, 1.0f, scale * 0.85f, life - 5), 5L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (td.isValid()) td.setTextOpacity((byte) 140); }, life - 7L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (td.isValid()) td.setTextOpacity((byte) 70); }, life - 3L);
        Bukkit.getScheduler().runTaskLater(plugin, td::remove, life);
    }

    private static void grow(TextDisplay td, float up, float scale, int ticks) {
        if (!td.isValid()) return;
        td.setInterpolationDelay(0);
        td.setInterpolationDuration(Math.max(1, ticks));
        td.setTransformation(new Transformation(new Vector3f(0, up, 0), new AxisAngle4f(), new Vector3f(scale), new AxisAngle4f()));
    }

    /** Staff just put something on the wall: a beam of light, a flash and a chime for everyone in the Arena. */
    private void newArrival(int slot) {
        Location f = frameLoc(slot).add(0.3, 0.5, 0.5);
        for (int i = 0; i < 12; i++) {
            int step = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                world.spawnParticle(Particle.END_ROD, f.clone().add(0.2, 6 - step * 0.5, 0), 4, 0.05, 0.1, 0.05, 0);
                if (step == 11) {
                    world.spawnParticle(Particle.FIREWORK, f, 30, 0.2, 0.3, 0.3, 0.12);
                    spinBurst(slot);
                }
            }, i);
        }
        Component msg = Ui.mm("<light_purple>✦ New on the wall: <white>").append(showcase[slot].effectiveName());
        for (Player p : world.getPlayers()) {
            p.sendActionBar(msg);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 1.3f);
        }
    }

    /** Looking at a frame shows a little card (to you only) with the item's name, its first lore lines and a NEW badge. */
    private void updateCards() {
        for (Player p : world.getPlayers()) {
            int slot = -1;
            if (p.getLocation().getX() < TestArenaBuilder.WALL_X + 10 && Math.abs(p.getLocation().getZ()) < 10) {
                RayTraceResult r = world.rayTraceEntities(p.getEyeLocation(), p.getEyeLocation().getDirection(), 7, 0.15, en -> en instanceof ItemFrame && ours(en));
                if (r != null && r.getHitEntity() != null) slot = slotOf(r.getHitEntity());
            }
            if (slot < 0 || showcase[slot] == null) { removeCard(p); continue; }
            if (Objects.equals(cardSlot.get(p.getUniqueId()), slot) && cards.get(p.getUniqueId()) != null && cards.get(p.getUniqueId()).isValid()) continue;
            removeCard(p);
            int s = slot;
            Location at = frameLoc(s).add(0.75, 1.05, 0.5);
            TextDisplay card = world.spawn(at, TextDisplay.class, d -> {
                mark(d);
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setLineWidth(200);
                d.setBackgroundColor(Color.fromARGB(170, 18, 12, 30));
                d.text(cardText(s));
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.2f), new AxisAngle4f()));
            });
            p.showEntity(plugin, card);
            Bukkit.getScheduler().runTaskLater(plugin, () -> grow(card, 0.1f, 0.6f, 3), 1L);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_STEP, 0.5f, 1.6f);
            cards.put(p.getUniqueId(), card);
            cardSlot.put(p.getUniqueId(), s);
        }
    }

    private Component cardText(int slot) {
        ItemStack it = showcase[slot];
        boolean fresh = showcaseTime[slot] > 0 && System.currentTimeMillis() - showcaseTime[slot] < 7L * 24 * 3600 * 1000;
        Component out = Component.empty();
        if (fresh) out = out.append(Ui.mm("<gradient:#ffd27a:#ff7a1a><bold>★ NEW ★</bold></gradient>")).append(Component.newline());
        out = out.append(it.effectiveName());
        List<Component> lore = it.hasItemMeta() && it.getItemMeta().lore() != null ? it.getItemMeta().lore() : List.of();
        for (int i = 0; i < Math.min(3, lore.size()); i++) out = out.append(Component.newline()).append(lore.get(i));
        return out.append(Component.newline()).append(Ui.mm("<dark_gray>right-click to grab"));
    }

    private void removeCard(Player p) {
        TextDisplay c = cards.remove(p.getUniqueId());
        cardSlot.remove(p.getUniqueId());
        if (c != null) c.remove();
    }

    // ------------------------------------------------------------------ animation + upkeep

    private void animate() {
        if (!ready) return;
        tick += 2;
        long now = System.currentTimeMillis();
        if (now - lastHitAt > 4000 && !hits.isEmpty()) hits.clear();
        if (tick % 10 == 0) updateDummyText();
        if (tick % 4 == 0) updateCards();
        // the dummy's wobble: a quick shake and nod that settles
        if (dummy != null && dummy.isValid() && wobbleAmp > 0.6) {
            wobbleT++;
            dummy.setRotation((float) (Math.sin(wobbleT * 1.9) * wobbleAmp), (float) (Math.abs(Math.cos(wobbleT * 1.9)) * wobbleAmp * 0.7));
            wobbleAmp *= 0.72;
            if (wobbleAmp <= 0.6) dummy.setRotation(0f, 0f);
        }
        // keep the dummy exactly on its spot (wind charges can still shove it)
        if (dummy != null && dummy.isValid() && tick % 20 == 0) {
            Location home = new Location(world, TestArenaBuilder.DUMMY_X, TestArenaBuilder.DUMMY_Y, TestArenaBuilder.DUMMY_Z, 0f, 0f);
            if (dummy.getLocation().distanceSquared(home) > 0.25) dummy.teleport(home);
        } else if ((dummy == null || !dummy.isValid()) && tick % 100 == 0 && world.isChunkLoaded(0, 0)) spawnDummy();
        // showcase title: a shimmering gradient that slides back and forth
        double phase = Math.sin(tick / 20.0);
        if (wallHolo != null && wallHolo.isValid() && tick % 4 == 0)
            wallHolo.text(Ui.mm("<gradient:#c084fc:#60e0ff:#c084fc:" + String.format(Locale.ROOT, "%.2f", phase) + "><bold>✦ NEW ITEMS ✦</bold></gradient>"
                    + "<newline><gray>Right-click a frame to grab one<newline><dark_gray>" + filled() + "/" + SLOTS + " on show"));
        // filled frames slowly turn their items, each a beat behind the last: a ripple across the wall
        if (tick % 10 == 0)
            for (int i = 0; i < SLOTS; i++)
                if (frames[i] != null && frames[i].isValid() && showcase[i] != null && ((tick / 10) + i) % 4 == 0)
                    frames[i].setRotation(frames[i].getRotation().rotateClockwise());
        double fx = TestArenaBuilder.WALL_X + 1.15;
        double z0 = TestArenaBuilder.GRID_Z0 - 0.15, z1 = TestArenaBuilder.GRID_Z0 + TestArenaBuilder.GRID + 0.15;
        double y0 = TestArenaBuilder.GRID_Y0 - 0.15, y1 = TestArenaBuilder.GRID_Y0 + TestArenaBuilder.GRID + 0.15;
        // two coloured comets chasing each other round the grid
        double w = z1 - z0, hgt = y1 - y0, per = 2 * (w + hgt);
        for (int k = 0; k < 2; k++) {
            double s = ((tick * 0.35) + k * per / 2) % per;
            double[] pt = s < w ? new double[]{z0 + s, y0} : s < w + hgt ? new double[]{z1, y0 + (s - w)} : s < 2 * w + hgt ? new double[]{z1 - (s - w - hgt), y1} : new double[]{z0, y1 - (s - 2 * w - hgt)};
            Color col = Color.fromRGB(java.awt.Color.HSBtoRGB((float) ((tick / 120.0 + k * 0.5) % 1.0), 0.6f, 1f) & 0xFFFFFF);
            world.spawnParticle(Particle.DUST, fx, pt[1], pt[0], 2, 0.03, 0.03, 0.03, 0, new Particle.DustOptions(col, 1.3f));
        }
        // sparkles on filled frames, enchanting glyphs drifting toward the wall
        if (tick % 6 == 0)
            for (int i = 0; i < SLOTS; i++)
                if (showcase[i] != null && rnd.nextInt(5) == 0) {
                    Location l = frameLoc(i).add(0.25, 0.5, 0.5);
                    world.spawnParticle(rnd.nextBoolean() ? Particle.END_ROD : Particle.WAX_ON, l, 1, 0.2, 0.2, 0.2, 0.01);
                }
        if (tick % 4 == 0) world.spawnParticle(Particle.ENCHANT, fx + 2.5, TestArenaBuilder.GRID_Y0 + 2.5, TestArenaBuilder.GRID_Z0 + 2.5, 6, 1.2, 1.5, 1.8, 0.6);
        // fireflies around the glade
        if (tick % 8 == 0) world.spawnParticle(Particle.GLOW, rnd.nextDouble() * 36 - 18, TestArenaBuilder.Y + 1.5 + rnd.nextDouble() * 3, rnd.nextDouble() * 36 - 18, 1, 0, 0, 0, 0);
    }

    /** Arrows, thrown tridents and dropped items don't pile up. */
    private void cleanup() {
        for (Entity e : world.getEntities()) {
            if (e instanceof Item it && it.getTicksLived() > 1200) it.remove();
            else if (e instanceof AbstractArrow a && a.getTicksLived() > 400) a.remove();   // loyalty tridents are long home by then
        }
    }
}
