package net.alloymc.core.arcade.games.tycoon;

import java.util.*;
import java.util.function.Consumer;

/**
 * Forge Tycoon rules, as plain Java (no Bukkit) so whole games can be simulated in tests.
 *
 * A ring of 24 tiles: the Forge Heart, 18 properties in six districts, two Smelters and three Forge Event tiles.
 * Roll the Forge Die (1-10, a 10 rolls again), buy what you land on or it goes to the Auction Anvil, charge rent,
 * and upgrade a complete district through Workshop, Foundry and Great Forge. Passing the Forge Heart pays wages
 * that grow with your buildings; landing on it exactly also empties the Forge Pool, where fees and taxes collect.
 * Run out of Ingots and your buildings and then your properties are sold off at half price; still short and you're out.
 * Last one solvent wins, or the richest when the clock runs out.
 */
public final class TycoonEngine {
    public enum Type { HEART, PROPERTY, SMELTER, EVENT }

    /** A district: three properties that share a colour; owning all three unlocks building. */
    public record District(String name, String mm, String block, int upgradeCost) {}

    public static final District ASH = new District("Ash Lane", "<gray>", "light_gray_concrete", 50);
    public static final District COPPER = new District("Copper Quarter", "<#e07a45>", "orange_terracotta", 50);
    public static final District IRON = new District("Ironworks", "<white>", "white_concrete", 100);
    public static final District GOLD = new District("Gold Market", "<yellow>", "yellow_concrete", 100);
    public static final District EMBER = new District("Ember Heights", "<red>", "red_concrete", 150);
    public static final District CROWN = new District("Netherite Crown", "<dark_purple>", "purple_concrete", 200);

    public static final class Tile {
        public final int index;
        public final Type type;
        public final String name;
        public final District district;
        public final int price;
        UUID owner;
        int level;          // 0 = bare land, 1 Workshop, 2 Foundry, 3 Great Forge

        Tile(int index, Type type, String name, District district, int price) {
            this.index = index; this.type = type; this.name = name; this.district = district; this.price = price;
        }

        public UUID owner() { return owner; }
        public int level() { return level; }
        public int baseRent() { return Math.max(6, price / 8); }
        public String label() { return district == null ? name : district.mm + name; }
    }

    public static final class Seat {
        public final UUID id;
        public final String name;
        int cash, pos, skip;
        boolean bankrupt, luckyHammer;

        Seat(UUID id, String name, int cash) { this.id = id; this.name = name; this.cash = cash; }

        public int cash() { return cash; }
        public int pos() { return pos; }
        public boolean bankrupt() { return bankrupt; }
        public int skip() { return skip; }
    }

    public static final String[] LEVEL = {"Land", "Workshop", "Foundry", "Great Forge"};
    public static final double[] RENT_MULT = {1, 5, 14, 26};
    public static final int START_CASH = 1500, WAGES = 100, WAGES_PER_BUILDING = 10, SMELTER_FEE = 50;

    public final List<Tile> board = new ArrayList<>();
    public final List<Seat> seats = new ArrayList<>();
    private final Random rnd;
    private final Consumer<String> log;
    private final Consumer<Tile> changed;
    int pool;

    public TycoonEngine(Random rnd, Consumer<String> log, Consumer<Tile> changed) {
        this.rnd = rnd; this.log = log; this.changed = changed;
        heart(); prop("Cinder Alley", ASH, 60); prop("Soot Row", ASH, 60); event(); prop("Ash Lane", ASH, 80);
        prop("Copperwick", COPPER, 100); smelter(); prop("Verdigris Way", COPPER, 100); prop("Kettle Street", COPPER, 120);
        event(); prop("Rivet Row", IRON, 140); prop("Bellows Yard", IRON, 140); prop("Ironclad Court", IRON, 160);
        prop("Gilded Arcade", GOLD, 180); prop("Bullion Bridge", GOLD, 180); smelter(); prop("Crown Mint", GOLD, 200);
        event(); prop("Ember Terrace", EMBER, 220); prop("Flare Spire", EMBER, 220); prop("Kiln Gardens", EMBER, 240);
        prop("Obsidian Keep", CROWN, 280); prop("Debris Vault", CROWN, 300); prop("Netherite Throne", CROWN, 350);
    }

