package net.blackforge.core;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;

import java.io.File;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Predicate;

/**
 * /cosmetics: kill effects, victory effects, trails, auras, pets and capes. Purely visual - nothing here changes how strong anyone is.
 * <p>
 * Cosmetics come from three places: Scrap (the original kill/victory effects), event rewards and achievements, and the
 * Cosmetic Crate, opened with a Cosmetic Key (sold in the store, handed out as rewards). A crate duplicate is not wasted: it
 * turns into Forge Dust, which can be spent on crafting any crate cosmetic you are missing. Epic and Legendary pulls are guaranteed
 * every so often (pity), so nobody goes endlessly unlucky.
 * <p>
 * Everything a player owns, equips, and their keys and dust live in shared/cosmetics/&lt;uuid&gt;.yml, so it follows them across servers.
 * The visuals themselves (trails, auras, pets, capes) are drawn by {@link CosmeticFx}.
 */
public final class Cosmetics implements Listener {
    public enum Kind {
        KILL("Kill Effects", Material.IRON_SWORD, "Plays where you kill a player"),
        VICTORY("Victory Effects", Material.FIREWORK_ROCKET, "Plays when you win an Arcade game"),
        TRAIL("Trails", Material.LEATHER_BOOTS, "Follows your feet as you move"),
        AURA("Auras", Material.NETHER_STAR, "Swirls around you wherever you go"),
        PET("Pets", Material.BONE, "A little friend that follows you around"),
        CAPE("Capes", Material.WHITE_BANNER, "A cloak that flows behind you"),
        ICON("Name Icons", Material.NAME_TAG, "Shown beside your name in chat and the tab list");

        public final String title;
        public final Material icon;
        public final String blurb;

        Kind(String title, Material icon, String blurb) { this.title = title; this.icon = icon; this.blurb = blurb; }

        public String key() { return name().toLowerCase(); }
    }

    public enum Rarity {
        COMMON("<white>", "Common", 58, 5, 30),
        RARE("<aqua>", "Rare", 28, 15, 90),
        EPIC("<light_purple>", "Epic", 11, 40, 240),
        LEGENDARY("<gold>", "Legendary", 3, 100, 600);

        public final String color, label;
        /** Share of crate pulls, out of 100. */
        public final int weight;
        /** Forge Dust a duplicate turns into, and what it costs to craft one of this rarity. */
        public final int dust, craft;

        Rarity(String color, String label, int weight, int dust, int craft) {
            this.color = color; this.label = label; this.weight = weight; this.dust = dust; this.craft = craft;
        }
    }

    /** price: Scrap price, or -1 when it can't be bought; crate: whether the Cosmetic Crate can give it. */
    /** season: the limited-time event this belongs to (see {@link #SEASONS}), or null for a cosmetic that is always available. */
    public record Cosmetic(String id, Kind kind, String name, Material icon, int price, String lore, Rarity rarity, boolean crate, String season) {}

    private static Cosmetic scrap(String id, Kind k, String name, Material icon, int price, String lore) {
        Rarity r = price < 0 ? Rarity.EPIC : price <= 300 ? Rarity.COMMON : price <= 900 ? Rarity.RARE : price <= 1500 ? Rarity.EPIC : Rarity.LEGENDARY;
        return new Cosmetic(id, k, name, icon, price, lore, r, false, null);
    }

    private static Cosmetic crate(String id, Kind k, Rarity r, String name, Material icon, String lore) {
        return new Cosmetic(id, k, name, icon, -1, lore, r, true, null);
    }

    /** Limited time, found in the Cosmetic Crate while its season is on. */
    private static Cosmetic limited(String id, Kind k, String season, Rarity r, String name, Material icon, String lore) {
        return new Cosmetic(id, k, name, icon, -1, lore, r, true, season);
    }

    /** Never in a crate and never sold: given out as an award (Founders, event champions). */
    private static Cosmetic award(String id, Kind k, String season, String name, Material icon, String lore) {
        return new Cosmetic(id, k, name, icon, -1, lore, Rarity.LEGENDARY, false, season);
    }

    /** Limited time, handed out by the event itself (its shop), never by the crate. */

    private static Cosmetic event(String id, Kind k, String season, String name, Material icon, String lore) {
        return new Cosmetic(id, k, name, icon, -1, lore, Rarity.EPIC, false, season);
    }

    /**
     * A limited-time event. While it is on, some crate openings turn into its cosmetics; once it ends they can no longer be
     * obtained, but everyone who has one keeps it for good. Dates come from the event's own config keys, so the cosmetics
     * and the event always run together.
     */
    public record Season(String id, String name, String color, LocalDate start, LocalDate end, boolean grantOnly, LocalDate tbStart, LocalDate tbEnd) {
        /** The season itself is on (awards-only seasons like the Founders never are). */
        public boolean live(LocalDate today) { return !grantOnly && !today.isBefore(start) && !today.isAfter(end); }
        /** A returning window: last year's cosmetics are back for a short while. */
        public boolean throwback(LocalDate today) { return !grantOnly && tbStart != null && !today.isBefore(tbStart) && !today.isAfter(tbEnd); }
        public boolean active(LocalDate today) { return live(today) || throwback(today); }
        public long daysLeft(LocalDate today) { LocalDate e = live(today) || tbEnd == null ? end : tbEnd; return java.time.temporal.ChronoUnit.DAYS.between(today, e) + 1; }
        public String label() { return color + name + " " + start.getYear(); }
    }

    private static final Map<String, String[]> SEASON_DEFAULTS = new LinkedHashMap<>();
    static {
        //                        name           colour       start date    end date       config key
        SEASON_DEFAULTS.put("hallow", new String[]{"Hallowforge", "<#ff9a1f>", "2026-10-28", "2026-11-10", "event.hallowforge"});
        SEASON_DEFAULTS.put("frost", new String[]{"Frostforge", "<#9fe6ff>", "2026-12-01", "2027-01-06", "event.frostforge"});
        SEASON_DEFAULTS.put("emberheart", new String[]{"Emberheart", "<#ff6b9d>", "2027-02-07", "2027-02-16", "event.emberheart"});
        SEASON_DEFAULTS.put("bloomforge", new String[]{"Bloomforge", "<#ffb7d5>", "2027-03-01", "2027-04-30", "event.bloomforge"});
        SEASON_DEFAULTS.put("sunforge", new String[]{"Sunforge", "<#ffd23f>", "2027-06-01", "2027-08-31", "event.sunforge"});
        SEASON_DEFAULTS.put("anniversary", new String[]{"Anniversary", "<#ffd27a>", "2027-12-01", "2027-12-07", "event.anniversary"});
        SEASON_DEFAULTS.put("founder", new String[]{"Founders", "<#ffb347>", "2000-01-01", "2000-01-01", "event.founder"});
    }
    private final Map<String, Season> seasons = new LinkedHashMap<>();
    /** Share of crate openings that turn into a live season's cosmetic (config cosmetics.limited-chance). */
    private double limitedChance = 0.2;

