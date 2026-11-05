package com.fiscalcraft.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 金额统一用 long 分币。任何浮点只允许出现在配置/Vault 边界，并立即取整。 */
public final class Money {
    private Money() {
    }

    public static long fromVault(double amount) {
        return BigDecimal.valueOf(amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static double toVault(long cents) {
        return BigDecimal.valueOf(cents, 2).doubleValue();
    }

    public static long parse(String raw) throws IllegalArgumentException {
        if (raw == null) throw new IllegalArgumentException("金额为空");
        String cleaned = raw.trim().replace(",", "").replace("_", "");
        if (cleaned.startsWith("$")) cleaned = cleaned.substring(1);
        if (cleaned.isEmpty()) throw new IllegalArgumentException("金额为空");
        BigDecimal value;
        try {
            value = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("金额格式错误");
        }
        if (value.scale() > 2) throw new IllegalArgumentException("金额最多两位小数");
        if (value.signum() <= 0) throw new IllegalArgumentException("金额必须大于 0");
        try {
            return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("金额过大");
        }
    }

    public static long dollars(int dollars) {
        return Math.multiplyExact((long) dollars, 100L);
    }

    public static long dollars(long dollars) {
        return Math.multiplyExact(dollars, 100L);
    }

    public static long percentToPpm(double percent) {
        return BigDecimal.valueOf(percent).multiply(BigDecimal.valueOf(10_000))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** ppm 为百万分之一，0.025% = 250 ppm。向下取整，避免向玩家多收/多付。 */
    public static long permille(long base, long ppm) {
        if (base <= 0 || ppm <= 0) return 0;
        return Math.multiplyExact(base, ppm) / 1_000_000L;
    }

    public static String format(long cents, String symbol) {
        BigDecimal value = BigDecimal.valueOf(cents, 2);
        return symbol + value.setScale(2, RoundingMode.DOWN).toPlainString();
    }

    public static String formatPlain(long cents) {
        return BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.DOWN).toPlainString();
    }
}
