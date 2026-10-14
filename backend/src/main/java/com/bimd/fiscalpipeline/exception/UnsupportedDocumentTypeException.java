package com.bimd.fiscalpipeline.exception;

import org.springframework.http.HttpStatus;

public class UnsupportedDocumentTypeException extends BusinessException {

    public UnsupportedDocumentTypeException(String mimeType) {
        super(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Tipo de arquivo '%s' não suportado. Envie uma imagem ou um PDF".formatted(mimeType));
    }
}