    private void heart() { board.add(new Tile(board.size(), Type.HEART, "<gold>Forge Heart", null, 0)); }
    private void prop(String n, District d, int price) { board.add(new Tile(board.size(), Type.PROPERTY, n, d, price)); }
    private void smelter() { board.add(new Tile(board.size(), Type.SMELTER, "<#ff7a1a>Smelter", null, 0)); }
    private void event() { board.add(new Tile(board.size(), Type.EVENT, "<light_purple>Forge Event", null, 0)); }

    public Seat seat(UUID id, String name) { Seat s = new Seat(id, name, START_CASH); seats.add(s); return s; }
    public Seat seat(UUID id) { for (Seat s : seats) if (s.id.equals(id)) return s; return null; }
    public List<Seat> solvent() { return seats.stream().filter(s -> !s.bankrupt).toList(); }
    public int pool() { return pool; }
    public Tile tile(int i) { return board.get(Math.floorMod(i, board.size())); }

    // ------------------------------------------------------------------ ownership and rent

    public List<Tile> owned(Seat s) { return board.stream().filter(t -> s.id.equals(t.owner)).toList(); }

    public boolean ownsDistrict(UUID who, District d) {
        return who != null && board.stream().filter(t -> t.district == d).allMatch(t -> who.equals(t.owner));
    }

    public int rent(Tile t) {
        if (t.owner == null) return 0;
        double r = t.baseRent() * RENT_MULT[t.level];
        if (t.level == 0 && ownsDistrict(t.owner, t.district)) r *= 1.5;   // full district, no buildings yet
        return (int) Math.round(r);
    }

    public int buildings(Seat s) { return owned(s).stream().mapToInt(t -> t.level).sum(); }
    public int wages(Seat s) { return WAGES + WAGES_PER_BUILDING * buildings(s); }

    /** Cash plus what everything owned cost. */
    public int netWorth(Seat s) {
        int w = s.cash;
        for (Tile t : owned(s)) w += t.price + t.level * t.district.upgradeCost;
        return w;
    }

    /** Can this seat put the next building on this tile? Buildings go up evenly across the district. */
    public boolean canUpgrade(Seat s, Tile t) {
        if (t.type != Type.PROPERTY || !s.id.equals(t.owner) || t.level >= 3 || s.cash < t.district.upgradeCost) return false;
        if (!ownsDistrict(s.id, t.district)) return false;
        int min = board.stream().filter(o -> o.district == t.district).mapToInt(o -> o.level).min().orElse(0);
        return t.level == min;
    }

    public void upgrade(Seat s, Tile t) {
        if (!canUpgrade(s, t)) return;
        s.cash -= t.district.upgradeCost;
        t.level++;
        log.accept("<white>" + s.name + " <gray>built a <gold>" + LEVEL[t.level] + " <gray>on " + t.label() + " <gray>(rent now <green>" + rent(t) + "<gray>)");
        changed.accept(t);
    }

    public boolean canSellBuilding(Seat s, Tile t) {
        if (!s.id.equals(t.owner) || t.level == 0) return false;
        int max = board.stream().filter(o -> o.district == t.district).mapToInt(o -> o.level).max().orElse(0);
        return t.level == max;
    }

    public void sellBuilding(Seat s, Tile t) {
        if (!canSellBuilding(s, t)) return;
        t.level--;
        s.cash += t.district.upgradeCost / 2;
        log.accept("<white>" + s.name + " <gray>sold a building on " + t.label() + " <gray>for <green>" + t.district.upgradeCost / 2);
        changed.accept(t);
    }

    public boolean buy(Seat s, Tile t, int price) {
        if (t.type != Type.PROPERTY || t.owner != null || s.cash < price) return false;
        s.cash -= price;
        t.owner = s.id;
        changed.accept(t);
        return true;
    }

