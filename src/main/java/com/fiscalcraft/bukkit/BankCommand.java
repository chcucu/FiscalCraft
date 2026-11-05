package com.fiscalcraft.bukkit;

import com.fiscalcraft.FiscalCraftPlugin;
import com.fiscalcraft.model.Models.Account;
import com.fiscalcraft.model.Models.Card;
import com.fiscalcraft.model.Models.Loan;
import com.fiscalcraft.model.Models.Report;
import com.fiscalcraft.queue.Outcome;
import com.fiscalcraft.service.EconomyService;
import com.fiscalcraft.service.LevelService;
import com.fiscalcraft.service.LoanService;
import com.fiscalcraft.service.TaxService;
import com.fiscalcraft.util.Money;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天栏指令入口。所有数据库与余额操作都提交到单线程资金队列，回调在主线程执行后才提示成功。
 */
public final class BankCommand implements CommandExecutor, TabCompleter {
    private record Pending(String target, long amount, EconomyService.Channel channel, long expiresAt) {
    }

    private record CardOffer(String cardNo, String senderUuid, long expiresAt) {
    }

    private final FiscalCraftPlugin plugin;
    private final SessionManager sessions;
    private final TerminalManager terminals;
    private final PromptManager prompts;
    private final CardItems cards;
    private final EconomyService economy;
    private final LevelService levels;
    private final LoanService loans;
    private final TaxService tax;
    private final BackupService backup;

    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, Long> closeConfirm = new HashMap<>();
    private final Map<UUID, CardOffer> cardOffers = new HashMap<>();

