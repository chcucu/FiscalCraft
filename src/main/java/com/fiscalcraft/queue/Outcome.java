package com.fiscalcraft.queue;

public record Outcome(boolean ok, String message, String errorCode) {
    public static Outcome ok(String message) {
        return new Outcome(true, message, null);
    }

    public static Outcome fail(String message) {
        return new Outcome(false, message, "FAIL");
    }

    public static Outcome fail(String message, String code) {
        return new Outcome(false, message, code);
    }
}
