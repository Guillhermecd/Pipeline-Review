package com.bimd.fiscalpipeline.exception;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import org.springframework.http.HttpStatus;

public class DocumentNotDeadLetteredException extends BusinessException {

    public DocumentNotDeadLetteredException(String documentCode, DocumentStatus status) {
        super(
                HttpStatus.CONFLICT,
                "Só documentos na DLQ podem ser reprocessados; %s não está na DLQ (status atual: %s)"
                        .formatted(documentCode, status));
    }
}
