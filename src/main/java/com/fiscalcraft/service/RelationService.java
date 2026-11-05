package com.fiscalcraft.service;

import com.fiscalcraft.config.FiscalConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关联识别：近 7 天同 IP（同 IP 组过大时弃用，避免 NAT 误合并）+ 近 7 天资金往来边（次数达标）。
 * 关联只作为观察标记，不封号；边随时间窗口自然解除。
 */
public final class RelationService {
    private final FiscalConfig cfg;
    private final Store store;
    private final com.fiscalcraft.util.TimeSource time;
    private final int maxIpGroup = 4;

    public RelationService(FiscalConfig cfg, Store store, com.fiscalcraft.util.TimeSource time) {
        this.cfg = cfg;
        this.store = store;
        this.time = time;
    }

    public Set<String> cluster(Connection con, String uuid) throws SQLException {
        Set<String> result = new HashSet<>();
        result.add(uuid);
        if (!cfg.relationEnabled) return result;
        long now = time.now();
        long ipCutoff = now - cfg.relationIpWindowDays * 86_400_000L;
        long edgeCutoff = now - cfg.relationEdgeWindowDays * 86_400_000L;

        Map<String, String> parent = new HashMap<>();
        Map<String, String> ips = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT player_uuid,last_ip FROM players WHERE last_ip_at>=? AND last_ip IS NOT NULL")) {
            ps.setLong(1, ipCutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString(1);
                    parent.put(id, id);
                    ips.put(id, rs.getString(2));
                }
            }
        }
        parent.putIfAbsent(uuid, uuid);

        Map<String, List<String>> byIp = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : ips.entrySet()) {
            byIp.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        for (List<String> group : byIp.values()) {
            // 同一 IP 组过大时视为 NAT，不用 IP 强行合并
            if (group.size() > maxIpGroup) continue;
            for (int i = 1; i < group.size(); i++) union(parent, group.get(0), group.get(i));
        }

        try (PreparedStatement ps = con.prepareStatement(
                "SELECT from_uuid,to_uuid,COUNT(*) c FROM transactions " +
                        "WHERE ts>=? AND from_uuid IS NOT NULL AND to_uuid IS NOT NULL " +
                        "AND from_uuid<>to_uuid AND type IN ('TRANSFER','REMOTE') " +
                        "GROUP BY from_uuid,to_uuid")) {
            ps.setLong(1, edgeCutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt(3) < cfg.relationEdgeMinCount) continue;
                    String a = rs.getString(1);
                    String b = rs.getString(2);
                    parent.putIfAbsent(a, a);
                    parent.putIfAbsent(b, b);
                    union(parent, a, b);
                }
            }
        }

        String root = find(parent, uuid);
        for (String id : parent.keySet()) {
            if (find(parent, id).equals(root)) result.add(id);
        }
        return result;
    }

    public boolean related(Connection con, String a, String b) throws SQLException {
        return cluster(con, a).contains(b);
    }

    public boolean clusterHasOverdueLoan(Connection con, String uuid) throws SQLException {
        for (String member : cluster(con, uuid)) {
            var loan = store.activeLoan(con, member);
            if (loan != null && "OVERDUE".equals(loan.status())) return true;
        }
        return false;
    }

    private static String find(Map<String, String> parent, String value) {
        String p = parent.getOrDefault(value, value);
        if (!p.equals(value)) {
            String root = find(parent, p);
            parent.put(value, root);
            return root;
        }
        return value;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(rb, ra);
    }
}
