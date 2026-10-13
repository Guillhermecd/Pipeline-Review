package com.bimd.fiscalpipeline.exception;

import java.util.UUID;
import lombok.Getter;

/** The uploaded file's SHA-256 already belongs to a document. Nothing new is created. */
@Getter
public class DuplicateDocumentException extends RuntimeException {

    private final String idempotencyKey;
    private final UUID documentId;
    private final String code;

    /** The message is shown as is in the Upload log, so it carries the short hash like the mock does. */
    public DuplicateDocumentException(String idempotencyKey, String shortHash, UUID documentId, String code) {
        super("idempotency_key sha256:%s… já existe".formatted(shortHash));
        this.idempotencyKey = idempotencyKey;
        this.documentId = documentId;
        this.code = code;
    }
}
