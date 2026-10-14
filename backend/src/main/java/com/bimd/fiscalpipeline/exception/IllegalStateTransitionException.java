package com.bimd.fiscalpipeline.exception;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import org.springframework.http.HttpStatus;

public class IllegalStateTransitionException extends BusinessException {

    public IllegalStateTransitionException(String documentCode, DocumentStatus from, DocumentStatus to) {
        super(HttpStatus.CONFLICT, "Document %s cannot go from %s to %s".formatted(documentCode, from, to));
    }
}
