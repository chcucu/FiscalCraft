package com.fiscalcraft.service;

import com.fiscalcraft.config.FiscalConfig;
import com.fiscalcraft.db.Database;
import com.fiscalcraft.model.Models.Account;
import com.fiscalcraft.model.Models.Card;
import com.fiscalcraft.model.Models.Loan;
import com.fiscalcraft.model.Models.PlayerData;
import com.fiscalcraft.model.Models.SharedAccount;
import com.fiscalcraft.model.Models.SharedMember;
import com.fiscalcraft.model.Models.TaxRecord;
import com.fiscalcraft.model.Models.Tx;
import com.fiscalcraft.model.Models.WalletOp;
import com.fiscalcraft.util.TimeSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class Store {
    public static final String TREASURY_ID = "system.treasury";
    public static final String ISSUANCE_ID = "system.issuance";

    public record AuthResult(boolean allowed, boolean justLocked, int attempts, long lockedUntil) {
    }

    private final Database db;
    private final FiscalConfig cfg;
    private final TimeSource time;

    public Store(Database db, FiscalConfig cfg, TimeSource time) {
        this.db = db;
        this.cfg = cfg;
        this.time = time;
    }

    public Database db() {
        return db;
    }

    public FiscalConfig config() {
        return cfg;
    }

    public TimeSource time() {
        return time;
    }

    // ---------------------------------------------------------------- players

    public void upsertJoin(Connection con, String uuid, String name, String ip, long now) throws SQLException {
        PlayerData existing = getPlayer(con, uuid);
        if (existing == null) {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO players (player_uuid,name,first_join,last_seen,last_ip,last_ip_at,level,level_since,grace_until,compliance,credit_score,risk_level) " +
                            "VALUES (?,?,?,?,?,?,0,?,0,100,?, 'LOW')")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setString(5, ip);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.setInt(8, cfg.creditStart);
                ps.executeUpdate();
            }
            ensureCredit(con, uuid, cfg.creditStart, now);
            return;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE players SET name=?, last_seen=?, last_ip=?, last_ip_at=? WHERE player_uuid=?")) {
            ps.setString(1, name);
            ps.setLong(2, now);
            ps.setString(3, ip);
            ps.setLong(4, now);
            ps.setString(5, uuid);
            ps.executeUpdate();
        }
    }

    public void ensurePlayer(Connection con, String uuid, String name, long now) throws SQLException {
        if (getPlayer(con, uuid) == null) {
            upsertJoin(con, uuid, name, null, now);
        }
    }

    public PlayerData getPlayer(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM players WHERE player_uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? player(rs) : null;
            }
        }
    }

    public PlayerData getPlayerByName(Connection con, String name) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM players WHERE LOWER(name)=LOWER(?) ORDER BY last_seen DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? player(rs) : null;
            }
        }
    }

    private PlayerData player(ResultSet rs) throws SQLException {
        return new PlayerData(rs.getString("player_uuid"), rs.getString("name"),
                rs.getLong("first_join"), rs.getLong("last_seen"), rs.getString("last_ip"),
                rs.getLong("last_ip_at"), rs.getInt("level"), rs.getLong("level_since"),
                rs.getLong("grace_until"), rs.getInt("compliance"), rs.getInt("credit_score"),
                rs.getString("risk_level"));
    }

    public void updatePlayerLevel(Connection con, String uuid, int level, long graceUntil, long now) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE players SET level=?, level_since=?, grace_until=? WHERE player_uuid=?")) {
            ps.setInt(1, level);
            ps.setLong(2, now);
            ps.setLong(3, graceUntil);
            ps.setString(4, uuid);
            ps.executeUpdate();
        }
    }

    public void setGrace(Connection con, String uuid, long graceUntil) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE players SET grace_until=? WHERE player_uuid=?")) {
            ps.setLong(1, graceUntil);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public void setCompliance(Connection con, String uuid, int compliance) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE players SET compliance=? WHERE player_uuid=?")) {
            ps.setInt(1, Math.max(0, Math.min(100, compliance)));
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public void setRiskLevel(Connection con, String uuid, String level) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE players SET risk_level=? WHERE player_uuid=?")) {
            ps.setString(1, level);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public List<PlayerData> allPlayers(Connection con) throws SQLException {
        List<PlayerData> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM players");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(player(rs));
        }
        return list;
    }

    // ---------------------------------------------------------------- credits

    public int creditScore(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT score FROM credits WHERE player_uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : cfg.creditStart;
            }
        }
    }

    public void adjustCredit(Connection con, String uuid, int delta, long now) throws SQLException {
        int current = creditScore(con, uuid);
        int next = Math.max(cfg.creditMin, Math.min(cfg.creditMax, current + delta));
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE credits SET score=?, updated_at=? WHERE player_uuid=?")) {
            ps.setInt(1, next);
            ps.setLong(2, now);
            ps.setString(3, uuid);
            if (ps.executeUpdate() == 0) ensureCredit(con, uuid, next, now);
        }
    }

    private void ensureCredit(Connection con, String uuid, int score, long now) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM credits WHERE player_uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO credits (player_uuid,score,updated_at) VALUES (?,?,?)")) {
            ps.setString(1, uuid);
            ps.setInt(2, score);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- accounts

    public Account getAccount(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM accounts WHERE player_uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? account(rs) : null;
            }
        }
    }

    public Account getAccountByName(Connection con, String name) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM accounts WHERE LOWER(name)=LOWER(?) ORDER BY opened_at DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? account(rs) : null;
            }
        }
    }

    private Account account(ResultSet rs) throws SQLException {
        return new Account(rs.getString("player_uuid"), rs.getString("name"),
                rs.getLong("balance"), rs.getString("pin_hash"), rs.getString("pin_salt"),
                rs.getString("security_question"), rs.getString("security_answer_hash"),
                rs.getString("security_answer_salt"), rs.getInt("fail_count"),
                rs.getLong("locked_until"), rs.getString("status"), rs.getLong("opened_at"));
    }

    public void insertAccount(Connection con, Account account) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO accounts (player_uuid,name,balance,pin_hash,pin_salt,security_question,security_answer_hash,security_answer_salt," +
                        "fail_count,locked_until,status,opened_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, account.uuid());
            ps.setString(2, account.name());
            ps.setLong(3, account.balance());
            ps.setString(4, account.pinHash());
            ps.setString(5, account.pinSalt());
            ps.setString(6, account.securityQuestion());
            ps.setString(7, account.securityAnswerHash());
            ps.setString(8, account.securityAnswerSalt());
            ps.setInt(9, account.failCount());
            ps.setLong(10, account.lockedUntil());
            ps.setString(11, account.status());
            ps.setLong(12, account.openedAt());
            ps.executeUpdate();
        }
    }

    public void updatePin(Connection con, String uuid, String hash, String salt) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE accounts SET pin_hash=?, pin_salt=?, fail_count=0, locked_until=0 WHERE player_uuid=?")) {
            ps.setString(1, hash);
            ps.setString(2, salt);
            ps.setString(3, uuid);
            ps.executeUpdate();
        }
    }

    public void updateSecurity(Connection con, String uuid, String question, String hash, String salt) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE accounts SET security_question=?, security_answer_hash=?, security_answer_salt=? WHERE player_uuid=?")) {
            ps.setString(1, question);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setString(4, uuid);
            ps.executeUpdate();
        }
    }

    public void pinFailure(Connection con, String uuid, int failCount, long lockedUntil) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE accounts SET fail_count=?, locked_until=? WHERE player_uuid=?")) {
            ps.setInt(1, failCount);
            ps.setLong(2, lockedUntil);
            ps.setString(3, uuid);
            ps.executeUpdate();
        }
    }

    public void clearPinFailures(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE accounts SET fail_count=0, locked_until=0 WHERE player_uuid=?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    public void setAccountStatus(Connection con, String uuid, String status) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE accounts SET status=? WHERE player_uuid=?")) {
            ps.setString(1, status);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public void deleteAccount(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM accounts WHERE player_uuid=?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    /** 条件扣款，绝不允许负余额。返回扣款后余额，扣款失败返回 null。 */
    public Long debitAccount(Connection con, String uuid, long amount) throws SQLException {
        Account account = getAccount(con, uuid);
        if (account == null || account.balance() < amount) return null;
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE accounts SET balance=balance-? WHERE player_uuid=? AND balance>=?")) {
            ps.setLong(1, amount);
            ps.setString(2, uuid);
            ps.setLong(3, amount);
            if (ps.executeUpdate() != 1) return null;
        }
        return account.balance() - amount;
    }

    public long creditAccount(Connection con, String uuid, long amount) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE accounts SET balance=balance+? WHERE player_uuid=?")) {
            ps.setLong(1, amount);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
        return balance(con, uuid);
    }

    public long balance(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT balance FROM accounts WHERE player_uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public List<Account> allAccounts(Connection con) throws SQLException {
        List<Account> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM accounts WHERE status='ACTIVE'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(account(rs));
        }
        return list;
    }

    public long totalBankDeposits(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(balance),0) FROM accounts WHERE status='ACTIVE'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // ---------------------------------------------------------------- shared accounts

    public void insertShared(Connection con, SharedAccount account) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO shared_accounts (shared_id,name,type,balance,created_by,created_at,status) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, account.id());
            ps.setString(2, account.name());
            ps.setString(3, account.type());
            ps.setLong(4, account.balance());
            ps.setString(5, account.createdBy());
            ps.setLong(6, account.createdAt());
            ps.setString(7, account.status());
            ps.executeUpdate();
        }
    }

    public SharedAccount getShared(Connection con, String id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM shared_accounts WHERE shared_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? shared(rs) : null;
            }
        }
    }

    private SharedAccount shared(ResultSet rs) throws SQLException {
        return new SharedAccount(rs.getString("shared_id"), rs.getString("name"), rs.getString("type"),
                rs.getLong("balance"), rs.getString("created_by"), rs.getLong("created_at"), rs.getString("status"));
    }

    public List<SharedAccount> sharedForPlayer(Connection con, String uuid) throws SQLException {
        List<SharedAccount> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT s.* FROM shared_accounts s JOIN account_members m ON s.shared_id=m.shared_id " +
                        "WHERE m.player_uuid=? AND s.status='ACTIVE'")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(shared(rs));
            }
        }
        return list;
    }

    public List<SharedAccount> allShared(Connection con) throws SQLException {
        List<SharedAccount> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM shared_accounts WHERE status='ACTIVE'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(shared(rs));
        }
        return list;
    }

    public void updateSharedBalance(Connection con, String id, long delta) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE shared_accounts SET balance=balance+? WHERE shared_id=? AND status='ACTIVE'")) {
            ps.setLong(1, delta);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    public Long debitShared(Connection con, String id, long amount) throws SQLException {
        SharedAccount account = getShared(con, id);
        if (account == null || account.balance() < amount) return null;
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE shared_accounts SET balance=balance-? WHERE shared_id=? AND balance>=? AND status='ACTIVE'")) {
            ps.setLong(1, amount);
            ps.setString(2, id);
            ps.setLong(3, amount);
            if (ps.executeUpdate() != 1) return null;
        }
        return account.balance() - amount;
    }

    public long sharedBalance(Connection con, String id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT balance FROM shared_accounts WHERE shared_id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long totalSharedDeposits(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(balance),0) FROM shared_accounts WHERE status='ACTIVE'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public void closeShared(Connection con, String id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE shared_accounts SET status='CLOSED' WHERE shared_id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    public void addMember(Connection con, String sharedId, String uuid, long now, long contribution) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO account_members (shared_id,player_uuid,joined_at,net_contribution) VALUES (?,?,?,?)")) {
            ps.setString(1, sharedId);
            ps.setString(2, uuid);
            ps.setLong(3, now);
            ps.setLong(4, contribution);
            ps.executeUpdate();
        }
    }

    public void removeMember(Connection con, String sharedId, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM account_members WHERE shared_id=? AND player_uuid=?")) {
            ps.setString(1, sharedId);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public SharedMember getMember(Connection con, String sharedId, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM account_members WHERE shared_id=? AND player_uuid=?")) {
            ps.setString(1, sharedId);
            ps.setString(2, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? member(rs) : null;
            }
        }
    }

    private SharedMember member(ResultSet rs) throws SQLException {
        return new SharedMember(rs.getString("shared_id"), rs.getString("player_uuid"),
                rs.getLong("joined_at"), rs.getLong("net_contribution"));
    }

    public List<SharedMember> members(Connection con, String sharedId) throws SQLException {
        List<SharedMember> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM account_members WHERE shared_id=? ORDER BY joined_at")) {
            ps.setString(1, sharedId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(member(rs));
            }
        }
        return list;
    }

    public List<SharedMember> memberships(Connection con, String uuid) throws SQLException {
        List<SharedMember> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM account_members WHERE player_uuid=? ORDER BY joined_at")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(member(rs));
            }
        }
        return list;
    }

    public int memberCount(Connection con, String sharedId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM account_members WHERE shared_id=?")) {
            ps.setString(1, sharedId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void setMemberContribution(Connection con, String sharedId, String uuid, long value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE account_members SET net_contribution=? WHERE shared_id=? AND player_uuid=?")) {
            ps.setLong(1, Math.max(0L, value));
            ps.setString(2, sharedId);
            ps.setString(3, uuid);
            ps.executeUpdate();
        }
    }

    public long sharedShare(Connection con, String uuid) throws SQLException {
        long share = 0;
        for (SharedAccount account : sharedForPlayer(con, uuid)) {
            int members = memberCount(con, account.id());
            if (members > 0) share += account.balance() / members;
        }
        return share;
    }

    // ---------------------------------------------------------------- cards

    public void insertCard(Connection con, Card card) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO cards (card_no,owner_uuid,issued_at,status) VALUES (?,?,?,?)")) {
            ps.setString(1, card.cardNo());
            ps.setString(2, card.ownerUuid());
            ps.setLong(3, card.issuedAt());
            ps.setString(4, card.status());
            ps.executeUpdate();
        }
    }

    public Card getCard(Connection con, String cardNo) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM cards WHERE card_no=?")) {
            ps.setString(1, cardNo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? card(rs) : null;
            }
        }
    }

    private Card card(ResultSet rs) throws SQLException {
        return new Card(rs.getString("card_no"), rs.getString("owner_uuid"),
                rs.getLong("issued_at"), rs.getString("status"));
    }

    public List<Card> cardsOf(Connection con, String uuid) throws SQLException {
        List<Card> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM cards WHERE owner_uuid=? ORDER BY issued_at")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(card(rs));
            }
        }
        return list;
    }

    public int activeCardCount(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM cards WHERE owner_uuid=? AND status='ACTIVE'")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void setCardStatus(Connection con, String cardNo, String status) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE cards SET status=? WHERE card_no=?")) {
            ps.setString(1, status);
            ps.setString(2, cardNo);
            ps.executeUpdate();
        }
    }

    public void setCardOwner(Connection con, String cardNo, String owner) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE cards SET owner_uuid=? WHERE card_no=?")) {
            ps.setString(1, owner);
            ps.setString(2, cardNo);
            ps.executeUpdate();
        }
    }

    public List<Card> allActiveCards(Connection con) throws SQLException {
        List<Card> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM cards WHERE status='ACTIVE'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(card(rs));
        }
        return list;
    }

    // ---------------------------------------------------------------- transactions

    public void insertTx(Connection con, Tx tx) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO transactions (tx_id,ts,type,from_uuid,to_uuid,amount,fee,from_before,from_after,to_before,to_after,reason) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            bindTx(ps, tx);
            ps.executeUpdate();
        }
    }

    public void insertTxIfAbsent(Connection con, Tx tx) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM transactions WHERE tx_id=?")) {
            ps.setString(1, tx.txId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        insertTx(con, tx);
    }

    private void bindTx(PreparedStatement ps, Tx tx) throws SQLException {
        ps.setString(1, tx.txId());
        ps.setLong(2, tx.ts());
        ps.setString(3, tx.type());
        ps.setString(4, tx.fromUuid());
        ps.setString(5, tx.toUuid());
        ps.setLong(6, tx.amount());
        ps.setLong(7, tx.fee());
        ps.setObject(8, tx.fromBefore());
        ps.setObject(9, tx.fromAfter());
        ps.setObject(10, tx.toBefore());
        ps.setObject(11, tx.toAfter());
        ps.setString(12, tx.reason());
    }

    public boolean txExists(Connection con, String txId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM transactions WHERE tx_id=?")) {
            ps.setString(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public List<Tx> recentTransactions(Connection con, String uuid, int limit) throws SQLException {
        List<Tx> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM transactions WHERE from_uuid=? OR to_uuid=? ORDER BY ts DESC LIMIT ?")) {
            ps.setString(1, uuid);
            ps.setString(2, uuid);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(tx(rs));
            }
        }
        return list;
    }

    private Tx tx(ResultSet rs) throws SQLException {
        return new Tx(rs.getString("tx_id"), rs.getLong("ts"), rs.getString("type"),
                rs.getString("from_uuid"), rs.getString("to_uuid"), rs.getLong("amount"),
                rs.getLong("fee"), nullableLong(rs, "from_before"), nullableLong(rs, "from_after"),
                nullableLong(rs, "to_before"), nullableLong(rs, "to_after"), rs.getString("reason"));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    // ---------------------------------------------------------------- daily stats

    public void addDailyReceived(Connection con, String uuid, String day, long amount) throws SQLException {
        updateDaily(con, uuid, day, "received", amount);
    }

    public void addDailyPaid(Connection con, String uuid, String day, long amount) throws SQLException {
        updateDaily(con, uuid, day, "paid", amount);
    }

    public void addDailyRemoteOut(Connection con, String uuid, String day, long amount) throws SQLException {
        updateDaily(con, uuid, day, "remote_out", amount);
    }

    public void addDailyTerminalOut(Connection con, String uuid, String day, long amount) throws SQLException {
        updateDaily(con, uuid, day, "terminal_out", amount);
    }

    public record DailyStats(long received, long paid, long remoteOut, long terminalOut) {
    }

    public DailyStats dailyStats(Connection con, String uuid, String day) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT received,paid,remote_out,terminal_out FROM daily_stats WHERE player_uuid=? AND day_key=?")) {
            ps.setString(1, uuid);
            ps.setString(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new DailyStats(0, 0, 0, 0);
                return new DailyStats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4));
            }
        }
    }

    private void updateDaily(Connection con, String uuid, String day, String column, long amount) throws SQLException {
        DailyStats stats = dailyStats(con, uuid, day);
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM daily_stats WHERE player_uuid=? AND day_key=?")) {
            ps.setString(1, uuid);
            ps.setString(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    try (PreparedStatement ins = con.prepareStatement(
                            "INSERT INTO daily_stats (player_uuid,day_key,received,paid,remote_out,terminal_out) VALUES (?,?,0,0,0,0)")) {
                        ins.setString(1, uuid);
                        ins.setString(2, day);
                        ins.executeUpdate();
                    }
                }
            }
        }
        long received = stats.received() + ("received".equals(column) ? amount : 0);
        long paid = stats.paid() + ("paid".equals(column) ? amount : 0);
        long remote = stats.remoteOut() + ("remote_out".equals(column) ? amount : 0);
        long terminal = stats.terminalOut() + ("terminal_out".equals(column) ? amount : 0);
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE daily_stats SET received=?,paid=?,remote_out=?,terminal_out=? WHERE player_uuid=? AND day_key=?")) {
            ps.setLong(1, received);
            ps.setLong(2, paid);
            ps.setLong(3, remote);
            ps.setLong(4, terminal);
            ps.setString(5, uuid);
            ps.setString(6, day);
            ps.executeUpdate();
        }
    }

    public void snapshotBalance(Connection con, String uuid, String day, long bank, long wallet, long shared, long loan) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM daily_balance WHERE player_uuid=? AND day_key=?")) {
            ps.setString(1, uuid);
            ps.setString(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement upd = con.prepareStatement(
                            "UPDATE daily_balance SET bank=?,wallet=?,shared_share=?,loan_principal=? WHERE player_uuid=? AND day_key=?")) {
                        upd.setLong(1, bank);
                        upd.setLong(2, wallet);
                        upd.setLong(3, shared);
                        upd.setLong(4, loan);
                        upd.setString(5, uuid);
                        upd.setString(6, day);
                        upd.executeUpdate();
                    }
                    return;
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO daily_balance (player_uuid,day_key,bank,wallet,shared_share,loan_principal) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, uuid);
            ps.setString(2, day);
            ps.setLong(3, bank);
            ps.setLong(4, wallet);
            ps.setLong(5, shared);
            ps.setLong(6, loan);
            ps.executeUpdate();
        }
    }

    public record BalanceSnapshot(String day, long bank, long wallet, long shared, long loan) {
    }

    public List<BalanceSnapshot> balancesSince(Connection con, String uuid, String sinceDay) throws SQLException {
        List<BalanceSnapshot> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT day_key,bank,wallet,shared_share,loan_principal FROM daily_balance " +
                        "WHERE player_uuid=? AND day_key>=? ORDER BY day_key")) {
            ps.setString(1, uuid);
            ps.setString(2, sinceDay);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new BalanceSnapshot(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
                }
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- tax

    public void insertTax(Connection con, TaxRecord record) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO tax_records (record_id,ts,player_uuid,tax_type,base,amount,paid,status,day_key,detail) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, record.id());
            ps.setLong(2, record.ts());
            ps.setString(3, record.uuid());
            ps.setString(4, record.taxType());
            ps.setLong(5, record.base());
            ps.setLong(6, record.amount());
            ps.setLong(7, record.paid());
            ps.setString(8, record.status());
            ps.setString(9, record.dayKey());
            ps.setString(10, record.detail());
            ps.executeUpdate();
        }
    }

    public long owedTax(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(amount-paid),0) FROM tax_records WHERE player_uuid=? AND status IN ('OWED','PARTIAL')")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long totalOwedTax(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(amount-paid),0) FROM tax_records WHERE status IN ('OWED','PARTIAL')");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public long oldestOwedSince(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT MIN(ts) FROM tax_records WHERE player_uuid=? AND status IN ('OWED','PARTIAL')")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                long value = rs.next() ? rs.getLong(1) : 0L;
                return rs.wasNull() ? 0L : value;
            }
        }
    }

    /** 用一笔收入优先补缴欠税，返回实际补缴额；必须在事务内调用。 */
    public long payArrearsFromIncome(Connection con, String uuid, long available) throws SQLException {
        if (available <= 0) return 0;
        long paidTotal = 0;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT record_id,amount,paid FROM tax_records WHERE player_uuid=? AND status IN ('OWED','PARTIAL') ORDER BY ts")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && available > 0) {
                    long remaining = rs.getLong(2) - rs.getLong(3);
                    if (remaining <= 0) continue;
                    long pay = Math.min(remaining, available);
                    long newPaid = rs.getLong(3) + pay;
                    String status = newPaid >= rs.getLong(2) ? "PAID" : "PARTIAL";
                    try (PreparedStatement upd = con.prepareStatement(
                            "UPDATE tax_records SET paid=?,status=? WHERE record_id=?")) {
                        upd.setLong(1, newPaid);
                        upd.setString(2, status);
                        upd.setString(3, rs.getString(1));
                        upd.executeUpdate();
                    }
                    paidTotal += pay;
                    available -= pay;
                }
            }
        }
        return paidTotal;
    }

    public List<TaxRecord> taxRecords(Connection con, String uuid, int limit) throws SQLException {
        List<TaxRecord> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM tax_records WHERE player_uuid=? ORDER BY ts DESC LIMIT ?")) {
            ps.setString(1, uuid);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new TaxRecord(rs.getString("record_id"), rs.getLong("ts"), rs.getString("player_uuid"),
                            rs.getString("tax_type"), rs.getLong("base"), rs.getLong("amount"), rs.getLong("paid"),
                            rs.getString("status"), rs.getString("day_key"), rs.getString("detail")));
                }
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- loans

    public void insertLoan(Connection con, Loan loan) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO loans (loan_id,player_uuid,principal,rate_ppm,term_days,started_at,due_at,outstanding,status," +
                        "disbursed_at,related_out,extensions,last_accrual_day,written_off,closed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, loan.id());
            ps.setString(2, loan.uuid());
            ps.setLong(3, loan.principal());
            ps.setInt(4, loan.ratePpm());
            ps.setInt(5, loan.termDays());
            ps.setLong(6, loan.startedAt());
            ps.setLong(7, loan.dueAt());
            ps.setLong(8, loan.outstanding());
            ps.setString(9, loan.status());
            ps.setLong(10, loan.disbursedAt());
            ps.setLong(11, loan.relatedOut());
            ps.setInt(12, loan.extensions());
            ps.setString(13, loan.lastAccrualDay());
            ps.setLong(14, loan.writtenOff());
            ps.setLong(15, loan.closedAt());
            ps.executeUpdate();
        }
    }

    public Loan loan(ResultSet rs) throws SQLException {
        return new Loan(rs.getString("loan_id"), rs.getString("player_uuid"), rs.getLong("principal"),
                rs.getInt("rate_ppm"), rs.getInt("term_days"), rs.getLong("started_at"), rs.getLong("due_at"),
                rs.getLong("outstanding"), rs.getString("status"), rs.getLong("disbursed_at"),
                rs.getLong("related_out"), rs.getInt("extensions"), rs.getString("last_accrual_day"),
                rs.getLong("written_off"), rs.getLong("closed_at"));
    }

    public Loan activeLoan(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM loans WHERE player_uuid=? AND status IN ('ACTIVE','OVERDUE') ORDER BY started_at DESC LIMIT 1")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? loan(rs) : null;
            }
        }
    }

    public List<Loan> openLoans(Connection con) throws SQLException {
        List<Loan> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM loans WHERE status IN ('ACTIVE','OVERDUE') ORDER BY due_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(loan(rs));
        }
        return list;
    }

    public List<Loan> loansOf(Connection con, String uuid) throws SQLException {
        List<Loan> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM loans WHERE player_uuid=? ORDER BY started_at DESC")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(loan(rs));
            }
        }
        return list;
    }

    public List<Loan> loansWithStatus(Connection con, String status) throws SQLException {
        List<Loan> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM loans WHERE status=? ORDER BY due_at")) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(loan(rs));
            }
        }
        return list;
    }

    public void updateLoan(Connection con, Loan loan) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE loans SET rate_ppm=?,due_at=?,outstanding=?,status=?,related_out=?,extensions=?,last_accrual_day=?,written_off=?,closed_at=? WHERE loan_id=?")) {
            ps.setInt(1, loan.ratePpm());
            ps.setLong(2, loan.dueAt());
            ps.setLong(3, loan.outstanding());
            ps.setString(4, loan.status());
            ps.setLong(5, loan.relatedOut());
            ps.setInt(6, loan.extensions());
            ps.setString(7, loan.lastAccrualDay());
            ps.setLong(8, loan.writtenOff());
            ps.setLong(9, loan.closedAt());
            ps.setString(10, loan.id());
            ps.executeUpdate();
        }
    }

    public long totalOutstanding(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(outstanding),0) FROM loans WHERE status IN ('ACTIVE','OVERDUE')");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public long outstandingFor(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(outstanding),0) FROM loans WHERE player_uuid=? AND status IN ('ACTIVE','OVERDUE')")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long disbursedToday(Connection con, String day, long dayStart, long dayEnd) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(principal),0) FROM loans WHERE disbursed_at>=? AND disbursed_at<?")) {
            ps.setLong(1, dayStart);
            ps.setLong(2, dayEnd);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public long writtenOffToday(Connection con, String dayKey) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(written_off),0) FROM loans WHERE last_accrual_day=? AND written_off>0")) {
            ps.setString(1, dayKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public List<Loan> overdueLoans(Connection con) throws SQLException {
        return loansWithStatus(con, "OVERDUE");
    }

    // ---------------------------------------------------------------- treasury / flags / logs

    public long treasury(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT balance FROM treasury WHERE treasury_id=?")) {
            ps.setString(1, TREASURY_ID);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public void creditTreasury(Connection con, long amount, long now) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE treasury SET balance=balance+?, updated_at=? WHERE treasury_id=?")) {
            ps.setLong(1, amount);
            ps.setLong(2, now);
            ps.setString(3, TREASURY_ID);
            ps.executeUpdate();
        }
    }

    public boolean debitTreasury(Connection con, long amount, long now) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE treasury SET balance=balance-?, updated_at=? WHERE treasury_id=? AND balance>=?")) {
            ps.setLong(1, amount);
            ps.setLong(2, now);
            ps.setString(3, TREASURY_ID);
            ps.setLong(4, amount);
            return ps.executeUpdate() == 1;
        }
    }

    public void addFlag(Connection con, String uuid, String type, String reason, long now, long expiresAt) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO risk_flags (flag_id,player_uuid,flag_type,reason,created_at,expires_at,active) VALUES (?,?,?,?,?,?,1)")) {
            ps.setString(1, newId());
            ps.setString(2, uuid);
            ps.setString(3, type);
            ps.setString(4, reason);
            ps.setLong(5, now);
            ps.setLong(6, expiresAt);
            ps.executeUpdate();
        }
    }

    public List<String> activeFlags(Connection con, String uuid, long now) throws SQLException {
        expireFlags(con, now);
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT flag_type FROM risk_flags WHERE player_uuid=? AND active=1")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString(1));
            }
        }
        return list;
    }

    public List<String> activeFlagsWithReason(Connection con, String uuid, long now) throws SQLException {
        expireFlags(con, now);
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT flag_type,reason FROM risk_flags WHERE player_uuid=? AND active=1")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString(1) + ": " + rs.getString(2));
            }
        }
        return list;
    }

    public void expireFlags(Connection con, long now) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE risk_flags SET active=0 WHERE active=1 AND expires_at>0 AND expires_at<=?")) {
            ps.setLong(1, now);
            ps.executeUpdate();
        }
    }

    public void clearFlags(Connection con, String uuid) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE risk_flags SET active=0 WHERE player_uuid=?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    public void deactivateFlags(Connection con, String uuid, String type) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE risk_flags SET active=0 WHERE player_uuid=? AND flag_type=?")) {
            ps.setString(1, uuid);
            ps.setString(2, type);
            ps.executeUpdate();
        }
    }

    public void audit(Connection con, String actor, String action, String detail, long now) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO audit_log (audit_id,ts,actor,action,detail) VALUES (?,?,?,?,?)")) {
            ps.setString(1, newId());
            ps.setLong(2, now);
            ps.setString(3, actor);
            ps.setString(4, action);
            ps.setString(5, detail);
            ps.executeUpdate();
        }
    }

    public void suspicious(Connection con, String uuid, String type, long amount, String detail, long now) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO suspicious_log (log_id,ts,player_uuid,log_type,amount,detail) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, newId());
            ps.setLong(2, now);
            ps.setString(3, uuid);
            ps.setString(4, type);
            ps.setLong(5, amount);
            ps.setString(6, detail);
            ps.executeUpdate();
        }
    }

    public long suspiciousCount(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM suspicious_log");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public List<String> auditLogs(Connection con, String actor, int limit) throws SQLException {
        List<String> lines = new ArrayList<>();
        String sql = actor == null
                ? "SELECT ts,actor,action,detail FROM audit_log ORDER BY ts DESC LIMIT ?"
                : "SELECT ts,actor,action,detail FROM audit_log WHERE actor=? ORDER BY ts DESC LIMIT ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            int index = 1;
            if (actor != null) ps.setString(index++, actor);
            ps.setInt(index, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(rs.getLong(1) + " " + rs.getString(2) + " " + rs.getString(3) + " " + rs.getString(4));
                }
            }
        }
        return lines;
    }

    public List<String> suspiciousLogs(Connection con, int limit) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT ts,player_uuid,log_type,amount,detail FROM suspicious_log ORDER BY ts DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lines.add(rs.getLong(1) + " " + rs.getString(2) + " " + rs.getString(3)
                            + " " + rs.getLong(4) + " " + rs.getString(5));
                }
            }
        }
        return lines;
    }

    // ---------------------------------------------------------------- auth lock

    public boolean isLocked(Connection con, String key, long now) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT locked_until FROM auth_lock WHERE lock_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > now;
            }
        }
    }

    public AuthResult registerFailure(Connection con, String key, long now, int limit, int windowMinutes, int lockMinutes) throws SQLException {
        long windowMs = Math.max(1, windowMinutes) * 60_000L;
        long lockMs = Math.max(1, lockMinutes) * 60_000L;
        long attempts;
        long windowStart;
        long lockedUntil;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT attempts,window_start,locked_until FROM auth_lock WHERE lock_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    attempts = rs.getLong(1);
                    windowStart = rs.getLong(2);
                    lockedUntil = rs.getLong(3);
                } else {
                    attempts = 0;
                    windowStart = now;
                    lockedUntil = 0;
                }
            }
        }
        if (lockedUntil > now) {
            return new AuthResult(false, false, (int) attempts, lockedUntil);
        }
        if (now - windowStart >= windowMs) {
            attempts = 0;
            windowStart = now;
            lockedUntil = 0;
        }
        attempts++;
        boolean justLocked = false;
        if (attempts >= limit) {
            lockedUntil = now + lockMs;
            justLocked = true;
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM auth_lock WHERE lock_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement upd = con.prepareStatement(
                            "UPDATE auth_lock SET attempts=?,window_start=?,locked_until=? WHERE lock_key=?")) {
                        upd.setLong(1, attempts);
                        upd.setLong(2, windowStart);
                        upd.setLong(3, lockedUntil);
                        upd.setString(4, key);
                        upd.executeUpdate();
                    }
                    return new AuthResult(!justLocked, justLocked, (int) attempts, lockedUntil);
                }
            }
        }
        try (PreparedStatement ins = con.prepareStatement(
                "INSERT INTO auth_lock (lock_key,attempts,window_start,locked_until) VALUES (?,?,?,?)")) {
            ins.setString(1, key);
            ins.setLong(2, attempts);
            ins.setLong(3, windowStart);
            ins.setLong(4, lockedUntil);
            ins.executeUpdate();
        }
        return new AuthResult(!justLocked, justLocked, (int) attempts, lockedUntil);
    }

    public void clearAuth(Connection con, String key) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM auth_lock WHERE lock_key=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- wallet ops

    public void insertWalletOp(Connection con, WalletOp op) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO wallet_ops (op_id,player_uuid,direction,target,amount,state,wallet_before,bank_before,bank_after,reason,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, op.opId());
            ps.setString(2, op.playerUuid());
            ps.setString(3, op.direction());
            ps.setString(4, op.target());
            ps.setLong(5, op.amount());
            ps.setString(6, op.state());
            ps.setLong(7, op.walletBefore());
            ps.setLong(8, op.bankBefore());
            ps.setLong(9, op.bankAfter());
            ps.setString(10, op.reason());
            ps.setLong(11, op.createdAt());
            ps.setLong(12, op.updatedAt());
            ps.executeUpdate();
        }
    }

    public void updateWalletOp(Connection con, String opId, String state, long updatedAt) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE wallet_ops SET state=?, updated_at=? WHERE op_id=?")) {
            ps.setString(1, state);
            ps.setLong(2, updatedAt);
            ps.setString(3, opId);
            ps.executeUpdate();
        }
    }

    public List<WalletOp> pendingWalletOps(Connection con) throws SQLException {
        List<WalletOp> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM wallet_ops WHERE state NOT IN ('DONE','FAILED') ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new WalletOp(rs.getString("op_id"), rs.getString("player_uuid"),
                        rs.getString("direction"), rs.getString("target"), rs.getLong("amount"),
                        rs.getString("state"), rs.getLong("wallet_before"), rs.getLong("bank_before"),
                        rs.getLong("bank_after"), rs.getString("reason"), rs.getLong("created_at"),
                        rs.getLong("updated_at")));
            }
        }
        return list;
    }

    public WalletOp walletOp(Connection con, String opId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT * FROM wallet_ops WHERE op_id=?")) {
            ps.setString(1, opId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new WalletOp(rs.getString("op_id"), rs.getString("player_uuid"),
                        rs.getString("direction"), rs.getString("target"), rs.getLong("amount"),
                        rs.getString("state"), rs.getLong("wallet_before"), rs.getLong("bank_before"),
                        rs.getLong("bank_after"), rs.getString("reason"), rs.getLong("created_at"),
                        rs.getLong("updated_at"));
            }
        }
    }

    // ---------------------------------------------------------------- terminals / meta

    public void addTerminal(Connection con, String key, String world, int x, int y, int z, String creator, long now) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT 1 FROM terminals WHERE block_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return;
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO terminals (block_key,world,x,y,z,created_by,created_at) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, key);
            ps.setString(2, world);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.setString(6, creator);
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    public void removeTerminal(Connection con, String key) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("DELETE FROM terminals WHERE block_key=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    public boolean isTerminal(Connection con, String key) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM terminals WHERE block_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public String meta(Connection con, String key) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT v FROM meta WHERE k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setMeta(Connection con, String key, String value) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("UPDATE meta SET v=? WHERE k=?")) {
            ps.setString(1, value);
            ps.setString(2, key);
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement ins = con.prepareStatement("INSERT INTO meta (k,v) VALUES (?,?)")) {
                    ins.setString(1, key);
                    ins.setString(2, value);
                    ins.executeUpdate();
                }
            }
        }
    }

    // ---------------------------------------------------------------- reporting helpers

    public long countAccounts(Connection con) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM accounts WHERE status='ACTIVE'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public long countLoans(Connection con, String status) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM loans WHERE status=?")) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public Map<String, Long> accountBalances(Connection con) throws SQLException {
        Map<String, Long> map = new LinkedHashMap<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT player_uuid,balance FROM accounts WHERE status='ACTIVE' ORDER BY balance DESC")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) map.put(rs.getString(1), rs.getLong(2));
            }
        }
        return map;
    }

    public long sumTopAssets(Connection con, int count) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COALESCE(SUM(balance),0) FROM (SELECT balance FROM accounts WHERE status='ACTIVE' ORDER BY balance DESC LIMIT ?) t")) {
            ps.setInt(1, count);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public Optional<PlayerData> playerByName(Connection con, String name) throws SQLException {
        return Optional.ofNullable(getPlayerByName(con, name));
    }
}
