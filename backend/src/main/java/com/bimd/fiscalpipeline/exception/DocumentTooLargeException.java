package com.bimd.fiscalpipeline.exception;

import org.springframework.http.HttpStatus;

public class DocumentTooLargeException extends BusinessException {

    public DocumentTooLargeException(int maxSizeMb) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds the %d MB limit".formatted(maxSizeMb));
    }
}
