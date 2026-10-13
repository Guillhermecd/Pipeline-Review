package com.bimd.fiscalpipeline.util;

import java.security.SecureRandom;
import java.util.HexFormat;

/** W3C Trace Context identifiers. */
public final class TraceIds {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TRACE_ID_BYTES = 16;
    private static final int SPAN_ID_BYTES = 8;

    private TraceIds() {
    }

    public static String newTraceId() {
        return randomHex(TRACE_ID_BYTES);
    }

    public static String newSpanId() {
        return randomHex(SPAN_ID_BYTES);
    }

    /** {@code 00-<trace id>-<span id>-01}: version 00, sampled. */
    public static String traceparent(String traceId, String spanId) {
        return "00-%s-%s-01".formatted(traceId, spanId);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }
}