    /** One player sells a bare property to another (both agreed). */
    public boolean transfer(Seat from, Seat to, Tile t, int price) {
        if (!from.id.equals(t.owner) || t.level > 0 || to.cash < price || to.bankrupt) return false;
        if (board.stream().anyMatch(o -> o.district == t.district && o.level > 0)) return false;   // sell the district's buildings first
        to.cash -= price;
        from.cash += price;
        t.owner = to.id;
        log.accept("<white>" + to.name + " <gray>bought " + t.label() + " <gray>from <white>" + from.name + " <gray>for <green>" + price);
        changed.accept(t);
        return true;
    }

    // ------------------------------------------------------------------ money

    /**
     * Take {@code amount} from {@code from}, selling buildings and then property at half price if it must.
     * Pays {@code to} (or the Forge Pool when {@code toPool}, or the bank otherwise). False = bankrupt.
     */
    public boolean pay(Seat from, Seat to, boolean toPool, int amount) {
        if (amount <= 0) return true;
        raise(from, amount);
        int paid = Math.min(amount, from.cash);
        from.cash -= paid;
        if (to != null) to.cash += paid;
        else if (toPool) pool += paid;
        if (paid < amount) { bankrupt(from, to); return false; }
        return true;
    }

    /** Sell off buildings (most built-up first) and then properties (cheapest first) until there's enough cash. */
    void raise(Seat s, int need) {
        while (s.cash < need) {
            Tile b = owned(s).stream().filter(t -> canSellBuilding(s, t)).max(Comparator.comparingInt(t -> t.district.upgradeCost)).orElse(null);
            if (b != null) { sellBuilding(s, b); continue; }
            Tile p = owned(s).stream().min(Comparator.comparingInt(t -> t.price)).orElse(null);
            if (p == null) return;
            p.owner = null;
            s.cash += p.price / 2;
            log.accept("<white>" + s.name + " <gray>had to sell " + p.label() + " <gray>back to the bank for <green>" + p.price / 2);
            changed.accept(p);
        }
    }

    void bankrupt(Seat s, Seat creditor) {
        s.bankrupt = true;
        for (Tile t : owned(s)) { t.owner = null; t.level = 0; changed.accept(t); }
        if (creditor != null) creditor.cash += s.cash;
        else pool += s.cash;
        s.cash = 0;
        log.accept("<red><bold>" + s.name + " is bankrupt!</bold> <gray>Their properties go back to the bank.");
    }

    // ------------------------------------------------------------------ moving

    public int roll() { return 1 + rnd.nextInt(10); }

    /** Move forward (or back, for negative steps). Passing or landing on the Forge Heart going forward pays wages. */
    public void move(Seat s, int steps) {
        int from = s.pos;
        s.pos = Math.floorMod(from + steps, board.size());
        if (steps > 0 && from + steps >= board.size()) {
            int w = wages(s);
            s.cash += w;
            log.accept("<white>" + s.name + " <gray>passed the <gold>Forge Heart <gray>and collected <green>" + w + " <gray>in wages");
        }
    }

    /** What the tile you landed on wants from you. The match handles the choices (buy, smelter, events). */
    public enum Landing { NOTHING, BUY_OFFER, PAID_RENT, OWN, EVENT, SMELTER, POOL, BANKRUPT }

    public Landing land(Seat s) {
        Tile t = tile(s.pos);
        switch (t.type) {
            case HEART -> {
                if (pool > 0) {
                    log.accept("<white>" + s.name + " <gray>landed right on the <gold>Forge Heart <gray>and scooped the Forge Pool: <green>" + pool);
                    s.cash += pool;
                    pool = 0;
                    return Landing.POOL;
                }
                return Landing.NOTHING;
            }
            case SMELTER -> { return Landing.SMELTER; }
            case EVENT -> { return Landing.EVENT; }
            default -> {
                if (t.owner == null) return s.cash >= t.price ? Landing.BUY_OFFER : Landing.NOTHING;
                if (t.owner.equals(s.id)) return Landing.OWN;
                Seat owner = seat(t.owner);
                int r = rent(t);
                if (owner.luckyHammer) { r *= 2; owner.luckyHammer = false; log.accept("<yellow>Lucky Hammer! <gray>Rent doubled for <white>" + owner.name); }
                log.accept("<white>" + s.name + " <gray>pays <red>" + r + " <gray>rent to <white>" + owner.name + " <gray>for " + t.label());
                return pay(s, owner, false, r) ? Landing.PAID_RENT : Landing.BANKRUPT;
            }
        }
    }

