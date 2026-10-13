package com.bimd.fiscalpipeline.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PipelineNode {
    API("api"),
    BROKER("broker"),
    RETRY("retry"),
    WORKER("worker"),
    VALIDATION("validation");

    @JsonValue
    private final String code;
}
