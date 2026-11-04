package net.blackforge.core.raft;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.List;

import static net.blackforge.core.raft.RaftStructures.SEA;

/** Adds tiles to a raft and slides the whole raft (tiles, machines, everything built on it, and whoever stands on it) one block at a time. */
public final class RaftMover {
    private RaftMover() {}

    public static final int TILE_Y = SEA - 1, TOP_Y = SEA + 11;

    public enum Result { OK, BLOCKED, EDGE }

    private record Snap(int x, int y, int z, BlockData data, BlockState state) {}

    public static void placeTile(World w, RaftTeam t, int x, int z) {
        w.getBlockAt(x, TILE_Y, z).setType(Material.OAK_PLANKS, false);
        t.tiles.add(RaftTeam.pack(x, z));
    }

    /** True if a tile may be added at x, z: water at tile level, touching the raft on a side, nothing of the sea floor in the way. */
    public static boolean canAddTile(World w, RaftTeam t, int x, int z) {
        if (t.has(x, z)) return false;
        if (!(t.has(x + 1, z) || t.has(x - 1, z) || t.has(x, z + 1) || t.has(x, z - 1))) return false;
        Block b = w.getBlockAt(x, TILE_Y, z);
        if (b.getType() != Material.WATER) return false;
        return w.getBlockAt(x, TILE_Y + 1, z).getType().isAir();
    }

    /** Moves the raft one block (dx, dz is a unit step). */
    public static Result step(World w, RaftTeam t, int dx, int dz) {
        int ox = RaftStructures.originX(t.cell);
        // blocked by anything solid in the way, or by the edge of this team's ocean
        for (long k : t.tiles) {
            int nx = RaftTeam.tx(k) + dx, nz = RaftTeam.tz(k) + dz;
            if (Math.abs(nx - ox) > 9000 || Math.abs(nz) > 9000) return Result.EDGE;
            if (t.tiles.contains(RaftTeam.pack(nx, nz))) continue;
            for (int y = TILE_Y; y <= TOP_Y; y++) {
                Block b = w.getBlockAt(nx, y, nz);
                Material m = b.getType();
                if (m.isAir() || (y <= TILE_Y && m == Material.WATER)) continue;
                if (y > TILE_Y && m == Material.WATER) continue;
                return Result.BLOCKED;
            }
        }
        // snapshot everything on the raft, from the water layer up
        List<Snap> snaps = new ArrayList<>();
        for (long k : t.tiles) {
            int x = RaftTeam.tx(k), z = RaftTeam.tz(k);
            for (int y = TILE_Y; y <= TOP_Y; y++) {
                Block b = w.getBlockAt(x, y, z);
                if (b.getType().isAir() || (y > TILE_Y && b.getType() == Material.WATER)) continue;
                BlockState st = b.getState();
                snaps.add(new Snap(x, y, z, b.getBlockData(), st instanceof org.bukkit.block.TileState ? st : null));
            }
        }
        List<Entity> riders = new ArrayList<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long k : t.tiles) { minX = Math.min(minX, RaftTeam.tx(k)); maxX = Math.max(maxX, RaftTeam.tx(k)); minZ = Math.min(minZ, RaftTeam.tz(k)); maxZ = Math.max(maxZ, RaftTeam.tz(k)); }
        Location mid = new Location(w, (minX + maxX) / 2.0 + 0.5, (TILE_Y + TOP_Y) / 2.0, (minZ + maxZ) / 2.0 + 0.5);
        for (Entity e : w.getNearbyEntities(mid, (maxX - minX) / 2.0 + 2, (TOP_Y - TILE_Y) / 2.0 + 2, (maxZ - minZ) / 2.0 + 2)) {
            if (e instanceof org.bukkit.entity.Hanging || e.getScoreboardTags().contains(RaftDebris.TAG)) continue;
            int bx = e.getLocation().getBlockX(), bz = e.getLocation().getBlockZ();
            if (t.has(bx, bz)) riders.add(e);
        }
        // a container loses its contents when its block goes (they would spill); the snapshots carry them to the new spot
        for (Snap s : snaps) if (s.state() instanceof org.bukkit.block.Container) {
            if (w.getBlockAt(s.x(), s.y(), s.z()).getState(false) instanceof org.bukkit.block.Container live) live.getInventory().clear();
        }
        // lift off, then set down one block over: the old spot becomes water (the tile layer) or air
        for (Snap s : snaps) w.getBlockAt(s.x(), s.y(), s.z()).setBlockData((s.y() <= TILE_Y ? Material.WATER : Material.AIR).createBlockData(), false);
        for (Snap s : snaps) {
            Block b = w.getBlockAt(s.x() + dx, s.y(), s.z() + dz);
            b.setBlockData(s.data(), false);
            if (s.state() != null) {
                try { s.state().copy(b.getLocation()).update(true, false); } catch (Exception ignored) { }
            }
        }
        java.util.LinkedHashSet<Long> moved = new java.util.LinkedHashSet<>();
        for (long k : t.tiles) moved.add(RaftTeam.pack(RaftTeam.tx(k) + dx, RaftTeam.tz(k) + dz));
        t.tiles.clear();
        t.tiles.addAll(moved);
        java.util.Map<Long, RaftTeam.Station> st = new java.util.HashMap<>();
        for (RaftTeam.Station s : t.stations.values()) { s.x += dx; s.z += dz; st.put(s.key(), s); }
        t.stations.clear();
        t.stations.putAll(st);
        for (Entity e : riders) {
            Location to = e.getLocation().add(dx, 0, dz);
            if (e instanceof Player p) p.teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
            else e.teleport(to);
        }
        return Result.OK;
    }
}
