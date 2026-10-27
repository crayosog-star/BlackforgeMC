package net.alloymc.core.survival;

import io.papermc.paper.world.WeatheringCopperState;
import net.alloymc.core.AlloyCore;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Ui;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * Robots: little copper golems that work your island while they have fuel. Four jobs (Farmer,
 * Miner, Grinder, Fisherman, switchable any time) and five tiers. Fuel Cells power them; every
 * minute of fuel burned also builds Charge, and a robot needs enough Charge (plus Essence and
 * money) to reach its next tier, so better fuel = faster progression.
 */
public final class Robots implements Listener {
    enum Job {
        FARMER("Farmer", "<#7ed957>", Material.GOLDEN_HOE, "Harvests and replants grown crops around it"),
        MINER("Miner", "<#c6a15b>", Material.IRON_PICKAXE, "Mines the block in front of it (generators, OneBlock)"),
        GRINDER("Grinder", "<#ff5e5e>", Material.IRON_SWORD, "Kills mobs around it and keeps the loot + XP"),
        FISHERMAN("Fisherman", "<#5ec8ff>", Material.FISHING_ROD, "Fishes from water next to it");
        final String label, color, desc; final Material icon;
        Job(String label, String color, Material icon, String desc) { this.label = label; this.color = color; this.icon = icon; this.desc = desc; }
    }

    static final int MAX_TIER = 5;
    static final int[] INTERVAL = {8, 6, 4, 3, 2};          // seconds between jobs, per tier
    static final int[] RADIUS = {2, 3, 4, 5, 6};
    static final int[] STORAGE = {9, 18, 27, 36, 36};
    static final double[] DAMAGE = {8, 12, 16, 22, 30};
    // to reach tier t+2 from tier t+1
    static final int[] UP_MONEY = {10_000, 35_000, 100_000, 300_000};
    static final int[] UP_ESSENCE = {64, 256, 768, 2_048};
    static final int[] UP_CHARGE_HOURS = {2, 8, 24, 72};
    // fuel cells I..V
    static final int[] FUEL_MINUTES = {30, 120, 360, 720, 1440};
    static final double[] FUEL_CHARGE = {1.0, 1.25, 1.5, 2.0, 3.0};
    static final int[] FUEL_MONEY = {500, 2_000, 6_000, 12_000, 25_000};
    static final int[] FUEL_ESSENCE = {8, 32, 96, 192, 400};
    static final Material[] FUEL_ICON = {Material.COAL, Material.BLAZE_POWDER, Material.GLOWSTONE_DUST, Material.PRISMARINE_CRYSTALS, Material.NETHER_STAR};
    static final String[] FUEL_COLOR = {"<gray>", "<gold>", "<yellow>", "<aqua>", "<light_purple>"};
    static final long MAX_TANK = 48L * 3600_000;
    public static final int ROBOT_PRICE = 7_500;

    private static final NamespacedKey ROBOT = new NamespacedKey("alloycore", "robot_id");
    private static final NamespacedKey ITEM = new NamespacedKey("alloycore", "robot_item");
    private static final NamespacedKey FUEL = new NamespacedKey("alloycore", "fuel_tier");

    /** One robot's state; the entity is found by robot id each tick (entities can reload with new UUIDs). */
    final class Bot {
        final String id; UUID owner; Job job; int tier; long fuel, charge; int fuelTier = 1; int xp;
        Location loc; Inventory storage; UUID entity; int timer; String status = "";
        Bot(String id) { this.id = id; }
        int interval() { return job == Job.FISHERMAN ? INTERVAL[tier - 1] * 2 : INTERVAL[tier - 1]; }
    }

    private final AlloyCore plugin;
    private final File file;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private final Map<UUID, String> killedBy = new HashMap<>();   // mob -> robot that hit it
    private final Map<String, ConfigurationSection> pending = new HashMap<>();   // robots whose world isn't loaded yet
    private BiPredicate<Player, Location> canUse = (p, l) -> true;
    private BiPredicate<UUID, Location> workArea = (owner, l) -> true;   // blocks/animals a robot may touch
    private String crateOnly;   // non-null: robots can't be bought here, only won from these crates
    private Ranks ranks;
    private Economy eco;
    private final Random rnd = new Random();

