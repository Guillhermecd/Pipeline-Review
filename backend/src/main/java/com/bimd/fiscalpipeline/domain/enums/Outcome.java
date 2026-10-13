package com.bimd.fiscalpipeline.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Outcome {
    AUTO("auto"),
    REVIEW("review"),
    DLQ("dlq");

    @JsonValue
    private final String code;
}
