package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Hopper tiers. Vanilla still does all the moving, so furnace sides, double chests and redstone locks behave normally
 * and an upgraded hopper reaches only what a plain one can. This class just shortens the wait between moves.
 * The tier lives on the hopper itself; each chunk also lists its upgraded hoppers, so loading a chunk needs no scan.
 */
public final class HopperUpgrades implements Listener {
    private static final NamespacedKey UPGRADE = new NamespacedKey(BlackForgeCore.NS, "hopper_upgrade");
    private static final NamespacedKey TIER = new NamespacedKey(BlackForgeCore.NS, "hopper_tier");
    private static final NamespacedKey INDEX = new NamespacedKey(BlackForgeCore.NS, "upgraded_hoppers");
    private static final String[] ROMAN = {"", "I", "II", "III"};

    /** One loaded chunk's upgraded hoppers: packed position -> tier. */
    private static final class Loaded {
        final World world; final int cx, cz; final Map<Integer, Integer> tiers = new HashMap<>();
        /** Hoppers running at their claim's tier: packed position -> tier. Never saved, and never an item's tier. */
        final Map<Integer, Integer> claim = new HashMap<>();
        Loaded(World world, int cx, int cz) { this.world = world; this.cx = cx; this.cz = cz; }
    }
    private record ChunkId(UUID world, long key) {
        static ChunkId of(World w, int cx, int cz) { return new ChunkId(w.getUID(), Chunk.getChunkKey(cx, cz)); }
    }

    private final Map<ChunkId, Loaded> loaded = new HashMap<>();
    private final int[] delays, needed;
    private int maxPerChunk;
    /** Skins per chunk, so a claim-wide tier over a huge hopper wall can't fill a chunk with displays. The rest still run at their tier. */
    private int looksPerChunk;
    private boolean sandbox;
    final int price, essence;
    private final boolean enabled;
    private BiPredicate<Player, Location> access = (p, l) -> false;
    private HopperLook look;
    private final BlackForgeCore plugin;
    /** The tier a spot's claim has bought, or null where hoppers are only upgraded one at a time. */
    private java.util.function.Function<Location, Integer> claimTier;
    private int claimCap = Integer.MAX_VALUE;
    private int claimClock;

