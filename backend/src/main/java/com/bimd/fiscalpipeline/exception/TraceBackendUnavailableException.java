package com.bimd.fiscalpipeline.exception;

import org.springframework.http.HttpStatus;

public class TraceBackendUnavailableException extends BusinessException {

    public TraceBackendUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "Jaeger indisponível: não foi possível consultar o trace");
        initCause(cause);
    }
}
