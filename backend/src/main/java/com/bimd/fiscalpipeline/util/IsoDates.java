package com.bimd.fiscalpipeline.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** ISO 8601 UTC with fixed millisecond precision ({@code 2026-10-13T13:20:00.000Z}), as stored and sent. */
public final class IsoDates {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private IsoDates() {
    }

    public static String format(Instant instant) {
        return FORMATTER.format(instant);
    }

    public static Instant parse(String isoDate) {
        return Instant.parse(isoDate);
    }

    public static long millisBetween(String start, Instant end) {
        return Duration.between(parse(start), end).toMillis();
    }
}
