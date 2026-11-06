package com.fiscalcraft;

import com.fiscalcraft.bukkit.BackupService;
import com.fiscalcraft.bukkit.BankCommand;
import com.fiscalcraft.bukkit.BankListener;
import com.fiscalcraft.bukkit.CardItems;
import com.fiscalcraft.bukkit.PromptManager;
import com.fiscalcraft.bukkit.SessionManager;
import com.fiscalcraft.bukkit.TerminalManager;
import com.fiscalcraft.bukkit.TerminalMarker;
import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.db.Database;
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
import com.fiscalcraft.util.TimeSource;
import com.fiscalcraft.wallet.VaultWallet;
import com.fiscalcraft.wallet.WalletGateway;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * FiscalCraft 主类：装配所有核心服务，初始化在资金队列线程执行，主线程不做 JDBC。
 */
public final class FiscalCraftPlugin extends JavaPlugin {
    private FiscalConfig config;
    private TimeSource time;
    private Database database;
    private EconomyQueue queue;
    private Store store;
    private WalletGateway walletGateway;
    private VaultWallet vaultWallet;
    private RelationService relations;
    private RiskService risk;
    private LevelService levels;
    private TaxService tax;
    private LoanService loans;
    private WalletService walletService;
    private EconomyService economy;
    private SessionManager sessions;
    private TerminalManager terminals;
    private TerminalMarker markers;
    private PromptManager prompts;
    private BackupService backup;
    private CardItems cards;
    private volatile boolean ready;
    private volatile String lastBackupDay;
    private volatile String lastBackupAttemptDay;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = FiscalConfig.load(getConfig());
        time = new TimeSource(config.zoneId);
        database = new Database(config, getDataFolder());

        queue = new EconomyQueue(runnable -> Bukkit.getScheduler().runTask(this, runnable), getLogger());
        store = new Store(database, config, time);

        vaultWallet = new VaultWallet(this);
        if (!vaultWallet.setup()) {
            getLogger().severe("未找到 Vault Economy 服务，请安装 Vault 与 EssentialsX 后重启。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        walletGateway = vaultWallet;

        relations = new RelationService(config, store, time);
        risk = new RiskService(config, store, time);
        levels = new LevelService(config, store, time, walletGateway, relations, risk);
        tax = new TaxService(store, database, config, time, walletGateway, relations, levels, getLogger());
        loans = new LoanService(store, database, config, time, risk, relations);
        walletService = new WalletService(store, database, config, time, walletGateway, getLogger());
        economy = new EconomyService(store, database, config, time, walletService, walletGateway,
                levels, tax, loans, risk, relations, getLogger());

        sessions = new SessionManager(this, config, time);
        terminals = new TerminalManager(this, store, database, time);
        markers = new TerminalMarker(this, terminals);
        prompts = new PromptManager(this);
        cards = new CardItems(this);
        backup = new BackupService(getDataFolder(), getLogger(), database, config, time);

        BankCommand command = new BankCommand(this, sessions, terminals, markers, prompts, cards, economy,
                levels, loans, tax);
        BankListener listener = new BankListener(this, sessions, terminals, markers, prompts, cards, economy);
        listener.bind(command);
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(markers, this);
        if (getCommand("bank") != null) {
            getCommand("bank").setExecutor(command);
            getCommand("bank").setTabCompleter(command);
        }

        List<String> startupNotices = new ArrayList<>();
        queue.submit("init", () -> {
            database.open();
            walletService.recoverPending();
            terminals.load();
            lastBackupDay = database.read(con -> store.meta(con, "last_backup_day"));
            // 服务器跨日停机后补一次日结（0:00 定时任务只在运行期间触发）。
            String lastDay = database.read(con -> store.meta(con, "last_daily_day"));
            String today = time.dayKey();
            if (lastDay != null && lastDay.compareTo(today) < 0) {
                tax.settleDaily();
                startupNotices.addAll(loans.accrueDaily().notices());
            }
            economy.evaluateLevels(false);
            return Outcome.ok("初始化完成");
        }, outcome -> {
            if (!outcome.ok()) {
                getLogger().severe("数据库初始化失败: " + outcome.message());
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            ready = true;
            startTasks();
            markers.ensureAllLoaded();
            for (String notice : startupNotices) {
                notifyNotice(notice);
            }
            getLogger().info("FiscalCraft 已就绪：数据库 " + (database.isMysql() ? "MySQL" : "SQLite(WAL)")
                    + "，资金操作单线程串行执行。");
        });
    }

    @Override
    public void onDisable() {
        if (queue != null) queue.finish(Duration.ofSeconds(15));
        if (database != null) database.close();
        ready = false;
        getLogger().info("FiscalCraft 已停止，队列已排空。");
    }

    private void startTasks() {
        // 每 tick：会话距离/超时检查与聊天输入超时清理，均为内存操作。
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            sessions.tick();
            prompts.tick();
        }, 1L, 1L);
        // 每 2 秒：终端彩色粒子标识。
        Bukkit.getScheduler().runTaskTimer(this, markers::tickParticles, 40L, 40L);
        // 每分钟补齐一次已加载区块的全息标识（防止实体被意外移除）。
        Bukkit.getScheduler().runTaskTimer(this, markers::ensureAllLoaded, 1200L, 1200L);
        // 每分钟：日切、贷款计提、月度评级、自动备份。
        Bukkit.getScheduler().runTaskTimer(this, this::minuteTick, 60L, 60L);
    }

