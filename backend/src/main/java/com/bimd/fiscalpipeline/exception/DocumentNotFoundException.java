package com.bimd.fiscalpipeline.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

public class DocumentNotFoundException extends BusinessException {

    public DocumentNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "Documento %s não encontrado".formatted(id));
    }
}
