package com.fiscalcraft.bukkit;

import com.fiscalcraft.FiscalCraftPlugin;
import com.fiscalcraft.db.Database;
import com.fiscalcraft.service.Store;
import com.fiscalcraft.util.TimeSource;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 银行终端方块：木桶 / 箱子；缓存用于主线程快速判断，持久化写入 terminals 表。 */
public final class TerminalManager {
    public record Pos(String world, int x, int y, int z) {
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
            try (PreparedStatement ps = con.prepareStatement("SELECT world,x,y,z FROM terminals")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        map.put(key(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
                                new Pos(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
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

    public boolean nearby(Player player, double radius) {
        Location loc = player.getLocation();
        double squared = radius * radius;
        for (Pos pos : cache.values()) {
            if (!pos.world().equals(loc.getWorld().getName())) continue;
            double dx = loc.getX() - (pos.x() + 0.5);
            double dy = loc.getY() - (pos.y() + 0.5);
            double dz = loc.getZ() - (pos.z() + 0.5);
            if (dx * dx + dy * dy + dz * dz <= squared) return true;
        }
        return false;
    }

    /** 必须在队列线程调用。 */
    public boolean add(Block block, String creator) {
        return addPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), creator);
    }

    /** 必须在队列线程调用（坐标值来自主线程快照，避免跨线程访问方块）。 */
    public boolean addPos(String world, int x, int y, int z, String creator) {
        long now = time.now();
        String key = key(world, x, y, z);
        db.tx(con -> {
            store.addTerminal(con, key, world, x, y, z, creator, now);
            store.audit(con, creator, "TERMINAL_ADD", key, now);
            return null;
        });
        cache.put(key, new Pos(world, x, y, z));
        return true;
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
}