    public static final List<Cosmetic> ALL = List.of(
            // kill effects and victory effects: the originals (Scrap, events, achievements)
            scrap("flames", Kind.KILL, "<#ff7a1a>Flame Burst", Material.BLAZE_POWDER, 300, "A burst of fire where they fell"),
            scrap("hearts", Kind.KILL, "<#ff6b9d>Heartbreaker", Material.PINK_DYE, 300, "Hearts float up from the victim"),
            scrap("souls", Kind.KILL, "<#4fd1c5>Soul Harvest", Material.SOUL_LANTERN, 600, "Their soul rises out of the body"),
            scrap("lightning", Kind.KILL, "<yellow>Thunderclap", Material.LIGHTNING_ROD, 900, "A harmless bolt of lightning"),
            scrap("anvil", Kind.KILL, "<gray>Forge Strike", Material.ANVIL, 1200, "An anvil clang and a shower of sparks"),
            event("pumpkin", Kind.KILL, "hallow", "<gold>Pumpkin Burst", Material.JACK_O_LANTERN, "Hallowforge event exclusive"),
            event("frost", Kind.KILL, "frost", "<#9fe6ff>Frost Nova", Material.BLUE_ICE, "Frostforge event exclusive"),
            scrap("forgemaster", Kind.KILL, "<gradient:#ffd27a:#ff4d00>Forge Master", Material.ANVIL, -1, "Unlock " + Achievements.FORGE_MASTER_AT + " achievements"),
            scrap("fireworks", Kind.VICTORY, "<aqua>Firework Show", Material.FIREWORK_ROCKET, 500, "A volley of fireworks when you win"),
            scrap("roar", Kind.VICTORY, "<dark_purple>Dragon's Roar", Material.DRAGON_HEAD, 1000, "A dragon roars for your victory"),
            scrap("goldrain", Kind.VICTORY, "<gold>Gold Rain", Material.GOLD_INGOT, 1500, "It rains gold around you"),
            scrap("totem", Kind.VICTORY, "<green>Totem of Glory", Material.TOTEM_OF_UNDYING, 2000, "The totem animation, but for winning"),
            scrap("completionist", Kind.VICTORY, "<gradient:#ffd27a:#ff4d00>Completionist", Material.NETHER_STAR, -1, "Unlock every achievement"),
            // crate-only kill and victory effects
            crate("shatter", Kind.KILL, Rarity.RARE, "<#b8e4ff>Glass Shatter", Material.GLASS, "They break into shards of glass"),
            crate("inferno", Kind.KILL, Rarity.EPIC, "<#ff4d00>Inferno", Material.MAGMA_BLOCK, "A pillar of fire and lava"),
            crate("voidrip", Kind.KILL, Rarity.LEGENDARY, "<#b388ff>Void Rift", Material.ENDER_EYE, "The air tears open where they fell"),
            crate("confetti", Kind.VICTORY, Rarity.RARE, "<#ff6bd6>Confetti Pop", Material.PINK_DYE, "Confetti rains down on you"),
            crate("blaze_crown", Kind.VICTORY, Rarity.EPIC, "<#ffb347>Crown of Flame", Material.GOLDEN_HELMET, "A ring of fire crowns the winner"),
            crate("supernova", Kind.VICTORY, Rarity.LEGENDARY, "<gradient:#fff3a0:#ff7a1a>Supernova", Material.FIREWORK_STAR, "A star is born where you stand"),
            // trails
            crate("t_ember", Kind.TRAIL, Rarity.COMMON, "<#ff9a3c>Ember Steps", Material.CAMPFIRE, "Tiny embers glow at your feet"),
            crate("t_ash", Kind.TRAIL, Rarity.COMMON, "<gray>Ashen Wake", Material.GRAY_DYE, "A drift of grey smoke"),
            crate("t_spore", Kind.TRAIL, Rarity.COMMON, "<#9be37a>Spore Drift", Material.SPORE_BLOSSOM, "Green spores float up behind you"),
            crate("t_bubble", Kind.TRAIL, Rarity.COMMON, "<#7fd4ff>Tidewalker", Material.HEART_OF_THE_SEA, "Bubbles pop around your boots"),
            crate("t_arc", Kind.TRAIL, Rarity.RARE, "<yellow>Static Charge", Material.COPPER_INGOT, "Sparks crackle with every step"),
            crate("t_script", Kind.TRAIL, Rarity.RARE, "<#b69cff>Arcane Script", Material.ENCHANTED_BOOK, "Glowing glyphs rise from the ground"),
            crate("t_sakura", Kind.TRAIL, Rarity.RARE, "<#ffb7d5>Petal Drift", Material.CHERRY_LEAVES, "Cherry petals in your wake"),
            crate("t_prism", Kind.TRAIL, Rarity.EPIC, "<gradient:#ff5555:#55ff55:#5555ff>Prism Trail", Material.PRISMARINE_CRYSTALS, "A rainbow that shifts as you run"),
            crate("t_wraith", Kind.TRAIL, Rarity.EPIC, "<#4fd1c5>Wraith Steps", Material.SOUL_TORCH, "Soul fire and a faint whisper"),
            crate("t_molten", Kind.TRAIL, Rarity.LEGENDARY, "<gradient:#ffd27a:#ff4d00>Molten Wake", Material.LAVA_BUCKET, "Dripping lava and fire in your footprints"),
            crate("t_galaxy", Kind.TRAIL, Rarity.LEGENDARY, "<gradient:#b388ff:#5be3ff>Starforged", Material.ECHO_SHARD, "You leave stars and a nebula behind"),
            // auras
            crate("a_halo", Kind.AURA, Rarity.COMMON, "<#ffe08a>Golden Halo", Material.GOLD_NUGGET, "A shining ring above your head"),
            crate("a_ember", Kind.AURA, Rarity.RARE, "<#ff7a1a>Orbiting Embers", Material.FIRE_CHARGE, "Three embers circle you"),
            crate("a_frost", Kind.AURA, Rarity.RARE, "<#9fe6ff>Frost Orbit", Material.SNOWBALL, "Snowflakes spiral around you"),
            crate("a_soul", Kind.AURA, Rarity.EPIC, "<#4fd1c5>Soul Spiral", Material.SOUL_SAND, "Soul fire winds up your body"),
            crate("a_galaxy", Kind.AURA, Rarity.LEGENDARY, "<gradient:#b388ff:#5be3ff>Event Horizon", Material.ENDER_PEARL, "A tiny galaxy turns around you"),
            // pets
            crate("p_cat", Kind.PET, Rarity.COMMON, "<#ffd7a0>Forge Kitten", Material.COD, "A kitten that curls up by the anvil"),
            crate("p_rabbit", Kind.PET, Rarity.COMMON, "<#e6d3b3>Hopper", Material.RABBIT_FOOT, "A bunny that hops after you"),
            crate("p_chick", Kind.PET, Rarity.COMMON, "<#fff3a0>Ember Chick", Material.EGG, "A very small, very loyal chick"),
            crate("p_fox", Kind.PET, Rarity.RARE, "<#ff9a3c>Cinder Fox", Material.SWEET_BERRIES, "A fox kit with a tail like a torch"),
            crate("p_parrot", Kind.PET, Rarity.RARE, "<#5be38a>Parrot", Material.FEATHER, "Flies along and keeps you company"),
            crate("p_frog", Kind.PET, Rarity.RARE, "<#7bd88f>Marsh Frog", Material.SLIME_BALL, "A frog that never lets you out of sight"),
            crate("p_armadillo", Kind.PET, Rarity.RARE, "<#c9a27a>Plated Armadillo", Material.ARMADILLO_SCUTE, "A tiny armoured friend"),
            crate("p_allay", Kind.PET, Rarity.EPIC, "<#6bd6ff>Spark Allay", Material.AMETHYST_SHARD, "A glowing spirit that floats beside you"),
            crate("p_wolf", Kind.PET, Rarity.EPIC, "<#d9d9d9>Hellhound Pup", Material.BONE, "A pup that trots at your heels"),
            crate("p_bee", Kind.PET, Rarity.EPIC, "<#ffd23f>Forge Bee", Material.HONEYCOMB, "A bee that buzzes around your head"),
            crate("p_sniffer", Kind.PET, Rarity.LEGENDARY, "<gradient:#7bd88f:#2a8f6b>Ancient Snifflet", Material.TORCHFLOWER_SEEDS, "A baby sniffer from the old world"),
            crate("p_golem", Kind.PET, Rarity.LEGENDARY, "<gradient:#ffb36b:#c4622d>Copper Golem", Material.COPPER_BLOCK, "A little golem that follows its maker"),
            crate("p_ghast", Kind.PET, Rarity.LEGENDARY, "<gradient:#ffffff:#9fc4ff>Ghastling", Material.GHAST_TEAR, "A gentle baby ghast that floats behind you"),
            // capes
            crate("c_crimson", Kind.CAPE, Rarity.COMMON, "<red>Crimson Cloak", Material.RED_WOOL, "A plain red cloak with a black hem"),
            crate("c_forest", Kind.CAPE, Rarity.COMMON, "<green>Forest Mantle", Material.GREEN_WOOL, "A woodland cloak with a brown hem"),
            crate("c_midnight", Kind.CAPE, Rarity.COMMON, "<blue>Midnight Cloak", Material.BLUE_WOOL, "Deep blue with a white hem"),
            crate("c_ash", Kind.CAPE, Rarity.COMMON, "<gray>Ashen Shroud", Material.GRAY_WOOL, "Grey cloth, black at the edges"),
            crate("c_royal", Kind.CAPE, Rarity.RARE, "<#b24bf3>Royal Standard", Material.PURPLE_WOOL, "Purple with a gold hem and crest"),
            crate("c_frost", Kind.CAPE, Rarity.RARE, "<#9fe6ff>Frostweave", Material.LIGHT_BLUE_WOOL, "Icy blue with a frozen crest"),
            crate("c_sun", Kind.CAPE, Rarity.RARE, "<#ffb347>Sunforged", Material.ORANGE_WOOL, "Orange cloth with a golden sun"),
            crate("c_netherite", Kind.CAPE, Rarity.EPIC, "<#8b6f7a>Netherite Mantle", Material.NETHERITE_INGOT, "Black stone cloth with a gold edge"),
            crate("c_crystal", Kind.CAPE, Rarity.EPIC, "<#c78bff>Crystal Cloak", Material.AMETHYST_BLOCK, "Amethyst plates that shimmer in light"),
            crate("c_prism", Kind.CAPE, Rarity.EPIC, "<gradient:#ff5555:#55ff55:#5555ff>Prism Cape", Material.WHITE_DYE, "The colour shifts as you move"),
            crate("c_molten", Kind.CAPE, Rarity.LEGENDARY, "<gradient:#ffd27a:#ff4d00>Molten Cloak", Material.MAGMA_BLOCK, "A glowing cloak of cooling lava"),
            crate("c_star", Kind.CAPE, Rarity.LEGENDARY, "<gradient:#b388ff:#5be3ff>Starforged Mantle", Material.CRYING_OBSIDIAN, "Crying obsidian, lit from within"),
            crate("c_blackforge", Kind.CAPE, Rarity.LEGENDARY, "<gradient:#ffb347:#ff5a1a>Black Forge Mantle", Material.SHROOMLIGHT, "The mark of the forge, glowing gold"),
            // Hallowforge (limited)
            limited("t_haunt", Kind.TRAIL, "hallow", Rarity.EPIC, "<gradient:#ff9a1f:#9b4dff>Haunted Wake", Material.SOUL_LANTERN, "Orange and violet fire, with a whisper"),
            limited("a_swarm", Kind.AURA, "hallow", Rarity.RARE, "<#9b4dff>Wicked Swarm", Material.SPIDER_EYE, "Shadows circle you, fast"),
            limited("p_blackcat", Kind.PET, "hallow", Rarity.RARE, "<#b69cff>Midnight Cat", Material.BLACK_DYE, "A black cat. Bad luck for everyone else"),
            limited("c_hallow", Kind.CAPE, "hallow", Rarity.EPIC, "<gradient:#ff9a1f:#ffd27a>Jack's Cloak", Material.JACK_O_LANTERN, "Black cloth, an orange hem, a glowing pumpkin"),
            limited("reaper", Kind.KILL, "hallow", Rarity.LEGENDARY, "<gradient:#9b4dff:#4fd1c5>Reaper's Toll", Material.WITHER_SKELETON_SKULL, "Their soul is taken, and it screams"),
            // Frostforge (limited)
            limited("t_snowdrift", Kind.TRAIL, "frost", Rarity.RARE, "<#9fe6ff>Snowdrift", Material.SNOWBALL, "Snow tumbles off your boots"),
            limited("a_blizzard", Kind.AURA, "frost", Rarity.EPIC, "<#9fe6ff>Blizzard", Material.POWDER_SNOW_BUCKET, "Your own private snowstorm"),
            limited("p_snowfox", Kind.PET, "frost", Rarity.RARE, "<#e8f6ff>Snow Fox", Material.SNOW_BLOCK, "A white fox kit that blends right in"),
            limited("c_frostforged", Kind.CAPE, "frost", Rarity.EPIC, "<gradient:#9fe6ff:#ffffff>Frostforged Mantle", Material.BLUE_ICE, "Blue ice, white trim, lit from within"),
            limited("whiteout", Kind.VICTORY, "frost", Rarity.LEGENDARY, "<gradient:#ffffff:#9fe6ff>Whiteout", Material.POWDER_SNOW_BUCKET, "A blizzard blows in to crown you"),
            // Emberheart (limited)
            limited("t_heart", Kind.TRAIL, "emberheart", Rarity.RARE, "<#ff6b9d>Heartstrings", Material.PINK_DYE, "Little hearts float up behind you"),
            limited("a_cupid", Kind.AURA, "emberheart", Rarity.EPIC, "<#ff9ec4>Cupid's Halo", Material.ROSE_BUSH, "A pink halo and the odd stray heart"),
            limited("p_lovebird", Kind.PET, "emberheart", Rarity.RARE, "<#ff5577>Lovebird", Material.RED_DYE, "A red parrot that never leaves your side"),
            limited("c_rose", Kind.CAPE, "emberheart", Rarity.EPIC, "<#ff6b9d>Rose Cape", Material.PINK_WOOL, "Pink and red with a glowing heart"),
            limited("k_broken", Kind.KILL, "emberheart", Rarity.LEGENDARY, "<#e0244d>Broken Heart", Material.REDSTONE, "A heart shatters where they fell"),
            // Bloomforge (limited)
            limited("t_bloom", Kind.TRAIL, "bloomforge", Rarity.RARE, "<#9be37a>Wildflower", Material.POPPY, "Pink and green blossoms in your steps"),
            limited("a_butterflies", Kind.AURA, "bloomforge", Rarity.EPIC, "<#ffb7d5>Butterflies", Material.PINK_PETALS, "Bright specks flutter around you"),
            limited("p_bunny", Kind.PET, "bloomforge", Rarity.RARE, "<#ffffff>Spring Bunny", Material.CARROT, "A white bunny with springtime energy"),
            limited("c_blossom", Kind.CAPE, "bloomforge", Rarity.EPIC, "<#ffb7d5>Blossom Cape", Material.CHERRY_LEAVES, "Moss green with a cherry blossom crest"),
            limited("v_bloom", Kind.VICTORY, "bloomforge", Rarity.LEGENDARY, "<gradient:#ffb7d5:#9be37a>Full Bloom", Material.CHERRY_SAPLING, "Petals burst out around you"),
            // Sunforge (limited)
            limited("t_sun", Kind.TRAIL, "sunforge", Rarity.RARE, "<#ffd23f>Sun Trail", Material.HONEY_BOTTLE, "Warm gold light at your feet"),
            limited("a_sunburst", Kind.AURA, "sunforge", Rarity.EPIC, "<#ffb347>Sunburst", Material.SUNFLOWER, "Golden rays wind up your body"),
            limited("p_turtle", Kind.PET, "sunforge", Rarity.RARE, "<#7bd88f>Beach Turtle", Material.TURTLE_SCUTE, "A very small, very slow best friend"),
            limited("c_tide", Kind.CAPE, "sunforge", Rarity.EPIC, "<#5be3ff>Tidecloak", Material.PRISMARINE_SHARD, "Sea blue, sandy hem, a sea lantern crest"),
            limited("k_sunflare", Kind.KILL, "sunforge", Rarity.LEGENDARY, "<gradient:#fff3a0:#ff7a1a>Solar Flare", Material.BLAZE_ROD, "A flash of sunlight where they fell"),
            // Anniversary (limited)
            limited("t_anniv", Kind.TRAIL, "anniversary", Rarity.EPIC, "<#ffd27a>Golden Stride", Material.GOLD_INGOT, "Gold dust and little fireworks"),
            limited("a_anniv", Kind.AURA, "anniversary", Rarity.LEGENDARY, "<gradient:#ffd27a:#ff7a1a>Forge Anniversary", Material.GOLDEN_APPLE, "Gold sparks circle you"),
            limited("p_piglet", Kind.PET, "anniversary", Rarity.EPIC, "<#ffd27a>Golden Piglet", Material.GOLD_NUGGET, "A party pig, dressed for the birthday"),
            limited("c_anniv", Kind.CAPE, "anniversary", Rarity.LEGENDARY, "<gradient:#ffd27a:#ff7a1a>Anniversary Mantle", Material.GOLD_BLOCK, "Solid gold, black trim, glowing crest"),
            limited("v_anniv", Kind.VICTORY, "anniversary", Rarity.LEGENDARY, "<gradient:#ffd27a:#ffffff>Birthday Fireworks", Material.FIREWORK_ROCKET, "A golden firework finale"),
            // Founders (awarded to everyone who joined before the cut-off; never returns)
            award("c_founder", Kind.CAPE, "founder", "<gradient:#ffb347:#ffffff>Founder's Mantle", Material.NETHERITE_BLOCK, "Awarded to the people who were here first"),
            award("t_founder", Kind.TRAIL, "founder", "<gradient:#ffb347:#ff5a1a>Founder's Flame", Material.BLAZE_POWDER, "Gold and black fire"),
            award("a_founder", Kind.AURA, "founder", "<gradient:#ffb347:#ffffff>Founder's Crown", Material.GOLDEN_HELMET, "A crown of gold sparks"),
            // Champion capes: the top players of each season
            award("c_champ_hallow", Kind.CAPE, "hallow", "<gradient:#ff9a1f:#ffd27a>Hallowforge Champion", Material.CARVED_PUMPKIN, "Top of the Hallowforge leaderboard"),
            award("c_champ_frost", Kind.CAPE, "frost", "<gradient:#9fe6ff:#ffd27a>Frostforge Champion", Material.BLUE_ICE, "Top of the Frostforge leaderboard"),
            award("c_champ_emberheart", Kind.CAPE, "emberheart", "<gradient:#ff6b9d:#ffd27a>Emberheart Champion", Material.RED_CONCRETE, "Top of the Emberheart leaderboard"),
            award("c_champ_bloomforge", Kind.CAPE, "bloomforge", "<gradient:#ffb7d5:#ffd27a>Bloomforge Champion", Material.PINK_CONCRETE, "Top of the Bloomforge leaderboard"),
            award("c_champ_sunforge", Kind.CAPE, "sunforge", "<gradient:#ffd23f:#ffffff>Sunforge Champion", Material.YELLOW_CONCRETE, "Top of the Sunforge leaderboard"),
            award("c_champ_anniversary", Kind.CAPE, "anniversary", "<gradient:#ffd27a:#ffffff>Anniversary Champion", Material.GOLD_BLOCK, "Top of the Anniversary leaderboard"),
            // name icons (shown beside your name)
            crate("i_star", Kind.ICON, Rarity.COMMON, "<yellow>★ Star", Material.NAME_TAG, "A plain gold star"),
            crate("i_note", Kind.ICON, Rarity.COMMON, "<aqua>♪ Melody", Material.NAME_TAG, "For people who like a tune"),
            crate("i_bolt", Kind.ICON, Rarity.COMMON, "<yellow>⚡ Spark", Material.NAME_TAG, "Quick on your feet"),
            crate("i_clover", Kind.ICON, Rarity.COMMON, "<green>♣ Clover", Material.NAME_TAG, "Lucky, apparently"),
            crate("i_swords", Kind.ICON, Rarity.RARE, "<red>⚔ Duelist", Material.NAME_TAG, "Crossed swords"),
            crate("i_skull", Kind.ICON, Rarity.RARE, "<white>☠ Skull", Material.NAME_TAG, "Not to be messed with"),
            crate("i_gem", Kind.ICON, Rarity.RARE, "<aqua>❖ Gem", Material.NAME_TAG, "A diamond-cut gem"),
            crate("i_yin", Kind.ICON, Rarity.RARE, "<white>☯ Balance", Material.NAME_TAG, "Calm in a fight"),
            crate("i_medal", Kind.ICON, Rarity.EPIC, "<gold>✪ Medal", Material.NAME_TAG, "A gold medal"),
            crate("i_flame", Kind.ICON, Rarity.EPIC, "<#ff7a1a>✺ Blaze", Material.NAME_TAG, "A burst of fire"),
            crate("i_crown", Kind.ICON, Rarity.LEGENDARY, "<gradient:#ffd27a:#ff9500>♛ Crown", Material.NAME_TAG, "For the ones who rule"),
            crate("i_comet", Kind.ICON, Rarity.LEGENDARY, "<gradient:#b388ff:#5be3ff>☄ Comet", Material.NAME_TAG, "Streaks across the chat"),
            limited("i_hallow", Kind.ICON, "hallow", Rarity.RARE, "<gradient:#ff9a1f:#9b4dff>☾ Haunted Moon", Material.NAME_TAG, "Hallowforge only"),
            limited("i_frost", Kind.ICON, "frost", Rarity.RARE, "<#9fe6ff>❄ Snowflake", Material.NAME_TAG, "Frostforge only"),
            limited("i_emberheart", Kind.ICON, "emberheart", Rarity.RARE, "<#ff6b9d>♥ Heart", Material.NAME_TAG, "Emberheart only"),
            limited("i_bloomforge", Kind.ICON, "bloomforge", Rarity.RARE, "<#ffb7d5>✿ Blossom", Material.NAME_TAG, "Bloomforge only"),
            limited("i_sunforge", Kind.ICON, "sunforge", Rarity.RARE, "<#ffd23f>☀ Sun", Material.NAME_TAG, "Sunforge only"),
            limited("i_anniversary", Kind.ICON, "anniversary", Rarity.EPIC, "<gradient:#ffd27a:#ffffff>♚ Anniversary Seal", Material.NAME_TAG, "Anniversary only"),
            award("i_founder", Kind.ICON, "founder", "<gradient:#ffb347:#ffffff>✦ Founder's Mark", Material.NAME_TAG, "Awarded to the people who were here first"),
            award("i_champ_hallow", Kind.ICON, "hallow", "<gradient:#ff9a1f:#ffd27a>♛ Hallowforge Champion", Material.NAME_TAG, "Top of the Hallowforge leaderboard"),
            award("i_champ_frost", Kind.ICON, "frost", "<gradient:#9fe6ff:#ffd27a>♛ Frostforge Champion", Material.NAME_TAG, "Top of the Frostforge leaderboard"),
            award("i_champ_emberheart", Kind.ICON, "emberheart", "<gradient:#ff6b9d:#ffd27a>♛ Emberheart Champion", Material.NAME_TAG, "Top of the Emberheart leaderboard"),
            award("i_champ_bloomforge", Kind.ICON, "bloomforge", "<gradient:#ffb7d5:#ffd27a>♛ Bloomforge Champion", Material.NAME_TAG, "Top of the Bloomforge leaderboard"),
            award("i_champ_sunforge", Kind.ICON, "sunforge", "<gradient:#ffd23f:#ffffff>♛ Sunforge Champion", Material.NAME_TAG, "Top of the Sunforge leaderboard"),
            award("i_champ_anniversary", Kind.ICON, "anniversary", "<gradient:#ffd27a:#ffffff>♛ Anniversary Champion", Material.NAME_TAG, "Top of the Anniversary leaderboard"));

