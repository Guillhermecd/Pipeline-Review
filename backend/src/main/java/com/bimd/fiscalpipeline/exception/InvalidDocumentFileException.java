package com.bimd.fiscalpipeline.exception;

import org.springframework.http.HttpStatus;

public class InvalidDocumentFileException extends BusinessException {

    public InvalidDocumentFileException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