    // ------------------------------------------------------------------ Forge Events

    public enum Event {
        DRAGON_RAID("<red>Dragon Raid!", "A dragon swoops down and torches a building somewhere on the board."),
        MARKET_BOOM("<green>Market Boom", "Everyone collects 75 Ingots."),
        MARKET_CRASH("<dark_red>Market Crash", "Everyone pays a tenth of their Ingots into the Forge Pool."),
        ORE_STRIKE("<aqua>Ore Strike", "You hit a rich vein: collect 150."),
        FORGEMASTER_TAX("<gold>Forgemaster's Tax", "Pay 25 for every building you own, into the Forge Pool."),
        RIVAL_MERCHANT("<light_purple>Rival Merchant", "Swap places with the richest player."),
        TAILWIND("<white>Tailwind", "Move forward 3."),
        CAVE_IN("<dark_gray>Cave-in", "You lose your next turn digging out."),
        LUCKY_HAMMER("<yellow>Lucky Hammer", "The next rent anyone pays you is doubled."),
        SCRAP_SALE("<#c6a15b>Scrap Sale", "Sell your junk: collect 25 for each property you own.");

        public final String title, text;
        Event(String title, String text) { this.title = title; this.text = text; }
    }

    public Event draw() { Event[] all = Event.values(); return all[rnd.nextInt(all.length)]; }

    /** Applies the event. Returns the tile a Dragon Raid hit (for effects), or null. Movement events return the seat's new tile. */
    public Tile apply(Seat s, Event e) {
        log.accept("<light_purple><bold>FORGE EVENT</bold> <dark_gray>» " + e.title + " <gray>" + e.text);
        switch (e) {
            case DRAGON_RAID -> {
                List<Tile> built = board.stream().filter(t -> t.level > 0).toList();
                if (built.isEmpty()) { log.accept("<gray>...but there was nothing to burn. The dragon flies off."); return null; }
                Tile t = built.get(rnd.nextInt(built.size()));
                // keep the district even: burn the most built-up tile of that district
                Tile top = board.stream().filter(o -> o.district == t.district).max(Comparator.comparingInt(o -> o.level)).orElse(t);
                top.level--;
                log.accept("<red>The dragon burns a building on " + top.label() + "<red>!");
                changed.accept(top);
                return top;
            }
            case MARKET_BOOM -> { for (Seat o : solvent()) o.cash += 75; }
            case MARKET_CRASH -> { for (Seat o : solvent()) { int tax = o.cash / 10; o.cash -= tax; pool += tax; } }
            case ORE_STRIKE -> s.cash += 150;
            case FORGEMASTER_TAX -> pay(s, null, true, 25 * buildings(s));
            case RIVAL_MERCHANT -> {
                Seat rich = solvent().stream().filter(o -> o != s).max(Comparator.comparingInt(this::netWorth)).orElse(null);
                if (rich != null) { int p = rich.pos; rich.pos = s.pos; s.pos = p; log.accept("<white>" + s.name + " <gray>and <white>" + rich.name + " <gray>swap places"); }
            }
            case TAILWIND -> move(s, 3);
            case CAVE_IN -> s.skip++;
            case LUCKY_HAMMER -> s.luckyHammer = true;
            case SCRAP_SALE -> s.cash += 25 * owned(s).size();
        }
        return null;
    }

    /** Pay the Smelter fee into the Forge Pool to roll again. */
    public boolean smelt(Seat s) {
        if (s.cash < SMELTER_FEE) return false;
        s.cash -= SMELTER_FEE;
        pool += SMELTER_FEE;
        return true;
    }

    public Seat richest() { return solvent().stream().max(Comparator.comparingInt(this::netWorth)).orElse(null); }
}
