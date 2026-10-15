package com.bimd.fiscalpipeline.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** How the Traces screen draws a span; {@code wait} is time parked in a retry queue. */
@Getter
@RequiredArgsConstructor
public enum SpanStatus {
    OK("ok"),
    ERROR("error"),
    WAIT("wait");

    @JsonValue
    private final String code;
}