    HopperUpgrades(BlackForgeCore plugin) {
        this.plugin = plugin;
        var c = plugin.getConfig();
        delays = ints(c.getIntegerList("hopper-upgrades.delays"), HopperTiers.DELAYS);
        needed = ints(c.getIntegerList("hopper-upgrades.upgrades-needed"), HopperTiers.UPGRADES_NEEDED);
        maxPerChunk = c.getInt("hopper-upgrades.max-per-chunk", 16);
        looksPerChunk = Math.max(0, c.getInt("hopper-looks-max-per-chunk", 32));
        price = c.getInt("hopper-upgrades.price", 20_000);
        essence = c.getInt("hopper-upgrades.essence", 128);
        enabled = c.getBoolean("hopper-upgrades.enabled", true);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (!enabled) return;   // existing hoppers simply run at vanilla speed
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Chunk ch : w.getLoadedChunks()) load(ch);
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        look = new HopperLook(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLooks, 20L, 20L);
    }

    private static int[] ints(List<Integer> list, int[] fallback) {
        if (list.size() != fallback.length) return fallback;
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    void setAccess(BiPredicate<Player, Location> rule) { this.access = rule; }

    /** Upgrades every hopper by its claim's tier. {@code capPerChunk} is a safety limit against lag. */
    void setClaimTier(java.util.function.Function<Location, Integer> rule, int capPerChunk) {
        this.claimTier = rule;
        this.claimCap = capPerChunk;
    }

    /** A claim's tier changed: looks at every loaded chunk again. Rare enough that a full pass is fine. */
    void refreshClaims() {
        if (claimTier == null || !enabled) return;
        for (World w : Bukkit.getWorlds()) for (Chunk ch : w.getLoadedChunks()) refreshClaim(ch);
    }

    /** One chunk was just claimed: hoppers already in it join the claim's tier now, not on the next chunk load. */
    void refreshClaim(Chunk ch) {
        if (claimTier == null || !enabled) return;
        ChunkId id = ChunkId.of(ch.getWorld(), ch.getX(), ch.getZ());
        Loaded l = loaded.get(id);
        if (l == null) l = new Loaded(ch.getWorld(), ch.getX(), ch.getZ());
        scanClaim(ch, l);
        if (!l.tiers.isEmpty() || !l.claim.isEmpty()) loaded.put(id, l);
    }

    private void scanClaim(Chunk ch, Loaded l) {
        l.claim.clear();
        if (claimTier == null) return;
        for (BlockState st : ch.getTileEntities(false))
            if (st instanceof Hopper) addClaim(l, st.getBlock());
    }

    private void addClaim(Loaded l, Block b) {
        int tier = claimTier.apply(b.getLocation());
        if (tier <= 1) return;
        if (l.claim.size() >= claimCap) {
            plugin.getLogger().warning("Chunk " + l.cx + "," + l.cz + " in " + l.world.getName() + " passed the limit of " + claimCap + " claim-upgraded hoppers. The rest run at their own tier.");
            return;
        }
        l.claim.put(HopperTiers.pack(b.getX(), b.getY(), b.getZ()), HopperTiers.clampTier(tier));
    }

    /** The Arena's Foundry: one upgrade raises a tier and isn't used up, so Tier III can be tested with the kit's single upgrade. */
    void setSandbox(int maxPerChunk) {
        this.sandbox = true;
        this.maxPerChunk = maxPerChunk;
        looksPerChunk = HopperTiers.looksCap(looksPerChunk, maxPerChunk);   // every hopper the Foundry allows can show its tier
    }
    boolean enabled() { return enabled; }
    int delay(int tier) { return delays[HopperTiers.clampTier(tier) - 1]; }
    int needed(int tier) { return sandbox && tier < HopperTiers.MAX_TIER ? 1 : HopperTiers.upgradesToLeave(tier, needed); }

    /** Sets a fixed test hopper's tier, if it isn't already. */
    void applyTier(Block b, int tier) { if (tier > 1 && tierAt(b) != tier) setTier(b, tier); }

    // ------------------------------------------------------------------ items

    public static ItemStack upgradeItem() {
        ItemStack it = Ui.item(Material.GOLD_NUGGET, "<gradient:#ffb347:#ff5a1a><bold>Hopper Upgrade</bold></gradient>",
                "<gray>Sneak and right-click a hopper to",
                "<gray>raise its tier. <white>1<gray> takes it to Tier II,", "<white>10<gray> more take it to Tier III.",
                "", "<dark_gray>Buy these at a Base Station");
        it.editMeta(m -> m.getPersistentDataContainer().set(UPGRADE, PersistentDataType.BYTE, (byte) 1));
        return it;
    }

    public static boolean isUpgrade(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(UPGRADE);
    }

    ItemStack hopperItem(int tier) {
        ItemStack it = Ui.item(Material.HOPPER, "<gold><bold>Hopper Tier " + ROMAN[tier],
                "<gray>Moves <white>" + rate(tier) + "<gray> items a second.", "<dark_gray>Keeps its tier when broken.");
        it.editMeta(m -> m.getPersistentDataContainer().set(TIER, PersistentDataType.INTEGER, tier));
        return it;
    }

    private static int itemTier(ItemStack it) {
        if (it == null || it.getType() != Material.HOPPER || !it.hasItemMeta()) return 0;
        return it.getItemMeta().getPersistentDataContainer().getOrDefault(TIER, PersistentDataType.INTEGER, 0);
    }

    String rate(int tier) {
        double r = HopperTiers.itemsPerSecond(delay(tier));
        return r == Math.floor(r) ? String.valueOf((int) r) : String.valueOf(r);
    }

    // ------------------------------------------------------------------ the speed-up

    private void tick() {
        boolean recheck = ++claimClock % 100 == 0;   // a chunk can be unclaimed at any time
        for (Loaded l : loaded.values()) {
            boolean changed = false;
            for (Iterator<Map.Entry<Integer, Integer>> it = l.tiers.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, Integer> en = it.next();
                int pos = en.getKey();
                Block b = l.world.getBlockAt(l.cx << 4 | HopperTiers.localX(pos), HopperTiers.y(pos), l.cz << 4 | HopperTiers.localZ(pos));
                if (!(b.getState(false) instanceof Hopper h)) { it.remove(); changed = true; continue; }   // replaced without a break event
                int wait = delay(en.getValue());
                if (h.getTransferCooldown() > wait) h.setTransferCooldown(wait);
            }
            if (changed) writeIndex(l);
            tickClaim(l, recheck);
        }
    }

    private void tickClaim(Loaded l, boolean recheck) {
        for (Iterator<Map.Entry<Integer, Integer>> it = l.claim.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Integer> en = it.next();
            int pos = en.getKey();
            Block b = blockAt(l, pos);
            if (!(b.getState(false) instanceof Hopper h)) { it.remove(); continue; }
            if (recheck) {
                int now = claimTier.apply(b.getLocation());
                if (now <= 1) { it.remove(); continue; }
                en.setValue(HopperTiers.clampTier(now));
            }
            int tier = en.getValue();
            if (l.tiers.getOrDefault(pos, 1) >= tier) continue;   // its own tier is already as fast
            int wait = delay(tier);
            if (h.getTransferCooldown() > wait) h.setTransferCooldown(wait);
        }
    }

    private void load(Chunk ch) {
        int[] list = ch.getPersistentDataContainer().get(INDEX, PersistentDataType.INTEGER_ARRAY);
        if (list == null) list = new int[0];
        Loaded l = new Loaded(ch.getWorld(), ch.getX(), ch.getZ());
        for (int pos : list) {
            Block b = ch.getBlock(HopperTiers.localX(pos), HopperTiers.y(pos), HopperTiers.localZ(pos));
            if (b.getState(false) instanceof Hopper h) {
                int tier = h.getPersistentDataContainer().getOrDefault(TIER, PersistentDataType.INTEGER, 1);
                if (tier > 1) l.tiers.put(pos, HopperTiers.clampTier(tier));
            }
        }
        if (l.tiers.size() != list.length) writeIndex(l);
        scanClaim(ch, l);
        if (!l.tiers.isEmpty() || !l.claim.isEmpty()) loaded.put(ChunkId.of(l.world, l.cx, l.cz), l);
    }

    private void writeIndex(Loaded l) {
        PersistentDataContainer pdc = l.world.getChunkAt(l.cx, l.cz).getPersistentDataContainer();
        if (l.tiers.isEmpty()) pdc.remove(INDEX);
        else pdc.set(INDEX, PersistentDataType.INTEGER_ARRAY, l.tiers.keySet().stream().mapToInt(Integer::intValue).toArray());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) { if (enabled) load(e.getChunk()); }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) { loaded.remove(ChunkId.of(e.getWorld(), e.getChunk().getX(), e.getChunk().getZ())); }

    private int tierAt(Block b) {
        Loaded l = loaded.get(ChunkId.of(b.getWorld(), b.getX() >> 4, b.getZ() >> 4));
        return l == null ? 1 : l.tiers.getOrDefault(HopperTiers.pack(b.getX(), b.getY(), b.getZ()), 1);
    }

    private int countInChunk(Block b) {
        Loaded l = loaded.get(ChunkId.of(b.getWorld(), b.getX() >> 4, b.getZ() >> 4));
        return l == null ? 0 : l.tiers.size();
    }

    private static Block blockAt(Loaded l, int pos) {
        return l.world.getBlockAt(l.cx << 4 | HopperTiers.localX(pos), HopperTiers.y(pos), l.cz << 4 | HopperTiers.localZ(pos));
    }

    /** Puts each upgraded hopper's skin on, at the tier it really runs at, and takes it off any that have gone. */
    private void refreshLooks() {
        if (look == null || !look.active()) return;
        java.util.Set<Block> now = new java.util.HashSet<>();
        for (Loaded l : loaded.values()) {
            Map<Integer, Integer> all = new HashMap<>(l.claim);
            all.putAll(l.tiers);
            int shown = 0;
            // bought one at a time first: those are the ones a player paid to see
            List<Map.Entry<Integer, Integer>> order = new java.util.ArrayList<>(all.entrySet());
            order.sort(java.util.Comparator.comparing(e -> !l.tiers.containsKey(e.getKey())));
            for (Map.Entry<Integer, Integer> e : order) {
                if (shown >= looksPerChunk) break;
                int tier = HopperTiers.effective(l.tiers.getOrDefault(e.getKey(), 1), l.claim.getOrDefault(e.getKey(), 1));
                Block b = blockAt(l, e.getKey());
                if (tier < 2 || b.getType() != Material.HOPPER) continue;
                now.add(b);
                look.dress(b, tier);
                shown++;
            }
        }
        look.keepOnly(now);
    }

    private void setTier(Block b, int tier) {
        if (!(b.getState() instanceof Hopper h)) return;
        h.getPersistentDataContainer().set(TIER, PersistentDataType.INTEGER, tier);
        h.update(true, false);
        Loaded l = loaded.computeIfAbsent(ChunkId.of(b.getWorld(), b.getX() >> 4, b.getZ() >> 4), k -> new Loaded(b.getWorld(), b.getX() >> 4, b.getZ() >> 4));
        l.tiers.put(HopperTiers.pack(b.getX(), b.getY(), b.getZ()), tier);
        writeIndex(l);
    }

    private void forget(Block b) {
        if (look != null) look.remove(b);
        Loaded l = loaded.get(ChunkId.of(b.getWorld(), b.getX() >> 4, b.getZ() >> 4));
        if (l != null && l.tiers.remove(HopperTiers.pack(b.getX(), b.getY(), b.getZ())) != null) writeIndex(l);
    }

    private int claimTierAt(Block b) {
        Loaded l = loaded.get(ChunkId.of(b.getWorld(), b.getX() >> 4, b.getZ() >> 4));
        return l == null ? 1 : l.claim.getOrDefault(HopperTiers.pack(b.getX(), b.getY(), b.getZ()), 1);
    }

    // ------------------------------------------------------------------ using an upgrade

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (!isUpgrade(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || !e.getPlayer().isSneaking() || e.getClickedBlock().getType() != Material.HOPPER) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (e.getHand() != EquipmentSlot.HAND) {
            if (!isUpgrade(p.getInventory().getItemInMainHand())) p.sendActionBar(Ui.mm("<gray>Hold the Hopper Upgrade in your main hand."));
            return;
        }
        Block b = e.getClickedBlock();
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>Hopper upgrades are switched off right now.")); return; }
        if (!access.test(p, b.getLocation())) { p.sendActionBar(Ui.mm("<red>You can only upgrade hoppers on your own land.")); return; }
        int tier = tierAt(b);
        if (tier >= HopperTiers.MAX_TIER) { p.sendActionBar(Ui.mm("<gold>This hopper is already Tier " + ROMAN[tier] + ".")); return; }
        // it wouldn't get any faster, so don't let a player spend upgrades on it
        int claim = claimTierAt(b);
        if (claim > tier) { p.sendActionBar(Ui.mm("<gold>Your base already runs this hopper at Tier " + ROMAN[claim] + ". <gray>Keep your upgrades.")); return; }
        int need = needed(tier);
        int have = count(p);
        if (have < need) { p.sendActionBar(Ui.mm("<red>Tier " + ROMAN[tier + 1] + " takes " + need + " Hopper Upgrades. You have " + have + ".")); return; }
        if (tier == 1 && countInChunk(b) >= maxPerChunk) { p.sendActionBar(Ui.mm("<red>This chunk already has " + maxPerChunk + " upgraded hoppers.")); return; }
        if (!sandbox) take(p, need);
        setTier(b, tier + 1);
        b.getWorld().spawnParticle(Particle.WAX_OFF, b.getLocation().add(0.5, 0.8, 0.5), 25, 0.35, 0.35, 0.35);
        p.playSound(b.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1.2f);
        p.sendActionBar(Ui.mm("<gold><bold>Hopper Tier " + ROMAN[tier + 1] + "</bold> <gray>- <white>" + rate(tier + 1) + "<gray> items a second"));
    }

    private static int count(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (isUpgrade(it)) n += it.getAmount();
        return n;
    }

    private static void take(Player p, int n) {
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && n > 0; i++) {
            if (!isUpgrade(inv[i])) continue;
            int use = Math.min(n, inv[i].getAmount());
            inv[i].setAmount(inv[i].getAmount() - use);
            n -= use;
        }
        p.getInventory().setStorageContents(inv);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.HOPPER || e.getInventory().getLocation() == null) return;
        Block at = e.getInventory().getLocation().getBlock();
        int tier = HopperTiers.effective(tierAt(at), claimTierAt(at));
        if (tier > 1 && e.getPlayer() instanceof Player p)
            p.sendActionBar(Ui.mm("<gold>Hopper Tier " + ROMAN[tier] + " <gray>- <white>" + rate(tier) + "<gray> items a second"));
    }

    // ------------------------------------------------------------------ placing and breaking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceCheck(BlockPlaceEvent e) {
        if (itemTier(e.getItemInHand()) <= 1) return;
        if (countInChunk(e.getBlockPlaced()) >= maxPerChunk) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Ui.mm("<red>This chunk already has " + maxPerChunk + " upgraded hoppers."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        int tier = itemTier(e.getItemInHand());
        if (tier > 1) setTier(e.getBlockPlaced(), HopperTiers.clampTier(tier));
        Block b = e.getBlockPlaced();
        if (claimTier != null && enabled && b.getType() == Material.HOPPER && claimTier.apply(b.getLocation()) > 1) {
            Loaded l = loaded.computeIfAbsent(ChunkId.of(b.getWorld(), b.getX() >> 4, b.getZ() >> 4), k -> new Loaded(b.getWorld(), b.getX() >> 4, b.getZ() >> 4));
            addClaim(l, b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.HOPPER) return;
        Loaded inChunk = loaded.get(ChunkId.of(b.getWorld(), b.getX() >> 4, b.getZ() >> 4));
        if (inChunk != null) inChunk.claim.remove(HopperTiers.pack(b.getX(), b.getY(), b.getZ()));
        if (look != null) look.remove(b);
        int tier = tierAt(b);
        if (tier <= 1) return;
        forget(b);
        // a hopper needs a pickaxe to drop at all; don't let a wrong tool destroy an expensive tier
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE && b.getDrops(e.getPlayer().getInventory().getItemInMainHand()).isEmpty())
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), hopperItem(tier));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        BlockState st = e.getBlockState();
        if (!(st instanceof Hopper h)) return;
        int tier = h.getPersistentDataContainer().getOrDefault(TIER, PersistentDataType.INTEGER, 1);
        if (tier <= 1) return;
        for (Item drop : e.getItems()) {
            ItemStack it = drop.getItemStack();
            if (it.getType() == Material.HOPPER && it.getAmount() == 1 && !it.hasItemMeta()) {   // one hopper, one tier: no duplicates
                drop.setItemStack(hopperItem(HopperTiers.clampTier(tier)));
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> b.getType() == Material.HOPPER && tierAt(b) > 1); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> b.getType() == Material.HOPPER && tierAt(b) > 1); }

    /** A Wither chews through blocks without an explosion or a break event, and the tier would be lost with no drop. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (e.getBlock().getType() == Material.HOPPER && tierAt(e.getBlock()) > 1) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) { if (isUpgrade(e.getItem()) || itemTier(e.getItem()) > 1) e.setCancelled(true); }

    /** Upgrades are gold nuggets and tiers are hoppers underneath: neither may be used up as a crafting ingredient. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix())
            if (isUpgrade(it) || itemTier(it) > 1) { e.getInventory().setResult(null); return; }
    }
}
