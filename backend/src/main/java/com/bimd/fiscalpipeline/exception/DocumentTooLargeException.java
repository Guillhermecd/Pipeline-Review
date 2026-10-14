package com.bimd.fiscalpipeline.exception;

import org.springframework.http.HttpStatus;

public class DocumentTooLargeException extends BusinessException {

    public DocumentTooLargeException(int maxSizeMb) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "O arquivo excede o limite de %d MB".formatted(maxSizeMb));
    }
}
