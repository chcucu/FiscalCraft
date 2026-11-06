package com.fiscalcraft.bukkit;

import com.fiscalcraft.FiscalCraftPlugin;
import com.fiscalcraft.db.Database;
import com.fiscalcraft.service.Store;
import com.fiscalcraft.util.TimeSource;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 银行终端方块：木桶 / 箱子；缓存用于主线程快速判断，持久化写入 terminals 表。 */
public final class TerminalManager {
    public record Pos(String world, int x, int y, int z, TerminalType type) {
    }

    private final FiscalCraftPlugin plugin;
    private final Store store;
    private final Database db;
    private final TimeSource time;
    private final Map<String, Pos> cache = new ConcurrentHashMap<>();

    public TerminalManager(FiscalCraftPlugin plugin, Store store, Database db, TimeSource time) {
        this.plugin = plugin;
        this.store = store;
        this.db = db;
        this.time = time;
    }

    public static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public static String key(Block block) {
        return key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public void load() {
        Map<String, Pos> loaded = db.read(con -> {
            Map<String, Pos> map = new ConcurrentHashMap<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT world,x,y,z,terminal_type FROM terminals")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String world = rs.getString(1);
                        int x = rs.getInt(2);
                        int y = rs.getInt(3);
                        int z = rs.getInt(4);
                        TerminalType type = TerminalType.fromKey(rs.getString(5));
                        if (type == null) type = TerminalType.FULL;
                        map.put(key(world, x, y, z), new Pos(world, x, y, z, type));
                    }
                }
            }
            return map;
        });
        cache.clear();
        cache.putAll(loaded);
        plugin.getLogger().info("已加载 " + cache.size() + " 个银行终端");
    }

    public boolean isTerminal(Block block) {
        return cache.containsKey(key(block));
    }

    public boolean isTerminal(Location location) {
        return cache.containsKey(key(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    public Pos posAt(String world, int x, int y, int z) {
        return cache.get(key(world, x, y, z));
    }

    public Pos pos(Block block) {
        return cache.get(key(block));
    }

    public TerminalType typeAt(String world, int x, int y, int z) {
        Pos pos = cache.get(key(world, x, y, z));
        return pos == null ? null : pos.type();
    }

    public Collection<Pos> all() {
        return cache.values();
    }

    public boolean inChunk(Chunk chunk) {
        String world = chunk.getWorld().getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        for (Pos pos : cache.values()) {
            if (pos.world().equals(world) && (pos.x() >> 4) == chunkX && (pos.z() >> 4) == chunkZ) {
                return true;
            }
        }
        return false;
    }

    public boolean nearby(Player player, double radius) {
        return nearest(player, radius) != null;
    }

    /** 返回距离玩家最近且在半径内的终端；没有则返回 null。 */
    public Pos nearest(Player player, double radius) {
        Location loc = player.getLocation();
        double squared = radius * radius;
        Pos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Pos pos : cache.values()) {
            if (!pos.world().equals(loc.getWorld().getName())) continue;
            double dx = loc.getX() - (pos.x() + 0.5);
            double dy = loc.getY() - (pos.y() + 0.5);
            double dz = loc.getZ() - (pos.z() + 0.5);
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= squared && distance < bestDistance) {
                best = pos;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 必须在队列线程调用。 */
    public Pos add(Block block, TerminalType type, String creator) {
        return addPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), type, creator);
    }

    /** 必须在队列线程调用（坐标值来自主线程快照，避免跨线程访问方块）。 */
    public Pos addPos(String world, int x, int y, int z, TerminalType type, String creator) {
        long now = time.now();
        String key = key(world, x, y, z);
        Pos pos = new Pos(world, x, y, z, type == null ? TerminalType.FULL : type);
        db.tx(con -> {
            store.addTerminal(con, key, world, x, y, z, pos.type().key(), creator, now);
            store.audit(con, creator, "TERMINAL_ADD", key + " " + pos.type().key(), now);
            return null;
        });
        cache.put(key, pos);
        return pos;
    }

    /** 必须在队列线程调用。 */
    public boolean remove(Block block) {
        return removeKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** 必须在队列线程调用。 */
    public boolean removeKey(String world, int x, int y, int z) {
        String key = key(world, x, y, z);
        if (!cache.containsKey(key)) return false;
        long now = time.now();
        db.tx(con -> {
            store.removeTerminal(con, key);
            store.audit(con, "system", "TERMINAL_REMOVE", key, now);
            return null;
        });
        cache.remove(key);
        return true;
    }

    /** 主线程安全：仅清理缓存，数据库删除由队列任务完成。 */
    public void forget(Block block) {
        cache.remove(key(block));
    }

    public List<Pos> snapshot() {
        return List.copyOf(cache.values());
    }
}
