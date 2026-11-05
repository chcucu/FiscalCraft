package com.fiscalcraft.service;

import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.db.Database;
import com.fiscalcraft.model.Models.Account;
import com.fiscalcraft.model.Models.SharedAccount;
import com.fiscalcraft.model.Models.Tx;
import com.fiscalcraft.model.Models.WalletOp;
import com.fiscalcraft.queue.Outcome;
import com.fiscalcraft.util.Money;
import com.fiscalcraft.util.TimeSource;
import com.fiscalcraft.wallet.WalletGateway;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Vault 钱包与插件银行/财政/共享账户之间的边界。
 * 采用写前意图（wallet_ops）+ 启动恢复；运行期失败立即冲正。
 * 纯银行间转账不走本类，因此不受外部账本两阶段限制。
 */
public final class WalletService {
    private final Store store;
    private final Database db;
    private final FiscalConfig cfg;
    private final TimeSource time;
    private final WalletGateway wallet;
    private final Logger logger;

    public WalletService(Store store, Database db, FiscalConfig cfg, TimeSource time, WalletGateway wallet, Logger logger) {
        this.store = store;
        this.db = db;
        this.cfg = cfg;
        this.time = time;
        this.wallet = wallet;
        this.logger = logger;
    }

    public long walletBalance(String uuid) {
        return wallet.balance(uuid);
    }

    /** 钱包现金存入个人银行账户。 */
    public Outcome depositToBank(String uuid, long amount, String reason) {
        Account account = db.read(con -> store.getAccount(con, uuid));
        if (account == null || !account.active()) return Outcome.fail("请先开户");
        if (amount <= 0) return Outcome.fail("金额必须大于 0");
        long now = time.now();
        long before = wallet.balance(uuid);
        String opId = Store.newId();
        db.tx(con -> {
            store.insertWalletOp(con, new WalletOp(opId, uuid, "DEPOSIT", "BANK", amount, "PENDING",
                    before, account.balance(), account.balance() + amount, reason, now, now));
            return null;
        });
        if (!wallet.withdraw(uuid, amount)) {
            db.tx(con -> {
                store.updateWalletOp(con, opId, "FAILED", time.now());
                return null;
            });
            return Outcome.fail("钱包余额不足，存款未发生");
        }
        try {
            db.tx(con -> {
                Account fresh = store.getAccount(con, uuid);
                if (fresh == null || !fresh.active()) throw new SQLException("账户不存在");
                long after = store.creditAccount(con, uuid, amount);
                store.insertTxIfAbsent(con, new Tx(opId, time.now(), "DEPOSIT", uuid, null, amount, 0,
                        before, null, null, after, reason));
                store.updateWalletOp(con, opId, "DONE", time.now());
                return null;
            });
            return Outcome.ok("已存入 " + Money.format(amount, cfg.currencySymbol));
        } catch (RuntimeException e) {
            logger.severe("存款入账失败，执行钱包冲正: " + e.getMessage());
            if (!wallet.deposit(uuid, amount)) {
                logger.severe("钱包冲正失败，操作 " + opId + " 留待启动恢复");
                return Outcome.fail("入账异常，资金进入恢复队列，请联系管理员");
            }
            db.tx(con -> {
                store.updateWalletOp(con, opId, "FAILED", time.now());
                return null;
            });
            return Outcome.fail("存款失败，资金已退回钱包");
        }
    }

    /** 个人银行账户取现到钱包。 */
    public Outcome withdrawToWallet(String uuid, long amount, String reason) {
        if (amount <= 0) return Outcome.fail("金额必须大于 0");
        long now = time.now();
        long before = wallet.balance(uuid);
        String opId = Store.newId();
        Long after;
        try {
            after = db.tx(con -> {
                Account account = store.getAccount(con, uuid);
                if (account == null || !account.active()) throw new SQLException("账户不存在");
                Long remaining = store.debitAccount(con, uuid, amount);
                if (remaining == null) return null;
                store.insertWalletOp(con, new WalletOp(opId, uuid, "WITHDRAW", "WALLET", amount, "PENDING",
                        before, account.balance(), remaining, reason, now, now));
                store.insertTxIfAbsent(con, new Tx(opId, now, "WITHDRAW", uuid, null, amount, 0,
                        account.balance(), remaining, before, null, reason));
                return remaining;
            });
        } catch (RuntimeException e) {
            return Outcome.fail("取款失败: " + e.getMessage());
        }
        if (after == null) return Outcome.fail("银行余额不足");
        if (wallet.deposit(uuid, amount)) {
            db.tx(con -> {
                store.updateWalletOp(con, opId, "DONE", time.now());
                return null;
            });
            return Outcome.ok("已取现 " + Money.format(amount, cfg.currencySymbol));
        }
        // 钱包入账失败，冲正银行余额
        db.tx(con -> {
            store.creditAccount(con, uuid, amount);
            store.updateWalletOp(con, opId, "FAILED", time.now());
            store.insertTx(con, new Tx(Store.newId(), time.now(), "WITHDRAW_REVERSAL", null, uuid, amount, 0,
                    null, null, after, after + amount, "钱包入账失败冲正"));
            return null;
        });
        return Outcome.fail("钱包入账失败，已退回银行账户");
    }

