package com.fiscalcraft.util;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Supplier;

public final class TimeSource {
    private final Supplier<Instant> supplier;
    private final ZoneId zone;

    public TimeSource(ZoneId zone) {
        this(zone, Instant::now);
    }

    public TimeSource(ZoneId zone, Supplier<Instant> supplier) {
        this.zone = zone;
        this.supplier = supplier;
    }

    public static TimeSource fixed(ZoneId zone, Instant instant) {
        return new TimeSource(zone, () -> instant);
    }

    public long now() {
        return supplier.get().toEpochMilli();
    }

    public LocalDate today() {
        return LocalDate.now(Clock.fixed(supplier.get(), zone));
    }

    public LocalDateTime nowLocal() {
        return LocalDateTime.now(Clock.fixed(supplier.get(), zone));
    }

    public ZoneId zone() {
        return zone;
    }

    public String dayKey() {
        return today().toString();
    }

    public String dayKey(LocalDate date) {
        return date.toString();
    }
}
