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
import org.bukkit.block.BlockFace;
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
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

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
    private static final String MARKER = "arena-built-v1";
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

    private LivingEntity dummy;
    private TextDisplay dummyHolo, wallHolo, spawnHolo;
    private boolean ready;
    private long tick;
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
        dummy = null; dummyHolo = wallHolo = spawnHolo = null;
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
                        + "<newline><gray>Grab new items from the <light_purple>showcase wall <gray>(west)<newline><gray>/arena kit <dark_gray>· <gray>/hub to leave", 1.1f);
        for (TextDisplay td : List.of(dummyHolo, wallHolo, spawnHolo)) mark(td);
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
            var eq = h.getEquipment();
            eq.clear();
            eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            eq.setHelmetDropChance(0f);
        });
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
        for (int i = 0; i < SLOTS; i++) showcase[i] = y.getItemStack("slot." + i);
    }

    private void saveShowcase() {
        YamlConfiguration y = new YamlConfiguration();
        for (int i = 0; i < SLOTS; i++) if (showcase[i] != null) y.set("slot." + i, showcase[i]);
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
        if (p.getWorld() != world) return;
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
        inv.setItem(4, new ItemStack(Material.CROSSBOW));
        ItemStack trident = new ItemStack(Material.TRIDENT);
        trident.addEnchantment(Enchantment.LOYALTY, 3);
        inv.setItem(5, trident);
        inv.setItem(6, new ItemStack(Material.WIND_CHARGE, 64));
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
    public void onQuit(PlayerQuitEvent e) { grabCooldown.remove(e.getPlayer().getUniqueId()); }

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
            frames[slot].setItem(clear ? null : showcase[slot].clone(), false);
            saveShowcase();
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
        if (by != null) by.sendActionBar(Ui.mm("<gold>" + fmt(dmg) + " damage <gray>(" + fmt(dmg / 2) + "❤)  <dark_gray>|  <yellow>DPS " + fmt(dps(now))));
        Location head = dummy.getLocation().add(0, 2.1, 0);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, head, (int) Math.min(12, 1 + dmg / 3), 0.25, 0.2, 0.25, 0.05);
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

    // ------------------------------------------------------------------ animation + upkeep

    private void animate() {
        if (!ready) return;
        tick += 2;
        long now = System.currentTimeMillis();
        if (now - lastHitAt > 4000 && !hits.isEmpty()) hits.clear();
        if (tick % 10 == 0) updateDummyText();
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