    /** 钱包扣款进入财政池（开户费/卡费/共享创建费/改密费）。 */
    public Outcome chargeToTreasury(String uuid, long amount, String reason) {
        if (amount <= 0) return Outcome.fail("金额必须大于 0");
        long now = time.now();
        long before = wallet.balance(uuid);
        String opId = Store.newId();
        db.tx(con -> {
            store.insertWalletOp(con, new WalletOp(opId, uuid, "FEE", "TREASURY", amount, "PENDING",
                    before, 0, 0, reason, now, now));
            return null;
        });
        if (!wallet.withdraw(uuid, amount)) {
            db.tx(con -> {
                store.updateWalletOp(con, opId, "FAILED", time.now());
                return null;
            });
            return Outcome.fail("钱包余额不足，需 " + Money.format(amount, cfg.currencySymbol));
        }
        try {
            db.tx(con -> {
                store.creditTreasury(con, amount, time.now());
                store.insertTxIfAbsent(con, new Tx(opId, time.now(), "FEE", uuid, Store.TREASURY_ID, amount, 0,
                        before, null, null, null, reason));
                store.updateWalletOp(con, opId, "DONE", time.now());
                return null;
            });
            return Outcome.ok("已支付 " + Money.format(amount, cfg.currencySymbol));
        } catch (RuntimeException e) {
            logger.severe("财政入账失败，执行钱包冲正: " + e.getMessage());
            if (wallet.deposit(uuid, amount)) {
                db.tx(con -> {
                    store.updateWalletOp(con, opId, "FAILED", time.now());
                    return null;
                });
                return Outcome.fail("支付失败，资金已退回钱包");
            }
            return Outcome.fail("支付异常，资金进入恢复队列");
        }
    }

    /** 钱包现金存入共享账户。 */
    public Outcome depositToShared(String sharedId, String actor, long amount, String reason) {
        SharedAccount shared = db.read(con -> store.getShared(con, sharedId));
        if (shared == null || !shared.active()) return Outcome.fail("共享账户不存在");
        if (amount <= 0) return Outcome.fail("金额必须大于 0");
        long now = time.now();
        long before = wallet.balance(actor);
        String opId = Store.newId();
        db.tx(con -> {
            store.insertWalletOp(con, new WalletOp(opId, actor, "SHARED_DEPOSIT", "SHARED:" + sharedId,
                    amount, "PENDING", before, shared.balance(), shared.balance() + amount, reason, now, now));
            return null;
        });
        if (!wallet.withdraw(actor, amount)) {
            db.tx(con -> {
                store.updateWalletOp(con, opId, "FAILED", time.now());
                return null;
            });
            return Outcome.fail("钱包余额不足，存款未发生");
        }
        try {
            db.tx(con -> {
                SharedAccount fresh = store.getShared(con, sharedId);
                if (fresh == null || !fresh.active()) throw new SQLException("共享账户不存在");
                store.updateSharedBalance(con, sharedId, amount);
                var member = store.getMember(con, sharedId, actor);
                if (member != null) {
                    store.setMemberContribution(con, sharedId, actor, member.netContribution() + amount);
                }
                store.insertTxIfAbsent(con, new Tx(opId, time.now(), "SHARED_DEPOSIT", actor, null, amount, 0,
                        before, null, fresh.balance(), fresh.balance() + amount, reason));
                store.updateWalletOp(con, opId, "DONE", time.now());
                return null;
            });
            return Outcome.ok("已存入共享账户 " + Money.format(amount, cfg.currencySymbol));
        } catch (RuntimeException e) {
            if (!wallet.deposit(actor, amount)) {
                logger.severe("共享存款入账失败且冲正失败，op=" + opId);
                return Outcome.fail("共享存款异常，资金进入恢复队列");
            }
            db.tx(con -> {
                store.updateWalletOp(con, opId, "FAILED", time.now());
                return null;
            });
            return Outcome.fail("共享存款失败，资金已退回钱包");
        }
    }

    /** 共享账户提现到个人钱包；调用方已完成净投入与个税处理。 */
    public Outcome withdrawSharedToWallet(String sharedId, String actor, long amount, String reason) {
        if (amount <= 0) return Outcome.fail("金额必须大于 0");
        long now = time.now();
        long before = wallet.balance(actor);
        String opId = Store.newId();
        long beforeShared;
        try {
            beforeShared = db.read(con -> store.sharedBalance(con, sharedId));
        } catch (RuntimeException e) {
            return Outcome.fail("共享账户不存在");
        }
        Long after;
        try {
            after = db.tx(con -> {
                Long remaining = store.debitShared(con, sharedId, amount);
                if (remaining == null) return null;
                store.insertWalletOp(con, new WalletOp(opId, actor, "SHARED_WITHDRAW", "SHARED:" + sharedId,
                        amount, "PENDING", before, beforeShared, remaining, reason, now, now));
                store.insertTxIfAbsent(con, new Tx(opId, now, "SHARED_WITHDRAW", actor, null, amount, 0,
                        beforeShared, remaining, before, null, reason));
                return remaining;
            });
        } catch (RuntimeException e) {
            return Outcome.fail("共享提现失败: " + e.getMessage());
        }
        if (after == null) return Outcome.fail("共享账户余额不足");
        if (wallet.deposit(actor, amount)) {
            db.tx(con -> {
                store.updateWalletOp(con, opId, "DONE", time.now());
                return null;
            });
            return Outcome.ok("已提现 " + Money.format(amount, cfg.currencySymbol));
        }
        db.tx(con -> {
            store.updateSharedBalance(con, sharedId, amount);
            store.updateWalletOp(con, opId, "FAILED", time.now());
            return null;
        });
        return Outcome.fail("钱包入账失败，已退回共享账户");
    }

