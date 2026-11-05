package com.fiscalcraft.bukkit;

import com.fiscalcraft.FiscalCraftPlugin;
import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.util.TimeSource;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 银行终端会话：绑定 UUID + 方块坐标，>3 格、换世界、传送、死亡、超时都会失效。
 */
public final class SessionManager {
    public record Session(String world, int x, int y, int z, long expiresAt) {
    }

    private final FiscalCraftPlugin plugin;
    private final FiscalConfig cfg;
    private final TimeSource time;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public SessionManager(FiscalCraftPlugin plugin, FiscalConfig cfg, TimeSource time) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.time = time;
    }

    public void create(Player player, Block block, int minutes) {
        sessions.put(player.getUniqueId(), new Session(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), time.now() + Math.max(1, minutes) * 60_000L));
    }

    public boolean has(Player player) {
        return session(player.getUniqueId()) != null;
    }

    /** 校验并返回有效会话；无效时自动清理。 */
    public Session session(UUID uuid) {
        Session session = sessions.get(uuid);
        if (session == null) return null;
        if (session.expiresAt() <= time.now()) {
            sessions.remove(uuid);
            return null;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            sessions.remove(uuid);
            return null;
        }
        Location location = player.getLocation();
        if (!session.world().equals(location.getWorld().getName())) {
            sessions.remove(uuid);
            return null;
        }
        double dx = location.getX() - (session.x() + 0.5);
        double dy = location.getY() - (session.y() + 0.5);
        double dz = location.getZ() - (session.z() + 0.5);
        if (dx * dx + dy * dy + dz * dz > 9.0) {
            sessions.remove(uuid);
            return null;
        }
        return session;
    }

    public boolean require(Player player) {
        if (session(player.getUniqueId()) != null) return true;
        player.sendMessage(net.kyori.adventure.text.Component.text(
                "会话已失效：请靠近终端右键并重新输入支付密码（距离不得超过 3 格）。"));
        return false;
    }

    public void invalidate(UUID uuid) {
        sessions.remove(uuid);
    }

    public void invalidateAll() {
        sessions.clear();
    }

    /** 每 tick 清理过期/离开范围的会话。 */
    public void tick() {
        for (UUID uuid : sessions.keySet()) {
            session(uuid);
        }
    }
}
