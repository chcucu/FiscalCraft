package com.fiscalcraft.bukkit;

import com.fiscalcraft.FiscalCraftPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 敏感聊天输入：密码、密保答案、二次确认文本。消息被取消，不广播、不进入频道、不写日志。
 */
public final class PromptManager {
    public interface Handler {
        void accept(Player player, String input);
    }

    private record Prompt(Handler handler, long expiresAt) {
    }

    private final FiscalCraftPlugin plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public PromptManager(FiscalCraftPlugin plugin) {
        this.plugin = plugin;
    }

    public void prompt(Player player, String question, Handler handler) {
        long timeout = Math.max(10, plugin.config().promptTimeoutSeconds) * 1000L;
        prompts.put(player.getUniqueId(), new Prompt(handler, System.currentTimeMillis() + timeout));
        player.sendMessage(Component.text(question));
        player.sendMessage(Component.text("（本消息仅用于校验，不会被广播或写入聊天记录；输入 /bank cancel 可取消）"));
    }

    public boolean active(Player player) {
        return prompts.containsKey(player.getUniqueId());
    }

    /** 在主线程调用；返回是否消费了本次聊天。 */
    public boolean handle(Player player, String input) {
        Prompt prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) return false;
        if (prompt.expiresAt() <= System.currentTimeMillis()) {
            player.sendMessage(Component.text("输入已超时，请重新执行指令。"));
            return true;
        }
        prompt.handler().accept(player, input);
        return true;
    }

    public void clear(UUID uuid) {
        prompts.remove(uuid);
    }

    public void tick() {
        long now = System.currentTimeMillis();
        prompts.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }
}
