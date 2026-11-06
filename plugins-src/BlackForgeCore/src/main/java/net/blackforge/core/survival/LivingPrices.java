package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Living Prices (Survival 2 experiment "living-prices"): the Grove Trader pays less for what the whole server sells a
 * lot of. Each item's recent sales fade with a half-life, so its price climbs back once people stop flooding it.
 * The player's own daily soft cap still applies on top. {@link GroveTrader} asks {@link #factor} for every price.
 */
final class LivingPrices {
    static final String NAME = "<gradient:#7bd88f:#e5484d><bold>Living Prices</bold></gradient>";
    private static final String ID = "living-prices";

    private static final Map<String, double[]> RECENT = new HashMap<>();   // item id -> {sold, lastUpdateMillis}
    private static double baseline = 2000, floor = 0.3, halfLife = 6;
    private static File file;
    private static boolean dirty;
    private static boolean started;

    private LivingPrices() {}

    static void start(LabExperiments.Ctx c) {
        var cfg = c.plugin().getConfig();
        baseline = cfg.getDouble("lab-experiments.living-prices.half-price-at", 2000);
        floor = cfg.getDouble("lab-experiments.living-prices.floor", 0.3);
        halfLife = cfg.getDouble("lab-experiments.living-prices.recover-half-life-hours", 6);
        file = new File(c.plugin().getDataFolder(), "lab/prices.yml");
        var y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) RECENT.put(k, new double[]{y.getDouble(k + ".sold"), y.getLong(k + ".at")});
        started = true;
        Bukkit.getScheduler().runTaskTimer(c.plugin(), LivingPrices::save, 1200L, 1200L);
        LabExperiments.command(c.plugin(), ID, "prices", "Living Prices: what the Grove Trader pays now", List.of("market-prices"), (s, a) -> {
            List<String> ids = new ArrayList<>();
            for (SellCatalog.Item it : SellCatalog.ITEMS) ids.add(it.id());
            ids.sort(Comparator.comparingDouble(LivingPrices::factor));
            s.sendMessage(Ui.mm(NAME + " <gray>Grove Trader prices right now (lowest first)"));
            int shown = 0;
            for (String id : ids) {
                double f = factor(id);
                if (shown >= 10 && f > 0.99) break;
                SellCatalog.Item it = SellCatalog.ITEMS.stream().filter(i -> i.id().equals(id)).findFirst().orElseThrow();
                String col = f < 0.5 ? "<#e5484d>" : f < 0.9 ? "<#ffd76a>" : "<#7bd88f>";
                s.sendMessage(Ui.mm("<gray>" + it.label() + " " + col + Math.round(f * 100) + "% <dark_gray>($" + LabMath.round2(it.price() * f) + " each)"));
                if (++shown >= 12) break;
            }
        });
    }

    /** What share of the normal price the Trader pays for this item right now (1 when the experiment is off). */
    static double factor(String id) {
        if (!started || !Lab.enabled(ID)) return 1;
        double[] r = RECENT.get(id);
        if (r == null) return 1;
        double hours = (System.currentTimeMillis() - r[1]) / 3_600_000.0;
        return LabMath.priceFactor(LabMath.decay(r[0], hours, halfLife), baseline, floor);
    }

    /** A sale happened: the server-wide total for this item goes up (after fading what was there). */
    static void sold(String id, int amount) {
        if (!started || !Lab.enabled(ID) || amount <= 0) return;
        long now = System.currentTimeMillis();
        double[] r = RECENT.computeIfAbsent(id, k -> new double[]{0, now});
        r[0] = LabMath.decay(r[0], (now - r[1]) / 3_600_000.0, halfLife) + amount;
        r[1] = now;
        dirty = true;
    }

    private static void save() {
        if (!dirty || file == null) return;
        var y = new YamlConfiguration();
        RECENT.forEach((k, r) -> { y.set(k + ".sold", r[0]); y.set(k + ".at", (long) r[1]); });
        try { file.getParentFile().mkdirs(); y.save(file); dirty = false; } catch (Exception ignored) { }
    }
}