    /** 启动恢复；必须在队列初始化阶段、接受玩家请求前调用。 */
    public void recoverPending() {
        List<WalletOp> pending = db.read(store::pendingWalletOps);
        for (WalletOp op : pending) {
            try {
                if ("WITHDRAW".equals(op.direction()) || "SHARED_WITHDRAW".equals(op.direction())) {
                    recoverCredit(op);
                } else {
                    recoverDebit(op);
                }
            } catch (Throwable t) {
                logger.severe("恢复钱包操作失败 op=" + op.opId() + ": " + t.getMessage());
            }
        }
    }

    private void recoverDebit(WalletOp op) {
        long current = wallet.balance(op.playerUuid());
        long now = time.now();
        boolean debited;
        if (current <= op.walletBefore() - op.amount()) {
            debited = true;
        } else {
            debited = wallet.withdraw(op.playerUuid(), op.amount());
            if (!debited) {
                db.tx(con -> {
                    store.updateWalletOp(con, op.opId(), "FAILED", now);
                    store.audit(con, "system", "WALLET_RECOVERY_ABORT", op.opId() + " " + op.direction(), now);
                    return null;
                });
                return;
            }
        }
        if (!debited) return;
        try {
            db.tx(con -> {
                if (op.target().startsWith("SHARED:")) {
                    String sharedId = op.target().substring("SHARED:".length());
                    SharedAccount shared = store.getShared(con, sharedId);
                    if (shared == null || !shared.active()) {
                        wallet.deposit(op.playerUuid(), op.amount());
                        store.updateWalletOp(con, op.opId(), "FAILED", now);
                        return null;
                    }
                    store.updateSharedBalance(con, sharedId, op.amount());
                    SharedMemberContribution.add(store, con, sharedId, op.playerUuid(), op.amount());
                    store.insertTxIfAbsent(con, new Tx(op.opId(), now, "SHARED_DEPOSIT", op.playerUuid(), null,
                            op.amount(), 0, op.walletBefore(), null, shared.balance(), shared.balance() + op.amount(), "启动恢复"));
                } else if ("TREASURY".equals(op.target())) {
                    store.creditTreasury(con, op.amount(), now);
                    store.insertTxIfAbsent(con, new Tx(op.opId(), now, "FEE", op.playerUuid(), Store.TREASURY_ID,
                            op.amount(), 0, op.walletBefore(), null, null, null, "启动恢复"));
                } else {
                    Account account = store.getAccount(con, op.playerUuid());
                    if (account == null || !account.active()) {
                        wallet.deposit(op.playerUuid(), op.amount());
                        store.updateWalletOp(con, op.opId(), "FAILED", now);
                        return null;
                    }
                    store.creditAccount(con, op.playerUuid(), op.amount());
                    store.insertTxIfAbsent(con, new Tx(op.opId(), now, "DEPOSIT", op.playerUuid(), null,
                            op.amount(), 0, op.walletBefore(), null, null, account.balance() + op.amount(), "启动恢复"));
                }
                store.updateWalletOp(con, op.opId(), "DONE", now);
                store.audit(con, "system", "WALLET_RECOVERY_DEBIT", op.opId(), now);
                return null;
            });
        } catch (RuntimeException e) {
            logger.severe("恢复存款失败 op=" + op.opId() + ": " + e.getMessage());
        }
    }

    private void recoverCredit(WalletOp op) {
        long current = wallet.balance(op.playerUuid());
        long now = time.now();
        if (current <= op.walletBefore() + op.amount()) {
            if (!wallet.deposit(op.playerUuid(), op.amount())) {
                logger.severe("恢复取现失败，钱包入账被拒绝 op=" + op.opId());
                return;
            }
        }
        db.tx(con -> {
            store.updateWalletOp(con, op.opId(), "DONE", now);
            store.audit(con, "system", "WALLET_RECOVERY_CREDIT", op.opId(), now);
            return null;
        });
    }

    private static final class SharedMemberContribution {
        static void add(Store store, Connection con, String sharedId, String uuid, long amount) throws SQLException {
            var member = store.getMember(con, sharedId, uuid);
            if (member != null) store.setMemberContribution(con, sharedId, uuid, member.netContribution() + amount);
        }
    }
}
