package com.fiscalcraft.bukkit;

import com.fiscalcraft.FiscalCraftPlugin;
import com.fiscalcraft.queue.Outcome;
import com.fiscalcraft.service.EconomyService;
import io.papermc.paper.event.player.ChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BankListener implements Listener {
    private final FiscalCraftPlugin plugin;
    private final SessionManager sessions;
    private final TerminalManager terminals;
    private final TerminalMarker markers;
    private final PromptManager prompts;
    private final CardItems cards;
    private final EconomyService economy;
    private BankCommand commands;
    private final Map<UUID, List<ItemStack>> keptCards = new HashMap<>();

    public BankListener(FiscalCraftPlugin plugin, SessionManager sessions, TerminalManager terminals,
                        TerminalMarker markers, PromptManager prompts, CardItems cards, EconomyService economy) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.terminals = terminals;
        this.markers = markers;
        this.prompts = prompts;
        this.cards = cards;
        this.economy = economy;
    }

    public void bind(BankCommand commands) {
        this.commands = commands;
    }

    /** 聊天输入拦截：密码/密保答案不广播、不进频道、不写日志。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(ChatEvent event) {
        Player player = event.getPlayer();
        if (!prompts.active(player)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        event.setCancelled(true);
        prompts.handle(player, text.trim());
    }

    /** 接管 /pay：取消原指令，避免 EssentialsX 重复扣款。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        String lower = message.toLowerCase();
        if (!lower.startsWith("/pay") && !lower.startsWith("/minecraft:pay")) return;
        event.setCancelled(true);
        String[] parts = message.split("\\s+");
        if (commands != null) commands.handlePay(event.getPlayer(), parts);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String ip = player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress();
        if (plugin.ready()) {
            plugin.queue().submit("join", () -> {
                economy.touchJoin(player.getUniqueId().toString(), player.getName(), ip);
                return Outcome.ok("");
            });
        }
        List<ItemStack> restore = keptCards.remove(player.getUniqueId());
        if (restore != null) {
            for (ItemStack item : restore) player.getInventory().addItem(item);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.invalidate(event.getPlayer().getUniqueId());
        prompts.clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        sessions.invalidate(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        sessions.invalidate(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        sessions.invalidate(event.getEntity().getUniqueId());
        prompts.clear(event.getEntity().getUniqueId());
        List<ItemStack> saved = new ArrayList<>();
        Iterator<ItemStack> iterator = event.getDrops().iterator();
        while (iterator.hasNext()) {
            ItemStack item = iterator.next();
            if (cards.isCard(item)) {
                saved.add(item.clone());
                iterator.remove();
            }
        }
        if (!saved.isEmpty()) {
            keptCards.put(event.getEntity().getUniqueId(), saved);
            event.getEntity().sendMessage(Component.text("你的银行卡已保留，重生后自动归还。"));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        List<ItemStack> saved = keptCards.remove(event.getPlayer().getUniqueId());
        if (saved == null) return;
        for (ItemStack item : saved) event.getPlayer().getInventory().addItem(item);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!terminals.isTerminal(block)) return;
        TerminalManager.Pos pos = terminals.pos(block);
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        plugin.queue().submit("terminal-remove", () -> {
            terminals.removeKey(world, x, y, z);
            return Outcome.ok("");
        }, result -> {
            if (result.ok() && pos != null) markers.remove(pos);
        });
        event.getPlayer().sendMessage(Component.text("银行终端已移除。"));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !terminals.isTerminal(block)) return;
        Material type = block.getType();
        if (type != Material.CHEST && type != Material.BARREL && type != Material.TRAPPED_CHEST) {
            terminals.forget(block);
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!plugin.ready()) {
            player.sendMessage(Component.text("银行系统正在初始化，请稍后再试。"));
            return;
        }
        TerminalManager.Pos terminal = terminals.pos(block);
        if (terminal == null) return;
        player.playSound(block.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.4f);
        if (sessions.has(player)) {
            player.sendMessage(Component.text("已连接" + terminal.type().label() + "（"
                    + terminal.type().description() + "），输入 /bank help 查看可用操作。"));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String cardNo = cards.cardNo(hand);
        if (cardNo != null) {
            plugin.queue().submit("card-login", () -> {
                boolean ok = economy.cardLogin(player.getUniqueId().toString(), cardNo);
                return ok ? Outcome.ok("") : Outcome.fail("银行卡无效或不属于你", "CARD");
            }, result -> {
                if (result.ok()) {
                    int minutes = economy.sessionMinutes(player.getUniqueId().toString());
                    sessions.create(player, block, minutes);
                    player.sendMessage(Component.text("银行卡验证通过，已连接" + terminal.type().label()
                            + "（" + terminal.type().description() + "），会话 " + minutes + " 分钟。"));
                } else {
                    player.sendMessage(Component.text(result.message()));
                }
            });
            return;
        }
        // 先异步确认是否已开户：未开户玩家不应被要求输入支付密码，而是直接引导 /bank open。
        String uuid = player.getUniqueId().toString();
        plugin.queue().submit("terminal-account-check", () -> {
            boolean hasAccount = plugin.database().read(con -> plugin.store().getAccount(con, uuid) != null);
            return hasAccount ? Outcome.ok("") : Outcome.fail("");
        }, result -> {
            if (!result.ok()) {
                player.sendMessage(Component.text("你还没有银行账户。当前终端：" + terminal.type().label()
                        + "（" + terminal.type().description() + "）。"));
                if (terminal.type().allows("open")) {
                    player.sendMessage(Component.text("输入 ").append(Component.text("/bank open", NamedTextColor.WHITE))
                            .append(Component.text(" 开户，开户费 " + economy.openFeeText() + " 从钱包扣除。")));
                } else {
                    player.sendMessage(Component.text("该终端不支持开户，请前往开户终端或综合终端办理。"));
                }
                return;
            }
            prompts.prompt(player, "请输入 6 位数字支付密码（当前终端：" + terminal.type().label() + "）。",
                    (p, input) -> {
                        plugin.queue().submit("terminal-login", () -> {
                            String ip = p.getAddress() == null ? ""
                                    : p.getAddress().getAddress().getHostAddress();
                            EconomyService.AuthOutcome auth = economy.verifyPin(
                                    p.getUniqueId().toString(), input, ip);
                            if (!auth.success()) return Outcome.fail(auth.message());
                            return Outcome.ok("");
                        }, login -> {
                            if (!login.ok()) {
                                p.sendMessage(Component.text(login.message()));
                                return;
                            }
                            int minutes = economy.sessionMinutes(p.getUniqueId().toString());
                            sessions.create(p, block, minutes);
                            p.sendMessage(Component.text("登录成功，已连接" + terminal.type().label()
                                    + "（" + terminal.type().description() + "），会话 " + minutes
                                    + " 分钟；移动超过 3 格、传送或换世界将失效。"));
                        });
                    });
        });
    }
}