    /** 必须在资金队列线程执行：备份成功后持久化当日标记。 */
    public String backupNow() {
        String result = backup.run();
        if (result.contains("成功")) {
            String day = time.dayKey();
            database.tx(con -> {
                store.setMeta(con, "last_backup_day", day);
                return null;
            });
            lastBackupDay = day;
        }
        return result;
    }

    private void minuteTick() {
        if (!ready) return;
        LocalDateTime now = time.nowLocal();
        if (now.getHour() == 0 && now.getMinute() == 0) {
            queue.submit("daily-settle", () -> {
                TaxService.Settlement settlement = tax.settleDaily();
                LoanService.AccrualResult accrual = loans.accrueDaily();
                economy.evaluateLevels(false);
                if (now.getDayOfMonth() == config.levelEvalDayOfMonth) economy.evaluateLevels(true);
                for (String notice : accrual.notices()) {
                    notifyNotice(notice);
                }
                return Outcome.ok(String.join(" | ", settlement.notices()));
            }, outcome -> {
                if (outcome.ok() && !outcome.message().isBlank()) {
                    getLogger().info(outcome.message());
                }
            });
        }
        if (config.backupEnabled && now.getHour() >= config.backupHour
                && !time.dayKey().equals(lastBackupDay)
                && !time.dayKey().equals(lastBackupAttemptDay)) {
            lastBackupAttemptDay = time.dayKey();
            queue.submit("backup", () -> Outcome.ok(backupNow()), outcome -> {
                if (outcome.ok() && outcome.message().contains("成功")) {
                    getLogger().info("自动备份: " + outcome.message());
                } else {
                    getLogger().warning("自动备份失败: " + outcome.message());
                }
            });
        }
    }

    /** 通知行格式为 "uuid 文本"，仅当玩家在线时私聊。 */
    private void notifyNotice(String notice) {
        int space = notice.indexOf(' ');
        if (space <= 0) return;
        String uuid = notice.substring(0, space);
        String text = notice.substring(space + 1);
        Player player = Bukkit.getPlayer(java.util.UUID.fromString(uuid));
        if (player != null) player.sendMessage(Component.text(text));
    }

    public boolean ready() {
        return ready;
    }

    public FiscalConfig config() {
        return config;
    }

    public TimeSource time() {
        return time;
    }

    public Database database() {
        return database;
    }

    public EconomyQueue queue() {
        return queue;
    }

    public Store store() {
        return store;
    }

    public WalletGateway wallet() {
        return walletGateway;
    }

    public VaultWallet vault() {
        return vaultWallet;
    }

    public EconomyService economy() {
        return economy;
    }

    public LevelService levels() {
        return levels;
    }

    public RiskService risk() {
        return risk;
    }

    public TaxService tax() {
        return tax;
    }

    public LoanService loans() {
        return loans;
    }

    public WalletService walletService() {
        return walletService;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public TerminalManager terminals() {
        return terminals;
    }

    public PromptManager prompts() {
        return prompts;
    }

    public BackupService backup() {
        return backup;
    }

    public CardItems cards() {
        return cards;
    }
}
