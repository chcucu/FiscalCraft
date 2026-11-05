package com.fiscalcraft.service;

import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.db.Database;
import com.fiscalcraft.model.Models.Account;
import com.fiscalcraft.model.Models.Card;
import com.fiscalcraft.model.Models.Loan;
import com.fiscalcraft.model.Models.PlayerData;
import com.fiscalcraft.model.Models.Report;
import com.fiscalcraft.model.Models.SharedAccount;
import com.fiscalcraft.model.Models.SharedMember;
import com.fiscalcraft.model.Models.TaxRecord;
import com.fiscalcraft.model.Models.Tx;
import com.fiscalcraft.queue.Outcome;
import com.fiscalcraft.security.PasswordHasher;
import com.fiscalcraft.util.Money;
import com.fiscalcraft.util.TimeSource;
import com.fiscalcraft.wallet.WalletGateway;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class EconomyService {
    public record AuthOutcome(boolean success, String message, boolean locked, long lockedUntil, int remaining) {
    }

    public record CardIssue(String cardNo, long fee, int level) {
    }

    public record SharedView(SharedAccount account, int members, long myShare, long myNetContribution) {
    }

    private final Store store;
    private final Database db;
    private final FiscalConfig cfg;
    private final TimeSource time;
    private final WalletService walletService;
    private final WalletGateway wallet;
    private final LevelService levels;
    private final TaxService tax;
    private final LoanService loans;
    private final RiskService risk;
    private final RelationService relations;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();

    public EconomyService(Store store, Database db, FiscalConfig cfg, TimeSource time,
                          WalletService walletService, WalletGateway wallet, LevelService levels,
                          TaxService tax, LoanService loans, RiskService risk, RelationService relations,
                          Logger logger) {
        this.store = store;
        this.db = db;
        this.cfg = cfg;
        this.time = time;
        this.walletService = walletService;
        this.wallet = wallet;
        this.levels = levels;
        this.tax = tax;
        this.loans = loans;
        this.risk = risk;
        this.relations = relations;
        this.logger = logger;
    }

    // ---------------------------------------------------------------- account lifecycle

    public Outcome openAccount(String uuid, String name, String pin, String question, String answer, String ip) {
        if (!PasswordHasher.validPin(pin, cfg.pinLength)) {
            return Outcome.fail("支付密码必须为 " + cfg.pinLength + " 位数字");
        }
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return Outcome.fail("密保问题和答案不能为空");
        }
        long now = time.now();
        Account existing = db.read(con -> store.getAccount(con, uuid));
        if (existing != null) return Outcome.fail("你已经开户");
        Outcome fee = walletService.chargeToTreasury(uuid, cfg.openFee, "开户费");
        if (!fee.ok()) return fee;
        String pinSalt = PasswordHasher.salt();
        String answerSalt = PasswordHasher.salt();
        Account account = new Account(uuid, name, 0,
                PasswordHasher.hash(pin, pinSalt), pinSalt,
                question, PasswordHasher.hash(answer, answerSalt), answerSalt,
                0, 0, "ACTIVE", now);
        try {
            db.tx(con -> {
                store.ensurePlayer(con, uuid, name, now);
                store.insertAccount(con, account);
                store.audit(con, uuid, "ACCOUNT_OPEN", "fee=" + cfg.openFee, now);
                return null;
            });
        } catch (RuntimeException e) {
            logger.severe("开户写入失败，退费: " + e.getMessage());
            wallet.deposit(uuid, cfg.openFee);
            db.tx(con -> {
                store.debitTreasury(con, cfg.openFee, time.now());
                store.audit(con, uuid, "ACCOUNT_OPEN_REFUND", "fee=" + cfg.openFee, time.now());
                return null;
            });
            return Outcome.fail("开户失败，开户费已退回钱包");
        }
        return Outcome.ok("开户成功，已扣除开户费 " + Money.format(cfg.openFee, cfg.currencySymbol)
                + "。请妥善保管支付密码。");
    }

    private record CloseCheck(Outcome failure, long balance) {
    }

    public Outcome closeAccount(String uuid) {
        long now = time.now();
        CloseCheck check = db.tx(con -> {
            Account account = store.getAccount(con, uuid);
            if (account == null || !account.active()) return new CloseCheck(Outcome.fail("你还没有有效账户"), 0);
            if (store.activeLoan(con, uuid) != null) return new CloseCheck(Outcome.fail("有未结清贷款，不能销户"), 0);
            if (store.owedTax(con, uuid) > 0) return new CloseCheck(Outcome.fail("有欠税未清，不能销户"), 0);
            if (!store.memberships(con, uuid).isEmpty()) return new CloseCheck(Outcome.fail("仍在共享账户中，不能销户"), 0);
            return new CloseCheck(null, account.balance());
        });
        if (check.failure() != null) return check.failure();
        // 账户保持 ACTIVE 完成取款，再在同一队列步骤内删除账户。
        // 若中途崩溃，账户余额已为 0，玩家重新执行销户即可，不会丢失资金。
        if (check.balance() > 0) {
            Outcome outcome = walletService.withdrawToWallet(uuid, check.balance(), "销户余额转回钱包");
            if (!outcome.ok()) return outcome;
        }
        return db.tx(con -> {
            Account account = store.getAccount(con, uuid);
            if (account == null) return Outcome.ok("销户完成");
            if (!account.active()) return Outcome.fail("账户状态异常，无法销户");
            revokeCards(con, uuid, now);
            store.deleteAccount(con, uuid);
            store.audit(con, uuid, "ACCOUNT_CLOSE", "balance transferred", now);
            return Outcome.ok("销户完成，余额已转回钱包");
        });
    }

    private void revokeCards(Connection con, String uuid, long now) throws SQLException {
        for (Card card : store.cardsOf(con, uuid)) {
            if (card.active()) store.setCardStatus(con, card.cardNo(), "REVOKED");
        }
        store.audit(con, uuid, "CARDS_REVOKED", "all", now);
    }

    // ---------------------------------------------------------------- auth

    public boolean authLocked(String uuid, String ip) {
        long now = time.now();
        Account account = db.read(con -> store.getAccount(con, uuid));
        if (account != null && account.lockedUntil() > now) return true;
        if (ip == null || ip.isBlank()) return false;
        return db.read(con -> store.isLocked(con, "IP:" + ip, now));
    }

    public long lockRemaining(String uuid, String ip) {
        long now = time.now();
        Account account = db.read(con -> store.getAccount(con, uuid));
        long until = account == null ? 0 : account.lockedUntil();
        if (ip != null && !ip.isBlank()) {
            long ipUntil = db.read(con -> {
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT locked_until FROM auth_lock WHERE lock_key=?")) {
                    ps.setString(1, "IP:" + ip);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getLong(1) : 0L;
                    }
                }
            });
            until = Math.max(until, ipUntil);
        }
        return Math.max(0, until - now);
    }

    public boolean adminUnlock(String uuid) {
        long now = time.now();
        db.tx(con -> {
            store.clearPinFailures(con, uuid);
            store.clearAuth(con, "P:" + uuid);
            store.audit(con, "admin", "AUTH_UNLOCK", uuid, now);
            return null;
        });
        return true;
    }

    public AuthOutcome verifyPin(String uuid, String pin, String ip) {
        long now = time.now();
        if (!PasswordHasher.validPin(pin, cfg.pinLength)) {
            return new AuthOutcome(false, "密码必须为 " + cfg.pinLength + " 位数字", false, 0, 0);
        }
        return db.tx(con -> {
            Account account = store.getAccount(con, uuid);
            if (account == null || !account.active()) {
                return new AuthOutcome(false, "请先开户", false, 0, 0);
            }
            if (account.lockedUntil() > now) {
                return new AuthOutcome(false, "密码已锁定，请稍后再试", true, account.lockedUntil(), 0);
            }
            if (ip != null && !ip.isBlank() && store.isLocked(con, "IP:" + ip, now)) {
                return new AuthOutcome(false, "该 IP 尝试次数过多，请稍后再试", true, 0, 0);
            }
            boolean ok = PasswordHasher.verify(pin, account.pinSalt(), account.pinHash());
            if (ok) {
                store.clearPinFailures(con, uuid);
                store.clearAuth(con, "P:" + uuid);
                if (ip != null && !ip.isBlank()) store.clearAuth(con, "IP:" + ip);
                return new AuthOutcome(true, "密码正确", false, 0, 0);
            }
            int fails = account.failCount() + 1;
            long lockUntil = fails >= cfg.pinFailLimit ? now + cfg.pinLockMinutes * 60_000L : 0;
            store.pinFailure(con, uuid, fails, lockUntil);
            store.registerFailure(con, "P:" + uuid, now, cfg.pinFailLimit,
                    cfg.pinLockMinutes, cfg.pinLockMinutes);
            int remaining = Math.max(0, cfg.pinFailLimit - fails);
            if (ip != null && !ip.isBlank()) {
                Store.AuthResult ipResult = store.registerFailure(con, "IP:" + ip, now,
                        cfg.ipFailLimit, cfg.ipWindowMinutes, cfg.ipWindowMinutes);
                if (ipResult.justLocked() || !ipResult.allowed()) {
                    return new AuthOutcome(false, "该 IP 尝试次数过多，已锁定 " + cfg.ipWindowMinutes + " 分钟",
                            true, now + cfg.ipWindowMinutes * 60_000L, remaining);
                }
            }
            if (lockUntil > 0) {
                return new AuthOutcome(false, "密码错误 " + fails + " 次，已锁定 " + cfg.pinLockMinutes + " 分钟",
                        true, lockUntil, 0);
            }
            return new AuthOutcome(false, "密码错误，剩余 " + remaining + " 次", false, 0, remaining);
        });
    }

    public boolean verifySecurityAnswer(String uuid, String answer, String ip) {
        long now = time.now();
        if (answer == null || answer.isBlank()) return false;
        return db.tx(con -> {
            Account account = store.getAccount(con, uuid);
            if (account == null || !account.active()) return false;
            boolean ok = PasswordHasher.verify(answer, account.securityAnswerSalt(), account.securityAnswerHash());
            if (ok) {
                if (ip != null && !ip.isBlank()) store.clearAuth(con, "IP:" + ip);
                store.clearAuth(con, "P:" + uuid);
                return true;
            }
            if (ip != null && !ip.isBlank()) {
                store.registerFailure(con, "IP:" + ip, now, cfg.ipFailLimit,
                        cfg.ipWindowMinutes, cfg.ipWindowMinutes);
            }
            return false;
        });
    }

    public Outcome resetPin(String uuid, String newPin, String ip) {
        if (!PasswordHasher.validPin(newPin, cfg.pinLength)) {
            return Outcome.fail("新密码必须为 " + cfg.pinLength + " 位数字");
        }
        Outcome fee = walletService.chargeToTreasury(uuid, cfg.passwordResetFee, "密码重置费");
        if (!fee.ok()) return fee;
        String salt = PasswordHasher.salt();
        long now = time.now();
        db.tx(con -> {
            store.updatePin(con, uuid, PasswordHasher.hash(newPin, salt), salt);
            store.clearAuth(con, "P:" + uuid);
            if (ip != null && !ip.isBlank()) store.clearAuth(con, "IP:" + ip);
            store.audit(con, uuid, "PIN_RESET", "fee=" + cfg.passwordResetFee, now);
            return null;
        });
        return Outcome.ok("密码已重置，费用 " + Money.format(cfg.passwordResetFee, cfg.currencySymbol));
    }

    // ---------------------------------------------------------------- money movement

    public Outcome deposit(String uuid, long amount) {
        Account account = db.read(con -> store.getAccount(con, uuid));
        if (account == null || !account.active()) return Outcome.fail("请先开户");
        if ("BLACKLIST".equals(db.read(con -> risk.level(con, uuid)))) {
            return Outcome.fail("黑名单账户仅可取款与还贷");
        }
        return walletService.depositToBank(uuid, amount, "银行存款");
    }

    public Outcome withdraw(String uuid, long amount) {
        if (amount <= 0) return Outcome.fail("金额必须大于 0");
        Outcome check = db.tx(con -> {
            Account account = store.getAccount(con, uuid);
            if (account == null || !account.active()) return Outcome.fail("请先开户");
            if (loans.inTPlusOne(con, uuid)) return Outcome.fail("贷款放款 T+1 内禁止取款");
            if (account.balance() < amount) return Outcome.fail("银行余额不足");
            return Outcome.ok("");
        });
        if (!check.ok()) return check;
        return walletService.withdrawToWallet(uuid, amount, "银行取款");
    }

    public Outcome remotePay(String payer, String recipientName, long amount, boolean confirmed) {
        String recipient = resolveAccountUuid(recipientName);
        if (recipient == null) return Outcome.fail("收款方未开户，转账拒绝");
        return transfer(payer, recipient, amount, Channel.REMOTE, confirmed);
    }

    public Outcome terminalTransfer(String payer, String recipientName, long amount, boolean confirmed) {
        String recipient = resolveAccountUuid(recipientName);
        if (recipient == null) return Outcome.fail("收款方未开户，转账拒绝");
        return transfer(payer, recipient, amount, Channel.TERMINAL, confirmed);
    }

    public enum Channel { REMOTE, TERMINAL }

    public Outcome transfer(String payer, String recipient, long amount, Channel channel, boolean confirmed) {
        long now = time.now();
        String day = time.dayKey();
        return db.tx(con -> {
            if (amount <= 0) return Outcome.fail("金额必须大于 0");
            if (payer.equals(recipient)) return Outcome.fail("不能给自己转账");
            Account from = store.getAccount(con, payer);
            Account to = store.getAccount(con, recipient);
            if (from == null || !from.active()) return Outcome.fail("付款方未开户");
            if (to == null || !to.active()) return Outcome.fail("收款方未开户，转账拒绝");
            String riskLevel = risk.level(con, payer);
            if ("BLACKLIST".equals(riskLevel)) return Outcome.fail("黑名单账户冻结转账");
            if (channel == Channel.REMOTE && !risk.canRemoteTransfer(riskLevel)) {
                return Outcome.fail("高风险账户仅能在银行终端办理");
            }
            if (risk.needsConfirm(riskLevel, amount) && !confirmed) {
                return Outcome.fail("大额转账需二次确认：请使用 /bank confirm", "CONFIRM");
            }
            long singleLimit = channel == Channel.REMOTE
                    ? cfg.remoteSingleLimit : levels.terminalSingleLimit(con, payer);
            if (amount > singleLimit) {
                return Outcome.fail("超过单笔限额 " + Money.format(singleLimit, cfg.currencySymbol));
            }
            Store.DailyStats stats = store.dailyStats(con, payer, day);
            long dailyLimit = channel == Channel.REMOTE ? cfg.remoteDailyLimit : cfg.terminalDailyLimit;
            long used = channel == Channel.REMOTE ? stats.remoteOut() : stats.terminalOut();
            if (used + amount > dailyLimit) {
                return Outcome.fail("超过当日累计限额，明日 00:00 重置");
            }
            if (loans.inTPlusOne(con, payer)) return Outcome.fail("贷款放款 T+1 内禁止转出");
            LoanService.Check loanCheck = loans.checkRelatedOutflow(con, payer, recipient, amount, now);
            if (!loanCheck.allowed()) return Outcome.fail(loanCheck.reason());

            int feePpm = levels.feePpm(con, payer);
            long fee = Money.permille(amount, feePpm);
            if (from.balance() < amount + fee) {
                return Outcome.fail("余额不足（含手续费 " + Money.format(fee, cfg.currencySymbol) + "）");
            }
            long arrears = tax.takeArrearsFromIncome(con, recipient, amount, now);
            long net = amount - arrears;
            long fromBefore = from.balance();
            Long fromAfter = store.debitAccount(con, payer, amount + fee);
            if (fromAfter == null) return Outcome.fail("余额不足");
            long toBefore = to.balance();
            long toAfter = store.creditAccount(con, recipient, net);
            if (fee > 0) store.creditTreasury(con, fee, now);
            if (loans.activeLoan(con, payer) != null && relations.related(con, payer, recipient)) {
                loans.recordRelatedOutflow(con, payer, amount);
            }
            store.insertTx(con, new Tx(Store.newId(), now, channel == Channel.REMOTE ? "REMOTE" : "TRANSFER",
                    payer, recipient, amount, fee, fromBefore, fromAfter, toBefore, toAfter,
                    "转账" + (arrears > 0 ? "（代扣欠税 " + arrears + "）" : "")));
            store.addDailyReceived(con, recipient, day, net);
            store.addDailyPaid(con, payer, day, amount);
            if (channel == Channel.REMOTE) store.addDailyRemoteOut(con, payer, day, amount);
            else store.addDailyTerminalOut(con, payer, day, amount);
            long alertLimit = channel == Channel.REMOTE ? cfg.remoteSingleLimit : singleLimit;
            risk.inspectLarge(con, payer, amount, alertLimit);
            risk.inspectStructuring(con, payer, amount, alertLimit, 3);
            risk.inspectFastOut(con, payer, amount);
            store.audit(con, payer, channel == Channel.REMOTE ? "REMOTE_PAY" : "TRANSFER",
                    payer + "->" + recipient + " " + amount + " fee=" + fee, now);
            return Outcome.ok((channel == Channel.REMOTE ? "远程转账" : "终端转账") + "成功："
                    + Money.format(amount, cfg.currencySymbol)
                    + (fee > 0 ? "，手续费 " + Money.format(fee, cfg.currencySymbol) : "")
                    + (arrears > 0 ? "，收款方补缴欠税 " + Money.format(arrears, cfg.currencySymbol) : ""));
        });
    }

    private String resolveAccountUuid(String name) {
        if (name == null) return null;
        return db.read(con -> {
            Account account = store.getAccountByName(con, name);
            return account == null ? null : account.uuid();
        });
    }

    // ---------------------------------------------------------------- shared accounts

    public Outcome createShared(String owner, String type, String name) {
        if (!Set.of("family", "couple", "public").contains(type)) {
            return Outcome.fail("共享账户类型只能为 family / couple / public");
        }
        long now = time.now();
        int count = db.read(con -> store.memberships(con, owner).size());
        if (count >= cfg.sharedMaxPerPlayer) return Outcome.fail("每人最多同时加入 " + cfg.sharedMaxPerPlayer + " 个共享账户");
        Outcome fee = walletService.chargeToTreasury(owner, cfg.sharedCreateFee, "共享账户创建费");
        if (!fee.ok()) return fee;
        String id = Store.newId();
        try {
            db.tx(con -> {
                store.insertShared(con, new SharedAccount(id, name, type, 0, owner, now, "ACTIVE"));
                store.addMember(con, id, owner, now, 0);
                store.audit(con, owner, "SHARED_CREATE", id + " " + type + " " + name, now);
                return null;
            });
        } catch (RuntimeException e) {
            wallet.deposit(owner, cfg.sharedCreateFee);
            db.tx(con -> {
                store.debitTreasury(con, cfg.sharedCreateFee, time.now());
                return null;
            });
            return Outcome.fail("共享账户创建失败，创建费已退回");
        }
        return Outcome.ok("共享账户创建成功，编号 " + id + "，创建费 "
                + Money.format(cfg.sharedCreateFee, cfg.currencySymbol));
    }

    public Outcome joinShared(String uuid, String sharedId) {
        long now = time.now();
        return db.tx(con -> {
            SharedAccount shared = store.getShared(con, sharedId);
            if (shared == null || !shared.active()) return Outcome.fail("共享账户不存在");
            if (store.getMember(con, sharedId, uuid) != null) return Outcome.fail("你已在该共享账户中");
            if (store.memberCount(con, sharedId) >= cfg.sharedMaxMembers) {
                return Outcome.fail("共享账户成员已满（上限 " + cfg.sharedMaxMembers + " 人）");
            }
            if (store.memberships(con, uuid).size() >= cfg.sharedMaxPerPlayer) {
                return Outcome.fail("每人最多同时加入 " + cfg.sharedMaxPerPlayer + " 个共享账户");
            }
            store.addMember(con, sharedId, uuid, now, 0);
            store.audit(con, uuid, "SHARED_JOIN", sharedId, now);
            return Outcome.ok("已加入共享账户 " + shared.name());
        });
    }

    public Outcome leaveShared(String uuid, String sharedId) {
        long now = time.now();
        return db.tx(con -> {
            SharedMember member = store.getMember(con, sharedId, uuid);
            if (member == null) return Outcome.fail("你不在该共享账户中");
            SharedAccount shared = store.getShared(con, sharedId);
            if (shared == null || !shared.active()) return Outcome.fail("共享账户不存在");
            Account account = store.getAccount(con, uuid);
            if (account == null || !account.active()) return Outcome.fail("请先开户");
            int members = store.memberCount(con, sharedId);
            long share = members <= 1 ? shared.balance() : shared.balance() / members;
            if (share > 0) {
                Long remaining = store.debitShared(con, sharedId, share);
                if (remaining == null) return Outcome.fail("共享账户余额异常");
                store.creditAccount(con, uuid, share);
                store.insertTx(con, new Tx(Store.newId(), now, "SHARED_LEAVE", sharedId, uuid,
                        share, 0, shared.balance(), remaining, account.balance(), account.balance() + share,
                        "退出共享账户返还份额"));
            }
            store.removeMember(con, sharedId, uuid);
            if (members <= 1) store.closeShared(con, sharedId);
            store.audit(con, uuid, "SHARED_LEAVE", sharedId + " return=" + share, now);
            return Outcome.ok("已退出共享账户，返还 " + Money.format(share, cfg.currencySymbol) + " 到银行账户");
        });
    }

    public Outcome sharedDeposit(String actor, String sharedId, long amount) {
        if (amount <= 0) return Outcome.fail("金额必须大于 0");
        long now = time.now();
        String day = time.dayKey();
        Outcome check = db.tx(con -> {
            SharedMember member = store.getMember(con, sharedId, actor);
            if (member == null) return Outcome.fail("你不在该共享账户中");
            if (tax.arrearsLocked(con, actor, now)) return Outcome.fail("欠税满 " + cfg.arrearsLockDays + " 天，共享账户功能锁定");
            long limit = levels.terminalSingleLimit(con, actor);
            if (amount > limit) return Outcome.fail("超过终端单笔限额 " + Money.format(limit, cfg.currencySymbol));
            Store.DailyStats stats = store.dailyStats(con, actor, day);
            if (stats.terminalOut() + amount > cfg.terminalDailyLimit) {
                return Outcome.fail("超过终端当日累计限额");
            }
            return Outcome.ok("");
        });
        if (!check.ok()) return check;
        Outcome outcome = walletService.depositToShared(sharedId, actor, amount, "共享账户存款");
        if (outcome.ok()) {
            db.tx(con -> {
                store.addDailyTerminalOut(con, actor, day, amount);
                return null;
            });
        }
        return outcome;
    }

    public Outcome sharedWithdraw(String actor, String sharedId, long amount) {
        if (amount <= 0) return Outcome.fail("金额必须大于 0");
        long now = time.now();
        String day = time.dayKey();
        long[] state = db.read(con -> {
            SharedMember member = store.getMember(con, sharedId, actor);
            if (member == null) return null;
            SharedAccount shared = store.getShared(con, sharedId);
            if (shared == null || !shared.active()) return null;
            return new long[]{member.netContribution(), shared.balance()};
        });
        if (state == null) return Outcome.fail("你不在该共享账户中");
        if (db.read(con -> tax.arrearsLocked(con, actor, now))) {
            return Outcome.fail("欠税满 " + cfg.arrearsLockDays + " 天，共享账户功能锁定");
        }
        if (db.read(con -> levels.terminalSingleLimit(con, actor)) < amount) {
            return Outcome.fail("超过终端单笔限额");
        }
        Store.DailyStats stats = db.read(con -> store.dailyStats(con, actor, day));
        if (stats.terminalOut() + amount > cfg.terminalDailyLimit) {
            return Outcome.fail("超过终端当日累计限额");
        }
        long excess = Math.max(0, amount - state[0]);
        Outcome outcome = walletService.withdrawSharedToWallet(sharedId, actor, amount, "共享账户提现");
        if (!outcome.ok()) return outcome;
        db.tx(con -> {
            SharedMember member = store.getMember(con, sharedId, actor);
            if (member != null) {
                store.setMemberContribution(con, sharedId, actor, Math.max(0, member.netContribution() - amount));
            }
            if (excess > 0) store.addDailyReceived(con, actor, day, excess);
            store.addDailyTerminalOut(con, actor, day, amount);
            store.audit(con, actor, "SHARED_WITHDRAW", sharedId + " " + amount + " taxableExcess=" + excess, now);
            return null;
        });
        return Outcome.ok(outcome.message() + (excess > 0
                ? "，其中 " + Money.format(excess, cfg.currencySymbol) + " 计入当日个税收入" : ""));
    }

    public Outcome sharedTransferToBank(String actor, String sharedId, String targetName, long amount) {
        if (amount <= 0) return Outcome.fail("金额必须大于 0");
        long now = time.now();
        String day = time.dayKey();
        return db.tx(con -> {
            SharedMember member = store.getMember(con, sharedId, actor);
            if (member == null) return Outcome.fail("你不在该共享账户中");
            Account target = store.getAccountByName(con, targetName);
            if (target == null || !target.active()) return Outcome.fail("目标成员未开户");
            if (store.getMember(con, sharedId, target.uuid()) == null) return Outcome.fail("只能划转给共享账户成员");
            SharedAccount shared = store.getShared(con, sharedId);
            if (shared == null || !shared.active()) return Outcome.fail("共享账户不存在");
            if (loans.inTPlusOne(con, actor)) return Outcome.fail("贷款放款 T+1 内禁止共享划转");
            long limit = levels.terminalSingleLimit(con, actor);
            if (amount > limit) return Outcome.fail("超过终端单笔限额");
            Store.DailyStats stats = store.dailyStats(con, actor, day);
            if (stats.terminalOut() + amount > cfg.terminalDailyLimit) {
                return Outcome.fail("超过终端当日累计限额");
            }
            if (shared.balance() < amount) return Outcome.fail("共享账户余额不足");
            long fee = Money.permille(amount, levels.feePpm(con, actor));
            if (shared.balance() < amount + fee) return Outcome.fail("共享账户余额不足以支付手续费");
            long fromBefore = shared.balance();
            Long fromAfter = store.debitShared(con, sharedId, amount + fee);
            if (fromAfter == null) return Outcome.fail("共享账户余额不足");
            long toBefore = target.balance();
            long toAfter = store.creditAccount(con, target.uuid(), amount);
            if (fee > 0) store.creditTreasury(con, fee, now);
            store.insertTx(con, new Tx(Store.newId(), now, "SHARED_TRANSFER", sharedId, target.uuid(),
                    amount, fee, fromBefore, fromAfter, toBefore, toAfter, "共享账户内部划转"));
            store.addDailyTerminalOut(con, actor, day, amount);
            store.audit(con, actor, "SHARED_TRANSFER", sharedId + "->" + target.uuid() + " " + amount, now);
            return Outcome.ok("共享账户划转成功：" + Money.format(amount, cfg.currencySymbol));
        });
    }

    public List<SharedView> sharedViews(String uuid) {
        return db.read(con -> {
            List<SharedView> views = new ArrayList<>();
            for (SharedAccount shared : store.sharedForPlayer(con, uuid)) {
                int members = store.memberCount(con, shared.id());
                SharedMember member = store.getMember(con, shared.id(), uuid);
                views.add(new SharedView(shared, members, members > 0 ? shared.balance() / members : 0,
                        member == null ? 0 : member.netContribution()));
            }
            return views;
        });
    }

    // ---------------------------------------------------------------- cards

    public CardIssue issueCard(String uuid) {
        long now = time.now();
        Account account = db.read(con -> store.getAccount(con, uuid));
        if (account == null || !account.active()) return null;
        int level = db.read(con -> levels.level(con, uuid));
        long fee = levels.cardIssueFee(level);
        if (db.read(con -> store.activeCardCount(con, uuid)) >= cfg.cardMaxPerPlayer) {
            return null;
        }
        Outcome charged = walletService.chargeToTreasury(uuid, fee, "银行卡工本费");
        if (!charged.ok()) return null;
        String cardNo = generateCardNo();
        try {
            db.tx(con -> {
                store.insertCard(con, new Card(cardNo, uuid, now, "ACTIVE"));
                store.audit(con, uuid, "CARD_ISSUE", cardNo + " fee=" + fee, now);
                return null;
            });
        } catch (RuntimeException e) {
            wallet.deposit(uuid, fee);
            db.tx(con -> {
                store.debitTreasury(con, fee, time.now());
                return null;
            });
            return null;
        }
        return new CardIssue(cardNo, fee, level);
    }

    public CardIssue reissueCard(String uuid, String oldCardNo) {
        Card old = db.read(con -> store.getCard(con, oldCardNo));
        if (old == null || !old.ownerUuid().equals(uuid) || !old.active()) return null;
        long fee = cfg.cardReissueFee;
        Outcome charged = walletService.chargeToTreasury(uuid, fee, "银行卡补办费");
        if (!charged.ok()) return null;
        String cardNo = generateCardNo();
        long now = time.now();
        db.tx(con -> {
            store.setCardStatus(con, oldCardNo, "REVOKED");
            store.insertCard(con, new Card(cardNo, uuid, now, "ACTIVE"));
            store.audit(con, uuid, "CARD_REISSUE", oldCardNo + "->" + cardNo + " fee=" + fee, now);
            return null;
        });
        return new CardIssue(cardNo, fee, db.read(con -> levels.level(con, uuid)));
    }

    public Outcome bindCardOwner(String sender, String targetName, String cardNo) {
        Account target = db.read(con -> store.getAccountByName(con, targetName));
        if (target == null) return Outcome.fail("目标玩家未开户");
        if (target.uuid().equals(sender)) return Outcome.fail("不能转让给自己");
        long now = time.now();
        return db.tx(con -> {
            Card card = store.getCard(con, cardNo);
            if (card == null || !card.active() || !card.ownerUuid().equals(sender)) {
                return Outcome.fail("卡号无效或不属于你");
            }
            if (store.activeCardCount(con, target.uuid()) >= cfg.cardMaxPerPlayer) {
                return Outcome.fail("对方持卡已达上限");
            }
            store.setCardOwner(con, cardNo, target.uuid());
            store.audit(con, sender, "CARD_TRANSFER", cardNo + "->" + target.uuid(), now);
            return Outcome.ok("银行卡已转让给 " + target.name());
        });
    }

    public Card card(String cardNo) {
        return db.read(con -> store.getCard(con, cardNo));
    }

    public List<Card> cards(String uuid) {
        return db.read(con -> store.cardsOf(con, uuid));
    }

    /** 实体卡登录：卡有效、属于本人、且账户有效。 */
    public boolean cardLogin(String uuid, String cardNo) {
        return db.read(con -> {
            Card card = store.getCard(con, cardNo);
            Account account = store.getAccount(con, uuid);
            return card != null && card.active() && card.ownerUuid().equals(uuid)
                    && account != null && account.active();
        });
    }

    public int sessionMinutes(String uuid) {
        return db.read(con -> levels.sessionMinutes(con, uuid));
    }

    private String generateCardNo() {
        for (int i = 0; i < 20; i++) {
            int value = random.nextInt(100_000_000);
            String no = String.format("FC%08d", value);
            Card existing = db.read(con -> store.getCard(con, no));
            if (existing == null) return no;
        }
        throw new IllegalStateException("无法生成唯一卡号");
    }

    // ---------------------------------------------------------------- levels / tax / report

    public LevelService.Progress levelProgress(String uuid) {
        return db.read(con -> levels.progress(con, uuid));
    }

    public List<LevelService.Change> evaluateLevels(boolean monthly) {
        long now = time.now();
        return db.tx(con -> {
            List<LevelService.Change> changes = monthly ? levels.evaluateAll(con, now)
                    : levels.updateComplianceDaily(con, now);
            for (LevelService.Change change : changes) {
                store.audit(con, "system", monthly ? "LEVEL_EVAL" : "COMPLIANCE", change.uuid()
                        + " " + change.oldLevel() + "->" + change.newLevel() + " " + change.reason(), now);
            }
            return changes;
        });
    }

    public List<LevelService.Change> checkLevel(String uuid) {
        long now = time.now();
        return db.tx(con -> {
            LevelService.Change change = levels.evaluate(con, uuid, now);
            return change == null ? List.of() : List.of(change);
        });
    }

    public List<String> taxInfo(String uuid) {
        return db.read(con -> {
            List<String> lines = new ArrayList<>();
            lines.add("欠税: " + Money.format(store.owedTax(con, uuid), cfg.currencySymbol));
            for (TaxRecord record : store.taxRecords(con, uuid, 5)) {
                lines.add(record.dayKey() + " " + record.taxType() + " 应缴 " + Money.format(record.amount(), cfg.currencySymbol)
                        + " 已缴 " + Money.format(record.paid(), cfg.currencySymbol) + " [" + record.status() + "]");
            }
            return lines;
        });
    }

    public List<String> history(String uuid, int requested) {
        return db.read(con -> {
            int limit = Math.min(Math.max(1, requested), levels.historyLimit(con, uuid));
            List<String> lines = new ArrayList<>();
            for (Tx tx : store.recentTransactions(con, uuid, limit)) {
                lines.add(time.dayKey(LocalDate.ofInstant(java.time.Instant.ofEpochMilli(tx.ts()), time.zone()))
                        + " " + tx.type() + " " + Money.format(tx.amount(), cfg.currencySymbol)
                        + (tx.fee() > 0 ? " fee=" + Money.format(tx.fee(), cfg.currencySymbol) : "")
                        + " " + (tx.reason() == null ? "" : tx.reason()));
            }
            return lines;
        });
    }

    public Report report() {
        return db.read(con -> {
            List<PlayerData> players = store.allPlayers(con);
            long cash = 0;
            long bank = 0;
            long shared = 0;
            long loansOut = store.totalOutstanding(con);
            List<Long> totals = new ArrayList<>();
            for (PlayerData player : players) {
                long w;
                try {
                    w = wallet.balance(player.uuid());
                } catch (RuntimeException e) {
                    w = 0;
                }
                long b = store.balance(con, player.uuid());
                long s = store.sharedShare(con, player.uuid());
                long l = store.outstandingFor(con, player.uuid());
                cash += Math.max(0, w);
                bank += b;
                totals.add(Math.max(0, w + b + s - l));
            }
            shared = store.totalSharedDeposits(con);
            totals.sort(Comparator.reverseOrder());
            long top10 = 0;
            for (int i = 0; i < Math.min(10, totals.size()); i++) top10 += totals.get(i);
            double gini = gini(totals);
            long totalAssets = 0;
            for (long t : totals) totalAssets += t;
            long totalSupply = cash + bank + shared - loansOut;
            return new Report(totalSupply, cash, bank, shared, loansOut, store.treasury(con),
                    store.totalOwedTax(con), top10, gini, (int) store.countAccounts(con),
                    (int) store.countLoans(con, "ACTIVE") + (int) store.countLoans(con, "OVERDUE"),
                    (int) store.countLoans(con, "OVERDUE"), store.suspiciousCount(con));
        });
    }

    private double gini(List<Long> sortedDesc) {
        if (sortedDesc.isEmpty()) return 0;
        long sum = 0;
        int n = sortedDesc.size();
        double weighted = 0;
        List<Long> asc = new ArrayList<>(sortedDesc);
        asc.sort(Comparator.naturalOrder());
        for (int i = 0; i < n; i++) {
            long value = asc.get(i);
            sum += value;
            weighted += (2L * (i + 1) - n - 1) * value;
        }
        if (sum <= 0) return 0;
        return Math.max(0, Math.min(1, weighted / ((double) n * sum)));
    }

    public long treasury() {
        return db.read(store::treasury);
    }

    public Account account(String uuid) {
        return db.read(con -> store.getAccount(con, uuid));
    }

    public PlayerData player(String uuid) {
        return db.read(con -> store.getPlayer(con, uuid));
    }

    public void touchJoin(String uuid, String name, String ip) {
        long now = time.now();
        db.tx(con -> {
            store.upsertJoin(con, uuid, name, ip, now);
            return null;
        });
    }
}
