package com.bimd.fiscalpipeline.exception;

import org.springframework.http.HttpStatus;

public class StorageUnavailableException extends BusinessException {

    public StorageUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "File storage is unavailable. Try again shortly");
        initCause(cause);
    }
}
