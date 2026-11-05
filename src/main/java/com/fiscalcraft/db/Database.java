package com.fiscalcraft.db;

import com.fiscalcraft.config.FiscalConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * 单连接、单写者数据库。只允许 EconomyQueue 的工作线程调用。
 * SQLite 使用 WAL + synchronous=FULL；MySQL 使用显式事务与 SELECT ... FOR UPDATE。
 */
public final class Database implements AutoCloseable {
    public interface SqlCallable<T> {
        T call(Connection connection) throws SQLException;
    }

    private final FiscalConfig config;
    private final File dataFolder;
    private Connection connection;
    private boolean mysql;
    private volatile boolean ready;

    public Database(FiscalConfig config, File dataFolder) {
        this.config = config;
        this.dataFolder = dataFolder;
    }

    /** 必须在队列线程执行。 */
    public void open() throws SQLException {
        mysql = "mysql".equalsIgnoreCase(config.storageType);
        if (mysql) {
            String url = "jdbc:mysql://" + config.mysqlHost + ":" + config.mysqlPort + "/"
                    + config.mysqlDatabase + "?characterEncoding=utf8&useSSL=" + config.mysqlUseSsl
                    + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&rewriteBatchedStatements=true";
            Properties props = new Properties();
            props.setProperty("user", config.mysqlUser);
            props.setProperty("password", config.mysqlPassword);
            connection = DriverManager.getConnection(url, props);
        } else {
            File file = new File(dataFolder, config.sqliteFile);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new SQLException("无法创建数据库目录: " + parent);
            }
            Properties props = new Properties();
            props.setProperty("journal_mode", "WAL");
            props.setProperty("synchronous", "FULL");
            props.setProperty("busy_timeout", "5000");
            props.setProperty("foreign_keys", "true");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath(), props);
        }
        connection.setAutoCommit(true);
        Schema.create(this, mysql, System.currentTimeMillis());
        ready = true;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isMysql() {
        return mysql;
    }

    public Connection connection() {
        if (connection == null) throw new IllegalStateException("数据库尚未初始化");
        return connection;
    }

    public <T> T tx(SqlCallable<T> work) {
        Connection con = connection();
        try {
            if (con.getAutoCommit()) con.setAutoCommit(false);
            T result = work.call(con);
            con.commit();
            return result;
        } catch (SQLException e) {
            try {
                con.rollback();
            } catch (SQLException ignored) {
            }
            throw new DatabaseException("数据库事务失败: " + e.getMessage(), e);
        } finally {
            try {
                con.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    public <T> T read(SqlCallable<T> work) {
        try {
            return work.call(connection());
        } catch (SQLException e) {
            throw new DatabaseException("数据库读取失败: " + e.getMessage(), e);
        }
    }

    public void execute(String sql) throws SQLException {
        try (Statement statement = connection().createStatement()) {
            statement.execute(sql);
        }
    }

    public boolean tableExists(String table) {
        try {
            ResultSet rs;
            if (mysql) {
                rs = connection().getMetaData().getTables(connection().getCatalog(), null, table, new String[]{"TABLE"});
            } else {
                rs = connection().createStatement().executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'");
            }
            try (rs) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        ready = false;
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }

    public static final class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
