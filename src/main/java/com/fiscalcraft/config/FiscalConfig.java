package com.fiscalcraft.config;

import com.fiscalcraft.model.Models.Bracket;
import com.fiscalcraft.model.Models.LevelSpec;
import com.fiscalcraft.util.Money;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 所有可调参数集中在此，运行期只读。金额字段内部一律为 long 分币。 */
public final class FiscalConfig {
    public String currencySymbol = "$";
    public ZoneId zoneId = ZoneId.of("Asia/Shanghai");

    public String storageType = "sqlite";
    public String sqliteFile = "fiscalcraft.db";
    public String mysqlHost = "127.0.0.1";
    public int mysqlPort = 3306;
    public String mysqlDatabase = "fiscalcraft";
    public String mysqlUser = "fiscalcraft";
    public String mysqlPassword = "";
    public boolean mysqlUseSsl = false;

    public long openFee = 500_00L;
    public long cardFee = 800_00L;
    public long cardReissueFee = 200_00L;
    public long sharedCreateFee = 500_00L;
    public long passwordResetFee = 200_00L;

    public long depositInterestPpm = 250;          // 0.025%/日
    public long wealthTaxThreshold = 50_000_00L;
    public long wealthTaxPpm = 200;                // 0.02%/日
    public boolean wealthTaxEnabled = true;
    public long incomeTaxFree = 500_00L;
    public List<Bracket> incomeBrackets = new ArrayList<>();
    public int arrearsLockDays = 7;

    public int pinLength = 6;
    public int pinFailLimit = 5;
    public int pinLockMinutes = 10;
    public int ipFailLimit = 5;
    public int ipWindowMinutes = 10;
    public int promptTimeoutSeconds = 120;

    public long remoteSingleLimit = 2_000_00L;
    public long remoteDailyLimit = 5_000_00L;
    public long terminalDailyLimit = 20_000_00L;

    public int cardMaxPerPlayer = 5;
    public int sharedMaxMembers = 5;
    public int sharedMaxPerPlayer = 3;

    public boolean relationEnabled = true;
    public int relationIpWindowDays = 7;
    public int relationEdgeWindowDays = 7;
    public int relationEdgeMinCount = 2;

    public long mediumConfirmThreshold = 5_000_00L;
    public int highRiskFlagCount = 2;
    public long largeAlertRatioPpm = 800_000;       // 80%
    public int riskFlagExpireDays = 7;

    public boolean loanEnabled = true;
    public int loanMinAgeHours = 48;
    public int loanMinCreditScore = 650;
    public int loanAvgWindowDays = 14;
    public long loanSingleMax = 10_000_00L;
    public long loanMultiplier = 3;
    public double loanReserveRatio = 0.20;
    public long loanPoolMax = 100_000_00L;
    public long loanDailyDisbursement = 50_000_00L;
    public Map<Integer, Integer> loanTerms = new HashMap<>(); // 天数 -> 日利率 ppm
    public int loanExtensionDays = 7;
    public int loanExtensionRateBumpPpm = 100;
    public int loanPenaltyPpm = 1_000;              // 0.1%/日
    public int loanReminderDays = 3;
    public int loanWriteOffAfterDays = 30;
    public long loanDailyWriteOffCap = 5_000_00L;

    public int creditStart = 600;
    public int creditMax = 900;
    public int creditMin = 300;
    public int creditOnTime = 5;
    public int creditOverdue = -20;
    public int creditDefault = -50;

    public int levelFlowWindowDays = 30;
    public int levelAssetWindowDays = 30;
    public int levelGraceDays = 30;
    public int levelEvalDayOfMonth = 1;
    public List<LevelSpec> levels = new ArrayList<>();

    public int backupHour = 4;
    public int backupMinute = 0;
    public int backupKeep = 7;
    public boolean backupEnabled = true;

    public boolean treasuryEnabled = true;
    public boolean interestFromTreasuryFirst = true;

