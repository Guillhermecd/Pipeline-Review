package com.bimd.fiscalpipeline.exception;

import org.springframework.http.HttpStatus;

public class UnsupportedDocumentTypeException extends BusinessException {

    public UnsupportedDocumentTypeException(String mimeType) {
        super(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Unsupported file type '%s'. Send an image or a PDF".formatted(mimeType));
    }
}
