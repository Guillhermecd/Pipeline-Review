package com.bimd.fiscalpipeline.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ReviewDecision {
    APPROVED("approved"),
    REJECTED("rejected");

    @JsonValue
    private final String code;
}
