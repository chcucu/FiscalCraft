package com.fiscalcraft.security;

import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;

public final class PasswordHasher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int COST = 12;

    private PasswordHasher() {
    }

    public static String salt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static String hash(String raw, String salt) {
        return BCrypt.hashpw(salt + "$" + raw, BCrypt.gensalt(COST));
    }

    public static boolean verify(String raw, String salt, String hash) {
        if (raw == null || salt == null || hash == null) return false;
        try {
            return BCrypt.checkpw(salt + "$" + raw, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean validPin(String pin, int length) {
        if (pin == null || pin.length() != length) return false;
        for (int i = 0; i < pin.length(); i++) {
            if (!Character.isDigit(pin.charAt(i))) return false;
        }
        return true;
    }
}
