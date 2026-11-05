package com.fiscalcraft.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class Schema {
    private Schema() {
    }

    static void create(Database db, boolean mysql, long now) throws SQLException {
        Connection con = db.connection();
        String balanceCheck = " CHECK (balance >= 0)";
        String engine = mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";

        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS players (" +
                        "player_uuid VARCHAR(36) PRIMARY KEY," +
                        "name VARCHAR(64) NOT NULL," +
                        "first_join BIGINT NOT NULL," +
                        "last_seen BIGINT NOT NULL," +
                        "last_ip VARCHAR(64)," +
                        "last_ip_at BIGINT NOT NULL DEFAULT 0," +
                        "level INT NOT NULL DEFAULT 0," +
                        "level_since BIGINT NOT NULL DEFAULT 0," +
                        "grace_until BIGINT NOT NULL DEFAULT 0," +
                        "compliance INT NOT NULL DEFAULT 100," +
                        "credit_score INT NOT NULL DEFAULT 600," +
                        "risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW'" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS accounts (" +
                        "player_uuid VARCHAR(36) PRIMARY KEY," +
                        "name VARCHAR(64) NOT NULL," +
                        "balance BIGINT NOT NULL DEFAULT 0," +
                        "pin_hash VARCHAR(100)," +
                        "pin_salt VARCHAR(64)," +
                        "security_question VARCHAR(255)," +
                        "security_answer_hash VARCHAR(100)," +
                        "security_answer_salt VARCHAR(64)," +
                        "fail_count INT NOT NULL DEFAULT 0," +
                        "locked_until BIGINT NOT NULL DEFAULT 0," +
                        "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'," +
                        "opened_at BIGINT NOT NULL," +
                        "CHECK (balance >= 0)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS cards (" +
                        "card_no VARCHAR(20) PRIMARY KEY," +
                        "owner_uuid VARCHAR(36) NOT NULL," +
                        "issued_at BIGINT NOT NULL," +
                        "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS shared_accounts (" +
                        "shared_id VARCHAR(36) PRIMARY KEY," +
                        "name VARCHAR(64) NOT NULL," +
                        "type VARCHAR(16) NOT NULL," +
                        "balance BIGINT NOT NULL DEFAULT 0," +
                        "created_by VARCHAR(36) NOT NULL," +
                        "created_at BIGINT NOT NULL," +
                        "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'," +
                        "CHECK (balance >= 0)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS account_members (" +
                        "shared_id VARCHAR(36) NOT NULL," +
                        "player_uuid VARCHAR(36) NOT NULL," +
                        "joined_at BIGINT NOT NULL," +
                        "net_contribution BIGINT NOT NULL DEFAULT 0," +
                        "PRIMARY KEY (shared_id, player_uuid)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS transactions (" +
                        "tx_id VARCHAR(36) PRIMARY KEY," +
                        "ts BIGINT NOT NULL," +
                        "type VARCHAR(32) NOT NULL," +
                        "from_uuid VARCHAR(36)," +
                        "to_uuid VARCHAR(36)," +
                        "amount BIGINT NOT NULL," +
                        "fee BIGINT NOT NULL DEFAULT 0," +
                        "from_before BIGINT," +
                        "from_after BIGINT," +
                        "to_before BIGINT," +
                        "to_after BIGINT," +
                        "reason VARCHAR(255)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS tax_records (" +
                        "record_id VARCHAR(36) PRIMARY KEY," +
                        "ts BIGINT NOT NULL," +
                        "player_uuid VARCHAR(36) NOT NULL," +
                        "tax_type VARCHAR(16) NOT NULL," +
                        "base BIGINT NOT NULL," +
                        "amount BIGINT NOT NULL," +
                        "paid BIGINT NOT NULL DEFAULT 0," +
                        "status VARCHAR(16) NOT NULL," +
                        "day_key VARCHAR(10) NOT NULL," +
                        "detail VARCHAR(255)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS loans (" +
                        "loan_id VARCHAR(36) PRIMARY KEY," +
                        "player_uuid VARCHAR(36) NOT NULL," +
                        "principal BIGINT NOT NULL," +
                        "rate_ppm INT NOT NULL," +
                        "term_days INT NOT NULL," +
                        "started_at BIGINT NOT NULL," +
                        "due_at BIGINT NOT NULL," +
                        "outstanding BIGINT NOT NULL," +
                        "status VARCHAR(16) NOT NULL," +
                        "disbursed_at BIGINT NOT NULL," +
                        "related_out BIGINT NOT NULL DEFAULT 0," +
                        "extensions INT NOT NULL DEFAULT 0," +
                        "last_accrual_day VARCHAR(10)," +
                        "written_off BIGINT NOT NULL DEFAULT 0," +
                        "closed_at BIGINT NOT NULL DEFAULT 0" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS credits (" +
                        "player_uuid VARCHAR(36) PRIMARY KEY," +
                        "score INT NOT NULL," +
                        "updated_at BIGINT NOT NULL" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS risk_flags (" +
                        "flag_id VARCHAR(36) PRIMARY KEY," +
                        "player_uuid VARCHAR(36) NOT NULL," +
                        "flag_type VARCHAR(32) NOT NULL," +
                        "reason VARCHAR(255)," +
                        "created_at BIGINT NOT NULL," +
                        "expires_at BIGINT NOT NULL," +
                        "active INT NOT NULL DEFAULT 1" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS treasury (" +
                        "treasury_id VARCHAR(32) PRIMARY KEY," +
                        "balance BIGINT NOT NULL DEFAULT 0," +
                        "updated_at BIGINT NOT NULL," +
                        "CHECK (balance >= 0)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS audit_log (" +
                        "audit_id VARCHAR(36) PRIMARY KEY," +
                        "ts BIGINT NOT NULL," +
                        "actor VARCHAR(64)," +
                        "action VARCHAR(64) NOT NULL," +
                        "detail VARCHAR(1000)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS suspicious_log (" +
                        "log_id VARCHAR(36) PRIMARY KEY," +
                        "ts BIGINT NOT NULL," +
                        "player_uuid VARCHAR(36)," +
                        "log_type VARCHAR(32) NOT NULL," +
                        "amount BIGINT NOT NULL," +
                        "detail VARCHAR(500)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS auth_lock (" +
                        "lock_key VARCHAR(80) PRIMARY KEY," +
                        "attempts INT NOT NULL DEFAULT 0," +
                        "window_start BIGINT NOT NULL," +
                        "locked_until BIGINT NOT NULL DEFAULT 0" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS wallet_ops (" +
                        "op_id VARCHAR(36) PRIMARY KEY," +
                        "player_uuid VARCHAR(36) NOT NULL," +
                        "direction VARCHAR(24) NOT NULL," +
                        "target VARCHAR(64) NOT NULL," +
                        "amount BIGINT NOT NULL," +
                        "state VARCHAR(24) NOT NULL," +
                        "wallet_before BIGINT NOT NULL," +
                        "bank_before BIGINT NOT NULL DEFAULT 0," +
                        "bank_after BIGINT NOT NULL DEFAULT 0," +
                        "reason VARCHAR(255)," +
                        "created_at BIGINT NOT NULL," +
                        "updated_at BIGINT NOT NULL" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS daily_stats (" +
                        "player_uuid VARCHAR(36) NOT NULL," +
                        "day_key VARCHAR(10) NOT NULL," +
                        "received BIGINT NOT NULL DEFAULT 0," +
                        "paid BIGINT NOT NULL DEFAULT 0," +
                        "remote_out BIGINT NOT NULL DEFAULT 0," +
                        "terminal_out BIGINT NOT NULL DEFAULT 0," +
                        "PRIMARY KEY (player_uuid, day_key)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS daily_balance (" +
                        "player_uuid VARCHAR(36) NOT NULL," +
                        "day_key VARCHAR(10) NOT NULL," +
                        "bank BIGINT NOT NULL," +
                        "wallet BIGINT NOT NULL," +
                        "shared_share BIGINT NOT NULL," +
                        "loan_principal BIGINT NOT NULL," +
                        "PRIMARY KEY (player_uuid, day_key)" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS terminals (" +
                        "block_key VARCHAR(120) PRIMARY KEY," +
                        "world VARCHAR(64) NOT NULL," +
                        "x INT NOT NULL," +
                        "y INT NOT NULL," +
                        "z INT NOT NULL," +
                        "created_by VARCHAR(36) NOT NULL," +
                        "created_at BIGINT NOT NULL" +
                        ") " + engine,
                "CREATE TABLE IF NOT EXISTS meta (" +
                        "k VARCHAR(64) PRIMARY KEY," +
                        "v VARCHAR(255)" +
                        ") " + engine
        };

        String[] indexes = {
                "CREATE INDEX IF NOT EXISTS idx_tx_from ON transactions (from_uuid, ts)",
                "CREATE INDEX IF NOT EXISTS idx_tx_to ON transactions (to_uuid, ts)",
                "CREATE INDEX IF NOT EXISTS idx_tx_ts ON transactions (ts)",
                "CREATE INDEX IF NOT EXISTS idx_tax_player ON tax_records (player_uuid, status)",
                "CREATE INDEX IF NOT EXISTS idx_loan_player ON loans (player_uuid, status)",
                "CREATE INDEX IF NOT EXISTS idx_flag_player ON risk_flags (player_uuid, active)",
                "CREATE INDEX IF NOT EXISTS idx_member_player ON account_members (player_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_wallet_state ON wallet_ops (state)"
        };
        try (Statement st = con.createStatement()) {
            for (String sql : ddl) st.execute(sql);
        }
        for (String sql : indexes) {
            try (Statement st = con.createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                // MySQL 不支持 CREATE INDEX IF NOT EXISTS：已存在索引时忽略。
                if (!mysql) throw e;
            }
        }

        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM treasury WHERE treasury_id = 'system.treasury'");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getInt(1) == 0) {
                try (PreparedStatement ins = con.prepareStatement(
                        "INSERT INTO treasury (treasury_id, balance, updated_at) VALUES ('system.treasury', 0, ?)")) {
                    ins.setLong(1, now);
                    ins.executeUpdate();
                }
            }
        }
    }
}