    Robots(AlloyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "robots.yml");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 1200L, 1200L);
        registerCommand();
    }

    void setAccess(BiPredicate<Player, Location> rule) { this.canUse = rule; }
    void setCrateOnly(String crates) { this.crateOnly = crates; }
    void setWorkArea(BiPredicate<UUID, Location> rule) { this.workArea = rule; }
    void setRanks(Ranks r) { this.ranks = r; }
    void setEconomy(Economy e) { this.eco = e; }

    int limit(Player p) {
        Ranks.Rank r = ranks == null ? null : ranks.of(p);
        if (r == null) return 2;
        return switch (r.id()) { case "copper" -> 3; case "iron" -> 4; case "gold" -> 5; case "diamond" -> 6; default -> 8; };
    }

    int owned(UUID u) { int n = 0; for (Bot b : bots.values()) if (b.owner.equals(u)) n++; return n; }

    // ------------------------------------------------------------------ items

    static ItemStack fuelCell(int tier, int n) {
        int t = Math.max(1, Math.min(5, tier));
        ItemStack it = Ui.item(FUEL_ICON[t - 1], FUEL_COLOR[t - 1] + "<bold>Fuel Cell " + Spawners.roman(t),
                "<gray>Powers a robot for <white>" + time(FUEL_MINUTES[t - 1] * 60_000L),
                "<gray>Charge rate: <white>x" + FUEL_CHARGE[t - 1] + " <dark_gray>(levels robots up faster)",
                "", "<yellow>Open a robot and click its fuel slot");
        it.editMeta(m -> { m.getPersistentDataContainer().set(FUEL, PersistentDataType.INTEGER, t); m.setEnchantmentGlintOverride(true); });
        it.setAmount(Math.max(1, Math.min(64, n)));
        return it;
    }

    static int fuelTier(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        return it.getItemMeta().getPersistentDataContainer().getOrDefault(FUEL, PersistentDataType.INTEGER, 0);
    }

    /** A robot in item form. Carries its job, tier, charge and fuel so picking it up loses nothing. */
    static ItemStack robotItem(Job job, int tier, long charge, long fuel, int fuelTier) {
        ItemStack it = Ui.item(Material.COPPER_GOLEM_SPAWN_EGG, job.color + "<bold>⚙ " + job.label + " Robot " + Spawners.roman(tier),
                "<gray>" + job.desc, "<gray>Tier: <white>" + Spawners.roman(tier) + " <dark_gray>| <gray>Fuel: <white>" + time(fuel),
                "<gray>Charge: <white>" + time(charge), "", "<yellow>Right-click a block on your island or claim to place");
        it.editMeta(m -> {
            PersistentDataContainer pdc = m.getPersistentDataContainer();
            pdc.set(ITEM, PersistentDataType.STRING, job.name() + ";" + tier + ";" + charge + ";" + fuel + ";" + fuelTier);
            m.setEnchantmentGlintOverride(true);
        });
        return it;
    }

    static boolean isRobotItem(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(ITEM); }

    static String time(long ms) {
        long m = ms / 60_000;
        if (m <= 0) return ms > 0 ? "<1m" : "0m";
        return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
    }

    private static void give(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
    }

    // ------------------------------------------------------------------ placing / picking up

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !isRobotItem(e.getItem())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Block at = e.getClickedBlock().getRelative(e.getBlockFace());
        if (!at.isPassable() || !at.getRelative(BlockFace.UP).isPassable()) { p.sendMessage(Ui.mm("<red>Not enough room there.")); return; }
        if (!canUse.test(p, at.getLocation())) { p.sendMessage(Ui.mm("<red>Robots can only go on your own island or claimed land.")); return; }
        if (owned(p.getUniqueId()) >= limit(p)) { p.sendMessage(Ui.mm("<red>You have " + owned(p.getUniqueId()) + "/" + limit(p) + " robots placed. Ranks raise the limit.")); return; }
        String[] d = e.getItem().getItemMeta().getPersistentDataContainer().get(ITEM, PersistentDataType.STRING).split(";");
        Bot b = new Bot(UUID.randomUUID().toString());
        b.owner = p.getUniqueId();
        b.job = Job.valueOf(d[0]);
        b.tier = Integer.parseInt(d[1]);
        b.charge = Long.parseLong(d[2]);
        b.fuel = Long.parseLong(d[3]);
        b.fuelTier = Integer.parseInt(d[4]);
        // face the way the player is looking, snapped to N/E/S/W, so a Miner mines what you clicked toward
        float yaw = Math.round(p.getYaw() / 90f) * 90f;
        b.loc = at.getLocation().add(0.5, 0, 0.5);
        b.loc.setYaw(yaw);
        b.storage = Bukkit.createInventory(null, STORAGE[b.tier - 1]);
        bots.put(b.id, b);
        spawn(b);
        e.getItem().setAmount(e.getItem().getAmount() - 1);
        p.playSound(b.loc, Sound.ENTITY_COPPER_GOLEM_SPAWN, 1f, 1f);
        p.sendMessage(Ui.mm("<green>Robot placed! Right-click it to add fuel, switch its job or collect its loot."));
        save();
    }

    @EventHandler
    public void onDispense(BlockDispenseEvent e) { if (isRobotItem(e.getItem()) || fuelTier(e.getItem()) > 0) e.setCancelled(true); }

    private CopperGolem spawn(Bot b) {
        CopperGolem g = b.loc.getWorld().spawn(b.loc, CopperGolem.class, x -> {
            x.setAI(false);
            x.setInvulnerable(true);
            x.setSilent(true);
            x.setPersistent(true);
            x.setRemoveWhenFarAway(false);
            x.setCollidable(false);
            x.setOxidizing(CopperGolem.Oxidizing.waxed());
            x.getPersistentDataContainer().set(ROBOT, PersistentDataType.STRING, b.id);
            x.setCustomNameVisible(true);
        });
        b.entity = g.getUniqueId();
        style(b, g);
        return g;
    }

    /** Copper polishes up as the robot tiers up: oxidized I -> shiny IV, glowing V. */
    private void style(Bot b, CopperGolem g) {
        WeatheringCopperState[] look = {WeatheringCopperState.OXIDIZED, WeatheringCopperState.WEATHERED, WeatheringCopperState.EXPOSED,
                WeatheringCopperState.UNAFFECTED, WeatheringCopperState.UNAFFECTED};
        g.setWeatheringState(look[b.tier - 1]);
        g.setGlowing(b.tier >= 5);
        g.getEquipment().setItemInMainHand(new ItemStack(b.job.icon));
        String st = b.fuel <= 0 ? "<red>No fuel" : b.status.isEmpty() ? "<white>" + time(b.fuel) : b.status;
        g.customName(Ui.mm(b.job.color + "⚙ " + b.job.label + " " + Spawners.roman(b.tier) + " <dark_gray>• " + st));
    }

    private CopperGolem entity(Bot b) {
        if (b.entity != null && Bukkit.getEntity(b.entity) instanceof CopperGolem g && g.isValid()) return g;
        return null;
    }

    private Bot botOf(Entity e) {
        String id = e.getPersistentDataContainer().get(ROBOT, PersistentDataType.STRING);
        return id == null ? null : bots.get(id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClickRobot(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof CopperGolem g) || !g.getPersistentDataContainer().has(ROBOT)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Bot b = botOf(g);
        if (b == null) { if (!pending.containsKey(g.getPersistentDataContainer().get(ROBOT, PersistentDataType.STRING))) g.remove(); return; }   // orphan
        b.entity = g.getUniqueId();
        Player p = e.getPlayer();
        if (!b.owner.equals(p.getUniqueId()) && !canUse.test(p, g.getLocation()) && !p.hasPermission("alloy.admin")) {
            p.sendMessage(Ui.mm("<red>That's " + Bukkit.getOfflinePlayer(b.owner).getName() + "'s robot.")); return;
        }
        open(p, b);
    }

    /** When a chunk's entities load, adopt our robots and remove orphans/duplicates (e.g. after a crash). */
    @EventHandler
    public void onEntitiesLoad(org.bukkit.event.world.EntitiesLoadEvent e) {
        for (Entity x : e.getEntities()) {
            String id = x.getPersistentDataContainer().get(ROBOT, PersistentDataType.STRING);
            if (id == null || pending.containsKey(id)) continue;
            Bot b = bots.get(id);
            CopperGolem current = b == null ? null : entity(b);
            if (b == null || current != null && !current.getUniqueId().equals(x.getUniqueId())) { x.remove(); continue; }
            b.entity = x.getUniqueId();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHurtRobot(EntityDamageEvent e) { if (e.getEntity().getPersistentDataContainer().has(ROBOT)) e.setCancelled(true); }

    // ------------------------------------------------------------------ work loop

    private void tick() {
        for (Bot b : new ArrayList<>(bots.values())) {
            World w = b.loc.getWorld();
            if (w == null || !w.isChunkLoaded(b.loc.getBlockX() >> 4, b.loc.getBlockZ() >> 4)) continue;
            Chunk ch = b.loc.getChunk();
            if (!ch.isEntitiesLoaded()) continue;
            CopperGolem g = entity(b);
            if (g == null) {
                for (Entity x : w.getNearbyEntities(b.loc, 1.5, 1.5, 1.5))
                    if (b.id.equals(x.getPersistentDataContainer().get(ROBOT, PersistentDataType.STRING)) && x instanceof CopperGolem cg) { g = cg; b.entity = cg.getUniqueId(); break; }
                if (g == null) { plugin.getLogger().info("Robot " + b.id + " had no entity (was " + b.entity + ", lookup=" + Bukkit.getEntity(b.entity == null ? new UUID(0, 0) : b.entity) + ") - respawning it"); g = spawn(b); }
            }
            if (b.fuel <= 0) { if (++b.timer % 5 == 0) style(b, g); continue; }
            boolean full = b.storage.firstEmpty() < 0;
            b.status = full ? "<red>Storage full" : "";
            if (!full) {
                b.fuel = Math.max(0, b.fuel - 1000);
                b.charge += (long) (1000 * FUEL_CHARGE[b.fuelTier - 1]);
                if (++b.timer >= b.interval()) {
                    b.timer = 0;
                    try { work(b, g); } catch (Exception ex) { plugin.getLogger().warning("robot " + b.id + " " + b.job + ": " + ex); }
                }
            }
            if (b.timer % 5 == 0) style(b, g);
        }
    }

    private void work(Bot b, CopperGolem g) {
        switch (b.job) {
            case MINER -> mine(b, g);
            case FARMER -> farm(b, g);
            case GRINDER -> grind(b, g);
            case FISHERMAN -> fish(b, g);
        }
    }

    private void store(Bot b, Collection<ItemStack> items) {
        for (ItemStack it : items) if (it != null && !it.getType().isAir())
            b.storage.addItem(it).values().forEach(l -> b.loc.getWorld().dropItemNaturally(b.loc, l));
    }

    private static final Set<Material> NO_MINE = EnumSet.of(Material.BEDROCK, Material.SPAWNER, Material.END_PORTAL_FRAME, Material.END_PORTAL,
            Material.NETHER_PORTAL, Material.BARRIER, Material.COMMAND_BLOCK, Material.REINFORCED_DEEPSLATE);

    private void mine(Bot b, CopperGolem g) {
        Block target = b.loc.getBlock().getRelative(facing(b.loc));
        Material t = target.getType();
        if (t.isAir() || target.isLiquid() || NO_MINE.contains(t) || t.getHardness() < 0 || target.getState() instanceof Container) return;
        if (!workArea.test(b.owner, target.getLocation())) return;
        Material[] picks = {Material.STONE_PICKAXE, Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE, Material.NETHERITE_PICKAXE};
        ItemStack tool = new ItemStack(picks[b.tier - 1]);
        if (b.tier >= 4) tool.addUnsafeEnchantment(Enchantment.FORTUNE, b.tier - 2);
        Player owner = Bukkit.getPlayer(b.owner);
        if (owner != null) {
            // Let other plugins (OneBlock, island protection) see the break as the owner's
            BlockBreakEvent ev = new BlockBreakEvent(target, owner);
            ev.setDropItems(false);
            Collection<ItemStack> drops = target.getDrops(tool);
            Material before = target.getType();
            Bukkit.getPluginManager().callEvent(ev);
            if (ev.isCancelled()) return;
            store(b, drops);
            if (target.getType() == before) target.setType(Material.AIR);
        } else {
            if (isMagicBlock(target)) return;   // OneBlock needs its owner online to roll the next block
            store(b, target.getDrops(tool));
            target.setType(Material.AIR);
        }
        g.swingMainHand();
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_STONE_BREAK, 0.4f, 1f);
    }

    private boolean isMagicBlock(Block bl) {
        return bl.getWorld().getName().startsWith("oneblock") && Math.floorMod(bl.getX(), 400) == 0 && Math.floorMod(bl.getZ(), 400) == 0;
    }

    private static BlockFace facing(Location l) {
        float y = ((l.getYaw() % 360) + 360) % 360;
        return y < 45 || y >= 315 ? BlockFace.SOUTH : y < 135 ? BlockFace.WEST : y < 225 ? BlockFace.NORTH : BlockFace.EAST;
    }

    private static final Map<Material, Material> SEEDS = Map.of(Material.WHEAT, Material.WHEAT_SEEDS, Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO, Material.BEETROOTS, Material.BEETROOT_SEEDS, Material.NETHER_WART, Material.NETHER_WART,
            Material.COCOA, Material.COCOA_BEANS, Material.TORCHFLOWER_CROP, Material.TORCHFLOWER_SEEDS);

    private void farm(Bot b, CopperGolem g) {
        int r = RADIUS[b.tier - 1], done = 0;
        Block c = b.loc.getBlock();
        for (int dy = -1; dy <= 2 && done < b.tier; dy++)
            for (int dx = -r; dx <= r && done < b.tier; dx++)
                for (int dz = -r; dz <= r && done < b.tier; dz++) {
                    Block bl = c.getRelative(dx, dy, dz);
                    Material t = bl.getType();
                    if (!workArea.test(b.owner, bl.getLocation())) continue;
                    if (bl.getBlockData() instanceof Ageable a && SEEDS.containsKey(t)) {
                        if (a.getAge() < a.getMaximumAge()) continue;
                        List<ItemStack> drops = new ArrayList<>(bl.getDrops());
                        Material seed = SEEDS.get(t);
                        for (ItemStack d : drops) if (d.getType() == seed) { d.setAmount(d.getAmount() - 1); break; }
                        store(b, drops);
                        a.setAge(0);
                        bl.setBlockData(a);
                        done++;
                    } else if (t == Material.MELON || t == Material.PUMPKIN) {
                        store(b, bl.getDrops());
                        bl.setType(Material.AIR);
                        done++;
                    } else if ((t == Material.SUGAR_CANE || t == Material.CACTUS || t == Material.BAMBOO) && bl.getRelative(BlockFace.DOWN).getType() == t
                            && bl.getRelative(BlockFace.UP).getType() != t) {
                        store(b, bl.getDrops());
                        bl.setType(Material.AIR);
                        done++;
                    }
                }
        if (done > 0) g.swingMainHand();
    }

    private void grind(Bot b, CopperGolem g) {
        int r = RADIUS[b.tier - 1], hits = 0;
        Map<EntityType, Integer> count = new HashMap<>();
        Collection<Entity> near = b.loc.getWorld().getNearbyEntities(b.loc, r, 3, r);
        for (Entity e : near) count.merge(e.getType(), 1, Integer::sum);
        for (Entity e : near) {
            if (hits > b.tier) break;   // tier + 1 mobs per swing
            if (!(e instanceof Mob m) || e instanceof CopperGolem || e.getPersistentDataContainer().has(ROBOT)) continue;
            if (m instanceof Tameable t && t.isTamed()) continue;
            if (!(m instanceof Enemy) && !workArea.test(b.owner, m.getLocation())) continue;   // hostiles anywhere, animals only on the owner's land
            if (m instanceof AbstractVillager || m.customName() != null && !m.getPersistentDataContainer().has(Spawners.FROM_SPAWNER)) continue;
            // leave a breeding pair of farm animals alone
            if (m instanceof Animals an && !m.getPersistentDataContainer().has(Spawners.FROM_SPAWNER) && (count.get(e.getType()) <= 2 || !an.isAdult())) continue;
            killedBy.put(m.getUniqueId(), b.id);
            m.damage(DAMAGE[b.tier - 1]);
            if (!m.isDead()) killedBy.remove(m.getUniqueId());
            hits++;
        }
        if (hits > 0) { g.swingMainHand(); b.loc.getWorld().playSound(b.loc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.4f, 1.2f); }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKill(EntityDeathEvent e) {
        String id = killedBy.remove(e.getEntity().getUniqueId());
        Bot b = id == null ? null : bots.get(id);
        if (b == null) return;
        store(b, new ArrayList<>(e.getDrops()));
        e.getDrops().clear();
        b.xp += e.getDroppedExp();
        e.setDroppedExp(0);
    }

    private void fish(Bot b, CopperGolem g) {
        boolean water = false;
        Block c = b.loc.getBlock();
        for (int dx = -2; dx <= 2 && !water; dx++) for (int dy = -2; dy <= 0 && !water; dy++) for (int dz = -2; dz <= 2 && !water; dz++)
            water = c.getRelative(dx, dy, dz).getType() == Material.WATER;
        if (!water) { b.status = "<red>Needs water"; return; }
        double roll = rnd.nextDouble();
        ItemStack catchIt;
        if (roll < 0.02 * b.tier) {
            Material[] treasure = {Material.NAME_TAG, Material.SADDLE, Material.NAUTILUS_SHELL, Material.ENCHANTED_BOOK, Material.BOW};
            catchIt = new ItemStack(treasure[rnd.nextInt(treasure.length)]);
            if (catchIt.getType() == Material.ENCHANTED_BOOK)
                catchIt.editMeta(org.bukkit.inventory.meta.EnchantmentStorageMeta.class, m -> m.addStoredEnchant(
                        List.of(Enchantment.MENDING, Enchantment.UNBREAKING, Enchantment.LUCK_OF_THE_SEA, Enchantment.LURE, Enchantment.SHARPNESS).get(rnd.nextInt(5)), 1 + rnd.nextInt(3), true));
        } else if (roll < 0.02 * b.tier + 0.05) {
            Material[] junk = {Material.STICK, Material.STRING, Material.LEATHER, Material.BOWL, Material.INK_SAC, Material.BONE};
            catchIt = new ItemStack(junk[rnd.nextInt(junk.length)]);
        } else {
            double f = rnd.nextDouble();
            catchIt = new ItemStack(f < 0.6 ? Material.COD : f < 0.85 ? Material.SALMON : f < 0.98 ? Material.PUFFERFISH : Material.TROPICAL_FISH);
        }
        store(b, List.of(catchIt));
        b.xp += 1 + rnd.nextInt(3);
        g.swingMainHand();
        b.loc.getWorld().playSound(b.loc, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.3f, 1.2f);
    }

    // ------------------------------------------------------------------ robot menu

    private static final class Menu implements InventoryHolder {
        final String id; final String kind; Inventory inv;
        Menu(String id, String kind) { this.id = id; this.kind = kind; }
        public Inventory getInventory() { return inv; }
    }

    private static final int[] JOB_SLOTS = {10, 12, 14, 16};

    void open(Player p, Bot b) {
        Menu m = new Menu(b.id, "robot");
        m.inv = Bukkit.createInventory(m, 54, Ui.mm("<dark_gray>⚙ " + b.job.label + " Robot " + Spawners.roman(b.tier)));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 18; i++) m.inv.setItem(i, pane);
        int used = 0;
        for (ItemStack it : b.storage.getContents()) if (it != null) used++;
        m.inv.setItem(0, Ui.item(Material.CHEST, "<green><bold>Collect All", "<gray>Storage: <white>" + used + "/" + b.storage.getSize() + " slots", "<gray>Click an item below to take just that stack."));
        m.inv.setItem(1, Ui.item(Material.EXPERIENCE_BOTTLE, "<green><bold>Collect XP", "<gray>Stored: <white>" + b.xp + " XP"));
        m.inv.setItem(2, Ui.item(b.fuel > 0 ? FUEL_ICON[b.fuelTier - 1] : Material.BUCKET, "<gold><bold>Fuel: <white>" + time(b.fuel),
                "<gray>Tank holds up to " + time(MAX_TANK) + ".", "<gray>Burning: " + FUEL_COLOR[b.fuelTier - 1] + "Fuel Cell " + Spawners.roman(b.fuelTier) + " <gray>(x" + FUEL_CHARGE[b.fuelTier - 1] + " charge)",
                "", "<yellow>Click: <gray>add 1 Fuel Cell from your inventory", "<yellow>Shift-click: <gray>add as many as fit"));
        List<String> info = new ArrayList<>(List.of("<gray>Owner: <white>" + Bukkit.getOfflinePlayer(b.owner).getName(),
                "<gray>Job: " + b.job.color + b.job.label + " <dark_gray>- <gray>" + b.job.desc,
                "<gray>Works every <white>" + b.interval() + "s" + (b.job == Job.MINER ? "" : " <gray>in a <white>" + RADIUS[b.tier - 1] + "<gray>-block radius"),
                "<gray>Charge: <white>" + time(b.charge)));
        if (!b.status.isEmpty()) info.add(b.status);
        m.inv.setItem(4, Ui.item(Material.COPPER_GOLEM_SPAWN_EGG, b.job.color + "<bold>" + b.job.label + " Robot " + Spawners.roman(b.tier), info.toArray(String[]::new)));
        if (b.tier >= MAX_TIER) m.inv.setItem(6, Ui.item(Material.NETHER_STAR, "<gold><bold>MAX TIER"));
        else {
            int t = b.tier - 1;
            long need = UP_CHARGE_HOURS[t] * 3600_000L;
            int pct = (int) Math.min(100, 100 * b.charge / need);
            String bar = "<green>" + "|".repeat(pct / 5) + "<dark_gray>" + "|".repeat(20 - pct / 5);
            m.inv.setItem(6, Ui.item(Material.ANVIL, "<aqua><bold>Upgrade to Tier " + Spawners.roman(b.tier + 1),
                    "<gray>Faster, bigger radius, more storage.", "",
                    "<gray>Charge: " + bar + " <white>" + pct + "%", "<dark_gray>(" + time(b.charge) + " / " + UP_CHARGE_HOURS[t] + "h - burn fuel to charge)",
                    "<#9d7bff>" + UP_ESSENCE[t] + " Mob Essence", "<green>" + NetEconomy.money(UP_MONEY[t]), "", "<yellow>Click to upgrade"));
        }
        m.inv.setItem(8, Ui.item(Material.HOPPER, "<red><bold>Pick Up Robot", "<gray>Gives you the robot item (keeps tier,", "<gray>charge and fuel) and its storage."));
        for (int i = 0; i < 4; i++) {
            Job j = Job.values()[i];
            ItemStack icon = Ui.item(j.icon, j.color + "<bold>" + j.label, "<gray>" + j.desc, "", j == b.job ? "<green>✔ Current job" : "<yellow>Click to switch");
            if (j == b.job) icon.editMeta(x -> x.setEnchantmentGlintOverride(true));
            m.inv.setItem(JOB_SLOTS[i], icon);
        }
        for (int i = 0; i < b.storage.getSize() && 18 + i < 54; i++) m.inv.setItem(18 + i, b.storage.getItem(i));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (m.kind.equals("shop")) { shopClick(p, e.getRawSlot()); return; }
        Bot b = bots.get(m.id);
        if (b == null) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        if (slot >= 18) {
            int i = slot - 18;
            if (i >= b.storage.getSize()) return;
            ItemStack it = b.storage.getItem(i);
            if (it == null) return;
            b.storage.setItem(i, null);
            give(p, it);
            open(p, b);
            return;
        }
        switch (slot) {
            case 0 -> {
                for (int i = 0; i < b.storage.getSize(); i++) {
                    ItemStack it = b.storage.getItem(i);
                    if (it == null) continue;
                    Map<Integer, ItemStack> left = p.getInventory().addItem(it);
                    b.storage.setItem(i, left.isEmpty() ? null : left.values().iterator().next());
                }
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1f);
                open(p, b);
            }
            case 1 -> { if (b.xp > 0) { p.giveExp(b.xp); p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1f); b.xp = 0; } open(p, b); }
            case 2 -> { addFuel(p, b, e.isShiftClick()); open(p, b); }
            case 6 -> { upgrade(p, b); open(p, b); }
            case 8 -> pickUp(p, b);
            default -> {
                for (int i = 0; i < 4; i++) if (JOB_SLOTS[i] == slot && b.job != Job.values()[i]) {
                    b.job = Job.values()[i];
                    b.timer = 0;
                    CopperGolem g = entity(b);
                    if (g != null) style(b, g);
                    p.sendMessage(Ui.mm("<green>Your robot is now a " + b.job.color + b.job.label + "<green>."));
                    open(p, b);
                }
            }
        }
    }

    private void addFuel(Player p, Bot b, boolean all) {
        int added = 0;
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length; i++) {
            int t = fuelTier(inv[i]);
            if (t == 0) continue;
            while (inv[i] != null && inv[i].getAmount() > 0 && b.fuel + FUEL_MINUTES[t - 1] * 60_000L <= MAX_TANK) {
                b.fuel += FUEL_MINUTES[t - 1] * 60_000L;
                b.fuelTier = b.fuel - FUEL_MINUTES[t - 1] * 60_000L > 0 ? Math.max(b.fuelTier, t) : t;
                inv[i].setAmount(inv[i].getAmount() - 1);
                added++;
                if (!all) break;
            }
            if (added > 0 && !all) break;
        }
        p.getInventory().setStorageContents(inv);
        if (added == 0) p.sendMessage(Ui.mm(b.fuel >= MAX_TANK - 30 * 60_000L ? "<red>The tank is full." : "<red>You have no Fuel Cells. Buy them at <white>/robots<red> or the Scrap Shop."));
        else { p.sendMessage(Ui.mm("<green>Added " + added + " Fuel Cell" + (added > 1 ? "s" : "") + ". Fuel: <white>" + time(b.fuel))); p.playSound(p.getLocation(), Sound.ITEM_BUCKET_FILL_LAVA, 0.6f, 1.2f); }
    }

    private void upgrade(Player p, Bot b) {
        if (b.tier >= MAX_TIER) return;
        int t = b.tier - 1;
        if (b.charge < UP_CHARGE_HOURS[t] * 3600_000L) { p.sendMessage(Ui.mm("<red>Not enough Charge yet - keep it fuelled (" + time(b.charge) + " / " + UP_CHARGE_HOURS[t] + "h). Better fuel charges faster.")); return; }
        if (Spawners.countEssence(p) < UP_ESSENCE[t]) { p.sendMessage(Ui.mm("<red>You need " + UP_ESSENCE[t] + " Mob Essence.")); return; }
        if (eco == null || eco.getBalance(p) < UP_MONEY[t]) { p.sendMessage(Ui.mm("<red>You need " + NetEconomy.money(UP_MONEY[t]) + ".")); return; }
        eco.withdrawPlayer(p, UP_MONEY[t]);
        Spawners.takeEssence(p, UP_ESSENCE[t]);
        b.charge -= UP_CHARGE_HOURS[t] * 3600_000L;
        b.tier++;
        Inventory bigger = Bukkit.createInventory(null, STORAGE[b.tier - 1]);
        bigger.setContents(Arrays.copyOf(b.storage.getContents(), bigger.getSize()));
        b.storage = bigger;
        CopperGolem g = entity(b);
        if (g != null) { style(b, g); g.getWorld().spawnParticle(Particle.WAX_OFF, g.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4); }
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        p.sendMessage(Ui.mm("<gold><bold>Robot upgraded to Tier " + Spawners.roman(b.tier) + "!"));
        save();
    }

    private void pickUp(Player p, Bot b) {
        p.closeInventory();
        CopperGolem g = entity(b);
        if (g != null) g.remove();
        bots.remove(b.id);
        for (ItemStack it : b.storage.getContents()) if (it != null) give(p, it);
        if (b.xp > 0) p.giveExp(b.xp);
        give(p, robotItem(b.job, b.tier, b.charge, b.fuel, b.fuelTier));
        p.sendMessage(Ui.mm("<green>Robot picked up."));
        save();
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    // ------------------------------------------------------------------ /robots workshop

    void openShop(Player p) {
        Menu m = new Menu("", "shop");
        m.inv = Bukkit.createInventory(m, 36, Ui.mm("<dark_gray>⚙ Robot Workshop"));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 36; i++) m.inv.setItem(i, pane);
        m.inv.setItem(4, Ui.item(Material.BOOK, "<gold><bold>How robots work",
                crateOnly == null ? "<gray>1. Buy a robot and place it on your island" : "<gray>1. Win a robot from a crate, place it in your claim", "<gray>2. Right-click it and add Fuel Cells",
                "<gray>3. Pick a job: Farmer, Miner, Grinder, Fisherman", "<gray>4. Burning fuel builds Charge - with Charge,",
                "<gray>   Essence and money it can reach the next tier", "", "<gray>Your robots: <white>" + owned(p.getUniqueId()) + "/" + limit(p) + " <dark_gray>(ranks raise this)"));
        ItemStack r = robotItem(Job.MINER, 1, 0, 0, 1);
        r.editMeta(x -> { List<net.kyori.adventure.text.Component> l = new ArrayList<>(Optional.ofNullable(x.lore()).orElse(List.of())); l.add(Ui.mm(""));
            if (crateOnly == null) { l.add(Ui.mm("<green>Price: " + NetEconomy.money(ROBOT_PRICE))); l.add(Ui.mm("<yellow>Click to buy")); }
            else { l.add(Ui.mm("<red>Not for sale here")); l.add(Ui.mm("<gray>Win robots from " + crateOnly + " <gray>crates")); }
            x.lore(l); });
        m.inv.setItem(13, r);
        for (int t = 1; t <= 5; t++) {
            ItemStack f = fuelCell(t, 1);
            int tt = t;
            f.editMeta(x -> { List<net.kyori.adventure.text.Component> l = new ArrayList<>(Optional.ofNullable(x.lore()).orElse(List.of())); l.add(Ui.mm(""));
                l.add(Ui.mm("<green>" + NetEconomy.money(FUEL_MONEY[tt - 1]) + " <dark_gray>+ <#9d7bff>" + FUEL_ESSENCE[tt - 1] + " Mob Essence")); l.add(Ui.mm("<yellow>Click to buy")); x.lore(l); });
            m.inv.setItem(19 + 2 * (t - 1), f);
        }
        p.openInventory(m.inv);
    }

    private void shopClick(Player p, int slot) {
        if (slot == 13) {
            if (crateOnly != null) { p.sendMessage(Ui.mm("<gray>Robots can only be won from " + crateOnly + " <gray>crates.")); return; }
            if (eco == null || !eco.withdrawPlayer(p, ROBOT_PRICE).transactionSuccess()) { p.sendMessage(Ui.mm("<red>A robot costs " + NetEconomy.money(ROBOT_PRICE) + ".")); return; }
            give(p, robotItem(Job.MINER, 1, 0, 0, 1));
            p.sendMessage(Ui.mm("<green>You bought a robot! Place it on your island."));
        } else if (slot >= 19 && slot <= 27 && (slot - 19) % 2 == 0) {
            int t = (slot - 19) / 2 + 1;
            if (Spawners.countEssence(p) < FUEL_ESSENCE[t - 1]) { p.sendMessage(Ui.mm("<red>You need " + FUEL_ESSENCE[t - 1] + " Mob Essence.")); return; }
            if (eco == null || eco.getBalance(p) < FUEL_MONEY[t - 1]) { p.sendMessage(Ui.mm("<red>You need " + NetEconomy.money(FUEL_MONEY[t - 1]) + ".")); return; }
            eco.withdrawPlayer(p, FUEL_MONEY[t - 1]);
            Spawners.takeEssence(p, FUEL_ESSENCE[t - 1]);
            give(p, fuelCell(t, 1));
            p.sendMessage(Ui.mm("<green>Bought a Fuel Cell " + Spawners.roman(t) + "."));
        } else return;
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        openShop(p);
    }

    private void registerCommand() {
        Bukkit.getCommandMap().register("alloy", new Command("robots", "Robot workshop", "/robots", List.of("robot", "fuel")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (a.length >= 3 && a[0].equalsIgnoreCase("give") && s.hasPermission("alloy.admin")) {   // robots give <player> <robot|fuel|essence> [tier/amount] [job]
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) { s.sendMessage(Ui.mm("<red>Not online.")); return true; }
                    int n = a.length > 3 ? Integer.parseInt(a[3]) : 1;
                    switch (a[2].toLowerCase()) {
                        case "robot" -> give(t, robotItem(a.length > 4 ? Job.valueOf(a[4].toUpperCase()) : Job.MINER, Math.max(1, Math.min(5, n)), 0, 0, 1));
                        case "fuel" -> give(t, fuelCell(n, 1));
                        case "essence" -> Spawners.giveEssence(t, n);
                        case "fragment", "fragments" -> give(t, Spawners.fragment(n));
                        case "emptyspawner" -> { for (int i = 0; i < n; i++) give(t, Spawners.emptySpawner()); }
                        default -> { s.sendMessage(Ui.mm("<red>robot|fuel|essence|fragment|emptyspawner")); return true; }
                    }
                    s.sendMessage(Ui.mm("<green>Given."));
                    return true;
                }
                if (a.length >= 3 && a[0].equalsIgnoreCase("charge") && s.hasPermission("alloy.admin")) {   // robots charge <player> <hours>
                    Player t = Bukkit.getPlayerExact(a[1]);
                    if (t == null) { s.sendMessage(Ui.mm("<red>Not online.")); return true; }
                    for (Bot b : bots.values()) if (b.owner.equals(t.getUniqueId())) b.charge += Long.parseLong(a[2]) * 3600_000L;
                    s.sendMessage(Ui.mm("<green>Charged " + t.getName() + "'s robots.")); return true;
                }
                if (s instanceof Player p) openShop(p);
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("robots");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) pending.put(id, sec.getConfigurationSection(id));
        loadPending();
        // island worlds (BentoBox) are created after AlloyCore enables
        Bukkit.getScheduler().runTask(plugin, this::loadPending);
    }

    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent e) { if (!pending.isEmpty()) loadPending(); }

    private void loadPending() {
        int before = bots.size();
        for (Iterator<Map.Entry<String, ConfigurationSection>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, ConfigurationSection> en = it.next();
            String id = en.getKey();
            ConfigurationSection s = en.getValue();
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) continue;
            it.remove();
            Bot b = new Bot(id);
            b.owner = UUID.fromString(s.getString("owner"));
            b.job = Job.valueOf(s.getString("job"));
            b.tier = s.getInt("tier", 1);
            b.fuel = s.getLong("fuel");
            b.fuelTier = Math.max(1, s.getInt("fuel-tier", 1));
            b.charge = s.getLong("charge");
            b.xp = s.getInt("xp");
            b.loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), 0);
            b.storage = Bukkit.createInventory(null, STORAGE[b.tier - 1]);
            List<?> items = s.getList("storage", List.of());
            for (int i = 0; i < items.size() && i < b.storage.getSize(); i++) if (items.get(i) instanceof ItemStack st) b.storage.setItem(i, st);
            String ent = s.getString("entity");
            if (ent != null) b.entity = UUID.fromString(ent);
            bots.put(id, b);
        }
        if (bots.size() > before) plugin.getLogger().info("Loaded " + (bots.size() - before) + " robots" + (pending.isEmpty() ? "" : " (" + pending.size() + " waiting for their world)"));
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, ConfigurationSection> en : pending.entrySet()) y.set("robots." + en.getKey(), en.getValue());
        for (Bot b : bots.values()) {
            String k = "robots." + b.id + ".";
            y.set(k + "owner", b.owner.toString());
            y.set(k + "job", b.job.name());
            y.set(k + "tier", b.tier);
            y.set(k + "fuel", b.fuel);
            y.set(k + "fuel-tier", b.fuelTier);
            y.set(k + "charge", b.charge);
            y.set(k + "xp", b.xp);
            y.set(k + "world", b.loc.getWorld().getName());
            y.set(k + "x", b.loc.getX()); y.set(k + "y", b.loc.getY()); y.set(k + "z", b.loc.getZ()); y.set(k + "yaw", b.loc.getYaw());
            y.set(k + "entity", b.entity == null ? null : b.entity.toString());
            y.set(k + "storage", Arrays.asList(b.storage.getContents()));
        }
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("robots save failed: " + e.getMessage()); }
    }
}