    public BankCommand(FiscalCraftPlugin plugin, SessionManager sessions, TerminalManager terminals,
                       PromptManager prompts, CardItems cards, EconomyService economy, LevelService levels,
                       LoanService loans, TaxService tax, BackupService backup) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.terminals = terminals;
        this.prompts = prompts;
        this.cards = cards;
        this.economy = economy;
        this.levels = levels;
        this.loans = loans;
        this.tax = tax;
        this.backup = backup;
    }

    // ---------------------------------------------------------------- /pay

    public void handlePay(Player player, String[] parts) {
        if (!player.hasPermission("fiscalcraft.pay")) {
            player.sendMessage(Component.text("你没有远程转账权限（fiscalcraft.pay）。"));
            return;
        }
        if (parts.length != 3) {
            player.sendMessage(Component.text("用法: /pay <玩家> <金额>（收款方必须已开户）"));
            return;
        }
        long amount;
        try {
            amount = Money.parse(parts[2]);
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text("金额错误: " + e.getMessage()));
            return;
        }
        String target = parts[1];
        String uuid = player.getUniqueId().toString();
        prompts.prompt(player, "请输入支付密码以完成远程转账。", (p, pin) -> {
            String ip = ipOf(p);
            plugin.queue().submit("remote-pay", () -> {
                EconomyService.AuthOutcome auth = economy.verifyPin(uuid, pin, ip);
                if (!auth.success()) return Outcome.fail(auth.message());
                return economy.remotePay(uuid, target, amount, false);
            }, result -> handleTransferResult(p, result, target, amount, EconomyService.Channel.REMOTE));
        });
    }

    private void handleTransferResult(Player player, Outcome result, String target, long amount,
                                      EconomyService.Channel channel) {
        if (!result.ok() && "CONFIRM".equals(result.errorCode())) {
            pending.put(player.getUniqueId(), new Pending(target, amount, channel,
                    System.currentTimeMillis() + 60_000L));
            player.sendMessage(Component.text("该交易需要二次确认，请在 60 秒内输入 /bank confirm；/bank cancel 取消。"));
            return;
        }
        player.sendMessage(Component.text(result.message()));
    }

    // ---------------------------------------------------------------- /bank

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("admin".equals(sub)) {
            return admin(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该指令只能由玩家执行。"));
            return true;
        }
        if (!plugin.ready()) {
            player.sendMessage(Component.text("银行系统初始化中，请稍后再试。"));
            return true;
        }
        String uuid = player.getUniqueId().toString();
        switch (sub) {
            case "help" -> sendHelp(player);
            case "open" -> open(player);
            case "close" -> close(player, args);
            case "deposit" -> deposit(player, args);
            case "withdraw" -> withdraw(player, args);
            case "transfer" -> transfer(player, args);
            case "balance", "bal" -> balance(player);
            case "history" -> history(player, args);
            case "tax" -> taxInfo(player);
            case "loan" -> loan(player, args);
            case "repay" -> repay(player, args);
            case "card" -> card(player, args);
            case "shared" -> shared(player, args);
            case "reset" -> reset(player);
            case "upgrade" -> upgrade(player);
            case "confirm" -> confirm(player);
            case "cancel" -> cancel(player);
            default -> sendHelp(player);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("FiscalCraft 银行指令:"));
        sender.sendMessage(Component.text("/bank open|close — 开户 / 销户（终端）"));
        sender.sendMessage(Component.text("/bank deposit <金额> — 存款（终端，免密）"));
        sender.sendMessage(Component.text("/bank withdraw <金额> — 取款（需会话密码）"));
        sender.sendMessage(Component.text("/bank transfer <玩家> <金额> — 终端转账"));
        sender.sendMessage(Component.text("/bank balance|history [条数]|tax — 余额 / 流水 / 税务"));
        sender.sendMessage(Component.text("/bank loan apply <7|30> <金额> | status | extend"));
        sender.sendMessage(Component.text("/bank repay <金额|all> — 还贷"));
        sender.sendMessage(Component.text("/bank card issue|reissue <卡号>|list|transfer <卡号> <玩家>|accept <卡号>|take <卡号>"));
        sender.sendMessage(Component.text("/bank shared create <family|couple|public> <名称>|join|leave|list|deposit|withdraw|transfer"));
        sender.sendMessage(Component.text("/bank reset — 密保重置密码（200）"));
        sender.sendMessage(Component.text("/bank upgrade — 查看/刷新等级评定"));
        sender.sendMessage(Component.text("/pay <玩家> <金额> — 远程转账（单笔 2000，日累计 5000）"));
        sender.sendMessage(Component.text("/bank confirm|cancel — 二次确认/取消"));
    }

    // ---------------------------------------------------------------- 开户 / 销户

    private void open(Player player) {
        if (!terminals.nearby(player, 3.0)) {
            player.sendMessage(Component.text("开户只能在银行终端办理，请靠近终端（3 格内）。"));
            return;
        }
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        String ip = ipOf(player);
        prompts.prompt(player, "请设置 6 位数字支付密码（逐条输入，仅用于校验）。", (p, pin) -> {
            if (!com.fiscalcraft.security.PasswordHasher.validPin(pin, plugin.config().pinLength)) {
                p.sendMessage(Component.text("密码必须为 " + plugin.config().pinLength + " 位数字，请重新执行 /bank open。"));
                return;
            }
            prompts.prompt(p, "请设置密保问题（例如：你的第一只宠物叫什么？）。", (p2, question) -> {
                if (question.isBlank()) {
                    p2.sendMessage(Component.text("密保问题不能为空，请重新执行 /bank open。"));
                    return;
                }
                prompts.prompt(p2, "请输入密保答案（不区分大小写，仅用于校验）。", (p3, answer) -> {
                    plugin.queue().submit("open-account", () ->
                            economy.openAccount(uuid, name, pin, question, answer, ip),
                            result -> p3.sendMessage(Component.text(result.message())));
                });
            });
        });
    }

    private void close(Player player, String[] args) {
        if (!sessions.require(player)) return;
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = closeConfirm.get(uuid);
        boolean confirmed = args.length >= 2 && "confirm".equalsIgnoreCase(args[1])
                && until != null && until > now;
        if (!confirmed) {
            closeConfirm.put(uuid, now + 60_000L);
            player.sendMessage(Component.text("销户将删除账户并吊销银行卡；若有贷款/欠税/共享账户会被拒绝。"));
            player.sendMessage(Component.text("请在 60 秒内输入 /bank close confirm 再次确认。"));
            return;
        }
        closeConfirm.remove(uuid);
        plugin.queue().submit("close-account", () -> economy.closeAccount(uuid.toString()),
                result -> player.sendMessage(Component.text(result.message())));
    }

    // ---------------------------------------------------------------- 资金操作

    private void deposit(Player player, String[] args) {
        if (!terminals.nearby(player, 3.0)) {
            player.sendMessage(Component.text("存款只能在银行终端办理。"));
            return;
        }
        long amount = amount(player, args);
        if (amount <= 0) return;
        plugin.queue().submit("deposit", () -> economy.deposit(player.getUniqueId().toString(), amount),
                result -> player.sendMessage(Component.text(result.message())));
    }

    private void withdraw(Player player, String[] args) {
        if (!sessions.require(player)) return;
        long amount = amount(player, args);
        if (amount <= 0) return;
        plugin.queue().submit("withdraw", () -> economy.withdraw(player.getUniqueId().toString(), amount),
                result -> player.sendMessage(Component.text(result.message())));
    }

    private void transfer(Player player, String[] args) {
        if (!sessions.require(player)) return;
        if (args.length < 3) {
            player.sendMessage(Component.text("用法: /bank transfer <玩家> <金额>"));
            return;
        }
        long amount;
        try {
            amount = Money.parse(args[2]);
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text("金额错误: " + e.getMessage()));
            return;
        }
        String target = args[1];
        plugin.queue().submit("terminal-transfer", () ->
                        economy.terminalTransfer(player.getUniqueId().toString(), target, amount, false),
                result -> handleTransferResult(player, result, target, amount, EconomyService.Channel.TERMINAL));
    }

    private void confirm(Player player) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null || p.expiresAt() <= System.currentTimeMillis()) {
            player.sendMessage(Component.text("没有待确认的交易，或已超时。"));
            return;
        }
        if (p.channel() == EconomyService.Channel.TERMINAL && !sessions.require(player)) {
            return;
        }
        plugin.queue().submit("confirm-transfer", () -> {
            String uuid = player.getUniqueId().toString();
            return p.channel() == EconomyService.Channel.TERMINAL
                    ? economy.terminalTransfer(uuid, p.target(), p.amount(), true)
                    : economy.remotePay(uuid, p.target(), p.amount(), true);
        }, result -> player.sendMessage(Component.text(result.message())));
    }

    private void cancel(Player player) {
        pending.remove(player.getUniqueId());
        closeConfirm.remove(player.getUniqueId());
        prompts.clear(player.getUniqueId());
        player.sendMessage(Component.text("已取消待确认操作与输入流程。"));
    }

    // ---------------------------------------------------------------- 查询

    private void balance(Player player) {
        if (!sessions.require(player)) return;
        String uuid = player.getUniqueId().toString();
        plugin.queue().submit("balance", () -> {
            Account account = plugin.database().read(con -> plugin.store().getAccount(con, uuid));
            if (account == null) return Outcome.fail("请先开户");
            long wallet = plugin.wallet().balance(uuid);
            String riskDynamic = plugin.database().read(con -> plugin.risk().level(con, uuid));
            int level = plugin.database().read(con -> levels.level(con, uuid));
            return Outcome.ok("银行余额: " + money(account.balance())
                    + "\n钱包余额: " + money(wallet)
                    + "\n账户等级: " + levels.spec(level).label()
                    + "\n风险等级: " + riskDynamic
                    + "\n欠税: " + money(plugin.database().read(con -> plugin.store().owedTax(con, uuid))));
        }, result -> sendLines(player, result));
    }

    private void history(Player player, String[] args) {
        if (!sessions.require(player)) return;
        int limit = 20;
        if (args.length >= 2) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                player.sendMessage(Component.text("条数必须是数字。"));
                return;
            }
        }
        int request = limit;
        plugin.queue().submit("history", () -> {
            List<String> lines = economy.history(player.getUniqueId().toString(), request);
            if (lines.isEmpty()) return Outcome.ok("暂无流水");
            return Outcome.ok(String.join("\n", lines));
        }, result -> sendLines(player, result));
    }

    private void taxInfo(Player player) {
        if (!sessions.require(player)) return;
        plugin.queue().submit("tax-info", () -> Outcome.ok(
                        String.join("\n", economy.taxInfo(player.getUniqueId().toString()))),
                result -> sendLines(player, result));
    }

    // ---------------------------------------------------------------- 贷款

    private void loan(Player player, String[] args) {
        if (!sessions.require(player)) return;
        String uuid = player.getUniqueId().toString();
        if (args.length < 2 || "status".equalsIgnoreCase(args[1])) {
            plugin.queue().submit("loan-status", () -> {
                Loan loan = plugin.database().read(con -> loans.activeLoan(con, uuid));
                LoanService.Eligibility eligibility = loans.eligibility(uuid, 30);
                StringBuilder text = new StringBuilder();
                if (loan == null) {
                    text.append("当前无未结清贷款。");
                } else {
                    text.append("贷款 ").append(loan.id()).append(" 状态 ").append(loan.status())
                            .append(" 剩余 ").append(money(loan.outstanding()))
                            .append(" 日利率 ").append(String.format("%.3f", loan.ratePpm() / 10_000.0)).append("%")
                            .append(" 到期 ").append(plugin.time().dayKey(
                                    java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(loan.dueAt()),
                                            plugin.time().zone())))
                            .append(" 展期次数 ").append(loan.extensions());
                }
                text.append("\n14 天日均银行余额 ").append(money(eligibility.averageBank()))
                        .append("，可贷上限 ").append(money(eligibility.maxLoan()))
                        .append("，信用分 ").append(eligibility.creditScore())
                        .append("，资金池可放 ").append(money(eligibility.poolAvailable()));
                if (eligibility.reason() != null) text.append("\n").append(eligibility.reason());
                return Outcome.ok(text.toString());
            }, result -> sendLines(player, result));
            return;
        }
        if ("apply".equalsIgnoreCase(args[1])) {
            if (args.length < 4) {
                player.sendMessage(Component.text("用法: /bank loan apply <7|30> <金额>"));
                return;
            }
            int term;
            try {
                term = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("期限只能为 7 或 30 天。"));
                return;
            }
            long amount = amount(player, args, 3);
            if (amount <= 0) return;
            plugin.queue().submit("loan-apply", () -> loans.apply(uuid, term, amount),
                    result -> player.sendMessage(Component.text(result.message())));
            return;
        }
        if ("extend".equalsIgnoreCase(args[1])) {
            plugin.queue().submit("loan-extend", () -> loans.extend(uuid),
                    result -> player.sendMessage(Component.text(result.message())));
            return;
        }
        player.sendMessage(Component.text("用法: /bank loan apply <7|30> <金额> | status | extend"));
    }

    private void repay(Player player, String[] args) {
        if (!sessions.require(player)) return;
        if (args.length < 2) {
            player.sendMessage(Component.text("用法: /bank repay <金额|all>"));
            return;
        }
        long amount;
        if ("all".equalsIgnoreCase(args[1])) {
            amount = Long.MAX_VALUE;
        } else {
            try {
                amount = Money.parse(args[1]);
            } catch (IllegalArgumentException e) {
                player.sendMessage(Component.text("金额错误: " + e.getMessage()));
                return;
            }
        }
        long finalAmount = amount;
        plugin.queue().submit("repay", () -> loans.repay(player.getUniqueId().toString(), finalAmount),
                result -> player.sendMessage(Component.text(result.message())));
    }

    // ---------------------------------------------------------------- 银行卡

    private void card(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("用法: /bank card issue|reissue <卡号>|list|transfer <卡号> <玩家>|accept <卡号>|take <卡号>"));
            return;
        }
        final java.util.concurrent.atomic.AtomicReference<EconomyService.CardIssue> issued =
                new java.util.concurrent.atomic.AtomicReference<>();
        String action = args[1].toLowerCase(Locale.ROOT);
        String uuid = player.getUniqueId().toString();
        switch (action) {
            case "issue" -> {
                if (!terminals.nearby(player, 3.0)) {
                    player.sendMessage(Component.text("办卡只能在银行终端办理。"));
                    return;
                }
                plugin.queue().submit("card-issue", () -> {
                    EconomyService.CardIssue issue = economy.issueCard(uuid);
                    if (issue == null) return Outcome.fail("办卡失败：余额不足、未开户或已达 5 张上限");
                    issued.set(issue);
                    return Outcome.ok("");
                }, result -> {
                    if (!result.ok()) {
                        player.sendMessage(Component.text(result.message()));
                        return;
                    }
                    player.getInventory().addItem(cards.create(issued.get().cardNo(), player.getName()));
                    player.sendMessage(Component.text("办卡成功，卡号 " + issued.get().cardNo()
                            + "，费用 " + money(issued.get().fee())));
                });
            }
            case "reissue" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("用法: /bank card reissue <旧卡号>"));
                    return;
                }
                if (!terminals.nearby(player, 3.0)) {
                    player.sendMessage(Component.text("补办只能在银行终端办理。"));
                    return;
                }
                String old = args[2];
                plugin.queue().submit("card-reissue", () -> {
                    EconomyService.CardIssue issue = economy.reissueCard(uuid, old);
                    if (issue == null) return Outcome.fail("补办失败：卡号无效、不属于你或余额不足");
                    issued.set(issue);
                    return Outcome.ok("");
                }, result -> {
                    if (!result.ok()) {
                        player.sendMessage(Component.text(result.message()));
                        return;
                    }
                    player.getInventory().addItem(cards.create(issued.get().cardNo(), player.getName()));
                    player.sendMessage(Component.text("补办成功，新卡号 " + issued.get().cardNo()));
                });
            }
            case "list" -> plugin.queue().submit("card-list", () -> {
                List<Card> list = economy.cards(uuid);
                if (list.isEmpty()) return Outcome.ok("你还没有银行卡");
                List<String> lines = new ArrayList<>();
                for (Card card : list) {
                    lines.add(card.cardNo() + " [" + card.status() + "]");
                }
                return Outcome.ok(String.join("\n", lines));
            }, result -> sendLines(player, result));
            case "take" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("用法: /bank card take <卡号>"));
                    return;
                }
                String cardNo = args[2];
                plugin.queue().submit("card-take", () -> {
                    Card card = economy.card(cardNo);
                    if (card == null || !card.active() || !card.ownerUuid().equals(uuid)) {
                        return Outcome.fail("卡号无效或不属于你");
                    }
                    return Outcome.ok("");
                }, result -> {
                    if (!result.ok()) {
                        player.sendMessage(Component.text(result.message()));
                        return;
                    }
                    player.getInventory().addItem(cards.create(cardNo, player.getName()));
                    player.sendMessage(Component.text("已取出银行卡 " + cardNo));
                });
            }
            case "transfer" -> {
                if (args.length < 4) {
                    player.sendMessage(Component.text("用法: /bank card transfer <卡号> <玩家>"));
                    return;
                }
                Player target = plugin.getServer().getPlayerExact(args[3]);
                if (target == null) {
                    player.sendMessage(Component.text("对方必须在线。"));
                    return;
                }
                String cardNo = args[2];
                plugin.queue().submit("card-transfer-offer", () -> {
                    Card card = economy.card(cardNo);
                    if (card == null || !card.active() || !card.ownerUuid().equals(uuid)) {
                        return Outcome.fail("卡号无效或不属于你");
                    }
                    return Outcome.ok("");
                }, result -> {
                    if (!result.ok()) {
                        player.sendMessage(Component.text(result.message()));
                        return;
                    }
                    cardOffers.put(target.getUniqueId(), new CardOffer(cardNo, uuid,
                            System.currentTimeMillis() + 120_000L));
                    target.sendMessage(Component.text(player.getName() + " 想将银行卡 " + cardNo
                            + " 转让给你，请在 2 分钟内输入 /bank card accept " + cardNo));
                    player.sendMessage(Component.text("转让请求已发送，等待对方确认。"));
                });
            }
            case "accept" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("用法: /bank card accept <卡号>"));
                    return;
                }
                CardOffer offer = cardOffers.get(player.getUniqueId());
                if (offer == null || offer.expiresAt() <= System.currentTimeMillis()
                        || !offer.cardNo().equals(args[2])) {
                    player.sendMessage(Component.text("没有有效转让请求。"));
                    return;
                }
                Player sender = plugin.getServer().getPlayer(UUID.fromString(offer.senderUuid()));
                if (sender == null) {
                    player.sendMessage(Component.text("转让方必须在线。"));
                    return;
                }
                cardOffers.remove(player.getUniqueId());
                plugin.queue().submit("card-transfer", () ->
                                economy.bindCardOwner(offer.senderUuid(), player.getName(), offer.cardNo()),
                        result -> {
                            player.sendMessage(Component.text(result.message()));
                            if (!result.ok()) return;
                            ItemStack physical = takeCardItem(sender, offer.cardNo());
                            if (physical != null) player.getInventory().addItem(physical);
                        });
            }
            default -> player.sendMessage(Component.text("未知的银行卡操作。"));
        }
    }

    private ItemStack takeCardItem(Player player, String cardNo) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && cardNo.equals(cards.cardNo(item))) {
                player.getInventory().setItem(i, null);
                return item;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 共享账户

    private void shared(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("用法: /bank shared create <family|couple|public> <名称> | join|leave|list|deposit|withdraw|transfer"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String uuid = player.getUniqueId().toString();
        switch (action) {
            case "create" -> {
                if (!terminals.nearby(player, 3.0)) {
                    player.sendMessage(Component.text("创建共享账户只能在终端办理。"));
                    return;
                }
                if (args.length < 4) {
                    player.sendMessage(Component.text("用法: /bank shared create <family|couple|public> <名称>"));
                    return;
                }
                String type = args[2];
                String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                plugin.queue().submit("shared-create", () -> economy.createShared(uuid, type, name),
                        result -> player.sendMessage(Component.text(result.message())));
            }
            case "join" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("用法: /bank shared join <编号>"));
                    return;
                }
                if (!terminals.nearby(player, 3.0)) {
                    player.sendMessage(Component.text("加入共享账户只能在终端办理。"));
                    return;
                }
                String id = args[2];
                plugin.queue().submit("shared-join", () -> economy.joinShared(uuid, id),
                        result -> player.sendMessage(Component.text(result.message())));
            }
            case "leave" -> {
                if (args.length < 3) {
                    player.sendMessage(Component.text("用法: /bank shared leave <编号>"));
                    return;
                }
                if (!terminals.nearby(player, 3.0)) {
                    player.sendMessage(Component.text("退出共享账户只能在终端办理。"));
                    return;
                }
                String id = args[2];
                plugin.queue().submit("shared-leave", () -> economy.leaveShared(uuid, id),
                        result -> player.sendMessage(Component.text(result.message())));
            }
            case "list" -> plugin.queue().submit("shared-list", () -> {
                List<EconomyService.SharedView> views = economy.sharedViews(uuid);
                if (views.isEmpty()) return Outcome.ok("你还没有共享账户");
                List<String> lines = new ArrayList<>();
                for (EconomyService.SharedView view : views) {
                    lines.add(view.account().id() + " [" + view.account().type() + "] " + view.account().name()
                            + " 余额 " + money(view.account().balance()) + " 成员 " + view.members()
                            + " 我的份额 " + money(view.myShare()));
                }
                return Outcome.ok(String.join("\n", lines));
            }, result -> sendLines(player, result));
            case "deposit" -> {
                if (!sessions.require(player)) return;
                if (args.length < 4) {
                    player.sendMessage(Component.text("用法: /bank shared deposit <编号> <金额>"));
                    return;
                }
                long amount = amount(player, args, 3);
                if (amount <= 0) return;
                String id = args[2];
                plugin.queue().submit("shared-deposit", () -> economy.sharedDeposit(uuid, id, amount),
                        result -> player.sendMessage(Component.text(result.message())));
            }
            case "withdraw" -> {
                if (!sessions.require(player)) return;
                if (args.length < 4) {
                    player.sendMessage(Component.text("用法: /bank shared withdraw <编号> <金额>"));
                    return;
                }
                long amount = amount(player, args, 3);
                if (amount <= 0) return;
                String id = args[2];
                plugin.queue().submit("shared-withdraw", () -> economy.sharedWithdraw(uuid, id, amount),
                        result -> player.sendMessage(Component.text(result.message())));
            }
            case "transfer" -> {
                if (!sessions.require(player)) return;
                if (args.length < 5) {
                    player.sendMessage(Component.text("用法: /bank shared transfer <编号> <玩家> <金额>"));
                    return;
                }
                long amount = amount(player, args, 4);
                if (amount <= 0) return;
                String id = args[2];
                String target = args[3];
                plugin.queue().submit("shared-transfer", () -> economy.sharedTransferToBank(uuid, id, target, amount),
                        result -> player.sendMessage(Component.text(result.message())));
            }
            default -> player.sendMessage(Component.text("未知的共享账户操作。"));
        }
    }

    // ---------------------------------------------------------------- 密码重置 / 等级

    private void reset(Player player) {
        if (!terminals.nearby(player, 3.0)) {
            player.sendMessage(Component.text("重置密码只能在银行终端办理。"));
            return;
        }
        String uuid = player.getUniqueId().toString();
        String ip = ipOf(player);
        prompts.prompt(player, "请输入密保答案以验证身份。", (p, answer) -> {
            plugin.queue().submit("reset-verify", () -> economy.verifySecurityAnswer(uuid, answer, ip)
                    ? Outcome.ok("") : Outcome.fail("密保答案错误"), result -> {
                if (!result.ok()) {
                    p.sendMessage(Component.text(result.message()));
                    return;
                }
                prompts.prompt(p, "验证通过，请输入新的 6 位数字支付密码。", (p2, pin) -> {
                    plugin.queue().submit("reset-pin", () -> economy.resetPin(uuid, pin, ip),
                            outcome -> p2.sendMessage(Component.text(outcome.message())));
                });
            });
        });
    }

    private void upgrade(Player player) {
        if (!sessions.require(player)) return;
        String uuid = player.getUniqueId().toString();
        plugin.queue().submit("level", () -> {
            LevelService.Progress progress = economy.levelProgress(uuid);
            List<LevelService.Change> changes = economy.checkLevel(uuid);
            StringBuilder text = new StringBuilder();
            text.append("30 天日均资产 ").append(money(progress.avgAssets()))
                    .append("，有效流水 ").append(money(progress.flow()))
                    .append("，合规分 ").append(progress.compliance())
                    .append("，当前可评等级 ").append(levels.spec(progress.qualifiedLevel()).label());
            for (LevelService.Change change : changes) {
                text.append("\n等级调整: ").append(levels.spec(change.oldLevel()).label())
                        .append(" -> ").append(levels.spec(change.newLevel()).label())
                        .append(" (").append(change.reason()).append(")");
            }
            return Outcome.ok(text.toString());
        }, result -> sendLines(player, result));
    }

    // ---------------------------------------------------------------- 管理

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fiscalcraft.admin")) {
            sender.sendMessage(Component.text("缺少权限 fiscalcraft.admin"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("/bank admin report|treasury|backup|terminal create|remove|unlock <玩家>|audit <玩家>|suspicious"));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "report" -> plugin.queue().submit("admin-report", () -> {
                Report report = economy.report();
                List<String> lines = new ArrayList<>();
                lines.add("总供应量 " + money(report.totalSupply()) + " / 流通现金 " + money(report.cash())
                        + " / 银行存款 " + money(report.bankDeposits()) + " / 共享存款 " + money(report.sharedDeposits()));
                lines.add("贷款未还 " + money(report.loanOutstanding()) + " / 财政池 " + money(report.treasury())
                        + " / 欠税 " + money(report.arrears()));
                lines.add("账户 " + report.accounts() + " / 活跃贷款 " + report.activeLoans()
                        + " / 逾期 " + report.overdueLoans() + " / 可疑记录 " + report.suspiciousCount());
                lines.add("Top10 资产 " + money(report.top10Assets()) + " / 基尼系数约 " + String.format("%.3f", report.gini()));
                return Outcome.ok(String.join("\n", lines));
            }, result -> sendLines(sender, result));
            case "treasury" -> plugin.queue().submit("admin-treasury", () ->
                    Outcome.ok("财政池 system.treasury = " + money(economy.treasury())),
                    result -> sendLines(sender, result));
            case "backup" -> plugin.queue().submit("admin-backup", () -> Outcome.ok(backup.run()),
                    result -> sendLines(sender, result));
            case "terminal" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("创建/删除终端必须由玩家执行。"));
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage(Component.text("用法: /bank admin terminal <create|remove>（看向箱子/木桶）"));
                    return true;
                }
                Block block = player.getTargetBlockExact(6);
                if (block == null || (block.getType() != Material.CHEST && block.getType() != Material.BARREL
                        && block.getType() != Material.TRAPPED_CHEST)) {
                    player.sendMessage(Component.text("请先看向 6 格内的箱子或木桶。"));
                    return true;
                }
                String world = block.getWorld().getName();
                int x = block.getX();
                int y = block.getY();
                int z = block.getZ();
                if ("create".equalsIgnoreCase(args[2])) {
                    plugin.queue().submit("terminal-create", () -> {
                        terminals.addPos(world, x, y, z, player.getUniqueId().toString());
                        return Outcome.ok("终端已创建");
                    }, result -> player.sendMessage(Component.text(result.message())));
                } else if ("remove".equalsIgnoreCase(args[2])) {
                    plugin.queue().submit("terminal-delete", () -> terminals.removeKey(world, x, y, z)
                            ? Outcome.ok("终端已删除") : Outcome.fail("该方块不是银行终端"),
                            result -> player.sendMessage(Component.text(result.message())));
                } else {
                    player.sendMessage(Component.text("用法: /bank admin terminal <create|remove>"));
                }
            }
            case "unlock" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("用法: /bank admin unlock <玩家>"));
                    return true;
                }
                String name = args[2];
                plugin.queue().submit("admin-unlock", () -> {
                    Account account = plugin.database().read(con -> plugin.store().getAccountByName(con, name));
                    if (account == null) return Outcome.fail("该玩家未开户");
                    return Outcome.ok("");
                }, result -> {
                    if (result.ok()) {
                        plugin.queue().submit("admin-unlock-apply", () -> {
                            Account account = plugin.database().read(con -> plugin.store().getAccountByName(con, name));
                            if (account != null) economy.adminUnlock(account.uuid());
                            return Outcome.ok("已解锁 " + name);
                        }, outcome -> sendLines(sender, outcome));
                    } else {
                        sendLines(sender, result);
                    }
                });
            }
            case "audit" -> {
                String name = args.length >= 3 ? args[2] : null;
                int limit = args.length >= 4 ? parseInt(args[3], 50) : 50;
                plugin.queue().submit("admin-audit", () -> {
                    String actor = null;
                    if (name != null) {
                        Account account = plugin.database().read(con -> plugin.store().getAccountByName(con, name));
                        actor = account == null ? name : account.uuid();
                    }
                    final String filter = actor;
                    List<String> lines = plugin.database().read(con -> plugin.store().auditLogs(con, filter, limit));
                    return Outcome.ok(lines.isEmpty() ? "无审计记录" : String.join("\n", lines));
                }, result -> sendLines(sender, result));
            }
            case "suspicious" -> {
                int limit = args.length >= 3 ? parseInt(args[2], 30) : 30;
                plugin.queue().submit("admin-suspicious", () -> {
                    List<String> lines = plugin.database().read(con -> plugin.store().suspiciousLogs(con, limit));
                    return Outcome.ok(lines.isEmpty() ? "无可疑记录" : String.join("\n", lines));
                }, result -> sendLines(sender, result));
            }
            case "loan" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("用法: /bank admin loan <玩家>"));
                    return true;
                }
                String name = args[2];
                plugin.queue().submit("admin-loan", () -> {
                    Account account = plugin.database().read(con -> plugin.store().getAccountByName(con, name));
                    if (account == null) return Outcome.fail("该玩家未开户");
                    List<Loan> list = plugin.database().read(con -> plugin.store().loansOf(con, account.uuid()));
                    if (list.isEmpty()) return Outcome.ok("无贷款记录");
                    List<String> lines = new ArrayList<>();
                    for (Loan loan : list) {
                        lines.add(loan.id() + " " + loan.status() + " 本金 " + money(loan.principal())
                                + " 剩余 " + money(loan.outstanding()) + " 日利率 "
                                + String.format("%.3f", loan.ratePpm() / 10_000.0) + "%");
                    }
                    return Outcome.ok(String.join("\n", lines));
                }, result -> sendLines(sender, result));
            }
            default -> sender.sendMessage(Component.text("未知管理指令。"));
        }
        return true;
    }

    // ---------------------------------------------------------------- 工具

    private long amount(Player player, String[] args) {
        return amount(player, args, 1);
    }

    private long amount(Player player, String[] args, int index) {
        if (args.length <= index) {
            player.sendMessage(Component.text("请输入金额。"));
            return 0;
        }
        try {
            return Money.parse(args[index]);
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text("金额错误: " + e.getMessage()));
            return 0;
        }
    }

    private void sendLines(CommandSender sender, Outcome result) {
        if (result == null) {
            sender.sendMessage(Component.text("操作未完成，请稍后重试。"));
            return;
        }
        for (String line : result.message().split("\n")) {
            if (!line.isBlank()) sender.sendMessage(Component.text(line));
        }
    }

    private String money(long cents) {
        return Money.format(cents, plugin.config().currencySymbol);
    }

    private String ipOf(Player player) {
        return player.getAddress() == null ? "" : player.getAddress().getAddress().getHostAddress();
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("open", "close", "deposit", "withdraw", "transfer", "balance",
                    "history", "tax", "loan", "repay", "card", "shared", "reset", "upgrade", "confirm",
                    "cancel", "admin", "help"), args[0]);
        }
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0])) {
            return filter(Arrays.asList("report", "treasury", "backup", "terminal", "unlock", "audit",
                    "suspicious", "loan"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
