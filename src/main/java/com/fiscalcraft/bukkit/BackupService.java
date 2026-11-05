package com.fiscalcraft.bukkit;

import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.db.Database;
import com.fiscalcraft.util.TimeSource;

import java.io.File;
import java.io.IOException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * 每日备份：SQLite 使用 VACUUM INTO + PRAGMA integrity_check；
 * MySQL 使用 mysqldump（需系统安装 mysqldump），失败会记录日志但不影响主流程。
 */
public final class BackupService {
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final File dataFolder;
    private final Logger logger;
    private final Database db;
    private final FiscalConfig cfg;
    private final TimeSource time;

    public BackupService(File dataFolder, Logger logger, Database db, FiscalConfig cfg, TimeSource time) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.db = db;
        this.cfg = cfg;
        this.time = time;
    }

    public String run() {
        File folder = new File(dataFolder, "backups");
        if (!folder.exists() && !folder.mkdirs()) {
            return "备份失败：无法创建备份目录";
        }
        String name = "fiscalcraft-" + LocalDateTime.now().format(NAME) + (db.isMysql() ? ".sql" : ".db");
        File target = new File(folder, name);
        if (db.isMysql()) {
            return dumpMysql(target);
        }
        try {
            String path = target.getAbsolutePath().replace("'", "''");
            try (Statement st = db.connection().createStatement()) {
                st.execute("VACUUM INTO '" + path + "'");
                try (java.sql.ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                    if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                        return "备份完整性校验失败：" + name;
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            logger.severe("数据库备份失败: " + e.getMessage());
            return "备份失败：" + e.getMessage();
        }
        prune(folder);
        return "备份成功：" + name + "（完整性校验通过）";
    }

    private String dumpMysql(File target) {
        List<String> command = new ArrayList<>(List.of("mysqldump", "-h", cfg.mysqlHost, "-P",
                Integer.toString(cfg.mysqlPort), "-u", cfg.mysqlUser, cfg.mysqlDatabase));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("MYSQL_PWD", cfg.mysqlPassword);
        builder.redirectOutput(target);
        builder.redirectErrorStream(false);
        try {
            Process process = builder.start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return "MySQL 备份超时";
            }
            if (process.exitValue() != 0 || target.length() == 0) {
                return "MySQL 备份失败，请检查 mysqldump 与数据库权限";
            }
        } catch (IOException e) {
            return "MySQL 备份失败：未找到 mysqldump";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "MySQL 备份被中断";
        }
        return "MySQL 备份成功：" + target.getName();
    }

    private void prune(File folder) {
        File[] files = folder.listFiles((dir, fileName) -> fileName.startsWith("fiscalcraft-"));
        if (files == null || files.length <= cfg.backupKeep) return;
        List<File> sorted = new ArrayList<>(List.of(files));
        sorted.sort(Comparator.comparingLong(File::lastModified));
        int remove = sorted.size() - Math.max(1, cfg.backupKeep);
        for (int i = 0; i < remove; i++) {
            if (!sorted.get(i).delete()) {
                logger.warning("无法删除旧备份: " + sorted.get(i).getName());
            }
        }
    }
}
