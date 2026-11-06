package com.fiscalcraft.bukkit;

import com.fiscalcraft.FiscalCraftPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * 银行终端明显标识：方块上方悬浮全息文字 + 周期性彩色粒子。
 * 全息实体持久化在区块中，重启后不会消失；区块加载时自动补齐。
 */
public final class TerminalMarker implements Listener {
    private final TerminalManager terminals;
    private final NamespacedKey markerKey;

    public TerminalMarker(FiscalCraftPlugin plugin, TerminalManager terminals) {
        this.terminals = terminals;
        this.markerKey = new NamespacedKey(plugin, "terminal_marker");
    }

    public void ensureAllLoaded() {
        for (TerminalManager.Pos pos : terminals.snapshot()) {
            ensure(pos);
        }
    }

    /** 主线程调用。已有标识则更新文字，重复标识自动清理。 */
    public void ensure(TerminalManager.Pos pos) {
        World world = Bukkit.getWorld(pos.world());
        if (world == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return;
        Location location = markerLocation(world, pos);
        List<TextDisplay> existing = displays(world, location, pos);
        if (!existing.isEmpty()) {
            existing.get(0).text(text(pos));
            for (int i = 1; i < existing.size(); i++) existing.get(i).remove();
            return;
        }
        TextDisplay display = world.spawn(location, TextDisplay.class);
        configure(display, pos);
    }

    /** 主线程调用；删除终端时同步移除全息标识。 */
    public void remove(TerminalManager.Pos pos) {
        World world = Bukkit.getWorld(pos.world());
        if (world == null) return;
        Location location = markerLocation(world, pos);
        for (TextDisplay display : displays(world, location, pos)) {
            display.remove();
        }
    }

    /** 主线程调用：彩色粒子提示，每 2 秒一次。 */
    public void tickParticles() {
        for (TerminalManager.Pos pos : terminals.snapshot()) {
            World world = Bukkit.getWorld(pos.world());
            if (world == null || world.getPlayers().isEmpty()) continue;
            if (!world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) continue;
            world.spawnParticle(Particle.DUST, pos.x() + 0.5, pos.y() + 1.08, pos.z() + 0.5,
                    10, 0.32, 0.10, 0.32, 0.0,
                    new Particle.DustOptions(org.bukkit.Color.fromRGB(pos.type().color()), 1.1f));
            world.spawnParticle(Particle.END_ROD, pos.x() + 0.5, pos.y() + 1.55, pos.z() + 0.5,
                    2, 0.12, 0.30, 0.12, 0.01);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (TerminalManager.Pos pos : terminals.snapshot()) {
            if (pos.world().equals(event.getWorld().getName())
                    && (pos.x() >> 4) == event.getChunk().getX()
                    && (pos.z() >> 4) == event.getChunk().getZ()) {
                ensure(pos);
            }
        }
    }

    private void configure(TextDisplay display, TerminalManager.Pos pos) {
        display.text(text(pos));
        display.setBillboard(TextDisplay.Billboard.CENTER);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setShadowed(true);
        display.setSeeThrough(true);
        display.setViewRange(1.0f);
        display.setPersistent(true);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setSilent(true);
        display.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING,
                TerminalManager.key(pos.world(), pos.x(), pos.y(), pos.z()));
    }

    private Component text(TerminalManager.Pos pos) {
        return Component.text("银行终端 · " + pos.type().label(), TextColor.color(pos.type().color()),
                        TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.text(pos.type().description(), TextColor.color(0xD8D8D8)))
                .append(Component.newline())
                .append(Component.text("右键使用", TextColor.color(0x9A9A9A)));
    }

    private List<TextDisplay> displays(World world, Location location, TerminalManager.Pos pos) {
        String expected = TerminalManager.key(pos.world(), pos.x(), pos.y(), pos.z());
        List<TextDisplay> result = new ArrayList<>();
        for (Entity entity : world.getNearbyEntities(location, 2.0, 3.0, 2.0)) {
            if (!(entity instanceof TextDisplay display)) continue;
            String marker = display.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
            if (expected.equals(marker)) result.add(display);
        }
        return result;
    }

    private Location markerLocation(World world, TerminalManager.Pos pos) {
        return new Location(world, pos.x() + 0.5, pos.y() + 1.45, pos.z() + 0.5);
    }
}
