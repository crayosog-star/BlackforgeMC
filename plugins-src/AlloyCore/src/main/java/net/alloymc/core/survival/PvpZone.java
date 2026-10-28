package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.*;

import static net.alloymc.core.survival.SurvivalLayout.*;

/**
 * The Scrapyard (/pvp): a walled PvP zone. Every few hours a wave of supply crates drops at
 * random spots; smashing one open spills 1-2 random pieces of gear (with random enchant
 * upgrades) and, very rarely, a crate key.
 */
public final class PvpZone implements Listener {
    private final AlloyCore plugin;
    private final World world;
    private final Crates crates;
    private final Random rnd = new Random();
    private final SafeDrop.Pool landing = new SafeDrop.Pool(() -> SafeDrop.find(world(), () -> new Location(world(), PVP_X + rnd.nextInt(2 * PVP_HALF - 30) - PVP_HALF + 15, 0,
            PVP_Z + rnd.nextInt(2 * PVP_HALF - 30) - PVP_HALF + 15, 0, 70), PVP_Y + 40, PVP_Y + 6, 42, rnd));
    private final Map<Long, TextDisplay> active = new LinkedHashMap<>();   // crate block -> label
    private final Map<Long, org.bukkit.entity.BlockDisplay> glows = new HashMap<>();   // crate block -> glowing outline
    private final Map<Long, Long> playerFluids = new HashMap<>();           // fluid block -> remove at
    private final int intervalMin, cratesPerDrop;
    private long nextDrop;
    private long dropEndsAt;
    private boolean ticketsHeld;
    private CustomEnchants enchants;
    private Scrap scrap;

    void setScrap(Scrap s) { this.scrap = s; }

    void setEnchants(CustomEnchants ce) { this.enchants = ce; }

    private GaleBellows bellows;
    void setBellows(GaleBellows b) { this.bellows = b; }

