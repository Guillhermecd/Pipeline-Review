package com.bimd.fiscalpipeline.domain.dto;

import java.util.UUID;

/** Body of the 409 answered when the same file is uploaded again. */
public record DuplicateDocumentResponse(String message, String idempotencyKey, UUID documentId, String code) {
}
