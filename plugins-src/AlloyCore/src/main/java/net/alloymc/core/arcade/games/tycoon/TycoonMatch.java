package net.alloymc.core.arcade.games.tycoon;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import net.alloymc.core.arcade.games.tycoon.TycoonEngine.*;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * One table of Forge Tycoon. Players are the pieces: they hop round the ring tile by tile. Everything is driven by
 * hotbar items (roll, buy/pass, smelt, auction bids, end turn) and a Ledger menu for building, selling and trading.
 * Every choice has a timer, so an AFK player can never stall the table.
 */
final class TycoonMatch extends Match {
    private enum Phase { ROLL, MOVING, BUY, SMELTER, AUCTION, MANAGE, WAIT }

    private final TycoonEngine engine;
    private final List<UUID> order = new ArrayList<>();
    private final List<TextDisplay> labels = new ArrayList<>();
    private final NamespacedKey actionKey;
    private final ForgeTycoon.Ring ring;
    private Phase phase = Phase.WAIT;
    private int turn = -1, timer;
    private boolean extraTurn;
    // auction
    private Tile auctionTile;
    private int bid;
    private UUID bidder;
    // trade offer waiting for an answer
    private record Offer(UUID from, UUID to, int tile, int price) {}
    private Offer offer;
    private int offerTimer;

    TycoonMatch(MatchContext c) {
        super(c);
        engine = new TycoonEngine(rnd, msg -> broadcast(prefix() + msg), this::refreshTile);
        actionKey = new NamespacedKey(arcade.plugin(), "tycoon_action");
        ring = new ForgeTycoon.Ring(settings.i("ring-inner", 22), settings.i("ring-outer", 29));
    }

    @Override protected String objective() { return "Bankrupt everyone - or be the richest when time runs out!"; }
    @Override protected boolean pvp() { return false; }
    @Override protected boolean onDamage(Player v, Entity a, EntityDamageEvent e) { return false; }
    @Override protected boolean freezeWhileStarting() { return false; }

    private Seat seat(Player p) { return engine.seat(p.getUniqueId()); }
    private Seat current() { return turn < 0 ? null : engine.seat(order.get(turn)); }
    private Player currentPlayer() { return turn < 0 ? null : Bukkit.getPlayer(order.get(turn)); }
    private Location standAt(int tile, Player p) {
        int slot = Math.max(0, order.indexOf(p.getUniqueId()));
        return at(ring.stand(tile, slot));
    }

    // ------------------------------------------------------------------ setup

    @Override protected void setup(List<Player> ps) {
        List<Player> shuffled = new ArrayList<>(ps);
        Collections.shuffle(shuffled, rnd);
        for (Player p : shuffled) { order.add(p.getUniqueId()); engine.seat(p.getUniqueId(), p.getName()); }
        for (Player p : ps) p.teleport(standAt(0, p));
        for (Tile t : engine.board) label(t);
    }

    @Override protected void onStart() {
        broadcast(prefix() + "<white>Turn order: " + String.join("<gray>, ", order.stream().map(u -> nameOf(Bukkit.getPlayer(u))).toList()));
        broadcast(prefix() + "Everyone starts with <green>" + TycoonEngine.START_CASH + " Ingots<gray>. Open your <white>Ledger <gray>(slot 9) to see the board.");
        for (Player p : everyone()) giveItems(p);
        nextTurn();
    }

    @Override protected void onCleanup() { labels.forEach(Entity::remove); labels.clear(); }

    // ------------------------------------------------------------------ hotbar actions

