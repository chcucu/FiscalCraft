package com.fiscalcraft.bukkit;

import java.util.Locale;
import java.util.Set;

/**
 * 银行终端类型：不同类型只开放对应业务，避免玩家在任意终端办理全部业务。
 * 同时作为全息标识的文案与配色来源。
 */
public enum TerminalType {
    FULL("full", "综合终端", "全部银行业务", 0xFFD24A, Set.of()),
    ACCOUNT("account", "开户终端", "开户 / 销户 / 密码重置 / 银行卡 / 等级", 0x55D6A5,
            Set.of("open", "close", "reset", "card", "upgrade", "balance", "history", "tax")),
    CASH("cash", "现金终端", "存款 / 取款 / 余额 / 流水 / 税务", 0xF4C430,
            Set.of("deposit", "withdraw", "balance", "history", "tax")),
    TRANSFER("transfer", "转账终端", "银行转账 / 余额 / 流水", 0x58B7FF,
            Set.of("transfer", "balance", "history", "tax")),
    LOAN("loan", "贷款终端", "贷款申请 / 还贷 / 额度查询", 0xFF7A6B,
            Set.of("loan", "repay", "balance", "history", "tax")),
    SHARED("shared", "共享终端", "共享账户全部业务", 0xD48BFF,
            Set.of("shared", "balance", "history", "tax"));

    private final String key;
    private final String label;
    private final String description;
    private final int color;
    private final Set<String> allowed;

    TerminalType(String key, String label, String description, int color, Set<String> allowed) {
        this.key = key;
        this.label = label;
        this.description = description;
        this.color = color;
        this.allowed = allowed;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public int color() {
        return color;
    }

    public boolean allows(String operation) {
        return this == FULL || allowed.contains(operation);
    }

    public static TerminalType fromKey(String raw) {
        if (raw == null || raw.isBlank()) return FULL;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (TerminalType type : values()) {
            if (type.key.equals(value) || type.label.equals(raw.trim())) return type;
        }
        return null;
    }

    public static String keys() {
        return "full|account|cash|transfer|loan|shared";
    }
}
