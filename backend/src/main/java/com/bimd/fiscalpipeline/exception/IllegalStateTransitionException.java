package com.bimd.fiscalpipeline.exception;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import org.springframework.http.HttpStatus;

public class IllegalStateTransitionException extends BusinessException {

    public IllegalStateTransitionException(String documentCode, DocumentStatus from, DocumentStatus to) {
        super(HttpStatus.CONFLICT, "O documento %s não pode passar de %s para %s".formatted(documentCode, from, to));
    }
}
