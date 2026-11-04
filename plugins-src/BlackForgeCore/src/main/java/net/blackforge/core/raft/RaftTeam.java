package net.blackforge.core.raft;

import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/** One raft crew: who is on it, where its tiles and machines are, what it has researched and how far the story has gone. */
public final class RaftTeam {
    /** A machine on the raft. data: purifier salt/fresh/fuel/progress, motor fuel. */
    public static final class Station {
        public final String type;
        public int x, y, z;
        public int salt, fresh, fuel, progress;
        public Station(String type, int x, int y, int z) { this.type = type; this.x = x; this.y = y; this.z = z; }
        public long key() { return key(x, y, z); }
        public static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF); }
    }

    public static final String[] STARTER_BLUEPRINTS = {"foundation", "cup", "hook", "workbench"};
    /** Wind and sail directions: east, south, west, north. */
    public static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    public static final String[] DIR_NAMES = {"east", "south", "west", "north"};

    public UUID owner;
    public String name;
    public final Set<UUID> members = new LinkedHashSet<>();
    public int cell;
    /** The raft's tiles as packed x, z (the water surface layer is y = SEA - 1). */
    public final LinkedHashSet<Long> tiles = new LinkedHashSet<>();
    public final Map<Long, Station> stations = new HashMap<>();
    public final Set<String> unlocked = new LinkedHashSet<>(List.of(STARTER_BLUEPRINTS));
    public int towers;               // radio towers switched on, 0..3
    public boolean utopia;           // Utopia reached and the beacon lit
    public boolean won;              // Bruce is dead: the crew escaped
    public int wind;                 // index into DIRS
    public long windChangeAt;
    public int steer = -1;           // -1 anchored, else index into DIRS
    public boolean sailing;          // true = under sail, false = under motor
    public final Map<UUID, Integer> thirst = new HashMap<>();
    public final Map<UUID, Integer> hunger = new HashMap<>();
    public long sharkAt;

    public RaftTeam(UUID owner, String name, int cell) { this.owner = owner; this.name = name; this.cell = cell; members.add(owner); }

    public static long pack(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    public static int tx(long k) { return (int) (k >> 32); }
    public static int tz(long k) { return (int) k; }

    public boolean has(int x, int z) { return tiles.contains(pack(x, z)); }

    /** Centre of the raft's tiles (an actual tile, so it is always standable). */
    public int[] centre() {
        if (tiles.isEmpty()) return new int[]{RaftStructures.originX(cell), 0};
        double sx = 0, sz = 0;
        for (long k : tiles) { sx += tx(k); sz += tz(k); }
        sx /= tiles.size(); sz /= tiles.size();
        long best = 0; double bd = Double.MAX_VALUE;
        for (long k : tiles) { double d = Math.hypot(tx(k) - sx, tz(k) - sz); if (d < bd) { bd = d; best = k; } }
        return new int[]{tx(best), tz(best)};
    }

    public Station stationAt(int x, int y, int z) { return stations.get(Station.key(x, y, z)); }

    public boolean hasStation(String type) { return stations.values().stream().anyMatch(s -> s.type.equals(type)); }

    public void save(ConfigurationSection s) {
        s.set("owner", owner.toString());
        s.set("name", name);
        s.set("cell", cell);
        s.set("members", members.stream().map(UUID::toString).toList());
        List<String> t = new ArrayList<>();
        for (long k : tiles) t.add(tx(k) + "," + tz(k));
        s.set("tiles", t);
        List<String> st = new ArrayList<>();
        for (Station a : stations.values()) st.add(a.type + "," + a.x + "," + a.y + "," + a.z + "," + a.salt + "," + a.fresh + "," + a.fuel + "," + a.progress);
        s.set("stations", st);
        s.set("unlocked", new ArrayList<>(unlocked));
        s.set("towers", towers);
        s.set("utopia", utopia);
        s.set("won", won);
        s.set("wind", wind);
        s.set("sailing", sailing);
        for (var e : thirst.entrySet()) s.set("thirst." + e.getKey(), e.getValue());
        for (var e : hunger.entrySet()) s.set("hunger." + e.getKey(), e.getValue());
    }

    public static RaftTeam load(ConfigurationSection s) {
        RaftTeam t = new RaftTeam(UUID.fromString(s.getString("owner")), s.getString("name", "Raft"), s.getInt("cell"));
        t.members.clear();
        for (String m : s.getStringList("members")) t.members.add(UUID.fromString(m));
        if (t.members.isEmpty()) t.members.add(t.owner);
        for (String p : s.getStringList("tiles")) { String[] a = p.split(","); t.tiles.add(pack(Integer.parseInt(a[0]), Integer.parseInt(a[1]))); }
        for (String p : s.getStringList("stations")) {
            String[] a = p.split(",");
            Station st = new Station(a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
            st.salt = Integer.parseInt(a[4]); st.fresh = Integer.parseInt(a[5]); st.fuel = Integer.parseInt(a[6]); st.progress = Integer.parseInt(a[7]);
            t.stations.put(st.key(), st);
        }
        t.unlocked.clear();
        t.unlocked.addAll(s.getStringList("unlocked"));
        if (t.unlocked.isEmpty()) t.unlocked.addAll(List.of(STARTER_BLUEPRINTS));
        t.towers = s.getInt("towers");
        t.utopia = s.getBoolean("utopia");
        t.won = s.getBoolean("won");
        t.wind = s.getInt("wind");
        t.sailing = s.getBoolean("sailing");
        ConfigurationSection th = s.getConfigurationSection("thirst");
        if (th != null) for (String k : th.getKeys(false)) t.thirst.put(UUID.fromString(k), th.getInt(k));
        ConfigurationSection hu = s.getConfigurationSection("hunger");
        if (hu != null) for (String k : hu.getKeys(false)) t.hunger.put(UUID.fromString(k), hu.getInt(k));
        return t;
    }
}
