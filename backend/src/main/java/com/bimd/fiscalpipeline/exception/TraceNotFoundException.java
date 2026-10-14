package com.bimd.fiscalpipeline.exception;

import org.springframework.http.HttpStatus;

public class TraceNotFoundException extends BusinessException {

    public TraceNotFoundException(String traceId) {
        super(
                HttpStatus.NOT_FOUND,
                "Trace %s ainda não disponível. Os spans chegam ao Jaeger alguns segundos depois".formatted(traceId));
    }
}
