package com.bimd.fiscalpipeline.exception;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import org.springframework.http.HttpStatus;

public class DocumentNotPendingReviewException extends BusinessException {

    public DocumentNotPendingReviewException(String documentCode, DocumentStatus status) {
        super(HttpStatus.CONFLICT, "Document %s is %s, not PENDING_REVIEW".formatted(documentCode, status));
    }
}
