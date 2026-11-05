package com.fiscalcraft.service;

import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.db.Database;
import com.fiscalcraft.model.Models.Account;
import com.fiscalcraft.model.Models.Loan;
import com.fiscalcraft.model.Models.PlayerData;
import com.fiscalcraft.model.Models.SharedAccount;
import com.fiscalcraft.model.Models.SharedMember;
import com.fiscalcraft.model.Models.TaxRecord;
import com.fiscalcraft.model.Models.Tx;
import com.fiscalcraft.util.Money;
import com.fiscalcraft.util.TimeSource;
import com.fiscalcraft.wallet.WalletGateway;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class TaxService {
    public record Settlement(boolean skipped, long interest, long wealthTax, long incomeTax,
                             List<String> notices) {
    }

    private final Store store;
    private final Database db;
    private final FiscalConfig cfg;
    private final TimeSource time;
    private final WalletGateway wallet;
    private final RelationService relations;
    private final LevelService levels;
    private final Logger logger;

    public TaxService(Store store, Database db, FiscalConfig cfg, TimeSource time, WalletGateway wallet,
                      RelationService relations, LevelService levels, Logger logger) {
        this.store = store;
        this.db = db;
        this.cfg = cfg;
        this.time = time;
        this.wallet = wallet;
        this.relations = relations;
        this.levels = levels;
        this.logger = logger;
    }

    /** 每日 00:00：计息 → 财富税快照/征收 → 个税（前一日）→ 资产快照。 */
    public Settlement settleDaily() {
        LocalDate today = time.today();
        String day = today.toString();
        String previous = today.minusDays(1).toString();
        return db.tx(con -> {
            String last = store.meta(con, "last_daily_day");
            if (day.equals(last)) return new Settlement(true, 0, 0, 0, List.of());
            long now = time.now();
            List<String> notices = new ArrayList<>();
            long interest = payDepositInterest(con, now);
            long wealth = collectWealthTax(con, now, day);
            long income = collectIncomeTax(con, now, previous);
            snapshotAll(con, day);
            store.setMeta(con, "last_daily_day", day);
            store.audit(con, "system", "DAILY_SETTLE", "interest=" + interest + " wealth=" + wealth + " income=" + income, now);
            notices.add("每日结算完成：存款利息 " + Money.format(interest, cfg.currencySymbol)
                    + "，财富税 " + Money.format(wealth, cfg.currencySymbol)
                    + "，个税 " + Money.format(income, cfg.currencySymbol));
            return new Settlement(false, interest, wealth, income, notices);
        });
    }

    public long payDepositInterest(Connection con, long now) throws SQLException {
        long total = 0;
        for (Account account : store.allAccounts(con)) {
            if (account.balance() <= 0) continue;
            int bonus = levels.interestBonusPpm(con, account.uuid());
            long rate = cfg.depositInterestPpm + Math.max(0, bonus);
            long amount = Money.permille(account.balance(), rate);
            if (amount <= 0) continue;
            fundInterest(con, account.uuid(), null, amount, now, "个人存款利息");
            store.creditAccount(con, account.uuid(), amount);
            total += amount;
        }
        for (SharedAccount shared : store.allShared(con)) {
            if (shared.balance() <= 0) continue;
            long amount = Money.permille(shared.balance(), cfg.depositInterestPpm);
            if (amount <= 0) continue;
            fundInterest(con, null, shared.id(), amount, now, "共享账户利息");
            store.updateSharedBalance(con, shared.id(), amount);
            total += amount;
        }
        return total;
    }

    /** 利息先由财政池支付，不足部分记系统发行；两者都写流水。 */
    private void fundInterest(Connection con, String toPlayer, String toShared, long amount, long now, String reason) throws SQLException {
        long fromTreasury = 0;
        if (cfg.interestFromTreasuryFirst) {
            long available = store.treasury(con);
            fromTreasury = Math.min(available, amount);
            if (fromTreasury > 0 && !store.debitTreasury(con, fromTreasury, now)) fromTreasury = 0;
        }
        long issuance = amount - fromTreasury;
        if (fromTreasury > 0) {
            store.insertTx(con, new Tx(Store.newId(), now, "INTEREST", Store.TREASURY_ID,
                    toPlayer != null ? toPlayer : toShared, fromTreasury, 0, null, null, null, null, reason + "（财政池）"));
        }
        if (issuance > 0) {
            store.insertTx(con, new Tx(Store.newId(), now, "INTEREST_ISSUANCE", Store.ISSUANCE_ID,
                    toPlayer != null ? toPlayer : toShared, issuance, 0, null, null, null, null, reason + "（系统发行）"));
        }
    }

    /** 关联簇只计一次；共享余额按成员均摊份额计入，簇内成员份数之和不会重复。 */
    public long collectWealthTax(Connection con, long now, String day) throws SQLException {
        if (!cfg.wealthTaxEnabled) return 0;
        List<PlayerData> players = store.allPlayers(con);
        Map<String, Set<String>> clusters = new HashMap<>();
        Map<String, String> owner = new HashMap<>();
        for (PlayerData player : players) {
            if (clusters.containsKey(player.uuid())) continue;
            Set<String> cluster = new HashSet<>(relations.cluster(con, player.uuid()));
            clusters.put(player.uuid(), cluster);
            for (String member : cluster) owner.putIfAbsent(member, player.uuid());
        }

        Map<String, Long> bank = new HashMap<>();
        Map<String, Long> loan = new HashMap<>();
        Map<String, Long> sharedShare = new HashMap<>();
        Map<String, Long> walletCache = new HashMap<>();
        Map<String, Long> gross = new LinkedHashMap<>();
        for (PlayerData player : players) {
            long walletCents;
            try {
                walletCents = wallet.balance(player.uuid());
            } catch (RuntimeException e) {
                walletCents = 0;
                logger.warning("读取钱包失败 " + player.uuid() + ": " + e.getMessage());
            }
            bank.put(player.uuid(), store.balance(con, player.uuid()));
            loan.put(player.uuid(), store.outstandingFor(con, player.uuid()));
            walletCache.put(player.uuid(), walletCents);
            gross.put(player.uuid(), walletCents + bank.get(player.uuid()) - loan.get(player.uuid()));
        }
        for (SharedAccount shared : store.allShared(con)) {
            int count = store.memberCount(con, shared.id());
            if (count <= 0) continue;
            long share = shared.balance() / count;
            for (SharedMember member : store.members(con, shared.id())) {
                sharedShare.merge(member.uuid(), share, Long::sum);
            }
        }
        for (Map.Entry<String, Long> entry : sharedShare.entrySet()) {
            gross.merge(entry.getKey(), entry.getValue(), Long::sum);
        }

        long collected = 0;
        Set<String> processedClusters = new HashSet<>();
        for (PlayerData player : players) {
            Set<String> cluster = clusters.get(player.uuid());
            if (cluster == null) continue;
            String key = clusterKey(cluster);
            if (!processedClusters.add(key)) continue;
            long clusterBase = 0;
            for (String member : cluster) {
                clusterBase += gross.getOrDefault(member, 0L);
            }
            long tax = cfg.wealthTaxFor(clusterBase);
            if (tax <= 0) continue;
            long weight = 0;
            for (String member : cluster) {
                weight += Math.max(0L, gross.getOrDefault(member, 0L));
            }
            if (weight <= 0) continue;
            long allocated = 0;
            List<String> members = new ArrayList<>(cluster);
            members.sort(String::compareTo);
            for (int i = 0; i < members.size(); i++) {
                String member = members.get(i);
                long share = i == members.size() - 1
                        ? tax - allocated
                        : tax * Math.max(0L, gross.getOrDefault(member, 0L)) / weight;
                share = Math.max(0, share);
                allocated += share;
                collected += collectTax(con, member, share, "WEALTH", clusterBase, day, now,
                        "家庭合并税基 " + clusterBase + "，簇 " + cluster.size() + " 人");
            }
        }
        return collected;
    }

    private String clusterKey(Set<String> cluster) {
        List<String> list = new ArrayList<>(cluster);
        list.sort(String::compareTo);
        return String.join(",", list);
    }

    public long collectIncomeTax(Connection con, long now, String previousDay) throws SQLException {
        long total = 0;
        for (PlayerData player : store.allPlayers(con)) {
            Store.DailyStats stats = store.dailyStats(con, player.uuid(), previousDay);
            long net = Math.max(0, stats.received() - stats.paid());
            long tax = cfg.incomeTaxFor(net);
            if (tax <= 0) continue;
            total += collectTax(con, player.uuid(), tax, "INCOME", net, previousDay, now,
                    "当日净收入 " + net);
        }
        return total;
    }

    /** 扣税不足不扣成负数：银行余额不足部分记为欠税。 */
    public long collectTax(Connection con, String uuid, long amount, String type, long base,
                           String day, long now, String detail) throws SQLException {
        if (amount <= 0) return 0;
        Account account = store.getAccount(con, uuid);
        long already = 0;
        if (account != null && account.active()) {
            long available = Math.min(account.balance(), amount);
            if (available > 0) {
                Long remaining = store.debitAccount(con, uuid, available);
                if (remaining != null) {
                    already = available;
                    store.creditTreasury(con, available, now);
                    store.insertTx(con, new Tx(Store.newId(), now, "TAX", uuid, Store.TREASURY_ID,
                            available, 0, account.balance(), remaining, null, null, type + " " + detail));
                }
            }
        }
        String status = already >= amount ? "PAID" : (already > 0 ? "PARTIAL" : "OWED");
        if (already < amount) {
            store.insertTax(con, new TaxRecord(Store.newId(), now, uuid, type, base, amount, already,
                    status, day, detail));
        } else {
            store.insertTax(con, new TaxRecord(Store.newId(), now, uuid, type, base, amount, already,
                    "PAID", day, detail));
        }
        return already;
    }

    /** 收入优先补缴欠税；在转账/共享提现事务内调用。返回实际补缴额。 */
    public long takeArrearsFromIncome(Connection con, String uuid, long available, long now) throws SQLException {
        if (available <= 0) return 0;
        long paid = store.payArrearsFromIncome(con, uuid, available);
        if (paid > 0) {
            store.creditTreasury(con, paid, now);
            store.insertTx(con, new Tx(Store.newId(), now, "TAX_ARREARS", uuid, Store.TREASURY_ID,
                    paid, 0, null, null, null, null, "收入优先补缴欠税"));
        }
        return paid;
    }

    public boolean arrearsLocked(Connection con, String uuid, long now) throws SQLException {
        long owed = store.owedTax(con, uuid);
        if (owed <= 0) return false;
        long oldest = store.oldestOwedSince(con, uuid);
        return oldest > 0 && now - oldest >= cfg.arrearsLockDays * 86_400_000L;
    }

    public long owed(Connection con, String uuid) throws SQLException {
        return store.owedTax(con, uuid);
    }

    private void snapshotAll(Connection con, String day) throws SQLException {
        for (PlayerData player : store.allPlayers(con)) {
            Account account = store.getAccount(con, player.uuid());
            long bank = account == null ? 0 : account.balance();
            long walletCents;
            try {
                walletCents = wallet.balance(player.uuid());
            } catch (RuntimeException e) {
                walletCents = 0;
            }
            long shared = store.sharedShare(con, player.uuid());
            long loan = store.outstandingFor(con, player.uuid());
            store.snapshotBalance(con, player.uuid(), day, bank, walletCents, shared, loan);
        }
    }

    public List<TaxRecord> records(Connection con, String uuid, int limit) throws SQLException {
        return store.taxRecords(con, uuid, limit);
    }

    /** 报告用：财政池余额与欠税总额。 */
    public long treasury(Connection con) throws SQLException {
        return store.treasury(con);
    }

    public long totalArrears(Connection con) throws SQLException {
        return store.totalOwedTax(con);
    }

    public long loanOutstanding(Connection con) throws SQLException {
        return store.totalOutstanding(con);
    }

    public List<Loan> openLoans(Connection con) throws SQLException {
        return store.openLoans(con);
    }
}
