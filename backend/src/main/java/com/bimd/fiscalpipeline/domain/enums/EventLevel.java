package com.bimd.fiscalpipeline.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventLevel {
    OK("ok"),
    WARN("warn"),
    ERROR("error"),
    WAIT("wait");

    @JsonValue
    private final String code;
}
