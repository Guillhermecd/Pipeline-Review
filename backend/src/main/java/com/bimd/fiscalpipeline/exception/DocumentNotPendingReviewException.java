package com.bimd.fiscalpipeline.exception;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import org.springframework.http.HttpStatus;

public class DocumentNotPendingReviewException extends BusinessException {

    public DocumentNotPendingReviewException(String documentCode, DocumentStatus status) {
        super(HttpStatus.CONFLICT, "O documento %s não está em revisão (status atual: %s)".formatted(documentCode, status));
    }
}
