package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.player.*;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Chunk claiming: /claim protects the chunk you stand in. Owners can trust friends.
 * Claim allowance grows with playtime.
 */
public final class Claims implements Listener {
    private final BlackForgeCore plugin;
    private final File file;
    private final boolean qol;
    private final Map<UUID, Confirmation> confirmations = new HashMap<>();
    private Set<UUID> pendingRtpTips = new HashSet<>();
    private Map<String, UUID> owners = new HashMap<>();          // "world;cx;cz" -> owner
    private Map<UUID, Set<UUID>> trusted = new HashMap<>();
    private final Map<UUID, String> lastArea = new HashMap<>();
    private final Set<UUID> bypass = new HashSet<>();
    private Ranks ranks;
    private java.util.function.Consumer<Chunk> onClaimed = ch -> {};

    void setRanks(Ranks r) { this.ranks = r; }

    /** Runs after a chunk is claimed and saved, so blocks already there can pick up the new owner's perks. */
    void setOnClaimed(java.util.function.Consumer<Chunk> hook) { this.onClaimed = hook; }

    static final int BASE_CLAIMS = 6, PER_HOURS = 2, MAX_CLAIMS = 120;   // +PER_HOURS claims per hour played

    Claims(BlackForgeCore plugin) {
        this.plugin = plugin;
        qol = plugin.getConfig().getBoolean("survival-claims-base-qol.enabled", false);
        file = new File(plugin.getDataFolder(), "claims.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var cs = y.getConfigurationSection("chunks");
        if (cs != null) for (String k : cs.getKeys(false)) owners.put(k.replace('|', ';'), UUID.fromString(cs.getString(k)));
        var ts = y.getConfigurationSection("trusted");
        if (ts != null) for (String k : ts.getKeys(false)) {
            Set<UUID> s = new HashSet<>();
            for (String id : ts.getStringList(k)) s.add(UUID.fromString(id));
            trusted.put(UUID.fromString(k), s);
        }
        for (String id : y.getStringList("pending-rtp-tips")) pendingRtpTips.add(UUID.fromString(id));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Commit on the server thread so permission checks only see a saved snapshot. */
    private boolean commit(Player player, Map<String, UUID> nextOwners, Map<UUID, Set<UUID>> nextTrusted) {
        return commit(player, nextOwners, nextTrusted, pendingRtpTips);
    }

    private boolean commit(Player player, Map<String, UUID> nextOwners, Map<UUID, Set<UUID>> nextTrusted,
                           Set<UUID> nextTips) {
        Path target = file.toPath();
        Path temp = null;
        try {
            YamlConfiguration y = new YamlConfiguration();
            nextOwners.forEach((k, v) -> y.set("chunks." + k.replace(';', '|'), v.toString()));
            nextTrusted.forEach((k, v) -> y.set("trusted." + k, v.stream().map(UUID::toString).toList()));
            if (!nextTips.isEmpty()) y.set("pending-rtp-tips", nextTips.stream().map(UUID::toString).toList());
            temp = Files.createTempFile(target.getParent(), "claims-", ".tmp");
            y.save(temp.toFile());
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException | SecurityException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not save claim changes", e);
            if (player != null) msg(player, "<red>Couldn't save your claim changes. Please try again.");
            return false;
        }
        owners = nextOwners;
        trusted = nextTrusted;
        pendingRtpTips = nextTips;
        return true;
    }

    static String key(Chunk ch) { return ch.getWorld().getName() + ";" + ch.getX() + ";" + ch.getZ(); }
    static String key(World w, int cx, int cz) { return w.getName() + ";" + cx + ";" + cz; }

    public UUID ownerAt(Location l) { return owners.get(key(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4)); }

    /** May this player build/interact here? */
    public boolean canUse(Player p, Location l) {
        if (bypass.contains(p.getUniqueId())) return true;
        UUID owner = ownerAt(l);
        if (owner == null) return true;
        return owner.equals(p.getUniqueId()) || trusted.getOrDefault(owner, Set.of()).contains(p.getUniqueId());
    }

    private UUID stockOwner(Inventory inventory) {
        Location at = inventory instanceof DoubleChestInventory chest ? chest.getLeftSide().getLocation() : inventory.getLocation();
        return at == null ? null : ownerAt(at);
    }

    // Wilderness is a separate owner. An unclaimed half cannot authorize a claimed half.
    boolean ownsStock(UUID owner, Inventory stock) {
        if (stock instanceof DoubleChestInventory chest)
            return ownsStock(owner, chest.getLeftSide()) && ownsStock(owner, chest.getRightSide());
        return Objects.equals(owner, stockOwner(stock));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        UUID owner = stockOwner(e.getSource());
        if (!ownsStock(owner, e.getSource()) || !ownsStock(owner, e.getDestination())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !(e.getInventory() instanceof DoubleChestInventory chest)) return;
        Location left = chest.getLeftSide().getLocation(), right = chest.getRightSide().getLocation();
        if ((left != null && deny(p, left)) || (right != null && deny(p, right))) e.setCancelled(true);
    }

    int count(UUID owner) { int n = 0; for (UUID u : owners.values()) if (u.equals(owner)) n++; return n; }

    int allowance(Player p) {
        int hours = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600;
        return Math.min(MAX_CLAIMS, BASE_CLAIMS + hours * PER_HOURS) + (ranks == null ? 0 : ranks.bonusClaims(p));
    }

    private static String name(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString().substring(0, 8);
    }

    private boolean nearProtectedArea(int cx, int cz) {
        int x = (cx << 4) + 8, z = (cz << 4) + 8;
        return Math.hypot(x, z) < 96 || Math.hypot(x - SurvivalLayout.KOTH_X, z - SurvivalLayout.KOTH_Z) < 96
                || (Math.abs(x - SurvivalLayout.PVP_X) < SurvivalLayout.PVP_HALF + 48 && Math.abs(z - SurvivalLayout.PVP_Z) < SurvivalLayout.PVP_HALF + 48);
    }

    enum LandRelation { WILDERNESS, OWNED, TRUSTED, FOREIGN }

    enum ClaimDenial { NONE, OCCUPIED, MAIN_END_ISLAND, PROTECTED_AREA, ALLOWANCE }

    record ClaimEligibility(String world, int chunkX, int chunkZ, UUID owner, LandRelation relation,
                            ClaimDenial denial, int used, int allowance) {
        boolean eligible() { return denial == ClaimDenial.NONE; }
    }

    /** Checks the proposed location without loading chunks or mutating claim state. */
    ClaimEligibility claimEligibility(Player player, Location at) {
        World world = at.getWorld();
        int cx = at.getBlockX() >> 4, cz = at.getBlockZ() >> 4;
        UUID playerId = player.getUniqueId(), owner = owners.get(key(world, cx, cz));
        LandRelation relation = owner == null ? LandRelation.WILDERNESS
                : owner.equals(playerId) ? LandRelation.OWNED
                : trusted.getOrDefault(owner, Set.of()).contains(playerId) ? LandRelation.TRUSTED : LandRelation.FOREIGN;
        int used = count(playerId), max = allowance(player);
        ClaimDenial denial = ClaimDenial.NONE;
        if (owner != null) denial = ClaimDenial.OCCUPIED;
        else if (world.getEnvironment() == World.Environment.THE_END && Math.hypot(at.getX(), at.getZ()) < 300)
            denial = ClaimDenial.MAIN_END_ISLAND;
        else if (nearProtectedArea(cx, cz)) denial = ClaimDenial.PROTECTED_AREA;
        else if (used >= max && !player.hasPermission("blackforge.admin")) denial = ClaimDenial.ALLOWANCE;
        return new ClaimEligibility(world.getName(), cx, cz, owner, relation, denial, used, max);
    }

    boolean qolEnabled() { return qol; }

    void noteNewPlayer(Player p) {
        UUID id = p.getUniqueId();
        if (!qol || pendingRtpTips.contains(id) || count(id) != 0) return;
        Set<UUID> next = new HashSet<>(pendingRtpTips);
        next.add(id);
        commit(null, owners, trusted, next);
    }

    void onRtpLanding(Player p, Location at) {
        UUID id = p.getUniqueId();
        if (!qol || !pendingRtpTips.contains(id)) return;
        ClaimEligibility claim = claimEligibility(p, at);
        if (!claim.eligible() || claim.used() != 0) return;
        if (!commit(null, owners, trusted, withoutRtpTip(id))) return;
        msg(p, "<gold><bold>Ready to build your first base?</bold> <gray>Find a spot you like and type <white>/claim<gray>. "
                + "You’ll see the exact chunk you’re about to protect. Check the outline, then click <yellow>Confirm Claim<gray>. "
                + "If your build needs more room, step into the next chunk and repeat. Type <white>/claim help<gray> anytime for borders, trust, and more.");
    }

    private Set<UUID> withoutRtpTip(UUID id) {
        if (!pendingRtpTips.contains(id)) return pendingRtpTips;
        Set<UUID> next = new HashSet<>(pendingRtpTips);
        next.remove(id);
        return next;
    }

    // ------------------------------------------------------------------ commands

    private static final long CONFIRM_NANOS = 20_000_000_000L;

    private record Confirmation(String code, String action, String chunkKey, UUID owner, Set<String> chunks, long expiresAt) {
        boolean expired() { return System.nanoTime() - expiresAt >= 0; }
    }

    private Set<String> ownedChunks(UUID owner) {
        Set<String> chunks = new HashSet<>();
        owners.forEach((k, v) -> { if (v.equals(owner)) chunks.add(k); });
        return chunks;
    }

    private void offerConfirmation(Player p, String action, Chunk chunk, UUID owner, Set<String> chunks, String button) {
        Confirmation preview = new Confirmation(UUID.randomUUID().toString().substring(0, 8), action,
                chunk == null ? null : key(chunk), owner, chunks, System.nanoTime() + CONFIRM_NANOS);
        confirmations.put(p.getUniqueId(), preview);
        String command = "/claim confirm " + preview.code();
        p.sendMessage(Ui.mm("<dark_gray>[<gold>Claims<dark_gray>] ")
                .append(Ui.mm("<yellow><underlined>[" + button + "]</underlined>").clickEvent(ClickEvent.runCommand(command)))
                .append(Ui.mm(" <gray>or type <white>" + command + "<gray>. Expires in 20 seconds. <white>/claim cancel")));
        if (chunk != null) show(p, chunk, preview);
    }

    private static String bounds(Chunk chunk) {
        int x = chunk.getX() << 4, z = chunk.getZ() << 4;
        return chunk.getWorld().getName() + " · X " + x + " to " + (x + 15) + ", Z " + z + " to " + (z + 15) + " · full height";
    }

    private void guide(Player p) {
        msg(p, "<gold><bold>Claiming land in BlackForgeMC Survival</bold>");
        msg(p, "<yellow>1. Find a spot. <gray>Use <white>/rtp<gray> to head into the wild. You can claim eligible wilderness away from protected areas.");
        msg(p, "<yellow>2. Preview your land. <gray>Stand where you want to build and type <white>/claim<gray>. Each claim covers one 16 × 16 chunk "
                + "from the bottom of the world to the top. Check the outline before you confirm.");
        msg(p, "<yellow>3. Confirm the claim. <gray>Click <white>Confirm Claim<gray> or type the confirmation command shown in chat. "
                + "You’ll see how many claims you have left. Only you and players you trust can build in your claim.");
        msg(p, "<yellow>Need more room? <gray>Walk into the next chunk and type <white>/claim<gray> again. "
                + "<white>/claim info<gray> shows your allowance. You earn more as you play.");
        msg(p, "<yellow>See your borders. <gray>Use <white>/claim show<gray> for the current outline, <white>/claim map<gray> for a chat map, "
                + "or <white>/claim list<gray> to find your own chunks.");
        msg(p, "<yellow>Build with a friend. <gray>Use <white>/trust \\<player><gray> to give them access to all your claims. "
                + "Use <white>/untrust \\<player><gray> to remove access. See your trust list in <white>/claim info<gray>.");
        msg(p, "<yellow>Give up land. <gray>Stand in the chunk and type <white>/unclaim<gray>. Review the location before confirming. "
                + "Other players may gain access to the blocks and containers there.");
    }

    public boolean command(Player p, String label, String[] a) {
        String sub = label.equalsIgnoreCase("claim") ? (a.length > 0 ? a[0].toLowerCase() : "here")
                : label.equalsIgnoreCase("unclaim") ? "unclaim" : label.equalsIgnoreCase("trust") ? "trust" : label.equalsIgnoreCase("untrust") ? "untrust" : "help";
        String arg = label.equalsIgnoreCase("claim") ? (a.length > 1 ? a[1] : null) : (a.length > 0 ? a[0] : null);
        Location at = p.getLocation();
        Chunk ch = at.getChunk();
        String k = key(ch);
        Confirmation confirmed = null;
        if (qol && sub.equals("cancel")) {
            boolean cancelled = confirmations.remove(p.getUniqueId()) != null;
            msg(p, cancelled ? "<yellow>Claim preview cancelled." : "<gray>You have no claim preview.");
            return true;
        }
        if (qol && sub.equals("confirm")) {
            confirmed = confirmations.get(p.getUniqueId());
            if (confirmed == null || confirmed.expired()) {
                confirmations.remove(p.getUniqueId());
                msg(p, "<red>There’s no active preview. Start again with the original command.");
                return true;
            }
            if (!confirmed.code().equals(arg)) {
                msg(p, "<red>That code doesn’t match your current preview. Use the newest confirmation.");
                return true;
            }
            confirmations.remove(p.getUniqueId());
            if (confirmed.chunkKey() != null && !confirmed.chunkKey().equals(k)) {
                msg(p, "<red>You’ve left the previewed chunk. Start again with the original command.");
                return true;
            }
            sub = confirmed.action();
        } else if (qol && (sub.equals("here") || sub.equals("unclaim") || sub.equals("unclaimall"))) {
            confirmations.remove(p.getUniqueId());
        }
        switch (sub) {
            case "here" -> {
                ClaimEligibility claim = claimEligibility(p, at);
                if (!claim.eligible()) {
                    switch (claim.denial()) {
                        case OCCUPIED -> {
                            msg(p, claim.relation() == LandRelation.OWNED ? "<yellow>You already own this chunk."
                                    : "<red>This chunk belongs to <white>" + name(claim.owner()) + "<red>.");
                            show(p, ch);
                        }
                        case MAIN_END_ISLAND -> msg(p, "<red>You can't claim the main End island.");
                        case PROTECTED_AREA -> msg(p, qol ? "<red>This area is protected. <white>/rtp<gray> takes you to the wild."
                                : "<red>Too close to spawn or the KOTH arena to claim.");
                        case ALLOWANCE -> msg(p, "<red>You've used all <white>" + claim.allowance()
                                + "<red> claims. Play more to earn extra (<white>+" + PER_HOURS + "<red> per hour).");
                        case NONE -> throw new IllegalStateException("Eligible claim denied");
                    }
                    return true;
                }
                if (qol && confirmed == null) {
                    int remaining = Math.max(0, claim.allowance() - claim.used() - 1);
                    msg(p, "<gold>Claim preview <gray>· <white>" + bounds(ch));
                    msg(p, "<green>You can claim here. <gray>Used <white>" + claim.used() + "/" + claim.allowance()
                            + "<gray>. After confirming, <white>" + remaining + "<gray> remaining.");
                    offerConfirmation(p, sub, ch, null, Set.of(), "Confirm Claim");
                    return true;
                }
                Map<String, UUID> next = new HashMap<>(owners);
                next.put(k, p.getUniqueId());
                if (!commit(p, next, trusted, withoutRtpTip(p.getUniqueId()))) return true;
                onClaimed.accept(ch);
                msg(p, "<green>✔ Chunk claimed! <gray>(" + (claim.used() + 1) + "/" + claim.allowance() + ") Only you and trusted players can build here.");
                if (qol) msg(p, "<gray>Claims remaining: <white>" + Math.max(0, claim.allowance() - claim.used() - 1));
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
                show(p, ch);
            }
            case "unclaim" -> {
                UUID cur = owners.get(k);
                if (cur == null) { msg(p, "<gray>This chunk isn't claimed."); return true; }
                if (!cur.equals(p.getUniqueId()) && !p.hasPermission("blackforge.admin")) { msg(p, "<red>That's not your chunk."); return true; }
                if (qol && confirmed == null) {
                    msg(p, "<gold>Unclaim preview <gray>· <white>" + bounds(ch));
                    msg(p, "<yellow>Other players may gain access to this land and containers, and automation may stop.");
                    offerConfirmation(p, sub, ch, cur, Set.of(), "Confirm Unclaim");
                    return true;
                }
                if (confirmed != null && !cur.equals(confirmed.owner())) {
                    msg(p, "<red>Ownership changed. Run /unclaim again to review this chunk.");
                    return true;
                }
                Map<String, UUID> next = new HashMap<>(owners);
                next.remove(k);
                if (!commit(p, next, trusted)) return true;
                msg(p, "<yellow>Chunk unclaimed.");
            }
            case "unclaimall" -> {
                Set<String> chunks = ownedChunks(p.getUniqueId());
                if (qol && confirmed == null) {
                    if (chunks.isEmpty()) { msg(p, "<gray>You have no claimed chunks."); return true; }
                    msg(p, "<yellow>Release all <white>" + chunks.size() + "<yellow> chunks across all worlds? "
                            + "Other players may gain access to the land and containers, and automation may stop.");
                    offerConfirmation(p, sub, null, null, chunks, "Confirm Unclaim All");
                    return true;
                }
                if (confirmed != null && !chunks.equals(confirmed.chunks())) {
                    msg(p, "<red>Your claims changed. Run /claim unclaimall again to review the new total.");
                    return true;
                }
                Map<String, UUID> next = new HashMap<>(owners);
                next.keySet().removeAll(chunks);
                int n = chunks.size();
                if (!commit(p, next, trusted)) return true;
                msg(p, "<yellow>Unclaimed <white>" + n + "<yellow> chunks.");
            }
            case "trust", "untrust" -> {
                if (arg == null) { msg(p, "<gray>Usage: /" + sub + " <player>"); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(arg);
                if (t == null) t = Bukkit.getPlayerExact(arg);
                if (t == null) { msg(p, "<red>Never seen a player called " + arg + "."); return true; }
                UUID owner = p.getUniqueId();
                Map<UUID, Set<UUID>> next = new HashMap<>(trusted);
                Set<UUID> members = new HashSet<>(trusted.getOrDefault(owner, Set.of()));
                if (sub.equals("trust")) members.add(t.getUniqueId()); else members.remove(t.getUniqueId());
                next.put(owner, members);
                if (!commit(p, owners, next)) return true;
                msg(p, sub.equals("trust") ? "<green>" + t.getName() + " can now build in all your claims." : "<yellow>" + t.getName() + " is no longer trusted.");
            }
            case "info" -> {
                UUID cur = owners.get(k);
                msg(p, "<gray>Chunk <white>" + ch.getX() + ", " + ch.getZ() + "<gray>: " + (cur == null ? "<green>Wilderness" : "owned by <white>" + name(cur)));
                msg(p, "<gray>Your claims: <white>" + count(p.getUniqueId()) + "/" + allowance(p)
                        + " <gray>• Trusted: <white>" + String.join(", ", trusted.getOrDefault(p.getUniqueId(), Set.of()).stream().map(Claims::name).toList()));
                show(p, ch);
            }
            case "map" -> map(p);
            case "show" -> show(p, ch);
            case "list" -> {
                List<String> mine = new ArrayList<>();
                for (String owned : ownedChunks(p.getUniqueId())) {
                    String[] parts = owned.split(";");
                    int x = (Integer.parseInt(parts[1]) << 4) + 8, z = (Integer.parseInt(parts[2]) << 4) + 8;
                    mine.add((qol ? parts[0] + " " : "") + "(" + x + ", " + z + ")");
                }
                if (qol) Collections.sort(mine);
                msg(p, "<gray>Your claims (" + mine.size() + "/" + allowance(p) + "): <white>" + (mine.isEmpty() ? "none" : String.join(" ", mine)));
            }
            case "bypass" -> {
                if (!p.hasPermission("blackforge.admin")) return true;
                if (!bypass.remove(p.getUniqueId())) bypass.add(p.getUniqueId());
                msg(p, "<gold>Claim bypass " + (bypass.contains(p.getUniqueId()) ? "ON" : "OFF"));
            }
            default -> {
                if (qol) { guide(p); return true; }
                msg(p, "<gold><bold>Land claims</bold> <gray>- protect your builds, one chunk at a time");
                msg(p, "<yellow>/claim <gray>claim this chunk  <yellow>/unclaim <gray>release it");
                msg(p, "<yellow>/claim map <gray>see nearby claims  <yellow>/claim show <gray>see chunk edges");
                msg(p, "<yellow>/trust <name> <gray>let a friend build  <yellow>/untrust <name>");
                msg(p, "<yellow>/claim info <gray>  <yellow>/claim list <gray>  <yellow>/claim unclaimall");
            }
        }
        return true;
    }

    private void msg(Player p, String s) { p.sendMessage(Ui.mm("<dark_gray>[<gold>Claims<dark_gray>] " + s)); }

    private void map(Player p) {
        Chunk c = p.getLocation().getChunk();
        msg(p, "<gray>Claim map <dark_gray>(<green>■ <gray>yours <aqua>■ <gray>trusted <red>■ <gray>others <dark_gray>■ <gray>free <yellow>✦ <gray>you<dark_gray>)");
        float yaw = (p.getYaw() % 360 + 360) % 360;
        String facing = yaw >= 315 || yaw < 45 ? "S" : yaw < 135 ? "W" : yaw < 225 ? "N" : "E";
        for (int dz = -5; dz <= 5; dz++) {
            StringBuilder sb = new StringBuilder(dz == 0 ? "<gray>W " : "<gray>  ");
            for (int dx = -9; dx <= 9; dx++) {
                if (dx == 0 && dz == 0) { sb.append("<yellow>✦"); continue; }
                UUID o = owners.get(key(c.getWorld(), c.getX() + dx, c.getZ() + dz));
                if (o == null) sb.append("<dark_gray>■");
                else if (o.equals(p.getUniqueId())) sb.append("<green>■");
                else if (trusted.getOrDefault(o, Set.of()).contains(p.getUniqueId())) sb.append("<aqua>■");
                else sb.append("<red>■");
            }
            if (dz == 0) sb.append(" <gray>E");
            p.sendMessage(Ui.mm(sb.toString()));
        }
        p.sendMessage(Ui.mm("<gray>North is up. You're facing <white>" + facing));
    }

    /**
     * Outline a claim with particles for 10 seconds. Touching chunks with the same owner are one claim, so the
     * outline follows the outside edge of the whole connected area (no lines between your own chunks), with a
     * taller post at every corner. An unclaimed chunk is outlined on its own.
     */
    void show(Player p, Chunk ch) { show(p, ch, null); }

    private void show(Player p, Chunk ch, Confirmation preview) {
        World w = ch.getWorld();
        UUID o = owners.get(key(ch));
        Color col = preview != null ? Color.YELLOW : o == null ? Color.GRAY : o.equals(p.getUniqueId()) ? Color.LIME : Color.RED;
        Particle.DustOptions dust = new Particle.DustOptions(col, 1.4f);
        // the connected area: flood-fill neighbouring chunks with the same owner (capped so a huge claim stays cheap)
        Set<Long> area = new HashSet<>();
        Deque<long[]> todo = new ArrayDeque<>();
        todo.add(new long[]{ch.getX(), ch.getZ()});
        area.add(pack(ch.getX(), ch.getZ()));
        while (preview == null && o != null && !todo.isEmpty() && area.size() < 400) {
            long[] c = todo.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = (int) c[0] + d[0], nz = (int) c[1] + d[1];
                if (o.equals(owners.get(key(w, nx, nz))) && area.add(pack(nx, nz))) todo.add(new long[]{nx, nz});
            }
        }
        // outside edges only: a chunk side is drawn when the chunk across it isn't part of the area
        List<int[]> edges = new ArrayList<>();   // x0, z0, dx, dz (16 blocks long)
        Set<Long> corners = new HashSet<>();
        for (long k : area) {
            int cx = (int) (k >> 32), cz = (int) k, x0 = cx << 4, z0 = cz << 4;
            if (!area.contains(pack(cx, cz - 1))) edges.add(new int[]{x0, z0, 1, 0});
            if (!area.contains(pack(cx, cz + 1))) edges.add(new int[]{x0, z0 + 16, 1, 0});
            if (!area.contains(pack(cx - 1, cz))) edges.add(new int[]{x0, z0, 0, 1});
            if (!area.contains(pack(cx + 1, cz))) edges.add(new int[]{x0 + 16, z0, 0, 1});
        }
        for (int[] e : edges) { corners.add(pack(e[0], e[1])); corners.add(pack(e[0] + 16 * e[2], e[1] + 16 * e[3])); }
        // a corner is only a real corner if the outline turns there (not where two straight edges meet)
        Map<Long, Integer> dirs = new HashMap<>();
        for (int[] e : edges) for (long c : new long[]{pack(e[0], e[1]), pack(e[0] + 16 * e[2], e[1] + 16 * e[3])}) dirs.merge(c, e[2] == 1 ? 1 : 2, (x, y) -> x | y);
        corners.removeIf(c -> dirs.get(c) != 3);
        if (area.size() > 1) msg(p, (o.equals(p.getUniqueId()) ? "<green>Your claim" : "<red>" + name(o) + "'s claim") + " <gray>here is <white>" + area.size() + " chunks<gray> - outlined for 10 seconds.");
        final int[] runs = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline() || p.getWorld() != w
                    || (preview == null ? ++runs[0] > 20 : preview.expired() || confirmations.get(p.getUniqueId()) != preview)) {
                task.cancel();
                return;
            }
            Location at = p.getLocation();
            double y = at.getY() + 1;
            for (int[] e : edges) {
                if (Math.abs(e[0] + 8 * e[2] - at.getX()) > 120 || Math.abs(e[1] + 8 * e[3] - at.getZ()) > 120) continue;
                for (int i = 0; i <= 16; i++)
                    for (double dy = -1; dy <= 2; dy += 1.5)
                        p.spawnParticle(Particle.DUST, e[0] + i * e[2], y + dy, e[1] + i * e[3], 1, dust);
            }
            for (long c : corners) {
                int x = (int) (c >> 32), z = (int) c;
                if (Math.abs(x - at.getX()) > 120 || Math.abs(z - at.getZ()) > 120) continue;
                for (double dy = -2; dy <= 6; dy += 0.5) p.spawnParticle(Particle.DUST, x, y + dy, z, 1, dust);
            }
        }, 0L, 10L);
    }

    private static long pack(int a, int b) { return ((long) a << 32) | (b & 0xffffffffL); }

    // ------------------------------------------------------------------ protection

    private boolean deny(Player p, Location l) {
        if (canUse(p, l)) return false;
        p.sendActionBar(Ui.mm("<red>This land is claimed by <white>" + name(ownerAt(l))));
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        boolean physical = e.getAction() == org.bukkit.event.block.Action.PHYSICAL;
        boolean use = e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && (b.getType().isInteractable() || e.getItem() != null);
        if ((physical || use) && deny(e.getPlayer(), b.getLocation())) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            if (physical) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (t instanceof Villager || t instanceof WanderingTrader || t instanceof Player) return;
        if (deny(e.getPlayer(), t.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) { if (deny(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true); }

    private Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr) { ProjectileSource s = pr.getShooter(); if (s instanceof Player p) return p; }
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player || e.getEntity() instanceof Monster) return;
        Player p = attacker(e.getDamager());
        if (p != null && deny(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        if (p != null ? deny(p, e.getEntity().getLocation()) : ownerAt(e.getEntity().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> ownerAt(b.getLocation()) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> ownerAt(b.getLocation()) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player p) { if (deny(p, e.getBlock().getLocation())) e.setCancelled(true); }
        else if (!(e.getEntity() instanceof FallingBlock) && ownerAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (ownerAt(e.getBlock().getLocation()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }
        else if (e.getCause() != BlockIgniteEvent.IgniteCause.LIGHTNING && ownerAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    private boolean crosses(Location from, Location to) {
        return !Objects.equals(ownerAt(from), ownerAt(to)) && ownerAt(to) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (crosses(e.getBlock().getLocation(), e.getToBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        Location from = e.getBlock().getLocation();
        for (Block b : e.getBlocks())
            if (crosses(from, b.getRelative(e.getDirection()).getLocation())) { e.setCancelled(true); return; }
        if (crosses(from, e.getBlock().getRelative(e.getDirection()).getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        Location from = e.getBlock().getLocation();
        for (Block b : e.getBlocks()) if (crosses(from, b.getLocation())) { e.setCancelled(true); return; }
    }

    // entering / leaving land
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getChunk() == e.getTo().getChunk() && e.getFrom().getWorld() == e.getTo().getWorld()) return;
        UUID o = ownerAt(e.getTo());
        String area = o == null ? "wild" : o.toString();
        if (area.equals(lastArea.put(e.getPlayer().getUniqueId(), area))) return;
        Player p = e.getPlayer();
        if (o == null) p.sendActionBar(Ui.mm("<dark_green>☘ <green>Wilderness"));
        else p.sendActionBar(Ui.mm(o.equals(p.getUniqueId()) ? "<gold>⌂ <yellow>Your land" : "<gold>⌂ <white>" + name(o) + "<gray>'s land"));
    }

    private void clearMovedPreview(Player p, Location to) {
        Confirmation pending = confirmations.get(p.getUniqueId());
        if (pending != null && pending.chunkKey() != null
                && !pending.chunkKey().equals(key(to.getWorld(), to.getBlockX() >> 4, to.getBlockZ() >> 4))) {
            confirmations.remove(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreviewMove(PlayerMoveEvent e) { clearMovedPreview(e.getPlayer(), e.getTo()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreviewTeleport(PlayerTeleportEvent e) { clearMovedPreview(e.getPlayer(), e.getTo()); }

    @EventHandler
    public void onPreviewWorldChange(PlayerChangedWorldEvent e) { confirmations.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onPreviewDeath(PlayerDeathEvent e) { confirmations.remove(e.getEntity().getUniqueId()); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID player = e.getPlayer().getUniqueId();
        lastArea.remove(player);
        confirmations.remove(player);
    }
}
