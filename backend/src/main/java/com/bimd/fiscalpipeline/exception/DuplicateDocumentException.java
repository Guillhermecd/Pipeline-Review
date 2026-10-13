package com.bimd.fiscalpipeline.exception;

import java.util.UUID;
import lombok.Getter;

/** The uploaded file's SHA-256 already belongs to a document. Nothing new is created. */
@Getter
public class DuplicateDocumentException extends RuntimeException {

    private final String idempotencyKey;
    private final UUID documentId;
    private final String code;

    public DuplicateDocumentException(String idempotencyKey, UUID documentId, String code) {
        super("idempotency_key %s já existe".formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
        this.documentId = documentId;
        this.code = code;
    }
}
