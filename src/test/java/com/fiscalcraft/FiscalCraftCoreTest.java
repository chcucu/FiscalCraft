package com.fiscalcraft;

import com.fiscalcraft.bukkit.TerminalType;
import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.db.Database;
import com.fiscalcraft.model.Models.Account;
import com.fiscalcraft.model.Models.Loan;
import com.fiscalcraft.model.Models.TaxRecord;
import com.fiscalcraft.queue.EconomyQueue;
import com.fiscalcraft.queue.Outcome;
import com.fiscalcraft.service.EconomyService;
import com.fiscalcraft.service.LevelService;
import com.fiscalcraft.service.LoanService;
import com.fiscalcraft.service.RelationService;
import com.fiscalcraft.service.RiskService;
import com.fiscalcraft.service.Store;
import com.fiscalcraft.service.TaxService;
import com.fiscalcraft.service.WalletService;
import com.fiscalcraft.util.Money;
import com.fiscalcraft.util.TimeSource;
import com.fiscalcraft.wallet.WalletGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FiscalCraftCoreTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String B = "00000000-0000-0000-0000-000000000002";
    private static final String C = "00000000-0000-0000-0000-000000000003";
    private static final String PIN = "123456";

    @TempDir
    File temp;

    // ------------------------------------------------------------ 全流程

    @Test
    void openDepositWithdrawTransferHistory() {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T02:00:00Z"));
        h.fund(A, Money.dollars(1_000));
        h.fund(B, Money.dollars(1_000));
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.open(B, "Bob").ok());
        assertTrue(h.economy.deposit(A, Money.dollars(100)).ok());
        assertTrue(h.economy.withdraw(A, Money.dollars(10)).ok());
        long before = h.bank(A);
        long fee = Money.permille(Money.dollars(5), h.cfg.level(0).feePpm());
        Outcome transfer = h.economy.terminalTransfer(A, "Bob", Money.dollars(5), false);
        assertTrue(transfer.ok(), transfer.message());
        assertEquals(before - Money.dollars(5) - fee, h.bank(A));
        assertEquals(Money.dollars(5), h.bank(B));
        assertTrue(h.economy.history(A, 20).size() >= 4);
        h.close();
    }

    @Test
    void rejectionsAreEnforced() {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T02:00:00Z"));
        h.fund(A, Money.dollars(1_000));
        h.fund(B, Money.dollars(1_000));
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.open(B, "Bob").ok());
        assertFalse(h.economy.withdraw(A, Money.dollars(50)).ok(), "空账户不能取款");
        assertFalse(h.economy.terminalTransfer(A, "Nobody", Money.dollars(1), false).ok(),
                "收款方未开户必须拒绝");
        assertFalse(h.economy.remotePay(A, "Bob", Money.dollars(2_001), false).ok(),
                "远程单笔超限必须拒绝");
        int fail = 0;
        for (int i = 0; i < 5; i++) {
            EconomyService.AuthOutcome outcome = h.economy.verifyPin(A, "000000", "1.2.3.4");
            if (!outcome.success()) fail++;
        }
        assertEquals(5, fail);
        assertTrue(h.economy.verifyPin(A, PIN, "1.2.3.4").message().contains("锁定")
                || !h.economy.verifyPin(A, PIN, "1.2.3.4").success(), "错误 5 次后必须锁定");
        h.close();
    }

    // ------------------------------------------------------------ 重启持久化

    @Test
    void restartKeepsBalancesTransactionsLoansAndCredit() {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T02:00:00Z"));
        h.fund(A, Money.dollars(2_000));
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.economy.deposit(A, Money.dollars(1_000)).ok());
        h.db.tx(con -> {
            h.store.adjustCredit(con, A, 50, h.time.now());
            h.store.setCompliance(con, A, 90);
            return null;
        });
        int txBefore = h.db.read(con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM transactions");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
        long balance = h.bank(A);
        int credit = h.db.read(con -> h.store.creditScore(con, A));
        h.close();

        Harness reopened = new Harness(temp, h.wallet, Instant.parse("2026-01-01T02:00:00Z"));
        assertEquals(balance, reopened.bank(A));
        long restoredCredit = reopened.db.read(con -> reopened.store.creditScore(con, A));
        long restoredCompliance = reopened.db.read(con -> reopened.store.getPlayer(con, A).compliance());
        assertEquals((long) credit, restoredCredit);
        assertEquals(90L, restoredCompliance);
        int txAfter = reopened.db.read(con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM transactions");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
        assertEquals((long) txBefore, (long) txAfter);
        reopened.close();
        h.close();
    }

    // ------------------------------------------------------------ 并发

    @Test
    void concurrentTransfersKeepBooksBalanced() throws Exception {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T02:00:00Z"));
        h.fund(A, Money.dollars(1_000));
        h.fund(B, Money.dollars(1_000));
        h.fund(C, Money.dollars(1_000));
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.open(B, "Bob").ok());
        assertTrue(h.open(C, "Carol").ok());
        assertTrue(h.economy.deposit(A, Money.dollars(50)).ok());
        long totalBefore = h.bank(A) + h.bank(B) + h.bank(C) + h.treasury();
        EconomyQueue queue = new EconomyQueue(Runnable::run, Logger.getLogger("test"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Outcome> results = new ArrayList<>();
        java.util.Collections.synchronizedList(results);
        for (int i = 0; i < 100; i++) {
            final int index = i;
            final String target = index % 2 == 0 ? "Bob" : "Carol";
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                queue.submit("t" + index, () -> h.economy.terminalTransfer(A, target, 50, false),
                        results::add);
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(queue.finish(Duration.ofSeconds(30)));
        assertEquals(100, results.size());
        assertTrue(results.stream().allMatch(Outcome::ok), "并发转账应全部成功");
        long totalAfter = h.bank(A) + h.bank(B) + h.bank(C) + h.treasury();
        assertEquals(totalBefore, totalAfter);
        assertTrue(h.bank(A) >= 0 && h.bank(B) >= 0 && h.bank(C) >= 0);
        assertEquals(0, h.bank(A));
        h.close();
    }

    // ------------------------------------------------------------ 税收

    @Test
    void interestWealthAndIncomeTaxFollowConfig() {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T04:00:00Z"));
        h.fund(A, Money.dollars(5_000));
        h.fund(B, Money.dollars(5_000));
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.open(B, "Bob").ok());
        assertTrue(h.economy.deposit(A, Money.dollars(4_000)).ok());
        assertTrue(h.economy.deposit(B, Money.dollars(1_000)).ok());
        assertTrue(h.economy.terminalTransfer(A, "Bob", Money.dollars(3_000), false).ok());
        long bankABefore = h.bank(A);
        long bankBBefore = h.bank(B);
        h.advance(Duration.ofDays(1).plusSeconds(1));
        TaxService.Settlement settlement = h.tax.settleDaily();
        assertFalse(settlement.skipped());
        long expectedInterest = Money.permille(bankABefore, h.cfg.depositInterestPpm)
                + Money.permille(bankBBefore, h.cfg.depositInterestPpm);
        assertEquals(expectedInterest, settlement.interest(), "利息按 0.025%/日");
        long baseA = h.walletBalance(A) + h.bank(A) - h.loanOutstanding(A);
        long taxA = h.cfg.wealthTaxFor(baseA);
        assertTrue(taxA >= 0);
        assertEquals(h.cfg.incomeTaxFor(Money.dollars(3_000)), settlement.incomeTax(),
                "个税按当日净收入累进");
        h.close();
    }

    // ------------------------------------------------------------ 共享账户 + 关联合并计税

    @Test
    void sharedShareCountedOnceForRelatedCluster() {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T04:00:00Z"));
        h.fund(A, Money.dollars(100_000));
        h.fund(B, Money.dollars(100_000));
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.open(B, "Bob").ok());
        h.join(A);
        h.join(B);
        Outcome shared = h.economy.createShared(A, "family", "家庭账户");
        assertTrue(shared.ok(), shared.message());
        String id = sharedId(shared.message());
        assertFalse(id.isEmpty());
        assertTrue(h.economy.joinShared(B, id).ok());
        assertTrue(h.economy.sharedDeposit(A, id, Money.dollars(100)).ok());
        assertTrue(h.economy.sharedDeposit(B, id, Money.dollars(100)).ok());
        assertTrue(h.economy.deposit(A, Money.dollars(40_000)).ok());
        assertTrue(h.economy.deposit(B, Money.dollars(40_000)).ok());
        h.advance(Duration.ofDays(1));
        long expectedBase = h.walletBalance(A) + h.bank(A) + h.sharedShare(A)
                + h.walletBalance(B) + h.bank(B) + h.sharedShare(B)
                - h.loanOutstanding(A) - h.loanOutstanding(B);
        long expectedTax = h.cfg.wealthTaxFor(expectedBase);
        long collected = h.db.tx(con -> h.tax.collectWealthTax(con, h.time.now(), h.time.dayKey()));
        assertEquals(expectedTax, collected, "关联簇 + 共享均摊只计一次，不得重复计税");
        h.close();
    }

    // ------------------------------------------------------------ 贷款 T+1 与 20% 监控

    @Test
    void loanTPlusOneAndRelatedOutflowLimit() {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2025-12-29T04:00:00Z"));
        h.fund(A, Money.dollars(20_000));
        h.fund(B, Money.dollars(1_000));
        h.join(A);
        h.join(B);
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.open(B, "Bob").ok());
        h.fundBank(A, Money.dollars(10_000));
        h.credit(A, 100);
        h.snapshots(A, Money.dollars(10_000), 14);
        h.advance(Duration.ofDays(3));
        Outcome loan = h.loans.apply(A, 7, Money.dollars(5_000));
        assertTrue(loan.ok(), loan.message());
        assertTrue(h.economy.withdraw(A, Money.dollars(100)).message().contains("T+1"),
                "放款 24 小时内禁止取款");
        h.advance(Duration.ofHours(25));
        Outcome blocked = h.economy.terminalTransfer(A, "Bob", Money.dollars(1_200), false);
        assertFalse(blocked.ok());
        assertTrue(blocked.message().contains("20%"), blocked.message());
        assertEquals(Money.dollars(10_000) + Money.dollars(5_000), h.bank(A), "拦截后资金必须原路保留");
        h.close();
    }

    // ------------------------------------------------------------ 等级缓冲与合规降级

    @Test
    void levelGraceAndComplianceDowngrade() {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T00:00:00Z"));
        h.fund(A, Money.dollars(1_000));
        assertTrue(h.open(A, "Alice").ok());
        h.db.tx(con -> {
            h.store.updatePlayerLevel(con, A, 2, 0, h.time.now());
            return null;
        });
        LevelService.Change grace = h.txResult(con -> h.levels.evaluate(con, A, h.time.now()));
        assertNotNull(grace);
        assertEquals(2, grace.oldLevel());
        assertTrue(grace.reason().contains("缓冲"));
        h.advance(Duration.ofDays(31));
        LevelService.Change downgrade = h.txResult(con -> h.levels.evaluate(con, A, h.time.now()));
        assertNotNull(downgrade);
        assertEquals(2, downgrade.oldLevel());
        assertEquals(1, downgrade.newLevel());
        h.db.tx(con -> {
            h.store.updatePlayerLevel(con, A, 3, 0, h.time.now());
            h.risk.flag(con, A, "BLACKLIST", "测试黑名单", 7);
            return null;
        });
        h.db.tx(con -> h.levels.updateComplianceDaily(con, h.time.now()));
        assertEquals(2L, (long) h.db.read(con -> h.store.getPlayer(con, A).level()),
                "合规分跌破阈值立即临时降一级");
        h.close();
    }

    // ------------------------------------------------------------ 转账日限额与欠税

    @Test
    void dailyLimitAndArrearsRules() {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T04:00:00Z"));
        h.fund(A, Money.dollars(10_000));
        h.fund(B, Money.dollars(1_000));
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.open(B, "Bob").ok());
        assertTrue(h.economy.deposit(A, Money.dollars(1_000)).ok());
        long first = Money.dollars(20_000);
        assertFalse(h.economy.terminalTransfer(A, "Bob", first, false).ok(),
                "终端单笔不得超过等级限额");
        h.db.tx(con -> {
            h.store.insertTax(con, new TaxRecord(Store.newId(), h.time.now(), B, "WEALTH",
                    Money.dollars(1_000), Money.dollars(1_000), 0, "OWED", h.time.dayKey(), "测试欠税"));
            return null;
        });
        assertTrue(h.economy.terminalTransfer(A, "Bob", Money.dollars(100), false).ok());
        long owed = h.db.read(con -> h.store.owedTax(con, B));
        assertEquals(Money.dollars(900), owed, "收款方欠税应优先补缴");
        h.close();
    }

    // ------------------------------------------------------------ 销户

    @Test
    void closeAccountReturnsBalanceToWallet() {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T04:00:00Z"));
        h.fund(A, Money.dollars(1_000));
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.economy.deposit(A, Money.dollars(200)).ok());
        assertEquals(Money.dollars(300), h.walletBalance(A));
        assertEquals(Money.dollars(200), h.bank(A));
        assertTrue(h.economy.closeAccount(A).ok());
        assertEquals(Money.dollars(500), h.walletBalance(A), "销户余额必须转回钱包");
        assertEquals(0L, h.bank(A));
        assertNull(h.db.read(con -> h.store.getAccount(con, A)), "销户后账户记录必须删除");
        h.close();
    }

    // ------------------------------------------------------------ 终端类型

    @Test
    void terminalTypesRestrictOperations() {
        assertTrue(TerminalType.fromKey("full").allows("loan"));
        assertTrue(TerminalType.fromKey("cash").allows("deposit"));
        assertTrue(TerminalType.fromKey("cash").allows("withdraw"));
        assertFalse(TerminalType.fromKey("cash").allows("loan"));
        assertTrue(TerminalType.fromKey("loan").allows("repay"));
        assertFalse(TerminalType.fromKey("loan").allows("deposit"));
        assertTrue(TerminalType.fromKey("shared").allows("shared"));
        assertFalse(TerminalType.fromKey("transfer").allows("shared"));
        assertTrue(TerminalType.fromKey("account").allows("open"));
        assertFalse(TerminalType.fromKey("account").allows("transfer"));
        assertNull(TerminalType.fromKey("unknown"));
    }

    // ------------------------------------------------------------ 备份与还原

    @Test
    void backupCanBeRestored() throws Exception {
        Harness h = new Harness(temp, new FakeWallet(), Instant.parse("2026-01-01T04:00:00Z"));
        h.fund(A, Money.dollars(1_000));
        assertTrue(h.open(A, "Alice").ok());
        assertTrue(h.economy.deposit(A, Money.dollars(500)).ok());
        long balance = h.bank(A);
        com.fiscalcraft.bukkit.BackupService backup =
                new com.fiscalcraft.bukkit.BackupService(temp, Logger.getLogger("test"), h.db, h.cfg, h.time);
        String result = backup.run();
        assertTrue(result.startsWith("备份成功"), result);
        File backupFile = newestBackup();
        assertNotNull(backupFile);
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + backupFile.getAbsolutePath());
             PreparedStatement ps = con.prepareStatement("SELECT balance FROM accounts WHERE player_uuid=?")) {
            ps.setString(1, A);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(balance, rs.getLong(1));
            }
        }
        h.close();
        Files.copy(backupFile.toPath(), new File(temp, h.cfg.sqliteFile).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        Harness restored = new Harness(temp, h.wallet, Instant.parse("2026-03-01T04:00:00Z"));
        assertEquals(balance, restored.bank(A), "备份还原后余额一致");
        restored.close();
        h.close();
    }

    private String sharedId(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .matcher(message);
        return matcher.find() ? matcher.group() : "";
    }

    private File newestBackup() {
        File folder = new File(temp, "backups");
        File[] files = folder.listFiles();
        if (files == null || files.length == 0) return null;
        File newest = files[0];
        for (File file : files) if (file.lastModified() > newest.lastModified()) newest = file;
        return newest;
    }

    // ------------------------------------------------------------ 测试基础设施

    static final class FakeWallet implements WalletGateway {
        private final Map<String, Long> balances = new ConcurrentHashMap<>();

        void set(String uuid, long cents) {
            balances.put(uuid, cents);
        }

        @Override
        public long balance(String uuid) {
            return balances.getOrDefault(uuid, 0L);
        }

        @Override
        public synchronized boolean withdraw(String uuid, long cents) {
            long current = balances.getOrDefault(uuid, 0L);
            if (current < cents) return false;
            balances.put(uuid, current - cents);
            return true;
        }

        @Override
        public synchronized boolean deposit(String uuid, long cents) {
            balances.put(uuid, balances.getOrDefault(uuid, 0L) + cents);
            return true;
        }
    }

    static final class Harness {
        final FiscalConfig cfg = FiscalConfig.defaults();
        final FakeWallet wallet;
        final Database db;
        final Store store;
        final TimeSource time;
        final AtomicReference<Instant> clock;
        final WalletService walletService;
        final RelationService relations;
        final RiskService risk;
        final LevelService levels;
        final TaxService tax;
        final LoanService loans;
        final EconomyService economy;

        Harness(File dir, FakeWallet wallet, Instant start) {
            this.wallet = wallet;
            this.clock = new AtomicReference<>(start);
            this.time = new TimeSource(ZONE, clock::get);
            this.db = new Database(cfg, dir);
            try {
                this.db.open();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            this.store = new Store(db, cfg, time);
            Logger logger = Logger.getLogger("FiscalCraftTest");
            this.walletService = new WalletService(store, db, cfg, time, wallet, logger);
            this.relations = new RelationService(cfg, store, time);
            this.risk = new RiskService(cfg, store, time);
            this.levels = new LevelService(cfg, store, time, wallet, relations, risk);
            this.tax = new TaxService(store, db, cfg, time, wallet, relations, levels, logger);
            this.loans = new LoanService(store, db, cfg, time, risk, relations);
            this.economy = new EconomyService(store, db, cfg, time, walletService, wallet,
                    levels, tax, loans, risk, relations, logger);
        }

        void fund(String uuid, long cents) {
            wallet.set(uuid, cents);
        }

        Outcome open(String uuid, String name) {
            return economy.openAccount(uuid, name, PIN, "密保问题", "答案", "10.0.0.1");
        }

        void join(String uuid) {
            db.tx(con -> {
                store.upsertJoin(con, uuid, uuid.equals(A) ? "Alice" : uuid.equals(B) ? "Bob" : "Carol",
                        "10.0.0.1", time.now());
                return null;
            });
        }

        long bank(String uuid) {
            return db.read(con -> store.balance(con, uuid));
        }

        long walletBalance(String uuid) {
            return wallet.balance(uuid);
        }

        long sharedShare(String uuid) {
            return db.read(con -> store.sharedShare(con, uuid));
        }

        long treasury() {
            return db.read(con -> store.treasury(con));
        }

        long loanOutstanding(String uuid) {
            return db.read(con -> store.outstandingFor(con, uuid));
        }

        void fundBank(String uuid, long cents) {
            db.tx(con -> {
                store.creditAccount(con, uuid, cents);
                return null;
            });
        }

        void credit(String uuid, int delta) {
            db.tx(con -> {
                store.adjustCredit(con, uuid, delta, time.now());
                return null;
            });
        }

        void snapshots(String uuid, long bank, int days) {
            LocalDate today = time.today();
            db.tx(con -> {
                for (int i = 0; i < days; i++) {
                    store.snapshotBalance(con, uuid, today.minusDays(i).toString(), bank, 0, 0, 0);
                }
                return null;
            });
        }

        void advance(Duration duration) {
            clock.set(clock.get().plus(duration));
        }

        <T> T txResult(Database.SqlCallable<T> work) {
            return db.tx(work);
        }

        void close() {
            db.close();
        }
    }
}