    /** What a name icon looks like in front of a name (MiniMessage, tags closed). */
    private static final Map<String, String> GLYPHS = Map.ofEntries(
            Map.entry("i_star", "<yellow>★</yellow>"), Map.entry("i_note", "<aqua>♪</aqua>"), Map.entry("i_bolt", "<yellow>⚡</yellow>"), Map.entry("i_clover", "<green>♣</green>"),
            Map.entry("i_swords", "<red>⚔</red>"), Map.entry("i_skull", "<white>☠</white>"), Map.entry("i_gem", "<aqua>❖</aqua>"), Map.entry("i_yin", "<white>☯</white>"),
            Map.entry("i_medal", "<gold>✪</gold>"), Map.entry("i_flame", "<#ff7a1a>✺</#ff7a1a>"),
            Map.entry("i_crown", "<gradient:#ffd27a:#ff9500>♛</gradient>"), Map.entry("i_comet", "<gradient:#b388ff:#5be3ff>☄</gradient>"),
            Map.entry("i_hallow", "<gradient:#ff9a1f:#9b4dff>☾</gradient>"), Map.entry("i_frost", "<#9fe6ff>❄</#9fe6ff>"), Map.entry("i_emberheart", "<#ff6b9d>♥</#ff6b9d>"),
            Map.entry("i_bloomforge", "<#ffb7d5>✿</#ffb7d5>"), Map.entry("i_sunforge", "<#ffd23f>☀</#ffd23f>"), Map.entry("i_anniversary", "<gradient:#ffd27a:#ffffff>♚</gradient>"),
            Map.entry("i_founder", "<gradient:#ffb347:#ffffff>✦</gradient>"),
            Map.entry("i_champ_hallow", "<gradient:#ff9a1f:#ffd27a>♛</gradient>"), Map.entry("i_champ_frost", "<gradient:#9fe6ff:#ffd27a>♛</gradient>"),
            Map.entry("i_champ_emberheart", "<gradient:#ff6b9d:#ffd27a>♛</gradient>"), Map.entry("i_champ_bloomforge", "<gradient:#ffb7d5:#ffd27a>♛</gradient>"),
            Map.entry("i_champ_sunforge", "<gradient:#ffd23f:#ffffff>♛</gradient>"), Map.entry("i_champ_anniversary", "<gradient:#ffd27a:#ffffff>♛</gradient>"));

    public static String glyph(String id) { return GLYPHS.get(id); }

    private static Cosmetics instance;

    /** The icon the player picked, ready to put before their name ("" for none). */
    public static String iconBadge(Player p) {
        Cosmetics c = instance;
        if (c == null) return "";
        String id = c.equipped(p, Kind.ICON), g = id == null ? null : GLYPHS.get(id);
        return g == null ? "" : g + " ";
    }



    /** Cosmetics of this season, all kinds. */
    public static List<Cosmetic> ofSeason(String season) { return ALL.stream().filter(c -> season.equals(c.season)).toList(); }

    /**
     * Cosmetics settings: shared/cosmetics/config.yml (the same on every server, and the deploy script never touches it), falling back to the
     * plugin config. Founders cut-off, season dates, throwback windows, crate odds and champion count all live here.
     */
    private final YamlConfiguration conf;

    YamlConfiguration conf() { return conf; }

