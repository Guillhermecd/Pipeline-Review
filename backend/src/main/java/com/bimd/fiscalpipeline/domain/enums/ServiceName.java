package com.bimd.fiscalpipeline.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ServiceName {
    API("api"),
    BROKER("broker"),
    WORKER("worker"),
    DB("db");

    @JsonValue
    private final String code;
}
