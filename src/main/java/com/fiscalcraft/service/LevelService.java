package com.fiscalcraft.service;

import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.model.Models.Account;
import com.fiscalcraft.model.Models.LevelSpec;
import com.fiscalcraft.model.Models.PlayerData;
import com.fiscalcraft.model.Models.SharedMember;
import com.fiscalcraft.model.Models.Tx;
import com.fiscalcraft.util.TimeSource;
import com.fiscalcraft.wallet.WalletGateway;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LevelService {
    public record Change(String uuid, int oldLevel, int newLevel, String reason) {
    }

    public record Progress(long avgAssets, long flow, int compliance, int qualifiedLevel) {
    }

    private final FiscalConfig cfg;
    private final Store store;
    private final TimeSource time;
    private final WalletGateway wallet;
    private final RelationService relations;
    private final RiskService risk;

    public LevelService(FiscalConfig cfg, Store store, TimeSource time, WalletGateway wallet,
                        RelationService relations, RiskService risk) {
        this.cfg = cfg;
        this.store = store;
        this.time = time;
        this.wallet = wallet;
        this.relations = relations;
        this.risk = risk;
    }

    public LevelSpec spec(int level) {
        return cfg.level(level);
    }

    public int level(Connection con, String uuid) throws SQLException {
        PlayerData player = store.getPlayer(con, uuid);
        return player == null ? 0 : player.level();
    }

    public LevelSpec specFor(Connection con, String uuid) throws SQLException {
        return spec(level(con, uuid));
    }

    public int historyLimit(Connection con, String uuid) throws SQLException {
        return specFor(con, uuid).historyLimit();
    }

    public int sessionMinutes(Connection con, String uuid) throws SQLException {
        return specFor(con, uuid).sessionMinutes();
    }

    public long terminalSingleLimit(Connection con, String uuid) throws SQLException {
        return specFor(con, uuid).terminalSingleLimit();
    }

    public int feePpm(Connection con, String uuid) throws SQLException {
        return specFor(con, uuid).feePpm();
    }

    public int interestBonusPpm(Connection con, String uuid) throws SQLException {
        return specFor(con, uuid).interestBonusPpm();
    }

    public Progress progress(Connection con, String uuid) throws SQLException {
        PlayerData player = store.getPlayer(con, uuid);
        if (player == null) return new Progress(0, 0, 100, 0);
        long avg = averageAssets(con, uuid);
        long flow = validFlow(con, uuid);
        int compliance = player.compliance();
        int qualified = 0;
        for (LevelSpec spec : cfg.levels) {
            if (avg >= spec.minAvgAssets() && flow >= spec.minFlow() && compliance >= spec.minCompliance()) {
                qualified = Math.max(qualified, spec.index());
            }
        }
        return new Progress(avg, flow, compliance, qualified);
    }

    public long averageAssets(Connection con, String uuid) throws SQLException {
        LocalDate from = time.today().minusDays(Math.max(1, cfg.levelAssetWindowDays) - 1);
        List<Store.BalanceSnapshot> snaps = store.balancesSince(con, uuid, from.toString());
        if (snaps.isEmpty()) {
            Account account = store.getAccount(con, uuid);
            long bank = account == null ? 0 : account.balance();
            long shared = store.sharedShare(con, uuid);
            long loan = store.outstandingFor(con, uuid);
            return Math.max(0, bank + wallet.balance(uuid) + shared - loan);
        }
        long sum = 0;
        for (Store.BalanceSnapshot snap : snaps) {
            sum += Math.max(0, snap.bank() + snap.wallet() + snap.shared() - snap.loan());
        }
        return sum / snaps.size();
    }

    public long validFlow(Connection con, String uuid) throws SQLException {
        LocalDate from = time.today().minusDays(Math.max(1, cfg.levelFlowWindowDays) - 1);
        long cutoff = from.atStartOfDay(time.zone()).toInstant().toEpochMilli();
        Set<String> related = relations.cluster(con, uuid);
        long sum = 0;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT from_uuid,to_uuid,amount FROM transactions WHERE ts>=? AND (from_uuid=? OR to_uuid=?) " +
                        "AND type IN ('TRANSFER','REMOTE')")) {
            ps.setLong(1, cutoff);
            ps.setString(2, uuid);
            ps.setString(3, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sender = rs.getString(1);
                    String to = rs.getString(2);
                    String counterparty = uuid.equals(sender) ? to : sender;
                    if (counterparty == null || related.contains(counterparty)) continue;
                    sum += rs.getLong(3);
                }
            }
        }
        return sum;
    }

    public List<Change> evaluateAll(Connection con, long now) throws SQLException {
        List<Change> changes = new ArrayList<>();
        for (Account account : store.allAccounts(con)) {
            Change change = evaluate(con, account.uuid(), now);
            if (change != null) changes.add(change);
        }
        return changes;
    }

    /** 每月评估；资产/流水不足给 30 天缓冲，合规不足立即降一级。 */
    public Change evaluate(Connection con, String uuid, long now) throws SQLException {
        PlayerData player = store.getPlayer(con, uuid);
        if (player == null) return null;
        int current = player.level();
        Progress progress = progress(con, uuid);
        int qualified = progress.qualifiedLevel();
        if (qualified >= current) {
            if (qualified > current) {
                store.updatePlayerLevel(con, uuid, qualified, 0, now);
                store.audit(con, "system", "LEVEL_UP", uuid + " " + current + "->" + qualified, now);
                return new Change(uuid, current, qualified, "满足升级条件");
            }
            if (player.graceUntil() > 0) store.setGrace(con, uuid, 0);
            return null;
        }
        LevelSpec currentSpec = spec(current);
        if (progress.compliance() < currentSpec.minCompliance()) {
            int next = Math.max(0, current - 1);
            store.updatePlayerLevel(con, uuid, next, 0, now);
            store.audit(con, "system", "LEVEL_DOWN_COMPLIANCE", uuid + " " + current + "->" + next, now);
            return new Change(uuid, current, next, "合规分跌破当前等级要求");
        }
        boolean assetShort = progress.avgAssets() < currentSpec.minAvgAssets();
        boolean flowShort = progress.flow() < currentSpec.minFlow();
        if (!assetShort && !flowShort) return null;
        if (player.graceUntil() <= 0) {
            long grace = now + cfg.levelGraceDays * 86_400_000L;
            store.setGrace(con, uuid, grace);
            store.audit(con, "system", "LEVEL_GRACE", uuid + " until " + grace, now);
            return new Change(uuid, current, current, "进入 " + cfg.levelGraceDays + " 天缓冲期");
        }
        if (now > player.graceUntil()) {
            int next = Math.max(0, current - 1);
            store.updatePlayerLevel(con, uuid, next, 0, now);
            store.audit(con, "system", "LEVEL_DOWN", uuid + " " + current + "->" + next, now);
            return new Change(uuid, current, next, "缓冲期结束仍未达标，降一级");
        }
        return null;
    }

    /** 每日合规分更新；跌破当前等级阈值时临时降一级。 */
    public List<Change> updateComplianceDaily(Connection con, long now) throws SQLException {
        List<Change> changes = new ArrayList<>();
        for (PlayerData player : store.allPlayers(con)) {
            int target = 100;
            var flags = store.activeFlags(con, player.uuid(), now);
            for (String flag : flags) {
                switch (flag) {
                    case "DEFAULT", "BLACKLIST", "OVERDUE_30", "LOAN_VIOLATION" -> target -= 25;
                    case "FAST_IN_OUT", "STRUCTURING", "SUSPICIOUS_LARGE" -> target -= 8;
                    default -> target -= 3;
                }
            }
            if (store.owedTax(con, player.uuid()) > 0) target -= 10;
            target = Math.max(0, Math.min(100, target));
            int old = player.compliance();
            if (target != old) store.setCompliance(con, player.uuid(), target);
            if (target < spec(player.level()).minCompliance()) {
                int next = Math.max(0, player.level() - 1);
                if (next != player.level()) {
                    store.updatePlayerLevel(con, player.uuid(), next, 0, now);
                    changes.add(new Change(player.uuid(), player.level(), next, "合规分临时降级"));
                }
            }
        }
        return changes;
    }

    public Set<String> relatedSet(Connection con, String uuid) throws SQLException {
        return new HashSet<>(relations.cluster(con, uuid));
    }

    /** 校验卡片工本费：基础办卡费之外，按当前等级补收。 */
    public long cardIssueFee(int level) {
        return cfg.cardFee + spec(level).cardFee();
    }

    public List<Tx> history(Connection con, String uuid, int limit) throws SQLException {
        return store.recentTransactions(con, uuid, limit);
    }

    public String levelLabel(int level) {
        return spec(level).label();
    }
}