    private YamlConfiguration loadConf() {
        File f = new File(dir, "config.yml");
        if (!f.exists()) {
            try {
                java.nio.file.Files.writeString(f.toPath(), String.join("\n",
                        "# BlackForgeMC cosmetics settings, shared by every server (read when a server starts).",
                        "#",
                        "# Everyone who joins on or before this date gets the Founders set (cape, flame, crown), once. Leave empty to switch it off.",
                        "# Set it to the end of launch week, e.g. founders-until: 2026-12-31",
                        "founders-until: ''",
                        "# Share of Cosmetic Crate openings that turn into a live event's cosmetic.",
                        "limited-chance: 0.2",
                        "# How many top players of each event season get the Champion cape.",
                        "champions: 3",
                        "# Bring a past season back for a short while (originals keep their gold border):",
                        "# throwbacks:",
                        "#   hallow: {start: '2027-10-28', end: '2027-11-03'}",
                        "# Event dates can be moved too (these are the defaults):",
                        "# event.emberheart: {start: '2027-02-07', end: '2027-02-16'}",
                        ""), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        // the file uses short keys; the code asks for cosmetics.<key>
        YamlConfiguration merged = new YamlConfiguration();
        for (String k : y.getKeys(true)) if (!y.isConfigurationSection(k)) merged.set(k.startsWith("event") ? k : "cosmetics." + k, y.get(k));
        merged.setDefaults(plugin.getConfig());
        return merged;
    }

    public Season season(String id) { return seasons.get(id); }

    /** Seasons that are on today (not counting throwbacks). */
    public List<Season> liveSeasons() {
        LocalDate today = LocalDate.now();
        return seasons.values().stream().filter(s -> s.live(today)).toList();
    }

    /** Seasons whose cosmetics can be got today: the live ones and any throwback. */
    public List<Season> activeSeasons() {
        LocalDate today = LocalDate.now();
        return seasons.values().stream().filter(s -> s.active(today)).toList();
    }

    public Collection<Season> allSeasons() { return seasons.values(); }

    /** Awarded rather than found: Founders and season champions. They can't be got any other way, ever. */
    public static boolean awardOnly(Cosmetic c) { return c.season != null && !c.crate && c.price < 0 && (c.id.startsWith("c_champ_") || c.id.startsWith("i_champ_") || "founder".equals(c.season)); }

    /** Whether this can still be obtained: always for ordinary ones, only while its season is on for limited ones. */
    public boolean available(Cosmetic c) {
        if (c.season == null) return true;
        if (awardOnly(c)) return false;
        Season s = seasons.get(c.season);
        return s != null && s.active(LocalDate.now());
    }

    private void loadSeasons() {
        for (Map.Entry<String, String[]> e : SEASON_DEFAULTS.entrySet()) {
            String[] d = e.getValue();
            try {
                LocalDate start = LocalDate.parse(conf.getString(d[4] + ".start", d[2])), end = LocalDate.parse(conf.getString(d[4] + ".end", d[3]));
                String tb = "cosmetics.throwbacks." + e.getKey();
                LocalDate tbStart = conf.isString(tb + ".start") ? LocalDate.parse(conf.getString(tb + ".start")) : null;
                LocalDate tbEnd = conf.isString(tb + ".end") ? LocalDate.parse(conf.getString(tb + ".end")) : null;
                if (tbEnd == null) tbStart = null;
                seasons.put(e.getKey(), new Season(e.getKey(), d[0], d[1], start, end, e.getKey().equals("founder"), tbStart, tbEnd));
            } catch (Exception ex) {
                plugin.getLogger().warning("cosmetics season " + e.getKey() + ": bad dates in config (" + ex.getMessage() + ")");
            }
        }
        limitedChance = Math.max(0, Math.min(1, conf.getDouble("cosmetics.limited-chance", 0.2)));
    }

    public static Cosmetic byId(String id) { for (Cosmetic c : ALL) if (c.id.equals(id)) return c; return null; }

    public static List<Cosmetic> of(Kind k) { return ALL.stream().filter(c -> c.kind == k).toList(); }

    /** Epic or better is guaranteed within this many crate openings, and Legendary within the second number. */
    public static final int PITY_EPIC = 10, PITY_LEGENDARY = 50;

    private final BlackForgeCore plugin;
    private final File dir;
    private final CosmeticFx fx;
    private final SeasonBoard board;
    private final Random rnd = new Random();
    private final Set<UUID> opening = new HashSet<>();

    /** What a player has equipped, remembered for a few seconds so effects don't read a file every tick. */
    private record Snap(long at, Map<Kind, String> equipped, Set<String> originals, boolean hideOthers) {}
    private final Map<UUID, Snap> snaps = new HashMap<>();

    public Cosmetics(BlackForgeCore plugin, File shared) {
        this.plugin = plugin;
        this.dir = new File(shared, "cosmetics");
        dir.mkdirs();
        this.conf = loadConf();
        loadSeasons();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        instance = this;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { for (Player online : Bukkit.getOnlinePlayers()) claimKeys(online); }, 100L);
        this.fx = new CosmeticFx(plugin, this);
        this.board = new SeasonBoard(plugin, this, shared);
        Hooks.onKill((killer, victim, where) -> {
            String id = equipped(killer, Kind.KILL);
            if (id != null) killEffect(id, victim.getLocation());
        });
        Hooks.onArcadeEnd((p, game, won) -> {
            String id = equipped(p, Kind.VICTORY);
            if (won && id != null) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) victory(id, p); }, 10L);
        });
        Bukkit.getCommandMap().register("blackforge", new Command("cosmetics", "BlackForgeMC cosmetics", "/cosmetics [crate]", List.of("cosmetic", "effects")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (a.length > 0 && a[0].equalsIgnoreCase("crate")) openCrate(p); else open(p);
                return true;
            }
        });
        Bukkit.getCommandMap().register("blackforge", new Command("pet", "Toggle your orange companion", "/pet <on|off>", List.of()) {
            @Override public boolean execute(CommandSender s, String label, String[] args) {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                OrangeCompanion.Toggle toggle = OrangeCompanion.Toggle.parse(args);
                if (toggle == null) { p.sendMessage(Ui.mm("<yellow>Use /pet on or /pet off.")); return true; }
                boolean enabled = toggle == OrangeCompanion.Toggle.ON;
                fx.setOrangeCompanion(p, enabled);
                p.sendMessage(Ui.mm(enabled ? "<gold>Orange companion on." : "<gray>Orange companion off."));
                return true;
            }
        });
        Bukkit.getCommandMap().register("blackforge", new Command("cosmeticcrate", "Open a Cosmetic Crate", "/cosmeticcrate", List.of("ccrate", "ckey")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) openCrate(p); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

    /** Pets, capes, trails and auras are hidden while this says true (e.g. inside an arcade match, where they would give hiders away). */
    public void setBlocked(Predicate<Player> rule) { fx.setBlocked(rule); }

    public void shutdown() { fx.shutdown(); }

    // ------------------------------------------------------------------ storage

    private File file(UUID u) { return new File(dir, u + ".yml"); }
    private YamlConfiguration load(UUID u) { return YamlConfiguration.loadConfiguration(file(u)); }
    private void save(UUID u, YamlConfiguration y) {
        try { y.save(file(u)); } catch (Exception e) { plugin.getLogger().warning("cosmetics save failed for " + u + ": " + e.getMessage()); }
        snaps.remove(u);
    }

    public boolean owns(Player p, String id) { return load(p.getUniqueId()).getStringList("owned").contains(id); }

    /** Give a cosmetic for free (event rewards). */
    public void unlock(Player p, String id) { unlock(p.getUniqueId(), id); }

    public void unlock(UUID u, String id) {
        YamlConfiguration y = load(u);
        List<String> owned = new ArrayList<>(y.getStringList("owned"));
        if (!owned.contains(id)) { owned.add(id); markOriginal(y, byId(id)); }
        y.set("owned", owned);
        save(u, y);
    }

    /** Give a cosmetic and record that they have it from its first run (awards, founders). */
    void unlockOriginal(UUID u, String id) {
        YamlConfiguration y = load(u);
        List<String> owned = new ArrayList<>(y.getStringList("owned"));
        if (!owned.contains(id)) owned.add(id);
        List<String> orig = new ArrayList<>(y.getStringList("originals"));
        if (!orig.contains(id)) orig.add(id);
        y.set("owned", owned);
        y.set("originals", orig);
        save(u, y);
    }

    /** Remember that this was got while its season was really on, so a later throwback can't take the gold border away from the first owners. */
    private void markOriginal(YamlConfiguration y, Cosmetic c) {
        if (c == null || c.season == null) return;
        Season s = seasons.get(c.season);
        if (s == null || !(s.grantOnly() || s.live(LocalDate.now()))) return;
        List<String> orig = new ArrayList<>(y.getStringList("originals"));
        if (!orig.contains(c.id)) { orig.add(c.id); y.set("originals", orig); }
    }

    /** A message for a player who may be offline or on another server; shown when they next join. */
    void addNotice(UUID u, String mm) {
        YamlConfiguration y = load(u);
        List<String> n = new ArrayList<>(y.getStringList("notices"));
        n.add(mm);
        y.set("notices", n);
        save(u, y);
    }

    void deliverNotices(Player p) {
        YamlConfiguration y = load(p.getUniqueId());
        List<String> n = y.getStringList("notices");
        if (n.isEmpty()) return;
        y.set("notices", null);
        save(p.getUniqueId(), y);
        for (String m : n) p.sendMessage(Ui.mm(m));
    }

    /** Keys waiting in the player's file: given while they were offline, turned into items the next time they join. */
    public int keys(UUID u) { return load(u).getInt("keys", 0); }

    private final NamespacedKey keyTag = new NamespacedKey(BlackForgeCore.NS, "cosmetic_key");

    /** The Cosmetic Key as an item: a Trial Key you can hold, trade or gift, and right-click to open a crate. */
    public ItemStack keyItem(int n) {
        ItemStack it = Ui.item(Material.TRIAL_KEY, "<gradient:#ffd27a:#ff4d00><bold>Cosmetic Key",
                "<gray>Right-click to open a Cosmetic Crate.", "<gray>Trails, auras, pets, capes, icons and effects.", "", "<dark_gray>Cosmetic only - nothing here changes how strong you are");
        it.setAmount(Math.max(1, Math.min(64, n)));
        it.editMeta(m -> { m.setEnchantmentGlintOverride(true); m.getPersistentDataContainer().set(keyTag, PersistentDataType.BYTE, (byte) 1); });
        return it;
    }

    public boolean isKey(ItemStack it) {
        return it != null && it.getType() == Material.TRIAL_KEY && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(keyTag, PersistentDataType.BYTE);
    }

    /** Keys in the player's inventory. */
    public int itemKeys(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (isKey(it)) n += it.getAmount();
        return n;
    }

    /** Keys the player can use right now: the items they hold plus any still waiting to be handed over. */
    public int keyCount(Player p) { return itemKeys(p) + keys(p.getUniqueId()); }

    /** Hand over n keys as items; whatever doesn't fit lands at their feet. */
    public void giveKeys(Player p, int n) {
        while (n > 0) {
            int stack = Math.min(64, n);
            p.getInventory().addItem(keyItem(stack)).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            n -= stack;
        }
    }

    /** Take up to n key items out of the inventory; returns how many were taken. */
    public int takeKeyItems(Player p, int n) {
        int taken = 0;
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length && taken < n; i++) {
            ItemStack it = inv[i];
            if (!isKey(it)) continue;
            int t = Math.min(n - taken, it.getAmount());
            if (t >= it.getAmount()) p.getInventory().setItem(i, null); else it.setAmount(it.getAmount() - t);
            taken += t;
        }
        return taken;
    }

    /** Keys that were given while the player was offline become real items now. */
    public void claimKeys(Player p) {
        YamlConfiguration y = load(p.getUniqueId());
        int n = y.getInt("keys", 0);
        if (n <= 0) return;
        y.set("keys", 0);
        save(p.getUniqueId(), y);
        giveKeys(p, n);
        p.sendMessage(Ui.mm("<gold>✦ <gray>You have <white>" + n + " <gradient:#ffd27a:#ff4d00>Cosmetic Key" + (n == 1 ? "" : "s") + "<gray> waiting - they're in your inventory. Right-click one to open a crate."));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
    }

    @EventHandler
    public void onUseKey(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        if (!isKey(e.getItem())) return;
        e.setCancelled(true);
        openCrate(e.getPlayer());
    }
    public int dust(UUID u) { return load(u).getInt("dust", 0); }

    SeasonBoard board() { return board; }

    /** Add (or take, when negative) Forge Dust for a player who may be offline; never goes below zero. */
    void addDust(UUID u, int n) {
        YamlConfiguration y = load(u);
        y.set("dust", Math.max(0, y.getInt("dust", 0) + n));
        save(u, y);
    }

    /** Keys as items when the player is here, else waiting in their file until they join. */
    void grantKeys(UUID u, int n) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) giveKeys(p, n); else addKeys(u, n);
    }

    /** Take a cosmetic away (it was sold or listed): off the owned list, unequipped. Returns whether they had it. */
    boolean takeOwned(UUID u, String id) {
        YamlConfiguration y = load(u);
        List<String> owned = new ArrayList<>(y.getStringList("owned"));
        if (!owned.remove(id)) return false;
        y.set("owned", owned);
        List<String> orig = new ArrayList<>(y.getStringList("originals"));
        if (orig.remove(id)) y.set("originals", orig);
        for (Kind k : Kind.values()) if (id.equals(y.getString("equipped." + k.key()))) y.set("equipped." + k.key(), null);
        save(u, y);
        Player p = Bukkit.getPlayer(u);
        if (p != null) fx.refresh(p);
        return true;
    }

    public List<String> ownedIds(UUID u) { return load(u).getStringList("owned"); }

    // pet levels: a pet gains experience while it walks with you, and more when you play

    public int petXp(UUID u, String id) { return load(u).getInt("petxp." + id, 0); }

    public void addPetXp(UUID u, String id, int n) {
        YamlConfiguration y = load(u);
        y.set("petxp." + id, y.getInt("petxp." + id, 0) + n);
        save(u, y);
    }

    public static final int PET_MAX = 10;

    /** Experience needed to reach level {@code lvl} (level 1 is free). */
    public static int petNeeded(int lvl) { return lvl <= 1 ? 0 : 150 * (lvl - 1) * (lvl - 1); }

    public static int petLevel(int xp) {
        int l = 1;
        while (l < PET_MAX && xp >= petNeeded(l + 1)) l++;
        return l;
    }

    /** Adds (or removes, when negative) Cosmetic Keys; returns the new total. */
    public int addKeys(UUID u, int n) {
        YamlConfiguration y = load(u);
        int total = Math.max(0, y.getInt("keys", 0) + n);
        y.set("keys", total);
        if (n > 0) y.set("keys-received", y.getInt("keys-received", 0) + n);
        save(u, y);
        return total;
    }

    /** Equipped cosmetic of this kind, from a copy that is at most a few seconds old. */
    public String equipped(Player p, Kind k) { return snap(p).equipped.get(k); }

    /** Whether this player got the cosmetic while it was first available (not in a later throwback). */
    public boolean isOriginal(Player p, String id) { return snap(p).originals.contains(id); }

    /** The player turned off other people's pets and capes (a setting in /settings). */
    public boolean hidesOthers(Player p) { return snap(p).hideOthers; }

    public boolean setting(UUID u, String key, boolean def) { return load(u).getBoolean("settings." + key, def); }

    public void setSetting(UUID u, String key, boolean value) {
        YamlConfiguration y = load(u);
        y.set("settings." + key, value);
        save(u, y);
    }

    /** Name (MiniMessage) of what the player has equipped in this category, or null. */
    public String equippedName(Player p, Kind k) {
        String id = equipped(p, k);
        Cosmetic c = id == null ? null : byId(id);
        return c == null ? null : c.name;
    }

    public int ownedCount(Player p, Kind k) {
        List<String> owned = load(p.getUniqueId()).getStringList("owned");
        return (int) of(k).stream().filter(c -> owned.contains(c.id)).count();
    }

    public void unequipAll(Player p) {
        YamlConfiguration y = load(p.getUniqueId());
        for (Kind k : Kind.values()) y.set("equipped." + k.key(), null);
        save(p.getUniqueId(), y);
        fx.refresh(p);
    }

    /** Set by /settings so the cosmetics menu can link back to it. */
    private java.util.function.Consumer<Player> settingsMenu;

    public void setSettingsMenu(java.util.function.Consumer<Player> open) { this.settingsMenu = open; }


    private Snap snap(Player p) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Snap s = snaps.get(u);
        if (s == null || now - s.at > 4000) {
            YamlConfiguration y = load(u);
            List<String> owned = y.getStringList("owned");
            Map<Kind, String> m = new EnumMap<>(Kind.class);
            for (Kind kind : Kind.values()) {
                String id = y.getString("equipped." + kind.key());
                if (id != null && owned.contains(id)) m.put(kind, id);
            }
            s = new Snap(now, m, new HashSet<>(y.getStringList("originals")), y.getBoolean("settings.hide-others"));
            snaps.put(u, s);
        }
        return s;
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            YamlConfiguration y = load(p.getUniqueId());
            boolean changed = false;
            LocalDate today = LocalDate.now();
            for (Season s : activeSeasons()) {
                boolean live = s.live(today);
                String seen = "seen." + s.id + (live ? s.start.getYear() : "tb" + s.tbStart);
                if (y.getBoolean(seen)) continue;
                y.set(seen, true);
                changed = true;
                p.sendMessage(Ui.mm(live
                        ? "<gold>★ <gray>Limited cosmetics are in the Cosmetic Crate for " + s.label() + "<gray> until <white>" + s.end + "<gray>. Gone after that! <white>/cosmetics"
                        : "<aqua>↺ <gray>" + s.label() + "<gray> is back for a short while, until <white>" + s.tbEnd + "<gray>! <white>/cosmetics"));
            }
            // Founders: everyone who joins before the cut-off (config cosmetics.founders-until) gets the Founders set, once
            String until = conf.getString("cosmetics.founders-until", "");
            if (!until.isBlank() && !y.getBoolean("founder")) {
                try {
                    if (!today.isAfter(LocalDate.parse(until.trim()))) {
                        List<String> owned = new ArrayList<>(y.getStringList("owned")), orig = new ArrayList<>(y.getStringList("originals"));
                        for (String id : new String[]{"c_founder", "t_founder", "a_founder", "i_founder"}) { if (!owned.contains(id)) owned.add(id); if (!orig.contains(id)) orig.add(id); }
                        y.set("owned", owned);
                        y.set("originals", orig);
                        y.set("founder", true);
                        changed = true;
                        p.sendMessage(Ui.mm("<gradient:#ffb347:#ffffff><bold>FOUNDER</bold></gradient> <gray>» You were here first. The <white>Founder's Mantle, Flame, Crown and Mark<gray> are yours for good, and they will never come back. <white>/cosmetics"));
                        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1f);
                    }
                } catch (Exception ex) { plugin.getLogger().warning("cosmetics.founders-until must be a date like 2026-12-31"); }
            }
            if (changed) { try { y.save(file(p.getUniqueId())); } catch (Exception ignored) { } snaps.remove(p.getUniqueId()); }
            claimKeys(p);
            deliverNotices(p);
        }, 80L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { snaps.remove(e.getPlayer().getUniqueId()); opening.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ menus

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        Kind kind;                 // null on the main page and the Limited page
        boolean limited;           // the Limited Time page
        Runnable back;             // where Back goes (null: the main cosmetics page)
        List<Cosmetic> items = List.of();
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int[] CATEGORY_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    /** The main page: six categories, the crate, and your totals. */
    public void open(Player p) {
        Menu m = new Menu();
        UUID u = p.getUniqueId();
        YamlConfiguration y = load(u);
        List<String> owned = y.getStringList("owned");
        m.inv = Bukkit.createInventory(m, 36, Ui.mm("<dark_gray>Cosmetics"));
        int total = 0, have = 0;
        Kind[] kinds = Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            Kind k = kinds[i];
            List<Cosmetic> list = of(k);
            int got = (int) list.stream().filter(c -> owned.contains(c.id)).count();
            total += list.size();
            have += got;
            String eq = y.getString("equipped." + k.key());
            Cosmetic ec = eq == null ? null : byId(eq);
            ItemStack it = Ui.item(k.icon, "<white><bold>" + k.title, "<gray>" + k.blurb, "",
                    "<gray>Owned: <white>" + got + "<dark_gray>/<white>" + list.size(),
                    "<gray>Equipped: " + (ec == null ? "<dark_gray>none" : ec.name),
                    "", "<yellow>Click to open");
            if (ec != null) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(CATEGORY_SLOTS[i], it);
        }
        int keys = keyCount(p), dust = y.getInt("dust", 0), sinceEpic = y.getInt("pity.epic", 0), sinceLegend = y.getInt("pity.legendary", 0);
        ItemStack crate = Ui.item(Material.TRIAL_KEY, "<gradient:#ffd27a:#ff4d00><bold>Cosmetic Crate",
                "<gray>Trails, auras, pets, capes and effects.", "<gray>Every pull is something new to show off.", "",
                "<gray>Your keys: " + (keys > 0 ? "<green>" : "<red>") + keys,
                "<gray>Epic or better guaranteed within <white>" + (PITY_EPIC - sinceEpic) + "<gray> openings",
                "<gray>Legendary guaranteed within <white>" + (PITY_LEGENDARY - sinceLegend) + "<gray> openings", "",
                keys > 0 ? "<yellow>Click to open a crate" : "<gray>Get Cosmetic Keys in the store: <white>/store");
        if (keys > 0) crate.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        m.inv.setItem(22, crate);
        m.inv.setItem(20, limitedButton(owned));
        m.inv.setItem(24, Ui.item(Material.COMPARATOR, "<white><bold>Settings", "<gray>Name colour, icons, visibility and more", "", "<yellow>Click to open /settings"));
        m.inv.setItem(30, Ui.item(Material.GLOWSTONE_DUST, "<#ffb347><bold>Forge Dust", "<gray>You have <white>" + dust + " <gray>dust",
                "<gray>Crate duplicates turn into dust.", "<gray>Shift-click a locked crate cosmetic to craft it:", Rarity.COMMON.color + Rarity.COMMON.craft + " <gray>common, "
                        + Rarity.RARE.color + Rarity.RARE.craft + " <gray>rare, " + Rarity.EPIC.color + Rarity.EPIC.craft + " <gray>epic, " + Rarity.LEGENDARY.color + Rarity.LEGENDARY.craft + " <gray>legendary"));
        m.inv.setItem(32, Ui.item(Material.BOOK, "<white>Your collection", "<gray>Unlocked <white>" + have + "<dark_gray>/<white>" + total,
                "<gray>Scrap: <#c6a15b>" + plugin.economy().scrap(u), "", "<gray>Kill and victory effects can also be bought with Scrap."));
        m.inv.setItem(35, Ui.item(Material.BARRIER, "<red>Unequip everything"));
        p.openInventory(m.inv);
    }

    public void openCategory(Player p, Kind k) { openCategory(p, k, null); }

    public void openCategory(Player p, Kind k, Runnable back) {
        Menu m = new Menu();
        m.kind = k;
        m.back = back;
        m.items = of(k);
        UUID u = p.getUniqueId();
        YamlConfiguration y = load(u);
        List<String> owned = y.getStringList("owned");
        int dust = y.getInt("dust", 0);
        m.inv = Bukkit.createInventory(m, 54, Ui.mm("<dark_gray>" + k.title));
        List<Cosmetic> sorted = new ArrayList<>(m.items);
        sorted.sort(Comparator.comparingInt(c -> c.season == null ? 1 : available(c) ? 0 : 2));   // live limited first, vaulted last
        m.items = sorted;
        for (int i = 0; i < sorted.size() && i < 45; i++) {
            m.inv.setItem(i, icon(sorted.get(i), y, owned, dust));
        }
        m.inv.setItem(45, Ui.item(Material.ARROW, "<white>Back"));
        m.inv.setItem(49, Ui.item(k.icon, "<white><bold>" + k.title, "<gray>" + k.blurb));
        m.inv.setItem(53, Ui.item(Material.BARRIER, "<red>Take off " + k.title.toLowerCase()));
        p.openInventory(m.inv);
    }

    /** One cosmetic as a menu item: rarity, how to get it, whether it is limited, and what clicking does. */
    private ItemStack icon(Cosmetic c, YamlConfiguration y, List<String> owned, int dust) {
        boolean has = owned.contains(c.id), on = c.id.equals(y.getString("equipped." + c.kind.key())), live = available(c);
        Season s = c.season == null ? null : seasons.get(c.season);
        List<String> lore = new ArrayList<>();
        lore.add(c.rarity.color + c.rarity.label + " <dark_gray>- <gray>" + c.lore);
        if (c.kind == Kind.ICON && GLYPHS.containsKey(c.id)) lore.add("<gray>Looks like: " + GLYPHS.get(c.id) + " <white>YourName");
        if (c.kind == Kind.PET && has) {
            int xp = y.getInt("petxp." + c.id, 0), lv = petLevel(xp);
            String pk = CosmeticFx.perkText(c.id);
            if (pk != null) lore.add("<aqua>Ability: <white>" + pk + " <dark_gray>(starts very slim, the second one joins at max level)");
            lore.add("<gold>Level " + lv + (lv >= PET_MAX ? " <gray>(max)" : " <dark_gray>- <gray>" + xp + "/" + petNeeded(lv + 1) + " xp"));
        }
        boolean original = has && y.getStringList("originals").contains(c.id);
        if (s != null) lore.add("<gold>★ LIMITED <dark_gray>- " + s.label() + (s.throwback(LocalDate.now()) && !s.live(LocalDate.now()) ? " <aqua>↺ THROWBACK" : ""));
        if (original) lore.add("<gold>✦ ORIGINAL OWNER <gray>- got it the first time round");
        lore.add("");
        if (on) lore.add("<green>✔ Equipped <gray>- click to take it off");
        else if (has) lore.add("<yellow>Click to equip");
        else if (awardOnly(c)) lore.add(c.id.startsWith("c_champ_") || c.id.startsWith("i_champ_") ? "<red>Awarded to the top players of " + (s == null ? "its event" : s.label()) : "<red>Awarded to the Founders only <gray>- it is never coming back");
        else if (!live) lore.add("<red>No longer available <gray>- it was only here during " + (s == null ? "its event" : s.label()));
        else {
            if (c.price >= 0) lore.add("<gray>Price: <#c6a15b>" + c.price + " Scrap <gray>- click to buy");
            else if (!c.crate) lore.add("<red>Event or achievement reward - can't be bought");
            else lore.add("<gray>Found in the <gradient:#ffd27a:#ff4d00>Cosmetic Crate" + (s != null ? " <gray>until <white>" + s.end : ""));
            if (c.crate) lore.add((dust >= c.rarity.craft ? "<green>" : "<gray>") + "Shift-click to craft: " + c.rarity.craft + " Forge Dust <dark_gray>(you have " + dust + ")");
        }
        ItemStack it = Ui.item(c.icon, (original ? "<gold>✦ </gold>" : "") + c.name + (on ? " <green>(equipped)" : has ? "" : live ? " <dark_gray>(locked)" : " <red>(vaulted)"), lore.toArray(new String[0]));
        if (on || (s != null && live && !has)) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return it;
    }

    private ItemStack limitedButton(List<String> owned) {
        List<Season> live = activeSeasons();
        if (live.isEmpty()) return Ui.item(Material.CLOCK, "<gold><bold>Limited Time", "<gray>Nothing limited is on right now.", "<gray>Event cosmetics return with the next event,", "<gray>and what you own stays yours forever.");
        LocalDate today = LocalDate.now();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Cosmetics you can only get right now:");
        for (Season s : live) {
            List<Cosmetic> items = ofSeason(s.id);
            long got = items.stream().filter(c -> owned.contains(c.id)).count();
            lore.add(" " + s.label() + " <gray>- " + got + "/" + items.size() + " <dark_gray>(" + s.daysLeft(today) + " day" + (s.daysLeft(today) == 1 ? "" : "s") + " left)");
        }
        lore.add("");
        lore.add("<gray>About " + Math.round(limitedChance * 100) + "% of crate openings turn into one.");
        lore.add("<yellow>Click to see them");
        return glint(Ui.item(Material.CLOCK, "<gold><bold>★ Limited Time", lore.toArray(new String[0])));
    }

    public void openLimited(Player p) { openLimited(p, null); }

    public void openLimited(Player p, Runnable back) {
        Menu m = new Menu();
        m.limited = true;
        m.back = back;
        YamlConfiguration y = load(p.getUniqueId());
        List<String> owned = y.getStringList("owned");
        int dust = y.getInt("dust", 0);
        m.inv = Bukkit.createInventory(m, 54, Ui.mm("<dark_gray>Limited Time"));
        List<Cosmetic> items = new ArrayList<>();
        for (Season s : activeSeasons()) for (Cosmetic c : ofSeason(s.id)) if (c.crate || available(c)) items.add(c);
        m.items = items;
        for (int i = 0; i < items.size() && i < 45; i++) m.inv.setItem(i, icon(items.get(i), y, owned, dust));
        m.inv.setItem(45, Ui.item(Material.ARROW, "<white>Back"));
        m.inv.setItem(47, boardItem(p));
        m.inv.setItem(49, Ui.item(Material.CLOCK, "<gold><bold>Limited Time", "<gray>Gone when the event ends -", "<gray>but yours forever if you have it."));
        p.openInventory(m.inv);
    }

    /** The season leaderboard: top three and where you stand, for every season that is on. */
    private ItemStack boardItem(Player p) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Earn Season Points from KOTH, bosses, the Colosseum,");
        lore.add("<gray>arcade wins, kills and event trades. When the season");
        lore.add("<gray>ends, the top " + conf.getInt("cosmetics.champions", 3) + " get a Champion cape for good.");
        List<Season> live = liveSeasons();
        if (live.isEmpty()) lore.add("");
        for (Season s : live) {
            lore.add("");
            lore.add(s.label());
            List<SeasonBoard.Entry> top = board.top(s, 3);
            if (top.isEmpty()) lore.add("<dark_gray> nobody has scored yet");
            for (int i = 0; i < top.size(); i++) lore.add(" <gold>#" + (i + 1) + " <white>" + top.get(i).name() + " <dark_gray>- <gray>" + top.get(i).points());
            int place = board.placeOf(s, p.getUniqueId());
            lore.add(place == 0 ? " <gray>You: no points yet" : " <green>You: #" + place + " <gray>(" + board.pointsOf(s, p.getUniqueId()) + " points)");
        }
        return Ui.item(Material.GOLD_INGOT, "<gold><bold>Season Leaderboard", lore.toArray(new String[0]));
    }

    private void reopen(Player p, Menu m) {
 if (m.limited) openLimited(p, m.back); else openCategory(p, m.kind, m.back); }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getView().getTopInventory().getHolder();
        if (h instanceof Menu || h instanceof CrateView) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getView().getTopInventory().getHolder();
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (holder instanceof CrateView cv) { e.setCancelled(true); if (e.getClickedInventory() == e.getView().getTopInventory()) crateClick(p, cv, e.getSlot()); return; }
        if (!(holder instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        if (m.kind == null && !m.limited) { mainClick(p, slot); return; }
        if (slot == 45) { if (m.back != null) m.back.run(); else open(p); return; }
        if (slot == 53 && !m.limited) {
            YamlConfiguration y = load(p.getUniqueId());
            y.set("equipped." + m.kind.key(), null);
            save(p.getUniqueId(), y);
            fx.refresh(p);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f);
            openCategory(p, m.kind, m.back);
            return;
        }
        if (slot >= m.items.size() || slot >= 45) return;
        Cosmetic c = m.items.get(slot);
        UUID u = p.getUniqueId();
        YamlConfiguration y = load(u);
        List<String> owned = new ArrayList<>(y.getStringList("owned"));
        String key = "equipped." + c.kind.key();
        if (owned.contains(c.id)) {
            y.set(key, c.id.equals(y.getString(key)) ? null : c.id);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        } else if (!available(c)) {
            p.sendMessage(Ui.mm("<red>" + c.name + "<red> was only available during " + (c.season == null ? "its event" : seasons.get(c.season).label()) + "<red> and can't be obtained any more."));
            return;
        } else if (e.isShiftClick() && c.crate) {
            int dust = y.getInt("dust", 0);
            if (dust < c.rarity.craft) { p.sendMessage(Ui.mm("<red>Crafting " + c.name + "<red> takes <#ffb347>" + c.rarity.craft + " Forge Dust<red>. You have " + dust + ".")); return; }
            y.set("dust", dust - c.rarity.craft);
            owned.add(c.id);
            markOriginal(y, c);
            y.set("owned", owned);
            y.set(key, c.id);
            p.sendMessage(Ui.mm("<green>Crafted and equipped " + c.name + "<green> for <#ffb347>" + c.rarity.craft + " Forge Dust<green>!"));
            p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 0.8f, 1.2f);
        } else if (c.price >= 0) {
            if (plugin.economy().walletOf(u) == null) { p.sendMessage(Ui.mm("<red>Buy cosmetics in a game mode: Scrap belongs to the mode you earned it in.")); return; }
            if (!plugin.economy().takeScrap(p, c.price)) { p.sendMessage(Ui.mm("<red>You need <#c6a15b>" + c.price + " Scrap<red> for that.")); return; }
            owned.add(c.id);
            y.set("owned", owned);
            y.set(key, c.id);
            p.sendMessage(Ui.mm("<green>Unlocked and equipped " + c.name + "<green>!"));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        } else {
            p.sendMessage(Ui.mm(c.crate ? "<gray>" + c.name + "<gray> comes from the <gold>Cosmetic Crate<gray>. Get keys with <white>/store<gray>." : "<red>That one is an event or achievement reward."));
            return;
        }
        save(u, y);
        String now = y.getString(key);
        if (now != null && now.equals(c.id)) preview(p, c);
        fx.refresh(p);
        reopen(p, m);
    }

    private void mainClick(Player p, int slot) {
        Kind[] kinds = Kind.values();
        for (int i = 0; i < kinds.length; i++) if (CATEGORY_SLOTS[i] == slot) { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f); openCategory(p, kinds[i]); return; }
        if (slot == 22) { openCrate(p); return; }
        if (slot == 20) { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f); openLimited(p); return; }
        if (slot == 24 && settingsMenu != null) { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f); settingsMenu.accept(p); return; }
        if (slot == 35) {
            YamlConfiguration y = load(p.getUniqueId());
            for (Kind k : kinds) y.set("equipped." + k.key(), null);
            save(p.getUniqueId(), y);
            fx.refresh(p);
            p.sendMessage(Ui.mm("<gray>Every cosmetic taken off."));
            open(p);
        }
    }

    /** Shows what a one-shot effect looks like right after you pick it. */
    private void preview(Player p, Cosmetic c) {
        if (c.kind == Kind.KILL) killEffect(c.id, p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2)));
        else if (c.kind == Kind.VICTORY) victory(c.id, p);
    }

    // ------------------------------------------------------------------ the crate

    /** The crate window; {@code done} flips when the roll has stopped and the buttons are live. */
    private static final class CrateView implements InventoryHolder {
        Inventory inv;
        boolean done;
        @Override public Inventory getInventory() { return inv; }
    }

    private Rarity rollRarity(YamlConfiguration y) {
        int sinceEpic = y.getInt("pity.epic", 0), sinceLegend = y.getInt("pity.legendary", 0);
        if (sinceLegend + 1 >= PITY_LEGENDARY) return Rarity.LEGENDARY;
        if (sinceEpic + 1 >= PITY_EPIC) return rnd.nextInt(Rarity.EPIC.weight + Rarity.LEGENDARY.weight) < Rarity.LEGENDARY.weight ? Rarity.LEGENDARY : Rarity.EPIC;
        int total = 0;
        for (Rarity r : Rarity.values()) total += r.weight;
        int n = rnd.nextInt(total);
        for (Rarity r : Rarity.values()) { if (n < r.weight) return r; n -= r.weight; }
        return Rarity.COMMON;
    }

    private Cosmetic pick(Rarity r) {
        List<Cosmetic> pool = ALL.stream().filter(c -> c.crate && c.season == null && c.rarity == r).toList();
        return pool.get(rnd.nextInt(pool.size()));
    }

    /** The cosmetic for a pull of this rarity: sometimes one from a live season (when it has one of that rarity), otherwise from the permanent pool. */
    private Cosmetic pickWithSeasons(Rarity r) {
        List<Season> live = activeSeasons();
        if (!live.isEmpty() && rnd.nextDouble() < limitedChance) {
            List<Cosmetic> pool = new ArrayList<>();
            for (Season s : live) for (Cosmetic c : ofSeason(s.id)) if (c.crate && c.rarity == r) pool.add(c);
            if (!pool.isEmpty()) return pool.get(rnd.nextInt(pool.size()));
        }
        return pick(r);
    }

    /** A random cosmetic for the spinning strip, to look like a real pool. */
    private Cosmetic filler() { return pickWithSeasons(rollRarity(new YamlConfiguration())); }

    /** Spend a key and open a crate. */
    public void openCrate(Player p) {
        UUID u = p.getUniqueId();
        if (!opening.add(u)) return;
        claimKeys(p);
        YamlConfiguration y = load(u);
        if (takeKeyItems(p, 1) < 1) {
            opening.remove(u);
            p.sendMessage(Ui.mm("<red>You need a <gradient:#ffd27a:#ff4d00>Cosmetic Key<red> to open a crate. <gray>Get one in the store: <white>/store"));
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.8f, 1f);
            return;
        }
        Rarity r = rollRarity(y);
        Cosmetic win = pickWithSeasons(r);
        List<String> owned = new ArrayList<>(y.getStringList("owned"));
        boolean dupe = owned.contains(win.id);
        int dustGain = dupe ? r.dust : 0;
        if (!dupe) owned.add(win.id);
        y.set("owned", owned);
        if (!dupe) markOriginal(y, win);
        y.set("owned", owned);
        y.set("dust", y.getInt("dust", 0) + dustGain);
        y.set("opened", y.getInt("opened", 0) + 1);
        y.set("pity.epic", r == Rarity.EPIC || r == Rarity.LEGENDARY ? 0 : y.getInt("pity.epic", 0) + 1);
        y.set("pity.legendary", r == Rarity.LEGENDARY ? 0 : y.getInt("pity.legendary", 0) + 1);
        save(u, y);
        Hooks.stat(p, "cosmetic_crate", 1);

        CrateView cv = new CrateView();
        cv.inv = Bukkit.createInventory(cv, 27, Ui.mm("<gradient:#ffd27a:#ff4d00>Cosmetic Crate"));
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) cv.inv.setItem(i, pane);
        cv.inv.setItem(4, Ui.item(Material.HOPPER, "<white>▼"));
        cv.inv.setItem(22, Ui.item(Material.HOPPER, "<white>▲"));
        p.openInventory(cv.inv);

        // the strip: the winner sits in the middle (slot 13) once the last step is reached
        int steps = 34;
        List<Cosmetic> strip = new ArrayList<>();
        for (int i = 0; i < steps + 5; i++) strip.add(filler());
        strip.set(steps, win);
        final boolean isDupe = dupe;
        final int gain = dustGain;
        new org.bukkit.scheduler.BukkitRunnable() {
            int step = 0, wait = 0, gap = 1;
            @Override public void run() {
                if (!p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != cv) {
                    // closed early: the prize is already theirs, so just announce it and stop
                    cancel();
                    cv.done = true;
                    opening.remove(u);
                    finishCrate(p, win, r, isDupe, gain, false);
                    return;
                }
                if (++wait < gap) return;
                wait = 0;
                step++;
                for (int s = 0; s < 9; s++) {
                    int idx = step - 4 + s;
                    Cosmetic c = idx >= 0 && idx < strip.size() ? strip.get(idx) : null;
                    cv.inv.setItem(9 + s, c == null ? pane : Ui.item(c.icon, c.name, c.rarity.color + c.rarity.label));
                }
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f + step * 0.02f);
                if (step > 18) gap = 2;
                if (step > 26) gap = 3;
                if (step > 30) gap = 5;
                if (step >= steps) {
                    cancel();
                    cv.done = true;
                    opening.remove(u);
                    cv.inv.setItem(13, glint(Ui.item(win.icon, win.name, win.rarity.color + win.rarity.label, "<gray>" + win.lore)));
                    finishCrate(p, win, r, isDupe, gain, true);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> showButtons(p, cv), 20L);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static ItemStack glint(ItemStack it) { it.editMeta(m -> m.setEnchantmentGlintOverride(true)); return it; }

    private void showButtons(Player p, CrateView cv) {
        if (!p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != cv) return;
        int keys = keyCount(p);
        cv.inv.setItem(20, Ui.item(Material.ARROW, "<white>Back to cosmetics"));
        cv.inv.setItem(24, keys > 0 ? Ui.item(Material.TRIAL_KEY, "<yellow>Open another crate", "<gray>Keys left: <white>" + keys) : Ui.item(Material.TRIPWIRE_HOOK, "<gray>No keys left", "<gray>Get more: <white>/store"));
    }

    private void crateClick(Player p, CrateView cv, int slot) {
        if (!cv.done) return;
        if (slot == 20) open(p);
        else if (slot == 24 && keyCount(p) > 0) openCrate(p);
    }

    private void finishCrate(Player p, Cosmetic win, Rarity r, boolean dupe, int dust, boolean animated) {
        p.sendMessage(Ui.mm("<gradient:#ffd27a:#ff4d00><bold>COSMETIC CRATE</bold></gradient> <gray>» " + r.color + "<bold>" + r.label.toUpperCase() + "</bold> <white>" + win.name
                + " <dark_gray>(" + win.kind.title.toLowerCase().replaceAll("s$", "") + ")" + (win.season != null ? " <gold>★ LIMITED" : "")));
        if (dupe) p.sendMessage(Ui.mm("<gray>You already had it, so it turned into <#ffb347>" + dust + " Forge Dust<gray>."));
        else p.sendMessage(Ui.mm("<green>New! <gray>Equip it in <white>/cosmetics<gray>."));
        Location at = p.getLocation().add(0, 1, 0);
        World w = p.getWorld();
        float pitch = r == Rarity.COMMON ? 1.2f : r == Rarity.RARE ? 1.4f : r == Rarity.EPIC ? 1.0f : 0.8f;
        p.playSound(p.getLocation(), r.ordinal() >= Rarity.EPIC.ordinal() ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.9f, pitch);
        if (r == Rarity.COMMON) return;
        w.spawnParticle(r == Rarity.RARE ? Particle.HAPPY_VILLAGER : r == Rarity.EPIC ? Particle.WITCH : Particle.TOTEM_OF_UNDYING, at, r == Rarity.LEGENDARY ? 80 : 30, 0.5, 0.8, 0.5, r == Rarity.LEGENDARY ? 0.5 : 0.05);
        if (r == Rarity.LEGENDARY) {
            Bukkit.broadcast(Ui.mm("<gold>✦ <white>" + p.getName() + " <gray>just pulled the " + win.rarity.color + "Legendary <white>" + win.name + "<gray> from a <gradient:#ffd27a:#ff4d00>Cosmetic Crate<gray>!"));
            w.spawn(at, Firework.class, fw -> {
                FireworkMeta m = fw.getFireworkMeta();
                m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.ORANGE, Color.YELLOW).withFade(Color.RED).trail(true).build());
                m.setPower(0);
                fw.setFireworkMeta(m);
                fw.detonate();
            });
        }
    }

    // ------------------------------------------------------------------ admin / store

    private OfflinePlayerRef find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new OfflinePlayerRef(online.getUniqueId(), online.getName(), online);
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        return off == null ? null : new OfflinePlayerRef(off.getUniqueId(), off.getName() == null ? name : off.getName(), null);
    }

    private record OfflinePlayerRef(UUID id, String name, Player online) {}

    /**
     * Console and staff: {@code /blackforge cosmetickey <give|take|check> <player> [n]} and {@code /blackforge cosmetic give <player> <id>}.
     * The store runs {@code blackforge cosmetickey give {username} 1} for every key bought.
     */
    public boolean admin(CommandSender s, String[] a) {
        if (a.length == 0) return false;
        boolean key = a[0].equalsIgnoreCase("cosmetickey"), item = a[0].equalsIgnoreCase("cosmetic");
        if (!key && !item) return false;
        if (!(s instanceof org.bukkit.command.ConsoleCommandSender) && !s.hasPermission("blackforge.admin")) { s.sendMessage("You can't use that."); return true; }
        if (a.length < 3) {
            s.sendMessage(key ? "/blackforge cosmetickey <give|take|check> <player> [amount]" : "/blackforge cosmetic <give|take> <player> <id>");
            return true;
        }
        OfflinePlayerRef t = find(a[2]);
        if (t == null) { s.sendMessage("Unknown player '" + a[2] + "' (they must have joined the network before)."); return true; }
        String op = a[1].toLowerCase();
        if (key) {
            int n = 1;
            if (a.length > 3) { try { n = Math.max(1, Integer.parseInt(a[3])); } catch (NumberFormatException ex) { s.sendMessage("Amount must be a number."); return true; } }
            switch (op) {
                case "give" -> {
                    if (t.online != null) giveKeys(t.online, n); else addKeys(t.id, n);
                    s.sendMessage("Gave " + n + " Cosmetic Key(s) to " + t.name + (t.online != null ? " (as items)." : " (they get the items when they next join)."));
                    if (t.online != null) {
                        t.online.sendMessage(Ui.mm("<gold>✦ <gray>You received <white>" + n + " <gradient:#ffd27a:#ff4d00>Cosmetic Key" + (n == 1 ? "" : "s") + "<gray>! Right-click one to open a crate."));
                        t.online.playSound(t.online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
                    }
                }
                case "take" -> {
                    int fromItems = t.online == null ? 0 : takeKeyItems(t.online, n);
                    if (fromItems < n) addKeys(t.id, -(n - fromItems));
                    s.sendMessage("Took keys from " + t.name + " (" + fromItems + " from their inventory, the rest from waiting keys).");
                }

                case "check" -> s.sendMessage(t.name + " has " + (t.online != null ? itemKeys(t.online) + " key item(s) and " : "") + keys(t.id) + " waiting key(s), and " + dust(t.id) + " Forge Dust.");
                default -> s.sendMessage("/blackforge cosmetickey <give|take|check> <player> [amount]");
            }
            return true;
        }
        Cosmetic c = a.length > 3 ? byId(a[3]) : null;
        if (c == null) { s.sendMessage("Unknown cosmetic id. Ids: " + String.join(", ", ALL.stream().map(Cosmetic::id).toList())); return true; }
        if (op.equals("give")) {
            unlock(t.id, c.id);
            s.sendMessage("Unlocked " + c.id + " for " + t.name + ".");
            if (t.online != null) t.online.sendMessage(Ui.mm("<gold>★ <gray>You unlocked " + c.name + "<gray>! Equip it in <white>/cosmetics"));
        } else if (op.equals("take")) {
            YamlConfiguration y = load(t.id);
            List<String> owned = new ArrayList<>(y.getStringList("owned"));
            owned.remove(c.id);
            y.set("owned", owned);
            save(t.id, y);
            s.sendMessage("Removed " + c.id + " from " + t.name + ".");
        } else s.sendMessage("/blackforge cosmetic <give|take> <player> <id>");
        return true;
    }

    public List<String> adminTabs(String[] a) {
        if (a.length == 1) return List.of("cosmetickey", "cosmetic");
        if (a.length == 2 && a[0].equalsIgnoreCase("cosmetickey")) return List.of("give", "take", "check");
        if (a.length == 2 && a[0].equalsIgnoreCase("cosmetic")) return List.of("give", "take");
        if (a.length == 4 && a[0].equalsIgnoreCase("cosmetic")) return ALL.stream().map(Cosmetic::id).toList();
        return null;
    }

    // ------------------------------------------------------------------ one-shot effects

    private record Layer(Particle p, int n, double dx, double dy, double dz, double speed, Color dust, float size) {}
    private record Burst(Layer[] layers, Sound[] sounds, float pitch) {}

    private static Layer fx(Particle p, int n, double dx, double dy, double dz, double speed) { return new Layer(p, n, dx, dy, dz, speed, null, 0); }
    private static Layer dust(Color c, int n, double dx, double dy, double dz, float size) { return new Layer(Particle.DUST, n, dx, dy, dz, 0, c, size); }

    /** Built on first use: sounds and particles need the running server. */
    private static final class Bursts {
    static final Map<String, Burst> MAP = Map.of(
            "k_broken", new Burst(new Layer[]{fx(Particle.HEART, 10, 0.4, 0.6, 0.4, 0), fx(Particle.DAMAGE_INDICATOR, 10, 0.3, 0.5, 0.3, 0.1), dust(Color.fromRGB(210, 30, 70), 40, 0.4, 0.7, 0.4, 1.5f), fx(Particle.CRIT, 20, 0.3, 0.5, 0.3, 0.3)},
                    new Sound[]{Sound.BLOCK_GLASS_BREAK, Sound.ENTITY_VILLAGER_NO}, 1.1f),
            "k_sunflare", new Burst(new Layer[]{fx(Particle.FLAME, 50, 0.3, 0.7, 0.3, 0.12), fx(Particle.END_ROD, 30, 0.2, 0.8, 0.2, 0.2), dust(Color.fromRGB(255, 225, 90), 40, 0.5, 0.8, 0.5, 1.6f)},
                    new Sound[]{Sound.ITEM_FIRECHARGE_USE, Sound.BLOCK_BEACON_ACTIVATE}, 1.6f),
            "v_bloom", new Burst(new Layer[]{fx(Particle.CHERRY_LEAVES, 14, 1.6, 0.4, 1.6, 0), fx(Particle.HAPPY_VILLAGER, 4, 1.4, 0.3, 1.4, 0), dust(Color.fromRGB(255, 160, 200), 6, 1.4, 0.3, 1.4, 1.3f)},
                    new Sound[]{Sound.ENTITY_PLAYER_LEVELUP, Sound.BLOCK_AMETHYST_BLOCK_CHIME}, 1.4f),
            "v_anniv", new Burst(new Layer[]{fx(Particle.FIREWORK, 12, 1.2, 0.5, 1.2, 0.1), fx(Particle.END_ROD, 6, 1.0, 0.5, 1.0, 0.1), dust(Color.fromRGB(255, 205, 80), 10, 1.4, 0.6, 1.4, 1.6f), fx(Particle.TOTEM_OF_UNDYING, 4, 0.8, 0.5, 0.8, 0.4)},
                    new Sound[]{Sound.ENTITY_FIREWORK_ROCKET_BLAST, Sound.UI_TOAST_CHALLENGE_COMPLETE}, 1.0f));
    }

    private static void play(Burst b, Location at) {
        World w = at.getWorld();
        for (Layer l : b.layers) {
            if (l.dust != null) w.spawnParticle(Particle.DUST, at, l.n, l.dx, l.dy, l.dz, 0, new Particle.DustOptions(l.dust, l.size));
            else w.spawnParticle(l.p, at, l.n, l.dx, l.dy, l.dz, l.speed);
        }
        for (Sound s : b.sounds) w.playSound(at, s, 0.9f, b.pitch);
    }

    public void killEffect(String id, Location at) {

        World w = at.getWorld();
        Location c = at.clone().add(0, 1, 0);
        switch (id) {
            case "flames" -> { w.spawnParticle(Particle.FLAME, c, 60, 0.3, 0.6, 0.3, 0.08); w.playSound(c, Sound.ITEM_FIRECHARGE_USE, 1f, 0.8f); }
            case "hearts" -> { w.spawnParticle(Particle.HEART, c, 12, 0.5, 0.6, 0.5, 0); w.playSound(c, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 2f); }
            case "souls" -> {
                w.spawnParticle(Particle.SOUL, c, 25, 0.3, 0.5, 0.3, 0.05);
                w.spawnParticle(Particle.SCULK_SOUL, c, 10, 0.2, 0.8, 0.2, 0.08);
                w.playSound(c, Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.8f);
            }
            case "lightning" -> w.strikeLightningEffect(at);
            case "anvil" -> {
                w.spawnParticle(Particle.LAVA, c, 15, 0.3, 0.3, 0.3, 0);
                w.spawnParticle(Particle.FIREWORK, c, 30, 0.2, 0.4, 0.2, 0.15);
                w.playSound(c, Sound.BLOCK_ANVIL_LAND, 0.8f, 1.2f);
            }
            case "pumpkin" -> {
                w.spawnParticle(Particle.BLOCK, c, 50, 0.4, 0.5, 0.4, Material.CARVED_PUMPKIN.createBlockData());
                w.spawnParticle(Particle.DUST, c, 30, 0.5, 0.6, 0.5, new Particle.DustOptions(Color.ORANGE, 1.6f));
                w.playSound(c, Sound.ENTITY_WITCH_CELEBRATE, 1f, 1f);
            }
            case "frost" -> {
                w.spawnParticle(Particle.SNOWFLAKE, c, 70, 0.2, 0.3, 0.2, 0.25);
                w.spawnParticle(Particle.BLOCK, c, 40, 0.4, 0.5, 0.4, Material.BLUE_ICE.createBlockData());
                w.playSound(c, Sound.BLOCK_GLASS_BREAK, 1f, 0.7f);
                w.playSound(c, Sound.BLOCK_POWDER_SNOW_BREAK, 1f, 0.6f);
            }
            case "forgemaster" -> {
                w.spawnParticle(Particle.LAVA, c, 20, 0.3, 0.3, 0.3, 0);
                w.spawnParticle(Particle.DUST, c, 50, 0.5, 0.8, 0.5, new Particle.DustOptions(Color.fromRGB(255, 160, 40), 1.8f));
                w.spawnParticle(Particle.FLAME, c, 40, 0.1, 0.6, 0.1, 0.12);
                w.playSound(c, Sound.BLOCK_ANVIL_USE, 0.9f, 0.6f);
                w.playSound(c, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.7f);
            }
            case "shatter" -> {
                w.spawnParticle(Particle.BLOCK, c, 70, 0.35, 0.6, 0.35, Material.LIGHT_BLUE_STAINED_GLASS.createBlockData());
                w.spawnParticle(Particle.CRIT, c, 25, 0.3, 0.5, 0.3, 0.3);
                w.playSound(c, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.9f);
                w.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1f, 1.3f);
            }
            case "inferno" -> {
                for (int i = 0; i < 12; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        Location up = at.clone().add(0, 0.3 + k * 0.28, 0);
                        w.spawnParticle(Particle.FLAME, up, 10, 0.18, 0.05, 0.18, 0.02);
                        w.spawnParticle(Particle.LAVA, up, 1, 0.1, 0.05, 0.1, 0);
                    }, k);
                }
                w.playSound(c, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.6f);
                w.playSound(c, Sound.ITEM_FIRECHARGE_USE, 1f, 0.5f);
            }
            case "voidrip" -> {
                w.spawnParticle(Particle.PORTAL, c, 120, 0.3, 0.6, 0.3, 1.2);
                w.spawnParticle(Particle.REVERSE_PORTAL, c, 40, 0.2, 0.4, 0.2, 0.2);
                w.spawnParticle(Particle.DUST, c, 30, 0.4, 0.7, 0.4, new Particle.DustOptions(Color.fromRGB(150, 90, 255), 1.6f));
                w.playSound(c, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 0.5f);
                w.playSound(c, Sound.BLOCK_END_PORTAL_SPAWN, 0.4f, 1.6f);
            }
            case "reaper" -> {
                w.spawnParticle(Particle.SOUL, c, 30, 0.3, 0.6, 0.3, 0.06);
                w.spawnParticle(Particle.SCULK_SOUL, c, 14, 0.25, 0.9, 0.25, 0.1);
                w.spawnParticle(Particle.DUST, c, 40, 0.4, 0.8, 0.4, new Particle.DustOptions(Color.fromRGB(110, 40, 190), 1.6f));
                w.spawnParticle(Particle.WITCH, c, 20, 0.3, 0.5, 0.3, 0);
                w.playSound(c, Sound.ENTITY_WITHER_AMBIENT, 0.8f, 0.6f);
                w.playSound(c, Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.5f);
            }
            default -> { Burst b = Bursts.MAP.get(id); if (b != null) play(b, c); }
        }
    }

    public void victory(String id, Player p) {
        World w = p.getWorld();
        Location l = p.getLocation();
        switch (id) {
            case "fireworks" -> {
                for (int i = 0; i < 4; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        Location at = p.getLocation().add(Math.cos(k * 1.57) * 2, 1, Math.sin(k * 1.57) * 2);
                        w.spawn(at, Firework.class, fw -> {
                            FireworkMeta m = fw.getFireworkMeta();
                            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.ORANGE, Color.YELLOW).withFade(Color.RED).trail(true).build());
                            m.setPower(1);
                            fw.setFireworkMeta(m);
                        });
                    }, i * 8L);
                }
            }
            case "roar" -> {
                w.playSound(l, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 1f);
                w.spawnParticle(Particle.DRAGON_BREATH, l.clone().add(0, 1, 0), 120, 1.2, 0.6, 1.2, 0.02);
            }
            case "goldrain" -> {
                for (int i = 0; i < 20; i++) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline()) w.spawnParticle(Particle.ITEM, p.getLocation().add(0, 4, 0), 12, 2, 0.3, 2, 0.02, new ItemStack(Material.GOLD_NUGGET));
                }, i * 3L);
                w.playSound(l, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
            }
            case "totem" -> { w.spawnParticle(Particle.TOTEM_OF_UNDYING, l.clone().add(0, 1, 0), 120, 0.5, 1, 0.5, 0.5); w.playSound(l, Sound.ITEM_TOTEM_USE, 0.8f, 1.1f); }
            case "completionist" -> {
                // a ring of forge-fire that climbs around you, then a burst of stars
                for (int i = 0; i < 24; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        Location at = p.getLocation().add(Math.cos(k * 0.52) * 1.4, k * 0.12, Math.sin(k * 0.52) * 1.4);
                        w.spawnParticle(Particle.FLAME, at, 4, 0.05, 0.05, 0.05, 0.01);
                        w.spawnParticle(Particle.DUST, at, 3, 0.05, 0.05, 0.05, new Particle.DustOptions(Color.fromRGB(255, 210, 120), 1.4f));
                    }, k);
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!p.isOnline()) return;
                    w.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 3, 0), 80, 0.3, 0.3, 0.3, 0.25);
                    w.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                }, 26L);
            }
            case "confetti" -> {
                Color[] cs = {Color.RED, Color.YELLOW, Color.LIME, Color.AQUA, Color.FUCHSIA, Color.ORANGE};
                for (int i = 0; i < 24; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        w.spawnParticle(Particle.DUST, p.getLocation().add(0, 3.2, 0), 8, 1.4, 0.2, 1.4, new Particle.DustOptions(cs[k % cs.length], 1.3f));
                    }, k * 2L);
                }
                w.playSound(l, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1f, 1.2f);
                w.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
            }
            case "blaze_crown" -> {
                for (int i = 0; i < 40; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        for (int j = 0; j < 8; j++) {
                            double a = (k * 0.4) + j * Math.PI / 4;
                            Location at = p.getLocation().add(Math.cos(a) * 0.5, 2.3 + Math.sin(k * 0.3) * 0.05, Math.sin(a) * 0.5);
                            w.spawnParticle(Particle.FLAME, at, 1, 0, 0, 0, 0);
                        }
                    }, k);
                }
                w.playSound(l, Sound.ITEM_FIRECHARGE_USE, 1f, 0.7f);
                w.playSound(l, Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
            }
            case "supernova" -> {
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) w.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.8f); }, 1L);
                for (int i = 0; i < 20; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        Location at = p.getLocation().add(0, 1, 0);
                        double r = k * 0.18;
                        for (int j = 0; j < 14; j++) {
                            double a = j * Math.PI * 2 / 14;
                            w.spawnParticle(Particle.END_ROD, at.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0);
                        }
                        w.spawnParticle(Particle.FLAME, at, 3, 0.1, 0.1, 0.1, 0.08);
                    }, k);
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!p.isOnline()) return;
                    w.spawnParticle(Particle.FLASH, p.getLocation().add(0, 1.5, 0), 1, Color.WHITE);
                    w.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 100, 0.4, 0.8, 0.4, 0.7);
                    w.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.6f);
                    w.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                }, 22L);
            }
            case "whiteout" -> {
                for (int i = 0; i < 40; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        Location at = p.getLocation().add(0, 3.5, 0);
                        w.spawnParticle(Particle.SNOWFLAKE, at, 14, 1.8, 0.3, 1.8, 0.02);
                        w.spawnParticle(Particle.CLOUD, at, 2, 1.2, 0.1, 1.2, 0.01);
                        if (k % 8 == 0) w.playSound(p.getLocation(), Sound.WEATHER_RAIN, 0.4f, 1.6f);
                    }, k);
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!p.isOnline()) return;
                    w.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(0, 1, 0), 120, 0.6, 0.9, 0.6, 0.25);
                    w.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1.5, 0), 40, 0.5, 0.8, 0.5, 0.2);
                    w.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 1.5f);
                    w.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
                }, 42L);
            }
            default -> {
                Burst b = Bursts.MAP.get(id);
                if (b == null) break;
                // a victory burst rains down over a second and a half, with the sounds on the first and last beat
                for (int i = 0; i < 12; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        Location at = p.getLocation().add(0, 2.4 - k * 0.1, 0);
                        for (Layer ly : b.layers) {
                            if (ly.dust != null) w.spawnParticle(Particle.DUST, at, ly.n, ly.dx, ly.dy, ly.dz, 0, new Particle.DustOptions(ly.dust, ly.size));
                            else w.spawnParticle(ly.p, at, ly.n, ly.dx, ly.dy, ly.dz, ly.speed);
                        }
                        if (k == 0 || k == 11) for (Sound s : b.sounds) w.playSound(p.getLocation(), s, 0.8f, b.pitch);
                    }, k * 3L);
                }
            }
        }
    }
}
