package com.fiscalcraft.service;

import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.model.Models.RiskSummary;
import com.fiscalcraft.util.TimeSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public final class RiskService {
    public static final String LEVEL_LOW = "LOW";
    public static final String LEVEL_MEDIUM = "MEDIUM";
    public static final String LEVEL_HIGH = "HIGH";
    public static final String LEVEL_BLACKLIST = "BLACKLIST";

    private final FiscalConfig cfg;
    private final Store store;
    private final TimeSource time;

    public RiskService(FiscalConfig cfg, Store store, TimeSource time) {
        this.cfg = cfg;
        this.store = store;
        this.time = time;
    }

    public RiskSummary summary(Connection con, String uuid) throws SQLException {
        List<String> flags = store.activeFlags(con, uuid, time.now());
        String level = levelsFor(flags);
        store.setRiskLevel(con, uuid, level);
        return new RiskSummary(level, flags, flags.size());
    }

    public String level(Connection con, String uuid) throws SQLException {
        return levelsFor(store.activeFlags(con, uuid, time.now()));
    }

    private String levelsFor(List<String> flags) {
        if (flags.isEmpty()) return LEVEL_LOW;
        for (String flag : flags) {
            if ("DEFAULT".equals(flag) || "BLACKLIST".equals(flag) || "OVERDUE_30".equals(flag)
                    || "LOAN_VIOLATION".equals(flag)) {
                return LEVEL_BLACKLIST;
            }
        }
        if (flags.size() >= cfg.highRiskFlagCount) return LEVEL_HIGH;
        return LEVEL_MEDIUM;
    }

    public boolean canRemoteTransfer(String level) {
        return LEVEL_LOW.equals(level) || LEVEL_MEDIUM.equals(level);
    }

    public boolean canTransfer(String level) {
        return !LEVEL_BLACKLIST.equals(level);
    }

    public boolean needsConfirm(String level, long amount) {
        return LEVEL_MEDIUM.equals(level) && amount >= cfg.mediumConfirmThreshold;
    }

    public void flag(Connection con, String uuid, String type, String reason, int days) throws SQLException {
        long now = time.now();
        store.addFlag(con, uuid, type, reason, now, now + Math.max(1, days) * 86_400_000L);
    }

    public void logSuspicious(Connection con, String uuid, String type, long amount, String detail) throws SQLException {
        store.suspicious(con, uuid, type, amount, detail, time.now());
    }

    public void inspectLarge(Connection con, String uuid, long amount, long limit) throws SQLException {
        if (limit <= 0) return;
        long threshold = limit * cfg.largeAlertRatioPpm / 1_000_000L;
        if (amount >= threshold) {
            logSuspicious(con, uuid, "LARGE", amount, "单笔达到限额 " + cfg.largeAlertRatioPpm / 10_000 + "%");
            flag(con, uuid, "SUSPICIOUS_LARGE", "大额交易 " + amount, cfg.riskFlagExpireDays);
        }
    }

    public void inspectFastOut(Connection con, String uuid, long amount) throws SQLException {
        long since = time.now() - 30 * 60_000L;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE to_uuid=? AND ts>=? " +
                        "AND type IN ('TRANSFER','REMOTE')")) {
            ps.setString(1, uuid);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                long incoming = rs.next() ? rs.getLong(1) : 0L;
                if (incoming > 0 && amount >= incoming * 8 / 10) {
                    logSuspicious(con, uuid, "FAST_IN_OUT", amount, "30 分钟内快进快出，流入累计 " + incoming);
                    flag(con, uuid, "FAST_IN_OUT", "快进快出", cfg.riskFlagExpireDays);
                }
            }
        }
    }

    public void inspectStructuring(Connection con, String uuid, long amount, long limit, int countSameWindow) throws SQLException {
        long since = time.now() - 10 * 60_000L;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*),COALESCE(SUM(amount),0) FROM transactions WHERE from_uuid=? AND ts>=? " +
                        "AND type IN ('TRANSFER','REMOTE')")) {
            ps.setString(1, uuid);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                long count = rs.getLong(1);
                long sum = rs.getLong(2) + amount;
                if (count + 1 >= countSameWindow && sum > limit) {
                    logSuspicious(con, uuid, "STRUCTURING", amount, "10 分钟内多笔接近限额，合计 " + sum);
                    flag(con, uuid, "STRUCTURING", "疑似拆分绕过限额", cfg.riskFlagExpireDays);
                }
            }
        }
    }
}
