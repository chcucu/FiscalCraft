package com.fiscalcraft.service;

import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.db.Database;
import com.fiscalcraft.model.Models.Account;
import com.fiscalcraft.model.Models.Loan;
import com.fiscalcraft.model.Models.PlayerData;
import com.fiscalcraft.model.Models.Tx;
import com.fiscalcraft.queue.Outcome;
import com.fiscalcraft.util.Money;
import com.fiscalcraft.util.TimeSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LoanService {
    public record AccrualResult(List<String> notices, int writtenOff) {
    }

    public record Eligibility(long averageBank, long maxLoan, long poolAvailable, int creditScore, String reason) {
    }

    private final Store store;
    private final Database db;
    private final FiscalConfig cfg;
    private final TimeSource time;
    private final RiskService risk;
    private final RelationService relations;

    public LoanService(Store store, Database db, FiscalConfig cfg, TimeSource time,
                       RiskService risk, RelationService relations) {
        this.store = store;
        this.db = db;
        this.cfg = cfg;
        this.time = time;
        this.risk = risk;
        this.relations = relations;
    }

    public Outcome apply(String uuid, int termDays, long amount) {
        long now = time.now();
        return db.tx(con -> {
            if (!cfg.loanEnabled) return Outcome.fail("贷款功能已关闭");
            Account account = store.getAccount(con, uuid);
            if (account == null || !account.active()) return Outcome.fail("请先开户");
            if (amount <= 0) return Outcome.fail("贷款金额必须大于 0");
            PlayerData player = store.getPlayer(con, uuid);
            if (player == null) return Outcome.fail("玩家数据不存在");
            long ageHours = (now - player.firstJoin()) / 3_600_000L;
            if (ageHours < cfg.loanMinAgeHours) {
                return Outcome.fail("账号年龄需满 " + cfg.loanMinAgeHours + " 小时");
            }
            if (store.activeLoan(con, uuid) != null) return Outcome.fail("欠债未清，不能再次贷款");
            int credit = store.creditScore(con, uuid);
            if (credit < cfg.loanMinCreditScore) {
                return Outcome.fail("信用分不足：当前 " + credit + "，要求 " + cfg.loanMinCreditScore);
            }
            if (relations.clusterHasOverdueLoan(con, uuid)) {
                return Outcome.fail("关联账号存在逾期贷款，授信受限");
            }
            Integer rate = cfg.loanTerms.get(termDays);
            if (rate == null) return Outcome.fail("不支持的期限，仅支持 " + cfg.loanTerms.keySet());

            long avgBank = averageBank(con, uuid);
            long max = Math.min(cfg.loanSingleMax, avgBank * Math.max(1, cfg.loanMultiplier));
            if (amount > max) {
                return Outcome.fail("超出授信额度，当前最高可贷 " + Money.format(max, cfg.currencySymbol));
            }
            long pool = poolAvailable(con, now);
            if (amount > pool) {
                return Outcome.fail("可贷资金池不足，当前可放 " + Money.format(Math.max(0, pool), cfg.currencySymbol));
            }
            long disbursed = store.disbursedToday(con, time.dayKey(), dayStart(now), dayEnd(now));
            if (disbursed + amount > cfg.loanDailyDisbursement) {
                return Outcome.fail("今日放款额度不足");
            }
            long due = now + termDays * 86_400_000L;
            String loanId = Store.newId();
            Loan loan = new Loan(loanId, uuid, amount, rate, termDays, now, due, amount, "ACTIVE",
                    now, 0, 0, null, 0, 0);
            store.insertLoan(con, loan);
            long before = account.balance();
            long after = store.creditAccount(con, uuid, amount);
            store.insertTx(con, new Tx(loanId, now, "LOAN_DISBURSE", Store.ISSUANCE_ID, uuid,
                    amount, 0, null, null, before, after, "贷款 " + termDays + " 天"));
            store.audit(con, uuid, "LOAN_APPLY", loanId + " " + amount + " rate=" + rate, now);
            return Outcome.ok("贷款已放款 " + Money.format(amount, cfg.currencySymbol)
                    + "，期限 " + termDays + " 天，T+1 内禁止取款/转出");
        });
    }

    public Eligibility eligibility(String uuid, int termDays) {
        return db.read(con -> {
            Account account = store.getAccount(con, uuid);
            if (account == null) return new Eligibility(0, 0, 0, 0, "请先开户");
            PlayerData player = store.getPlayer(con, uuid);
            long now = time.now();
            if (player != null && (now - player.firstJoin()) / 3_600_000L < cfg.loanMinAgeHours) {
                return new Eligibility(0, 0, 0, store.creditScore(con, uuid), "账号年龄不足");
            }
            if (store.activeLoan(con, uuid) != null) {
                return new Eligibility(0, 0, 0, store.creditScore(con, uuid), "已有未结清贷款");
            }
            int credit = store.creditScore(con, uuid);
            if (credit < cfg.loanMinCreditScore) {
                return new Eligibility(0, 0, 0, credit, "信用分不足");
            }
            if (relations.clusterHasOverdueLoan(con, uuid)) {
                return new Eligibility(0, 0, 0, credit, "关联账号逾期");
            }
            long avg = averageBank(con, uuid);
            long max = Math.min(cfg.loanSingleMax, avg * Math.max(1, cfg.loanMultiplier));
            long pool = poolAvailable(con, now);
            return new Eligibility(avg, max, pool, credit, "OK");
        });
    }

    private long averageBank(Connection con, String uuid) throws SQLException {
        LocalDate from = time.today().minusDays(Math.max(1, cfg.loanAvgWindowDays) - 1);
        List<Store.BalanceSnapshot> snaps = store.balancesSince(con, uuid, from.toString());
        if (snaps.isEmpty()) {
            Account account = store.getAccount(con, uuid);
            return account == null ? 0 : account.balance();
        }
        long sum = 0;
        for (Store.BalanceSnapshot snap : snaps) sum += snap.bank();
        return sum / snaps.size();
    }

    public long poolAvailable(Connection con, long now) throws SQLException {
        long deposits = store.totalBankDeposits(con) + store.totalSharedDeposits(con);
        long reserve = (long) (deposits * Math.max(0, Math.min(1, cfg.loanReserveRatio)));
        long poolLimit = Math.min(cfg.loanPoolMax, Math.max(0, deposits - reserve));
        long outstanding = store.totalOutstanding(con);
        long disbursed = store.disbursedToday(con, time.dayKey(), dayStart(now), dayEnd(now));
        long poolRemaining = Math.max(0, poolLimit - outstanding);
        long dailyRemaining = Math.max(0, cfg.loanDailyDisbursement - disbursed);
        return Math.min(poolRemaining, dailyRemaining);
    }

    public Outcome repay(String uuid, long requested) {
        long now = time.now();
        return db.tx(con -> {
            Loan loan = store.activeLoan(con, uuid);
            if (loan == null) return Outcome.fail("没有未结清贷款");
            if (requested <= 0) return Outcome.fail("还款金额必须大于 0");
            long amount = Math.min(requested, loan.outstanding());
            Long remaining = store.debitAccount(con, uuid, amount);
            if (remaining == null) return Outcome.fail("银行余额不足");
            long newOutstanding = loan.outstanding() - amount;
            boolean paidOff = newOutstanding <= 0;
            Loan updated = new Loan(loan.id(), loan.uuid(), loan.principal(), loan.ratePpm(), loan.termDays(),
                    loan.startedAt(), loan.dueAt(), newOutstanding, paidOff ? "PAID" : loan.status(),
                    loan.disbursedAt(), loan.relatedOut(), loan.extensions(), loan.lastAccrualDay(),
                    loan.writtenOff(), paidOff ? now : 0);
            store.updateLoan(con, updated);
            store.insertTx(con, new Tx(Store.newId(), now, "LOAN_REPAY", uuid, Store.ISSUANCE_ID,
                    amount, 0, remaining + amount, remaining, null, null, "贷款还款"));
            if (paidOff) {
                if ("ACTIVE".equals(loan.status())) {
                    store.adjustCredit(con, uuid, cfg.creditOnTime, now);
                }
                store.deactivateFlags(con, uuid, "OVERDUE");
                store.audit(con, uuid, "LOAN_PAID", loan.id(), now);
            }
            store.audit(con, uuid, "LOAN_REPAY", loan.id() + " " + amount, now);
            return Outcome.ok("还款 " + Money.format(amount, cfg.currencySymbol)
                    + (paidOff ? "，贷款已结清，信用分 +" + cfg.creditOnTime : ""));
        });
    }

    public Outcome extend(String uuid) {
        long now = time.now();
        return db.tx(con -> {
            Loan loan = store.activeLoan(con, uuid);
            if (loan == null) return Outcome.fail("没有未结清贷款");
            if (!"ACTIVE".equals(loan.status())) return Outcome.fail("已逾期，不能展期");
            if (loan.extensions() > 0) return Outcome.fail("只允许展期一次");
            if (now >= loan.dueAt()) return Outcome.fail("已到期，不能展期");
            Loan updated = new Loan(loan.id(), loan.uuid(), loan.principal(), loan.ratePpm() + cfg.loanExtensionRateBumpPpm,
                    loan.termDays(), loan.startedAt(), loan.dueAt() + cfg.loanExtensionDays * 86_400_000L,
                    loan.outstanding(), loan.status(), loan.disbursedAt(), loan.relatedOut(), 1,
                    loan.lastAccrualDay(), loan.writtenOff(), 0);
            store.updateLoan(con, updated);
            store.audit(con, uuid, "LOAN_EXTEND", loan.id(), now);
            return Outcome.ok("已展期 " + cfg.loanExtensionDays + " 天，日利率增加 "
                    + String.format("%.3f", cfg.loanExtensionRateBumpPpm / 10_000.0) + "%");
        });
    }

    /** 每日计息、逾期标记、提醒、核销。 */
    public AccrualResult accrueDaily() {
        long now = time.now();
        String day = time.dayKey();
        return db.tx(con -> {
            List<String> notices = new ArrayList<>();
            String writeOffDay = store.meta(con, "writeoff_day");
            long writeOffTotal = 0;
            if (day.equals(writeOffDay)) {
                String raw = store.meta(con, "writeoff_total");
                if (raw != null) {
                    try {
                        writeOffTotal = Long.parseLong(raw);
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else {
                store.setMeta(con, "writeoff_day", day);
                store.setMeta(con, "writeoff_total", "0");
            }
            for (Loan loan : store.openLoans(con)) {
                if (day.equals(loan.lastAccrualDay())) continue;
                String status = loan.status();
                boolean becameOverdue = false;
                if ("ACTIVE".equals(status) && now >= loan.dueAt()) {
                    status = "OVERDUE";
                    becameOverdue = true;
                    store.adjustCredit(con, loan.uuid(), cfg.creditOverdue, now);
                    risk.flag(con, loan.uuid(), "OVERDUE", "贷款逾期 " + loan.id(), cfg.riskFlagExpireDays);
                    store.audit(con, loan.uuid(), "LOAN_OVERDUE", loan.id(), now);
                }
                long rate = loan.ratePpm() + ("OVERDUE".equals(status) ? cfg.loanPenaltyPpm : 0);
                long interest = Money.permille(loan.outstanding(), rate);
                long outstanding = loan.outstanding() + interest;
                long due = loan.dueAt();
                if ("ACTIVE".equals(status) && due - now <= cfg.loanReminderDays * 86_400_000L) {
                    notices.add(loan.uuid() + " 贷款将于 " + time.dayKey(LocalDate.ofInstant(
                            java.time.Instant.ofEpochMilli(due), time.zone())) + " 到期");
                }
                if ("OVERDUE".equals(status) && now - due >= cfg.loanWriteOffAfterDays * 86_400_000L) {
                    long cap = Math.max(0, cfg.loanDailyWriteOffCap - writeOffTotal);
                    long treasury = store.treasury(con);
                    long absorb = Math.min(Math.min(outstanding, cap), treasury);
                    if (absorb > 0) {
                        store.debitTreasury(con, absorb, now);
                        outstanding -= absorb;
                        store.adjustCredit(con, loan.uuid(), cfg.creditDefault, now);
                        risk.flag(con, loan.uuid(), "OVERDUE_30", "逾期 30 天核销 " + absorb, 30);
                        store.insertTx(con, new Tx(Store.newId(), now, "LOAN_WRITE_OFF",
                                Store.TREASURY_ID, loan.uuid(), absorb, 0, null, null, null, null,
                                "坏账核销 " + loan.id()));
                        store.audit(con, loan.uuid(), "LOAN_WRITE_OFF", loan.id() + " " + absorb, now);
                        notices.add(loan.uuid() + " 贷款已核销 " + Money.format(absorb, cfg.currencySymbol));
                        writeOffTotal += absorb;
                        store.setMeta(con, "writeoff_total", Long.toString(writeOffTotal));
                    }
                    Loan updated = new Loan(loan.id(), loan.uuid(), loan.principal(), loan.ratePpm(),
                            loan.termDays(), loan.startedAt(), loan.dueAt(), outstanding,
                            outstanding <= 0 ? "WRITTEN_OFF" : "OVERDUE", loan.disbursedAt(),
                            loan.relatedOut(), loan.extensions(), day, loan.writtenOff() + absorb,
                            outstanding <= 0 ? now : 0);
                    store.updateLoan(con, updated);
                    if (becameOverdue) {
                        // 已经写入 updated，无需额外操作
                    }
                    continue;
                }
                Loan updated = new Loan(loan.id(), loan.uuid(), loan.principal(), loan.ratePpm(),
                        loan.termDays(), loan.startedAt(), loan.dueAt(), outstanding, status,
                        loan.disbursedAt(), loan.relatedOut(), loan.extensions(), day,
                        loan.writtenOff(), loan.closedAt());
                store.updateLoan(con, updated);
                if (becameOverdue) notices.add(loan.uuid() + " 贷款已逾期，日罚息 "
                        + String.format("%.3f", cfg.loanPenaltyPpm / 10_000.0) + "%");
            }
            return new AccrualResult(notices, 0);
        });
    }

    public boolean inTPlusOne(Connection con, String uuid) throws SQLException {
        Loan loan = store.activeLoan(con, uuid);
        if (loan == null) return false;
        return time.now() < loan.disbursedAt() + 86_400_000L;
    }

    public Loan activeLoan(Connection con, String uuid) throws SQLException {
        return store.activeLoan(con, uuid);
    }

    /** 贷后 20% 监控：收款人若在借款人关联簇内，累计转出不得超过本金的 20%。 */
    public Check checkRelatedOutflow(Connection con, String payer, String recipient, long amount, long now) throws SQLException {
        Loan loan = store.activeLoan(con, payer);
        if (loan == null) return new Check(true, null);
        if (!relations.related(con, payer, recipient)) return new Check(true, null);
        long limit = loan.principal() * 20 / 100;
        if (loan.relatedOut() + amount > limit) {
            risk.logSuspicious(con, payer, "LOAN_CONCENTRATION", amount,
                    "关联转出 " + loan.relatedOut() + "+" + amount + " 超过贷款本金 20%");
            risk.flag(con, payer, "LOAN_VIOLATION", "贷后关联集中转出", cfg.riskFlagExpireDays);
            return new Check(false, "贷后监控：向关联账号累计转出不得超过贷款本金的 20%");
        }
        return new Check(true, null);
    }

    public void recordRelatedOutflow(Connection con, String payer, long amount) throws SQLException {
        Loan loan = store.activeLoan(con, payer);
        if (loan == null) return;
        Loan updated = new Loan(loan.id(), loan.uuid(), loan.principal(), loan.ratePpm(), loan.termDays(),
                loan.startedAt(), loan.dueAt(), loan.outstanding(), loan.status(), loan.disbursedAt(),
                loan.relatedOut() + amount, loan.extensions(), loan.lastAccrualDay(),
                loan.writtenOff(), loan.closedAt());
        store.updateLoan(con, updated);
    }

    public record Check(boolean allowed, String reason) {
    }

    private long dayStart(long now) {
        return time.today().atStartOfDay(time.zone()).toInstant().toEpochMilli();
    }

    private long dayEnd(long now) {
        return time.today().plusDays(1).atStartOfDay(time.zone()).toInstant().toEpochMilli();
    }
}
