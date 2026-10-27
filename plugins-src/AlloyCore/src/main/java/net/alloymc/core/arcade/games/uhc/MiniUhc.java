package net.alloymc.core.arcade.games.uhc;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** UHC Meetup: everyone spawns geared up on natural terrain; no natural regeneration; the border closes in. */
public final class MiniUhc extends ArcadeGame {
    public MiniUhc() {
        super("uhc", "UHC Meetup", "<#ffd166>", Material.GOLDEN_APPLE, Category.SURVIVAL,
                "Spawn with a random meetup kit on open terrain. No natural regeneration - golden apples and heads only. Last alive wins.");
        variants.add(Variant.ffa("solo", "Solo", 2, 16));
        variants.add(new Variant("teams", "Teams of 2", 8, 2, 4, 16, false, ""));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().list("spawns", 8); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 900);
        m.put("grace-seconds", 10);
        m.put("border-start-seconds", 120);
        m.put("border-end-seconds", 480);
        m.put("border-final-size", 20);
        m.put("kit.always", List.of("GOLDEN_APPLE 3", "COBBLESTONE 64", "OAK_PLANKS 64", "WATER_BUCKET 1", "LAVA_BUCKET 1", "ARROW 24", "BOW 1", "COOKED_BEEF 16", "IRON_AXE 1", "IRON_PICKAXE 1"));
        m.put("kit.swords", List.of("IRON_SWORD 1 sharpness:1", "DIAMOND_SWORD 1", "IRON_SWORD 1 sharpness:2", "DIAMOND_SWORD 1 sharpness:1"));
        m.put("kit.armour", List.of("IRON", "IRON", "DIAMOND", "IRON"));
        return m;
    }

    @Override public Match newMatch(MatchContext c) { return new UhcMatch(c); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(terrain("highlands", "Highlands", "Rolling grassland with oak woods and ponds.", 101, "grass_block", "dirt", "oak_log", "oak_leaves[persistent=true]", false),
                       terrain("taiga_basin", "Taiga Basin", "A snowy spruce valley with a frozen lake.", 102, "snow_block", "dirt", "spruce_log", "spruce_leaves[persistent=true]", true),
                       terrain("badlands", "Badlands", "Red mesas and dry gullies.", 103, "red_sand", "terracotta", "", "", false));
    }

    static BuiltMap terrain(String id, String name, String desc, long seed, String top, String under, String log, String leaves, boolean snowy) {
        MapKit k = new MapKit(seed);
        int H = 55;
        for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
            double n = MapKit.fbm(x + 300, z + 300, seed);
            int h = (int) Math.round(n * 22) - 6;
            boolean mesa = top.equals("red_sand") && MapKit.noise(x / 18.0, z / 18.0, seed + 5) > 0.68;
            if (mesa) h += 10;
            for (int y = -14; y <= h; y++) {
                String b = y == h ? (mesa ? "terracotta" : top) : y > h - 4 ? (mesa ? (y % 3 == 0 ? "orange_terracotta" : "red_terracotta") : under) : "stone";
                if (b.equals("stone") && MapKit.noise(x * 0.4, z * 0.4 + y, seed + 9) > 0.86) b = "iron_ore";
                k.set(x, y, z, b);
            }
            if (h < 0) { for (int y = h + 1; y <= 0; y++) k.set(x, y, z, snowy ? "ice" : "water"); }
            else if (!log.isEmpty() && k.rnd.nextInt(snowy ? 55 : 70) == 0) { if (snowy) k.pine(x, h + 1, z, log, leaves, 7); else k.tree(x, h + 1, z, log, leaves, 5); }
            if (Math.abs(x) == H || Math.abs(z) == H) for (int y = Math.max(h, 0) + 1; y <= 45; y++) k.set(x, y, z, "barrier");
        }
        MapDef d = new MapDef("uhc", id);
        d.display = name; d.description = desc; d.minPlayers = 2; d.maxPlayers = 16; d.teams = 8;
        d.bounds = Box.of(-H, -16, -H, H, 50, H);
        d.voidY = -15;
        d.spectator = new Pt(0.5, 40, 0.5, 0, 60);
        // spawns in a ring, standing on the surface (height worked out from the same noise)
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI * 2 / 16;
            int x = (int) Math.round(Math.cos(a) * 38), z = (int) Math.round(Math.sin(a) * 38);
            int y = -14;
            while (k.isSet(x, y + 1, z) && !k.get(x, y + 1, z).contains("leaves")) y++;
            for (int c = 1; c <= 4; c++) k.set(x, y + c, z, "air");
            d.add("spawns", Pt.of(x + 0.5, Math.max(y + 1, 1), z + 0.5).facing(0.5, 0.5));
        }
        return new BuiltMap(d, k.c);
    }

    static final class UhcMatch extends Match {
        private double border;
        private final double start;

        UhcMatch(MatchContext c) { super(c); start = map.bounds.sizeX() / 2.0; border = start; }

        @Override protected String objective() { return "No natural regen. Last one alive wins!"; }
        @Override protected boolean naturalRegen() { return false; }
        @Override protected boolean hunger() { return true; }
        @Override protected boolean allowBreak(Player p, Block b) { return b.getType() != Material.BARRIER; }
        @Override protected boolean allowPlace(Player p, Block b) { return true; }
        @Override protected boolean allowDrop(Player p, ItemStack it) { return true; }
        @Override protected boolean allowCraft() { return true; }
        @Override protected boolean explodable(Block b) { return b.getType() != Material.BARRIER; }
        @Override protected boolean pvp() { return elapsed >= settings.i("grace-seconds", 10); }

        @Override protected void setup(List<Player> ps) {
            List<Location> sp = points("spawns");
            Collections.shuffle(sp, rnd);
            int step = Math.max(1, sp.size() / Math.max(1, teams.size()));
            for (int ti = 0; ti < teams.size(); ti++) {
                List<Player> ms = online(teams.get(ti).members);
                for (int j = 0; j < ms.size(); j++) { ms.get(j).teleport(sp.get((ti * step) % sp.size()).clone().add(j * 1.5, 0, 0)); kit(ms.get(j)); ms.get(j).setGameMode(GameMode.SURVIVAL); }
            }
        }

        private void kit(Player p) {
            List<String> swords = settings.list("kit.swords", List.of("IRON_SWORD 1")), armour = settings.list("kit.armour", List.of("IRON"));
            try { p.getInventory().addItem(Loot.item(swords.get(rnd.nextInt(swords.size())))); } catch (Exception ignored) { }
            for (String l : settings.list("kit.always", List.of())) try { p.getInventory().addItem(Loot.item(l)); } catch (Exception e) { arcade.plugin().getLogger().warning("[UHC] bad kit item " + l); }
            String[] slots = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
            ItemStack[] arm = new ItemStack[4];
            for (int i = 0; i < 4; i++) {
                Material m = Material.matchMaterial(armour.get(rnd.nextInt(armour.size())) + "_" + slots[i]);
                arm[3 - i] = new ItemStack(m == null ? Material.matchMaterial("IRON_" + slots[i]) : m);
                if (rnd.nextInt(3) == 0) arm[3 - i].addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION, 1 + rnd.nextInt(2));
            }
            p.getInventory().setArmorContents(arm);
            p.getInventory().addItem(goldenHead(1));
        }

        static ItemStack goldenHead(int n) {
            ItemStack it = Ui.item(Material.GOLDEN_APPLE, "<gold><bold>Golden Head", "<gray>Heals more than a golden apple");
            it.setAmount(n);
            it.editMeta(m -> m.setEnchantmentGlintOverride(true));
            return it;
        }

        @Override protected void onStart() { broadcast(prefix() + "PvP is enabled in <white>" + settings.i("grace-seconds", 10) + "s<gray>. No natural regeneration!"); }

        @Override protected void secondTick() {
            if (elapsed == settings.i("grace-seconds", 10)) { title("", "<red><bold>PvP ENABLED"); sound(Sound.ENTITY_WITHER_SPAWN, 1.4f); }
            int bs = settings.i("border-start-seconds", 120), be = settings.i("border-end-seconds", 480);
            double fin = settings.i("border-final-size", 20) / 2.0;
            if (elapsed >= bs) border = elapsed >= be ? fin : start - (start - fin) * (elapsed - bs) / (double) (be - bs);
            if (elapsed == bs) title("", "<red><bold>The border is shrinking!");
            for (Player p : living()) {
                double dx = Math.abs(p.getLocation().getX() - ox), dz = Math.abs(p.getLocation().getZ() - oz);
                if (dx > border || dz > border) { p.damage(1.5); p.sendActionBar(Ui.mm("<red><bold>Get inside the border!")); }
            }
            actionBar("<gold>Border <white>" + (int) (border * 2) + "x" + (int) (border * 2) + " <gray>• " + alive.size() + " alive");
        }

        @Override protected void onDeath(Player p, Player killer, String how) {
            for (ItemStack it : p.getInventory().getContents()) if (it != null) world.dropItemNaturally(p.getLocation(), it);
            world.dropItemNaturally(p.getLocation(), goldenHead(1));
            p.getInventory().clear();
            super.onDeath(p, killer, how);
        }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = super.sidebar(p);
            l.add("<gray>Kills: <white>" + kills.getOrDefault(p.getUniqueId(), 0));
            l.add("<gray>Border: <white>" + (int) (border * 2));
            return l;
        }

        /** Golden heads give Regeneration II for 8s on top of the normal golden apple effects. */
        @Override protected void onConsume(Player p, ItemStack it) {
            if (Ui.named(it, "Golden Head")) { p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 160, 1)); p.sendMessage(Ui.mm("<gold>Golden Head!")); }
        }
    }
}