    private static long dollarsToCents(double dollars) {
        return BigDecimal.valueOf(dollars).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private static int percentToPpm(double percent) {
        return (int) Money.percentToPpm(percent);
    }

    public static FiscalConfig defaults() {
        FiscalConfig c = new FiscalConfig();
        c.incomeBrackets.add(new Bracket(Money.dollars(500), 0));
        c.incomeBrackets.add(new Bracket(Money.dollars(5_000), percentToPpm(0.5)));
        c.incomeBrackets.add(new Bracket(Money.dollars(20_000), percentToPpm(1.0)));
        c.incomeBrackets.add(new Bracket(-1, percentToPpm(1.5)));

        c.loanTerms.put(7, percentToPpm(0.05));
        c.loanTerms.put(30, percentToPpm(0.04));

        c.levels.add(new LevelSpec(0, "normal", "普通", 0, 0, 0,
                Money.dollars(0), Money.dollars(10_000), 10_000, 5, 20, 0));
        c.levels.add(new LevelSpec(1, "silver", "银卡", Money.dollars(10_000), Money.dollars(50_000), 80,
                Money.dollars(200), Money.dollars(12_000), 9_000, 6, 30, 0));
        c.levels.add(new LevelSpec(2, "gold", "金卡", Money.dollars(50_000), Money.dollars(200_000), 85,
                Money.dollars(500), Money.dollars(15_000), 8_000, 8, 50, 20));
        c.levels.add(new LevelSpec(3, "platinum", "白金", Money.dollars(200_000), Money.dollars(800_000), 90,
                Money.dollars(1_000), Money.dollars(18_000), 6_000, 10, 80, 50));
        c.levels.add(new LevelSpec(4, "black", "黑金", Money.dollars(500_000), Money.dollars(2_000_000), 95,
                Money.dollars(2_000), Money.dollars(20_000), 5_000, 15, 100, 80));
        return c;
    }

    public static FiscalConfig load(FileConfiguration cfg) {
        FiscalConfig c = defaults();
        c.currencySymbol = cfg.getString("currency-symbol", c.currencySymbol);
        String zone = cfg.getString("timezone", "Asia/Shanghai");
        try {
            c.zoneId = ZoneId.of(zone);
        } catch (Exception ignored) {
            c.zoneId = ZoneId.of("Asia/Shanghai");
        }

        c.storageType = cfg.getString("storage.type", c.storageType);
        c.sqliteFile = cfg.getString("storage.sqlite.file", c.sqliteFile);
        c.mysqlHost = cfg.getString("storage.mysql.host", c.mysqlHost);
        c.mysqlPort = cfg.getInt("storage.mysql.port", c.mysqlPort);
        c.mysqlDatabase = cfg.getString("storage.mysql.database", c.mysqlDatabase);
        c.mysqlUser = cfg.getString("storage.mysql.username", c.mysqlUser);
        c.mysqlPassword = cfg.getString("storage.mysql.password", c.mysqlPassword);
        c.mysqlUseSsl = cfg.getBoolean("storage.mysql.use-ssl", c.mysqlUseSsl);

        c.openFee = dollarsToCents(cfg.getDouble("fees.open-account", c.openFee / 100.0));
        c.cardFee = dollarsToCents(cfg.getDouble("fees.card-issue", c.cardFee / 100.0));
        c.cardReissueFee = dollarsToCents(cfg.getDouble("fees.card-reissue", c.cardReissueFee / 100.0));
        c.sharedCreateFee = dollarsToCents(cfg.getDouble("fees.shared-create", c.sharedCreateFee / 100.0));
        c.passwordResetFee = dollarsToCents(cfg.getDouble("fees.password-reset", c.passwordResetFee / 100.0));

        c.depositInterestPpm = percentToPpm(cfg.getDouble("interest.daily-percent", 0.025));
        c.wealthTaxThreshold = dollarsToCents(cfg.getDouble("wealth-tax.threshold", 50_000));
        c.wealthTaxPpm = percentToPpm(cfg.getDouble("wealth-tax.daily-percent", 0.02));
        c.wealthTaxEnabled = cfg.getBoolean("wealth-tax.enabled", true);
        c.incomeTaxFree = dollarsToCents(cfg.getDouble("income-tax.free", 500));

        List<Map<?, ?>> brackets = cfg.getMapList("income-tax.brackets");
        if (!brackets.isEmpty()) {
            c.incomeBrackets.clear();
            for (Map<?, ?> map : brackets) {
                Object up = map.get("up-to");
                Object rate = map.get("rate-percent");
                if (up == null || rate == null) continue;
                double upD = ((Number) up).doubleValue();
                long upCents = upD < 0 ? -1 : dollarsToCents(upD);
                c.incomeBrackets.add(new Bracket(upCents, percentToPpm(((Number) rate).doubleValue())));
            }
            if (c.incomeBrackets.isEmpty()) {
                c.incomeBrackets = defaults().incomeBrackets;
            }
        }
        c.arrearsLockDays = cfg.getInt("tax.arrears-lock-days", c.arrearsLockDays);

        c.pinLength = cfg.getInt("security.pin-length", c.pinLength);
        c.pinFailLimit = cfg.getInt("security.fail-limit", c.pinFailLimit);
        c.pinLockMinutes = cfg.getInt("security.lock-minutes", c.pinLockMinutes);
        c.ipFailLimit = cfg.getInt("security.ip-limit", c.ipFailLimit);
        c.ipWindowMinutes = cfg.getInt("security.ip-window-minutes", c.ipWindowMinutes);
        c.promptTimeoutSeconds = cfg.getInt("security.prompt-timeout-seconds", c.promptTimeoutSeconds);

        c.remoteSingleLimit = dollarsToCents(cfg.getDouble("transfer.remote-single-limit", 2_000));
        c.remoteDailyLimit = dollarsToCents(cfg.getDouble("transfer.remote-daily-limit", 5_000));
        c.terminalDailyLimit = dollarsToCents(cfg.getDouble("transfer.terminal-daily-limit", 20_000));

        c.cardMaxPerPlayer = cfg.getInt("cards.max-per-player", c.cardMaxPerPlayer);
        c.sharedMaxMembers = cfg.getInt("shared.max-members", c.sharedMaxMembers);
        c.sharedMaxPerPlayer = cfg.getInt("shared.max-per-player", c.sharedMaxPerPlayer);

        c.relationEnabled = cfg.getBoolean("relation.enabled", true);
        c.relationIpWindowDays = cfg.getInt("relation.ip-window-days", c.relationIpWindowDays);
        c.relationEdgeWindowDays = cfg.getInt("relation.edge-window-days", c.relationEdgeWindowDays);
        c.relationEdgeMinCount = cfg.getInt("relation.edge-min-count", c.relationEdgeMinCount);

        c.mediumConfirmThreshold = dollarsToCents(cfg.getDouble("risk.medium-confirm-threshold", 5_000));
        c.highRiskFlagCount = cfg.getInt("risk.high-flag-count", c.highRiskFlagCount);
        c.largeAlertRatioPpm = (long) (cfg.getDouble("risk.large-alert-ratio", 0.8) * 1_000_000);
        c.riskFlagExpireDays = cfg.getInt("risk.flag-expire-days", c.riskFlagExpireDays);

        c.loanEnabled = cfg.getBoolean("loan.enabled", true);
        c.loanMinAgeHours = cfg.getInt("loan.min-account-age-hours", c.loanMinAgeHours);
        c.loanMinCreditScore = cfg.getInt("loan.min-credit-score", c.loanMinCreditScore);
        c.loanAvgWindowDays = cfg.getInt("loan.avg-window-days", c.loanAvgWindowDays);
        c.loanSingleMax = dollarsToCents(cfg.getDouble("loan.single-max", 10_000));
        c.loanMultiplier = cfg.getLong("loan.credit-multiplier", c.loanMultiplier);
        c.loanReserveRatio = cfg.getDouble("loan.reserve-ratio", c.loanReserveRatio);
        c.loanPoolMax = dollarsToCents(cfg.getDouble("loan.pool-max", 100_000));
        c.loanDailyDisbursement = dollarsToCents(cfg.getDouble("loan.daily-disbursement", 50_000));
        ConfigurationSection terms = cfg.getConfigurationSection("loan.terms");
        if (terms != null) {
            c.loanTerms.clear();
            for (String key : terms.getKeys(false)) {
                try {
                    int days = Integer.parseInt(key);
                    double percent = terms.getDouble(key);
                    c.loanTerms.put(days, percentToPpm(percent));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        if (c.loanTerms.isEmpty()) c.loanTerms = defaults().loanTerms;
        c.loanExtensionDays = cfg.getInt("loan.extension-days", c.loanExtensionDays);
        c.loanExtensionRateBumpPpm = percentToPpm(cfg.getDouble("loan.extension-rate-bump-percent", 0.01));
        c.loanPenaltyPpm = percentToPpm(cfg.getDouble("loan.penalty-percent", 0.1));
        c.loanReminderDays = cfg.getInt("loan.reminder-days", c.loanReminderDays);
        c.loanWriteOffAfterDays = cfg.getInt("loan.write-off-after-days", c.loanWriteOffAfterDays);
        c.loanDailyWriteOffCap = dollarsToCents(cfg.getDouble("loan.write-off-daily-cap", 5_000));

        c.creditStart = cfg.getInt("credit.start", c.creditStart);
        c.creditMax = cfg.getInt("credit.max", c.creditMax);
        c.creditMin = cfg.getInt("credit.min", c.creditMin);
        c.creditOnTime = cfg.getInt("credit.on-time", c.creditOnTime);
        c.creditOverdue = cfg.getInt("credit.overdue", c.creditOverdue);
        c.creditDefault = cfg.getInt("credit.default", c.creditDefault);

        c.levelFlowWindowDays = cfg.getInt("level.flow-window-days", c.levelFlowWindowDays);
        c.levelAssetWindowDays = cfg.getInt("level.asset-window-days", c.levelAssetWindowDays);
        c.levelGraceDays = cfg.getInt("level.grace-days", c.levelGraceDays);
        c.levelEvalDayOfMonth = cfg.getInt("level.eval-day-of-month", c.levelEvalDayOfMonth);

        List<Map<?, ?>> levelList = cfg.getMapList("levels");
        if (!levelList.isEmpty()) {
            c.levels.clear();
            int idx = 0;
            for (Map<?, ?> map : levelList) {
                String key = text(map.get("key"), "level" + idx);
                String label = text(map.get("label"), key);
                long avg = dollarsToCents(number(map.get("min-avg-assets"), 0));
                long flow = dollarsToCents(number(map.get("min-flow"), 0));
                int comp = (int) Math.round(number(map.get("min-compliance"), 0));
                long cardFee = dollarsToCents(number(map.get("card-fee"), 0));
                long single = dollarsToCents(number(map.get("terminal-single-limit"), 10_000));
                int fee = percentToPpm(number(map.get("fee-percent"), 1.0));
                int session = (int) Math.round(number(map.get("session-minutes"), 5));
                int history = (int) Math.round(number(map.get("history-limit"), 20));
                int bonus = percentToPpm(number(map.get("interest-bonus-percent"), 0.0));
                c.levels.add(new LevelSpec(idx, key, label, avg, flow, comp, cardFee, single, fee, session, history, bonus));
                idx++;
            }
            if (c.levels.isEmpty()) c.levels = defaults().levels;
        }

        c.backupEnabled = cfg.getBoolean("backup.enabled", true);
        c.backupHour = cfg.getInt("backup.hour", c.backupHour);
        c.backupMinute = cfg.getInt("backup.minute", c.backupMinute);
        c.backupKeep = cfg.getInt("backup.keep", c.backupKeep);
        c.treasuryEnabled = cfg.getBoolean("treasury.enabled", true);
        c.interestFromTreasuryFirst = cfg.getBoolean("treasury.pay-interest-first", true);
        return c;
    }

    public LevelSpec level(int index) {
        int clamped = Math.max(0, Math.min(levels.size() - 1, index));
        return levels.get(clamped);
    }

    public long wealthTaxFor(long base) {
        if (!wealthTaxEnabled) return 0;
        long excess = Math.max(0L, base - wealthTaxThreshold);
        return Money.permille(excess, wealthTaxPpm);
    }

    public long incomeTaxFor(long net) {
        if (net <= incomeTaxFree) return 0;
        long tax = 0;
        long lower = incomeTaxFree;
        for (Bracket bracket : incomeBrackets) {
            long upper = bracket.openEnded() ? Long.MAX_VALUE : bracket.upToCents();
            if (net <= lower) break;
            long taxable = Math.min(net, upper) - lower;
            if (taxable > 0) {
                tax += Money.permille(taxable, bracket.ratePpm());
            }
            lower = upper;
            if (upper == Long.MAX_VALUE) break;
        }
        return tax;
    }

    public long levelCardFee(int index) {
        return level(index).cardFee();
    }
}