    PvpZone(AlloyCore plugin, World world, Crates crates) {
        this.plugin = plugin;
        this.world = world;
        this.crates = crates;
        var cfg = plugin.getConfig();
        intervalMin = cfg.getInt("pvp.drop-interval-minutes", 300);
        cratesPerDrop = cfg.getInt("pvp.crates-per-drop", 15);
        nextDrop = System.currentTimeMillis() + cfg.getInt("pvp.first-drop-after-minutes", 60) * 60_000L;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF); }
    private static long key(Block b) { return key(b.getX(), b.getY(), b.getZ()); }

    private World world() { return world; }

    public String status() {
        if (!active.isEmpty()) return "<gold>" + active.size() + " supply crates waiting!";
        long mins = Math.max(0, (nextDrop - System.currentTimeMillis()) / 60_000);
        return "<gray>Next supply drop in <white>" + (mins >= 60 ? mins / 60 + "h " + mins % 60 + "m" : mins + "m");
    }

    public int waiting() { return active.size(); }

    /** Players drop in from the sky over a random spot, so there's no safe arrival pad to camp or run back to. */
    public Location arrival() {
        // only over open ground - never a roof you'd take fall damage jumping off
        return landing.next(rnd);
    }

    /** Highest solid block of the arena floor at a column (ignores the barrier roof). */
    private int groundY(int x, int z) {
        for (int y = PVP_Y + 30; y >= PVP_Y - 4; y--) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType().isSolid() && b.getType() != Material.BARRIER) return y;
        }
        return PVP_Y;
    }

    // ------------------------------------------------------------------ drops

    private void tick() {
        long now = System.currentTimeMillis();
        if (active.isEmpty() && dropEndsAt == 0) {
            long left = nextDrop - now;
            if (left <= 300_000 && left > 60_000 && warned < 1) { warned = 1; announce("<gold>☄ <gray>A supply drop lands in <white>the Scrapyard <gray>in <white>5 minutes<gray>! <white>/pvp"); }
            if (left <= 60_000 && left > 0 && warned < 2) { warned = 2; announce("<gold>☄ <gray>Supply drop in <white>1 minute<gray>! <white>/pvp"); }
            if (left <= 0) drop();
        }
        if (dropEndsAt > 0 && (now > dropEndsAt || active.isEmpty())) endDrop(active.isEmpty() ? "All supply crates have been looted!" : "The remaining supply crates crumbled to dust.");
        // beacons of light over each crate so players can find them
        for (long k : active.keySet()) {
            int x = (int) (k >> 38), z = (int) (k << 26 >> 38), y = (int) (k << 52 >> 52);
            for (int dy = 1; dy <= 24; dy += 2) world.spawnParticle(Particle.END_ROD, x + 0.5, y + dy, z + 0.5, 1, 0.05, 0.4, 0.05, 0);
            world.spawnParticle(Particle.FLAME, x + 0.5, y + 1.1, z + 0.5, 3, 0.3, 0.1, 0.3, 0.01);
        }
        // fluids players poured in the arena dry up after a while
        for (Iterator<Map.Entry<Long, Long>> it = playerFluids.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue() > now) continue;
            long k = e.getKey();
            Block b = world.getBlockAt((int) (k >> 38), (int) (k << 52 >> 52), (int) (k << 26 >> 38));
            if (b.isLiquid() || b.getType() == Material.OBSIDIAN || b.getType() == Material.COBBLESTONE) b.setType(Material.AIR);
            it.remove();
        }
    }

    private void announce(String mm) {
        Bukkit.broadcast(Ui.mm(mm).clickEvent(ClickEvent.runCommand("/pvp")));
    }

    private int warned;

    public void drop() {
        warned = 0;
        if (!active.isEmpty()) endDrop(null);
        holdChunks(true);
        int placed = 0;
        for (int tries = 0; tries < cratesPerDrop * 30 && placed < cratesPerDrop; tries++) {
            int x = PVP_X + rnd.nextInt(2 * PVP_HALF - 16) - PVP_HALF + 8;
            int z = PVP_Z + rnd.nextInt(2 * PVP_HALF - 16) - PVP_HALF + 8;
            int y = groundY(x, z);
            Block ground = world.getBlockAt(x, y, z), at = ground.getRelative(BlockFace.UP);
            if (!at.getType().isAir() || ground.isLiquid() || ground.getType() == Material.MAGMA_BLOCK || !ground.getType().isSolid()) continue;
            if (!at.getRelative(BlockFace.UP).getType().isAir()) continue;
            boolean crowded = false;
            for (long k : active.keySet()) if (Math.hypot(x - (int) (k >> 38), z - (int) (k << 26 >> 38)) < 14) crowded = true;
            if (crowded) continue;
            var data = crateBlock();
            at.setBlockData(data, false);
            // big label high above the crate, readable from across the arena and through walls
            TextDisplay label = Ui.hologram(at.getLocation().add(0.5, 2.6, 0.5), "<gold><bold>☄ SUPPLY CRATE ☄</bold><newline><yellow>Right-click to smash it open", 1.8f);
            label.setSeeThrough(true);
            label.setViewRange(12f);
            label.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
            active.put(key(at), label);
            // glowing gold outline of the crate itself, visible through blocks
            org.bukkit.entity.BlockDisplay glow = world.spawn(at.getLocation().add(-0.01, -0.01, -0.01), org.bukkit.entity.BlockDisplay.class, bd -> {
                bd.setBlock(data);
                bd.setGlowing(true);
                bd.setGlowColorOverride(Color.fromRGB(0xFFB000));
                bd.setPersistent(false);
                bd.setViewRange(12f);
                bd.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                        new org.joml.Vector3f(1.02f, 1.02f, 1.02f), new org.joml.Quaternionf()));
            });
            glows.put(key(at), glow);
            // a little meteor for flavour
            world.spawnParticle(Particle.EXPLOSION, at.getLocation().add(0.5, 0.5, 0.5), 1);
            world.playSound(at.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.4f);
            placed++;
        }
        dropEndsAt = System.currentTimeMillis() + 60 * 60_000L;
        nextDrop = System.currentTimeMillis() + intervalMin * 60_000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(Ui.mm("<gold><bold>SUPPLY DROP"), Ui.mm("<gray>" + placed + " crates hit the Scrapyard! <white>/pvp")));
            p.playSound(p.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.6f, 1.2f);
        }
        announce("<gold><bold>☄ SUPPLY DROP!</bold> <white>" + placed + " crates <gray>just crashed into the Scrapyard. Loot them before anyone else! <yellow><u>Click</u> <gray>or <white>/pvp");
    }

    private org.bukkit.block.data.BlockData crateBlock() {
        try { return Bukkit.createBlockData("minecraft:copper_chest[facing=" + new String[]{"north", "south", "east", "west"}[rnd.nextInt(4)] + "]"); }
        catch (IllegalArgumentException e) { return Bukkit.createBlockData("minecraft:barrel[facing=up]"); }
    }

    private void endDrop(String msg) {
        for (var e : active.entrySet()) {
            long k = e.getKey();
            Block b = world.getBlockAt((int) (k >> 38), (int) (k << 52 >> 52), (int) (k << 26 >> 38));
            b.setType(Material.AIR, false);
            if (e.getValue().isValid()) e.getValue().remove();
            var g = glows.remove(k);
            if (g != null && g.isValid()) g.remove();
        }
        active.clear();
        dropEndsAt = 0;
        holdChunks(false);
        if (msg != null) announce("<gold>☄ <gray>" + msg + " " + status());
    }

    /** Keep the arena loaded while crates are out so their labels and particles survive. */
    private void holdChunks(boolean hold) {
        if (hold == ticketsHeld) return;
        ticketsHeld = hold;
        for (int cx = (PVP_X - PVP_HALF) >> 4; cx <= (PVP_X + PVP_HALF) >> 4; cx++)
            for (int cz = (PVP_Z - PVP_HALF) >> 4; cz <= (PVP_Z + PVP_HALF) >> 4; cz++)
                if (hold) world.addPluginChunkTicket(cx, cz, plugin); else world.removePluginChunkTicket(cx, cz, plugin);
    }

    public void shutdown() { endDrop(null); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCrate(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK)) return;
        TextDisplay label = active.remove(key(b));
        if (label == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (label.isValid()) label.remove();
        var glow = glows.remove(key(b));
        if (glow != null && glow.isValid()) glow.remove();
        b.setType(Material.AIR, false);
        Location at = b.getLocation().add(0.5, 0.6, 0.5);
        int n = rnd.nextInt(100) < 55 ? 1 : 2;
        for (int i = 0; i < n; i++) {
            ItemStack loot = roll(p);
            if (loot == null) continue;
            Item drop = world.dropItem(at, loot);
            drop.setVelocity(new Vector((rnd.nextDouble() - 0.5) * 0.3, 0.35, (rnd.nextDouble() - 0.5) * 0.3));
            drop.setGlowing(true);
        }
        world.spawnParticle(Particle.BLOCK, at, 30, 0.3, 0.3, 0.3, Bukkit.createBlockData(Material.OXIDIZED_COPPER));
        world.spawnParticle(Particle.FIREWORK, at, 20, 0.2, 0.4, 0.2, 0.08);
        world.playSound(at, Sound.BLOCK_COPPER_BREAK, 1.2f, 0.8f);
        world.playSound(at, Sound.ENTITY_ITEM_PICKUP, 1f, 0.6f);
        p.sendActionBar(Ui.mm("<gold>☄ Supply crate smashed! <gray>(" + active.size() + " left)"));
        if (scrap != null) Bukkit.getScheduler().runTaskLater(plugin, () -> scrap.add(p, 5, "supply crate"), 30L);
    }

    // ------------------------------------------------------------------ loot

    private record Entry(double weight, String kind) {}
    private static final List<Entry> TABLE = List.of(
        new Entry(18, "iron_armor"), new Entry(14, "iron_tool"), new Entry(8, "diamond_armor"), new Entry(6, "diamond_tool"),
        new Entry(12, "golden_apple"), new Entry(1.5, "enchanted_golden_apple"),
        new Entry(6, "strength1"), new Entry(3, "strength2"), new Entry(6, "speed1"), new Entry(3, "speed2"),
        new Entry(5, "lava"), new Entry(5, "water"), new Entry(4, "book"),
        new Entry(0.8, "key_iron"), new Entry(0.25, "key_gold"), new Entry(0.06, "key_netherite"),
        new Entry(0.7, "bellows"));   // rare: about 1 in 130 rolls
    private static final double TOTAL = TABLE.stream().mapToDouble(Entry::weight).sum();

    private ItemStack roll(Player p) {
        double r = rnd.nextDouble() * TOTAL;
        String kind = TABLE.getLast().kind();
        for (Entry e : TABLE) { r -= e.weight(); if (r < 0) { kind = e.kind(); break; } }
        return switch (kind) {
            case "iron_armor" -> gear(pick("IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS"), false);
            case "iron_tool" -> gear(pick("IRON_SWORD", "IRON_SWORD", "IRON_AXE", "IRON_PICKAXE"), false);
            case "diamond_armor" -> gear(pick("DIAMOND_HELMET", "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS", "DIAMOND_BOOTS"), true);
            case "diamond_tool" -> gear(pick("DIAMOND_SWORD", "DIAMOND_SWORD", "DIAMOND_AXE", "DIAMOND_PICKAXE"), true);
            case "golden_apple" -> new ItemStack(Material.GOLDEN_APPLE, 1 + rnd.nextInt(3));
            case "enchanted_golden_apple" -> new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
            case "strength1" -> potion(PotionType.STRENGTH);
            case "strength2" -> potion(PotionType.STRONG_STRENGTH);
            case "speed1" -> potion(PotionType.SWIFTNESS);
            case "speed2" -> potion(PotionType.STRONG_SWIFTNESS);
            case "lava" -> new ItemStack(Material.LAVA_BUCKET);
            case "water" -> new ItemStack(Material.WATER_BUCKET);
            case "book" -> enchants != null ? enchants.randomBook() : new ItemStack(Material.GOLDEN_APPLE);
            case "bellows" -> {
                if (bellows == null) yield new ItemStack(Material.GOLDEN_APPLE);
                Bukkit.broadcast(Ui.mm("<gold>☄ <white>" + p.getName() + " <gray>found a rare " + GaleBellows.NAME + " <gray>in a supply crate!"));
                yield bellows.item();
            }
            default -> key(p, kind.substring(4));
        };
    }

    /** Admin: roll n crates and describe what came out (for tuning the loot table). */
    public List<String> sample(Player p, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = rnd.nextInt(100) < 55 ? 1 : 2;
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < k; j++) {
                ItemStack it = roll(p);
                String name = it.hasItemMeta() && it.getItemMeta().hasDisplayName()
                        ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName()) : it.getType().name().toLowerCase();
                sb.append(j > 0 ? " + " : "").append(it.getAmount() > 1 ? it.getAmount() + "x " : "").append(name);
                if (!it.getEnchantments().isEmpty()) sb.append(" ").append(it.getEnchantments().entrySet().stream().map(e -> e.getKey().getKey().getKey() + e.getValue()).toList());
                if (it.getItemMeta() instanceof PotionMeta pm) sb.append(" (").append(pm.getBasePotionType()).append(")");
            }
            out.add(sb.toString());
        }
        return out;
    }

    private String pick(String... o) { return o[rnd.nextInt(o.length)]; }

    private ItemStack potion(PotionType type) {
        ItemStack it = new ItemStack(rnd.nextInt(4) == 0 ? Material.SPLASH_POTION : Material.POTION);
        it.editMeta(PotionMeta.class, m -> m.setBasePotionType(type));
        return it;
    }

    private ItemStack key(Player finder, String crate) {
        Bukkit.broadcast(Ui.mm("<gold>☄ <white>" + finder.getName() + " <gray>found a rare " + crates.keyName(crate) + " <gray>in a supply crate!"));
        return crates.key(crate, 1);
    }

    private static final String[] TIER = {"<gray>Rusty", "<white>Salvaged", "<aqua>Forged", "<light_purple>Tempered", "<gold>Masterwork"};

    /** A piece of gear with 0-4 random enchantment "upgrades"; diamond rolls higher. */
    private ItemStack gear(String mat, boolean diamond) {
        Material m = Material.valueOf(mat);
        ItemStack it = new ItemStack(m);
        String n = mat.toLowerCase();
        List<Object[]> pool = new ArrayList<>();   // enchantment, max level
        if (n.endsWith("sword")) { pool.add(new Object[]{Enchantment.SHARPNESS, 5}); pool.add(new Object[]{Enchantment.FIRE_ASPECT, 2}); pool.add(new Object[]{Enchantment.KNOCKBACK, 2}); pool.add(new Object[]{Enchantment.LOOTING, 3}); pool.add(new Object[]{Enchantment.SWEEPING_EDGE, 3}); }
        else if (n.endsWith("axe") && !n.endsWith("pickaxe")) { pool.add(new Object[]{Enchantment.SHARPNESS, 5}); pool.add(new Object[]{Enchantment.EFFICIENCY, 5}); }
        else if (n.endsWith("pickaxe")) { pool.add(new Object[]{Enchantment.EFFICIENCY, 5}); pool.add(new Object[]{Enchantment.FORTUNE, 3}); }
        else {
            pool.add(new Object[]{Enchantment.PROTECTION, 4}); pool.add(new Object[]{Enchantment.PROJECTILE_PROTECTION, 4}); pool.add(new Object[]{Enchantment.FIRE_PROTECTION, 4});
            pool.add(new Object[]{Enchantment.THORNS, 3});
            if (n.endsWith("boots")) { pool.add(new Object[]{Enchantment.FEATHER_FALLING, 4}); pool.add(new Object[]{Enchantment.DEPTH_STRIDER, 3}); }
            if (n.endsWith("helmet")) pool.add(new Object[]{Enchantment.RESPIRATION, 3});
        }
        pool.add(new Object[]{Enchantment.UNBREAKING, 3});
        Collections.shuffle(pool, rnd);
        int count = diamond ? rnd.nextInt(4) + (rnd.nextInt(4) == 0 ? 1 : 0) : rnd.nextInt(3) + (rnd.nextInt(6) == 0 ? 1 : 0);
        int power = 0;
        Set<String> used = new HashSet<>();
        for (int i = 0; i < count && i < pool.size(); i++) {
            Enchantment en = (Enchantment) pool.get(i)[0];
            int max = (Integer) pool.get(i)[1];
            String fam = en.getKey().getKey().endsWith("protection") ? "protection" : en.getKey().getKey();
            if (!used.add(fam)) continue;                     // one protection type per piece, like vanilla
            int lvl = 1 + rnd.nextInt(max);
            if (!diamond) lvl = Math.min(lvl, Math.max(1, max - 1));
            final int fl = lvl;
            it.editMeta(meta -> meta.addEnchant(en, fl, true));
            power += lvl;
        }
        int tier = Math.min(4, power / 3 + (diamond ? 1 : 0));
        String tierName = TIER[tier];
        String base = mat.substring(mat.indexOf('_') + 1).toLowerCase();
        String pretty = (diamond ? "Diamond " : "Iron ") + Character.toUpperCase(base.charAt(0)) + base.substring(1);
        it.editMeta(meta -> {
            meta.displayName(Ui.mm(tierName + " " + pretty));
            meta.lore(List.of(Ui.mm("<dark_gray>Salvaged from a Scrapyard supply crate")));
        });
        return it;
    }

    // ------------------------------------------------------------------ buckets in the arena

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPour(PlayerBucketEmptyEvent e) {
        Block b = e.getBlock();
        if (!inPvp(b.getX(), b.getY(), b.getZ())) return;
        e.setCancelled(false);
        playerFluids.put(key(b), System.currentTimeMillis() + 20_000);
    }

    /** Cobblestone/obsidian made by poured fluids disappears too. */
    @EventHandler(ignoreCancelled = true)
    public void onForm(org.bukkit.event.block.BlockFormEvent e) {
        Block b = e.getBlock();
        if (inPvp(b.getX(), b.getY(), b.getZ())) playerFluids.put(key(b), System.currentTimeMillis() + 20_000);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onScoop(PlayerBucketFillEvent e) {
        Block b = e.getBlock();
        if (!inPvp(b.getX(), b.getY(), b.getZ())) return;
        // only fluids players poured can be picked back up; the arena's lava pits stay put
        e.setCancelled(playerFluids.remove(key(b)) == null);
    }
}