    private ItemStack action(Material m, String id, String name, String... lore) {
        ItemStack it = Ui.item(m, name, lore);
        it.editMeta(meta -> meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, id));
        return it;
    }

    /** Rebuild a player's hotbar for the current phase. */
    private void giveItems(Player p) {
        if (!isAlive(p)) return;
        var inv = p.getInventory();
        inv.clear();
        Seat s = seat(p);
        boolean mine = s != null && s == current();
        if (mine) switch (phase) {
            case ROLL -> inv.setItem(0, action(Material.FIRE_CHARGE, "roll", "<gold><bold>Roll the Forge Die", "<gray>Right-click to roll (1-10)", "<gray>A <white>10 <gray>rolls again!"));
            case BUY -> {
                Tile t = engine.tile(s.pos());
                inv.setItem(0, action(Material.EMERALD, "buy", "<green><bold>Buy " + strip(t.name) + " for " + t.price, "<gray>Rent from <white>" + t.baseRent()));
                inv.setItem(1, action(Material.ANVIL, "pass", "<gray>Pass - send it to the <white>Auction Anvil"));
            }
            case SMELTER -> {
                inv.setItem(0, action(Material.BLAST_FURNACE, "smelt", "<#ff7a1a><bold>Smelt: pay " + TycoonEngine.SMELTER_FEE + " to roll again", "<gray>The fee goes into the Forge Pool"));
                inv.setItem(1, action(Material.GRAY_DYE, "nosmelt", "<gray>No thanks"));
            }
            case MANAGE -> inv.setItem(8, action(Material.LIME_DYE, "end", "<green><bold>End turn", "<gray>Build and trade in your <white>Ledger <gray>first"));
            default -> {}
        }
        if (phase == Phase.AUCTION && s != null && !s.bankrupt()) {
            inv.setItem(1, action(Material.IRON_NUGGET, "bid10", "<yellow>Bid +10"));
            inv.setItem(2, action(Material.GOLD_NUGGET, "bid50", "<gold>Bid +50"));
            inv.setItem(3, action(Material.GOLD_INGOT, "bid100", "<gold><bold>Bid +100"));
        }
        inv.setItem(mine && phase == Phase.MANAGE ? 7 : 8, action(Material.WRITABLE_BOOK, "ledger", "<white><bold>Ledger", "<gray>See every tile; build, sell and trade on your turn"));
    }

    private void refreshItems() { for (Player p : living()) giveItems(p); }

    @Override protected void onInteract(Player p, PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL) return;
        e.setCancelled(true);
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (id == null) return;
        Seat s = seat(p);
        if (s == null || s.bankrupt()) return;
        if (id.equals("ledger")) { openLedger(p); return; }
        if (id.startsWith("bid")) { bid(p, Integer.parseInt(id.substring(3))); return; }
        if (s != current()) return;
        switch (id) {
            case "roll" -> { if (phase == Phase.ROLL) roll(p); }
            case "buy" -> { if (phase == Phase.BUY) buy(p); }
            case "pass" -> { if (phase == Phase.BUY) startAuction(engine.tile(s.pos())); }
            case "smelt" -> { if (phase == Phase.SMELTER) { if (engine.smelt(s)) { broadcast(prefix() + nameOf(p) + " <gray>feeds the Smelter and rolls again"); roll(p); } } }
            case "nosmelt" -> { if (phase == Phase.SMELTER) afterLanding(); }
            case "end" -> { if (phase == Phase.MANAGE) nextTurn(); }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ turns

    private void setPhase(Phase ph, int seconds) {
        phase = ph;
        timer = seconds * 20;
        refreshItems();
    }

    private void nextTurn() {
        if (engine.solvent().size() <= 1) return;
        extraTurn = false;
        for (int guard = 0; guard < order.size() * 2; guard++) {
            turn = (turn + 1) % order.size();
            Seat s = current();
            Player p = currentPlayer();
            if (s.bankrupt() || p == null || !isAlive(p)) continue;
            if (s.skip() > 0) {
                s.skip--;
                broadcast(prefix() + nameOf(p) + " <gray>is still digging out of the cave-in - turn skipped");
                continue;
            }
            title(p, "<gold><bold>YOUR TURN", "<gray>Right-click the <white>Forge Die");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.2f);
            actionBar("<gold>" + p.getName() + "'s turn");
            setPhase(Phase.ROLL, settings.i("roll-seconds", 25));
            return;
        }
    }

    private void roll(Player p) {
        Seat s = seat(p);
        setPhase(Phase.MOVING, 30);
        int value = engine.roll();
        // the die tumbles for a moment before it lands
        for (int k = 0; k < 8; k++) {
            int shown = k == 7 ? value : 1 + rnd.nextInt(10);
            later(k * 2L, () -> {
                for (Player v : everyone()) v.sendActionBar(Ui.mm("<gold>🎲 <white><bold>" + shown));
                for (Player v : everyone()) v.playSound(v.getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.6f, 1.4f + rnd.nextFloat() * 0.4f);
            });
        }
        later(16, () -> {
            title(p, "<gold><bold>" + value, value == 10 ? "<#ff7a1a>Molten roll - you go again!" : "");
            broadcast(prefix() + nameOf(p) + " <gray>rolled a <white><bold>" + value + (value == 10 ? " <#ff7a1a>(molten roll!)" : ""));
            extraTurn = value == 10;
            hop(p, s, value);
        });
    }

    /** Walk the piece round the board one tile at a time, then settle what it landed on. */
    private void hop(Player p, Seat s, int steps) {
        int from = s.pos();
        for (int k = 1; k <= steps; k++) {
            int tile = Math.floorMod(from + k, ForgeTycoon.TILES);
            boolean last = k == steps;
            later(k * 5L, () -> {
                if (!p.isOnline()) return;
                p.teleport(standAt(tile, p));
                p.playSound(p.getLocation(), last ? Sound.BLOCK_ANVIL_LAND : Sound.BLOCK_NOTE_BLOCK_HAT, last ? 0.5f : 0.8f, last ? 1.6f : 1.2f);
                if (tile == 0) world.spawnParticle(Particle.WAX_ON, p.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4);
            });
        }
        later(steps * 5L + 6, () -> {
            engine.move(s, steps);
            land(p, s);
        });
    }

    private void land(Player p, Seat s) {
        Tile t = engine.tile(s.pos());
        Landing l = engine.land(s);
        syncBankrupt();
        if (s.bankrupt()) { nextTurn(); return; }
        switch (l) {
            case BUY_OFFER -> {
                title(p, t.label(), "<gray>Buy for <green>" + t.price + " <gray>or pass to auction");
                setPhase(Phase.BUY, settings.i("buy-seconds", 20));
            }
            case EVENT -> forgeEvent(p, s);
            case SMELTER -> {
                if (s.cash() >= TycoonEngine.SMELTER_FEE) setPhase(Phase.SMELTER, 12);
                else afterLanding();
            }
            case POOL -> { firework(p.getLocation()); afterLanding(); }
            default -> {
                if (l == Landing.PAID_RENT) p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                afterLanding();
            }
        }
    }

    private void forgeEvent(Player p, Seat s) {
        TycoonEngine.Event e = engine.draw();
        title(e.title, "<gray>" + e.text);
        sound(Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f);
        int before = s.pos();
        Tile burnt = engine.apply(s, e);
        syncBankrupt();
        if (burnt != null) dragonRaid(burnt);
        if (s.bankrupt()) { nextTurn(); return; }
        if (s.pos() != before) {
            // moved by the event (tailwind, rival merchant): put every moved piece where it belongs, then settle the new tile
            for (Player o : living()) { Seat os = seat(o); if (os != null) o.teleport(standAt(os.pos(), o)); }
            later(10, () -> land(p, s));
            return;
        }
        afterLanding();
    }

    private void dragonRaid(Tile t) {
        int[] lot = ring.lot(t.index);
        Location at = at(lot[0] + 0.5, 4, lot[1] + 0.5);
        world.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.AMBIENT, 2f, 0.8f);
        for (int k = 0; k < 20; k++) {
            int d = k;
            later(k * 2L, () -> {
                world.spawnParticle(Particle.FLAME, at.clone().add(0, 6 - d * 0.3, 0), 40, 1.2, 1.5, 1.2, 0.05);
                world.spawnParticle(Particle.LARGE_SMOKE, at, 8, 1.2, 1, 1.2, 0.02);
                if (d == 10) world.playSound(at, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, SoundCategory.AMBIENT, 1.5f, 0.8f);
            });
        }
    }

    private void afterLanding() {
        Seat s = current();
        Player p = currentPlayer();
        if (s == null || p == null || s.bankrupt()) { nextTurn(); return; }
        if (extraTurn) {
            extraTurn = false;
            broadcast(prefix() + nameOf(p) + " <gray>rolls again!");
            setPhase(Phase.ROLL, settings.i("roll-seconds", 25));
            return;
        }
        setPhase(Phase.MANAGE, settings.i("manage-seconds", 30));
        p.sendActionBar(Ui.mm("<gray>Build or trade in your <white>Ledger<gray>, then <green>End turn"));
    }

    private void buy(Player p) {
        Seat s = seat(p);
        Tile t = engine.tile(s.pos());
        if (!engine.buy(s, t, t.price)) return;
        broadcast(prefix() + nameOf(p) + " <gray>bought " + t.label() + " <gray>for <green>" + t.price);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        if (engine.ownsDistrict(s.id, t.district)) broadcast(prefix() + nameOf(p) + " <gray>now owns all of " + t.district.mm() + t.district.name() + "<gray> - they can build!");
        afterLanding();
    }

    // ------------------------------------------------------------------ auction

    private void startAuction(Tile t) {
        auctionTile = t;
        bid = 0;
        bidder = null;
        broadcast(prefix() + "<white><bold>AUCTION ANVIL!</bold> <gray>Bid on " + t.label() + " <gray>(worth <green>" + t.price + "<gray>) with your hotbar!");
        sound(Sound.BLOCK_ANVIL_USE, 1f);
        setPhase(Phase.AUCTION, settings.i("auction-seconds", 8));
    }

    private void bid(Player p, int add) {
        if (phase != Phase.AUCTION) return;
        Seat s = seat(p);
        int next = bid + add;
        if (s.cash() < next) { p.sendActionBar(Ui.mm("<red>You only have " + s.cash() + " Ingots")); return; }
        bid = next;
        bidder = p.getUniqueId();
        timer = Math.max(timer, 4 * 20);          // a late bid gives everyone a moment to answer
        sound(Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f + Math.min(0.8f, bid / 1000f));
    }

    private void endAuction() {
        Tile t = auctionTile;
        auctionTile = null;
        Player w = bidder == null ? null : Bukkit.getPlayer(bidder);
        Seat s = w == null ? null : seat(w);
        if (s != null && !s.bankrupt() && engine.buy(s, t, bid)) {
            broadcast(prefix() + "<gray>Sold! " + t.label() + " <gray>goes to " + nameOf(w) + " <gray>for <green>" + bid);
            sound(Sound.BLOCK_ANVIL_LAND, 1.2f);
        } else broadcast(prefix() + "<gray>Nobody bid - " + t.label() + " <gray>stays with the bank.");
        afterLanding();
    }

    // ------------------------------------------------------------------ ticking

    @Override protected void fastTick() {
        if (offer != null && --offerTimer <= 0) {
            Player from = Bukkit.getPlayer(offer.from);
            if (from != null) from.sendMessage(Ui.mm(prefix() + "<gray>Your offer wasn't answered in time."));
            offer = null;
        }
        if (phase == Phase.WAIT || phase == Phase.MOVING) return;
        if (offer != null && phase == Phase.MANAGE) return;   // the clock waits while a trade offer is open
        if (--timer > 0) {
            if (timer % 20 == 0) {
                int secs = timer / 20;
                if (phase == Phase.AUCTION) {
                    String lead = bidder == null ? "<gray>no bids yet" : "<green>" + bid + " <gray>by <white>" + Bukkit.getOfflinePlayer(bidder).getName();
                    actionBar("<white>Auction: " + auctionTile.label() + " <dark_gray>| " + lead + " <dark_gray>| <yellow>" + secs + "s");
                } else if (secs <= 5) {
                    Player p = currentPlayer();
                    if (p != null) p.sendActionBar(Ui.mm("<yellow>" + secs + "s left"));
                }
            }
            return;
        }
        Player p = currentPlayer();
        switch (phase) {
            case ROLL -> { if (p != null) roll(p); else nextTurn(); }
            case BUY -> startAuction(engine.tile(current().pos()));
            case SMELTER -> afterLanding();
            case AUCTION -> endAuction();
            case MANAGE -> nextTurn();
            default -> {}
        }
    }

    // ------------------------------------------------------------------ bankruptcy and the end

    /** Anyone the engine has bankrupted is out of the game. */
    private void syncBankrupt() {
        for (Seat s : engine.seats) if (s.bankrupt() && alive.contains(s.id)) {
            Player p = Bukkit.getPlayer(s.id);
            if (p != null) {
                eliminate(p, nameOf(p) + " <gray>went bankrupt!");
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.5f, 1.4f);
            } else alive.remove(s.id);
        }
    }

    @Override protected void onLeave(Player p) {
        Seat s = seat(p);
        if (s == null || s.bankrupt()) return;
        boolean wasTurn = s == current();
        engine.bankrupt(s, null);
        if (offer != null && (offer.from.equals(p.getUniqueId()) || offer.to.equals(p.getUniqueId()))) offer = null;
        if (bidder != null && bidder.equals(p.getUniqueId())) { bidder = null; bid = 0; }
        if (wasTurn && phase != Phase.AUCTION) later(1, this::nextTurn);
    }

    @Override protected void onVoid(Player p) { Seat s = seat(p); p.teleport(standAt(s == null ? 0 : s.pos(), p)); }

    @Override protected void onTimeUp() {
        Seat rich = engine.richest();
        broadcast(prefix() + "<yellow>Time's up! Counting everyone's fortune...");
        for (Seat s : engine.solvent()) broadcast(prefix() + "<white>" + s.name + " <gray>is worth <green>" + engine.netWorth(s));
        finish(rich == null ? List.of() : List.of(rich.id), rich == null ? "Nobody's left standing." : rich.name + " is the richest forge tycoon!");
    }

    @Override protected List<String> sidebar(Player p) {
        List<String> l = new ArrayList<>();
        Player cur = currentPlayer();
        l.add("<gray>Turn: <white>" + (cur == null ? "-" : cur.getName()));
        Seat me = seat(p);
        if (me != null && !me.bankrupt()) {
            l.add("<gray>Ingots: <green>" + me.cash());
            l.add("<gray>Worth: <white>" + engine.netWorth(me));
        }
        l.add("<gray>Forge Pool: <gold>" + engine.pool());
        l.add(" ");
        engine.solvent().stream().sorted((a, b) -> engine.netWorth(b) - engine.netWorth(a)).limit(6)
                .forEach(s -> l.add(teamOf.get(s.id).color.mm + s.name + " <gray>" + engine.netWorth(s)));
        l.add("<gray>Time left: <white>" + clock(timeLimit() - elapsed));
        return l;
    }

    // ------------------------------------------------------------------ the board in the world

    private String strip(String mm) { return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Ui.mm(mm)); }

    private void label(Tile t) {
        double a = ForgeTycoon.angle(t.index), r = ring.outer() - 2.6;
        TextDisplay d = Ui.hologram(at(Math.cos(a) * r + 0.5, 7.2, Math.sin(a) * r + 0.5), labelText(t), 1.1f);
        labels.add(d);
    }

    private String labelText(Tile t) {
        if (t.type != Type.PROPERTY) return switch (t.type) {
            case HEART -> "<gold><bold>FORGE HEART</bold>\n<gray>Pass: wages\n<gray>Land: the Forge Pool <gold>" + engine.pool();
            case SMELTER -> "<#ff7a1a><bold>SMELTER</bold>\n<gray>Pay " + TycoonEngine.SMELTER_FEE + " to roll again";
            default -> "<light_purple><bold>FORGE EVENT</bold>\n<gray>Anything can happen";
        };
        StringBuilder sb = new StringBuilder(t.district.mm() + "<bold>" + strip(t.name) + "</bold>\n");
        if (t.owner() == null) sb.append("<gray>For sale: <green>").append(t.price);
        else {
            Team team = teamOf.get(t.owner());
            String who = Bukkit.getOfflinePlayer(t.owner()).getName();
            sb.append(team == null ? "<white>" : team.color.mm).append(who).append("\n<gray>Rent: <red>").append(engine.rent(t));
            if (t.level() > 0) sb.append("\n<gold>").append(TycoonEngine.LEVEL[t.level()]);
        }
        return sb.toString();
    }

    /** Owner colour on the tile's rim, its buildings, and its label. */
    private void refreshTile(Tile t) {
        if (t.index < labels.size()) labels.get(t.index).text(Ui.mm(labelText(t)));
        if (!labels.isEmpty()) labels.getFirst().text(Ui.mm(labelText(engine.tile(0))));
        if (t.type != Type.PROPERTY) return;
        Team team = t.owner() == null ? null : teamOf.get(t.owner());
        Material rim = team == null ? Material.POLISHED_BLACKSTONE : team.color.concrete();
        double a = ForgeTycoon.angle(t.index), mid = (ring.inner() + ring.outer()) / 2.0;
        int cx = (int) Math.round(Math.cos(a) * mid), cz = (int) Math.round(Math.sin(a) * mid);
        for (int x = cx - 8; x <= cx + 8; x++)
            for (int z = cz - 8; z <= cz + 8; z++)
                if (ring.tileAt(x, z) == t.index && ring.border(x, z)) world.getBlockAt(ox + x, oy, oz + z).setType(rim, false);
        build(t);
    }

    /** Workshop, Foundry or Great Forge on the tile's lot. */
    private void build(Tile t) {
        int[] lot = ring.lot(t.index);
        int bx = ox + lot[0], bz = oz + lot[1];
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 1; y <= 7; y++) world.getBlockAt(bx + x, oy + y, bz + z).setType(Material.AIR, false);
        Material accent = Material.matchMaterial(t.district.block());
        switch (t.level()) {
            case 1 -> {
                world.getBlockAt(bx, oy + 1, bz).setType(Material.SMITHING_TABLE, false);
                world.getBlockAt(bx, oy + 2, bz).setType(Material.LANTERN, false);
            }
            case 2 -> {
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.getBlockAt(bx + x, oy + 1, bz + z).setType(Material.BRICKS, false);
                world.getBlockAt(bx, oy + 2, bz).setType(Material.BLAST_FURNACE, false);
                world.getBlockAt(bx, oy + 3, bz).setType(Material.CAMPFIRE, false);
                if (accent != null) for (int[] c : new int[][]{{-1, -1}, {1, 1}}) world.getBlockAt(bx + c[0], oy + 2, bz + c[1]).setType(accent, false);
            }
            case 3 -> {
                for (int y = 1; y <= 3; y++) for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
                    world.getBlockAt(bx + x, oy + y, bz + z).setType(y == 3 && (x != 0 || z != 0) ? Material.GOLD_BLOCK : Material.POLISHED_BLACKSTONE_BRICKS, false);
                world.getBlockAt(bx, oy + 4, bz).setType(Material.SHROOMLIGHT, false);
                world.getBlockAt(bx, oy + 5, bz).setType(Material.SHROOMLIGHT, false);
                world.getBlockAt(bx, oy + 6, bz).setType(Material.LIGHTNING_ROD, false);
                world.spawnParticle(Particle.FLAME, bx + 0.5, oy + 6, bz + 0.5, 40, 0.6, 0.8, 0.6, 0.04);
                world.playSound(new Location(world, bx, oy + 3, bz), Sound.BLOCK_ANVIL_USE, 1f, 0.7f);
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ the Ledger and its menus

    private static final class Menu implements InventoryHolder {
        final String kind;
        final int tile;
        final UUID target;
        int price;
        Inventory inv;
        Menu(String kind, int tile, UUID target, int price) { this.kind = kind; this.tile = tile; this.target = target; this.price = price; }
        @Override public Inventory getInventory() { return inv; }
    }

    private Inventory menu(Menu m, int size, String title) {
        m.inv = Bukkit.createInventory(m, size, Ui.mm(title));
        return m.inv;
    }

    private boolean canManage(Player p) { Seat s = seat(p); return s != null && s == current() && phase == Phase.MANAGE && !s.bankrupt(); }

    private void openLedger(Player p) {
        Menu m = new Menu("ledger", -1, null, 0);
        Inventory inv = menu(m, 27, "<dark_gray>Ledger" + (canManage(p) ? " <gray>- click your tiles" : ""));
        for (Tile t : engine.board) inv.setItem(t.index, tileIcon(t, p));
        Seat s = seat(p);
        if (s != null) inv.setItem(26, Ui.item(Material.GOLD_INGOT, "<green>" + s.cash() + " Ingots", "<gray>Worth " + engine.netWorth(s), "<gray>Wages next lap: " + engine.wages(s)));
        p.openInventory(inv);
    }

    private ItemStack tileIcon(Tile t, Player viewer) {
        Material icon = switch (t.type) {
            case HEART -> Material.GOLD_BLOCK;
            case SMELTER -> Material.BLAST_FURNACE;
            case EVENT -> Material.AMETHYST_CLUSTER;
            default -> Objects.requireNonNullElse(Material.matchMaterial(t.district.block()), Material.STONE);
        };
        List<String> lore = new ArrayList<>();
        if (t.type == Type.PROPERTY) {
            lore.add(t.district.mm() + t.district.name());
            lore.add("<gray>Price: <white>" + t.price + "  <gray>Build: <white>" + t.district.upgradeCost());
            lore.add("<gray>Owner: <white>" + (t.owner() == null ? "the bank" : Bukkit.getOfflinePlayer(t.owner()).getName()));
            lore.add("<gray>Rent now: <red>" + (t.owner() == null ? t.baseRent() : engine.rent(t)) + (t.level() > 0 ? " <gold>(" + TycoonEngine.LEVEL[t.level()] + ")" : ""));
            if (viewer.getUniqueId().equals(t.owner()) && canManage(viewer)) lore.add("<yellow>Click to build, sell or trade");
        }
        List<String> here = new ArrayList<>();
        for (Seat s : engine.solvent()) if (s.pos() == t.index) here.add(s.name);
        if (!here.isEmpty()) lore.add("<aqua>Here: " + String.join(", ", here));
        return Ui.item(icon, t.label(), lore.toArray(new String[0]));
    }

    private void openTile(Player p, Tile t) {
        Seat s = seat(p);
        Menu m = new Menu("tile", t.index, null, 0);
        Inventory inv = menu(m, 9, "<dark_gray>" + strip(t.name));
        inv.setItem(0, tileIcon(t, p));
        if (engine.canUpgrade(s, t)) inv.setItem(2, Ui.item(Material.SMITHING_TABLE, "<gold>Build a " + TycoonEngine.LEVEL[t.level() + 1], "<gray>Costs <white>" + t.district.upgradeCost()));
        else inv.setItem(2, Ui.item(Material.GRAY_DYE, "<gray>Can't build here", t.level() >= 3 ? "<gray>It's a Great Forge already" : "<gray>Own the whole district, have the Ingots,", "<gray>and build evenly across it"));
        if (engine.canSellBuilding(s, t)) inv.setItem(4, Ui.item(Material.IRON_PICKAXE, "<red>Sell a building", "<gray>Get back <white>" + t.district.upgradeCost() / 2));
        if (t.level() == 0) inv.setItem(6, Ui.item(Material.PLAYER_HEAD, "<aqua>Sell to a player", "<gray>Offer it to someone at your price"));
        inv.setItem(8, Ui.item(Material.ARROW, "<gray>Back"));
        p.openInventory(inv);
    }

    private void openPickBuyer(Player p, Tile t) {
        Menu m = new Menu("buyer", t.index, null, 0);
        Inventory inv = menu(m, 9, "<dark_gray>Sell " + strip(t.name) + " to...");
        int i = 0;
        for (Seat s : engine.solvent()) {
            if (s.id.equals(p.getUniqueId()) || i >= 8) continue;
            ItemStack head = Ui.item(Material.PLAYER_HEAD, "<white>" + s.name, "<gray>Has <green>" + s.cash() + " Ingots");
            head.editMeta(org.bukkit.inventory.meta.SkullMeta.class, sm -> sm.setOwningPlayer(Bukkit.getOfflinePlayer(s.id)));
            head.editMeta(meta -> meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, s.id.toString()));
            inv.setItem(i++, head);
        }
        inv.setItem(8, Ui.item(Material.ARROW, "<gray>Back"));
        p.openInventory(inv);
    }

    private void openPrice(Player p, Tile t, UUID buyer, int price) {
        Menu m = new Menu("price", t.index, buyer, Math.max(0, price));
        Inventory inv = menu(m, 9, "<dark_gray>Price for " + strip(t.name));
        inv.setItem(0, Ui.item(Material.RED_CONCRETE, "<red>-100"));
        inv.setItem(1, Ui.item(Material.RED_TERRACOTTA, "<red>-10"));
        inv.setItem(4, Ui.item(Material.GOLD_INGOT, "<green><bold>" + m.price + " Ingots", "<gray>to <white>" + Bukkit.getOfflinePlayer(buyer).getName()));
        inv.setItem(6, Ui.item(Material.LIME_TERRACOTTA, "<green>+10"));
        inv.setItem(7, Ui.item(Material.LIME_CONCRETE, "<green>+100"));
        inv.setItem(8, Ui.item(Material.WRITABLE_BOOK, "<aqua><bold>Send offer"));
        p.openInventory(inv);
    }

    private void openOffer(Player to, Offer o) {
        Menu m = new Menu("offer", o.tile, o.from, o.price);
        Tile t = engine.tile(o.tile);
        Inventory inv = menu(m, 9, "<dark_gray>" + Bukkit.getOfflinePlayer(o.from).getName() + " offers you a deal");
        inv.setItem(2, Ui.item(Material.LIME_CONCRETE, "<green><bold>Accept", "<gray>Pay <white>" + o.price + " <gray>for " + t.label()));
        inv.setItem(4, tileIcon(t, to));
        inv.setItem(6, Ui.item(Material.RED_CONCRETE, "<red><bold>Decline"));
        to.openInventory(inv);
    }

    @Override protected void onInventoryClick(Player p, InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) {
            if (e.getClickedInventory() == p.getInventory()) e.setCancelled(true);   // hotbar items stay put
            return;
        }
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        Seat s = seat(p);
        if (s == null || s.bankrupt()) { p.closeInventory(); return; }
        switch (m.kind) {
            case "ledger" -> {
                if (slot < engine.board.size() && canManage(p) && p.getUniqueId().equals(engine.tile(slot).owner())) openTile(p, engine.tile(slot));
            }
            case "tile" -> {
                if (!canManage(p)) { p.closeInventory(); return; }
                Tile t = engine.tile(m.tile);
                if (slot == 2 && engine.canUpgrade(s, t)) { engine.upgrade(s, t); openTile(p, t); }
                else if (slot == 4 && engine.canSellBuilding(s, t)) { engine.sellBuilding(s, t); openTile(p, t); }
                else if (slot == 6 && t.level() == 0) {
                    if (engine.board.stream().anyMatch(o -> o.district == t.district && o.level() > 0)) p.sendMessage(Ui.mm(prefix() + "<red>Sell the buildings in " + t.district.name() + " first."));
                    else openPickBuyer(p, t);
                }
                else if (slot == 8) openLedger(p);
            }
            case "buyer" -> {
                if (slot == 8) { openTile(p, engine.tile(m.tile)); return; }
                ItemStack it = e.getCurrentItem();
                if (it == null || !it.hasItemMeta()) return;
                String id = it.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
                if (id != null) openPrice(p, engine.tile(m.tile), UUID.fromString(id), engine.tile(m.tile).price);
            }
            case "price" -> {
                int[] delta = {-100, -10, 0, 0, 0, 0, 10, 100, 0};
                if (slot < 8 && delta[slot] != 0) { openPrice(p, engine.tile(m.tile), m.target, m.price + delta[slot]); return; }
                if (slot != 8 || !canManage(p)) return;
                Player buyer = Bukkit.getPlayer(m.target);
                if (buyer == null || offer != null) { p.sendMessage(Ui.mm(prefix() + "<red>Can't send that offer right now.")); return; }
                offer = new Offer(p.getUniqueId(), m.target, m.tile, m.price);
                offerTimer = 20 * 20;
                p.closeInventory();
                p.sendMessage(Ui.mm(prefix() + "<gray>Offer sent to <white>" + buyer.getName() + "<gray>. Your turn clock waits for their answer."));
                openOffer(buyer, offer);
            }
            case "offer" -> {
                Offer o = offer;
                if (o == null || !o.to.equals(p.getUniqueId())) { p.closeInventory(); return; }
                if (slot != 2 && slot != 6) return;
                offer = null;
                p.closeInventory();
                Player from = Bukkit.getPlayer(o.from);
                Seat fs = engine.seat(o.from);
                if (slot == 6 || fs == null) {
                    if (from != null) from.sendMessage(Ui.mm(prefix() + "<white>" + p.getName() + " <red>declined <gray>your offer."));
                    return;
                }
                if (!engine.transfer(fs, s, engine.tile(o.tile), o.price) && from != null) from.sendMessage(Ui.mm(prefix() + "<red>The deal fell through."));
            }
            default -> {}
        }
    }
}
