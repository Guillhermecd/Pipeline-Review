package com.bimd.fiscalpipeline.messaging;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import java.util.Map;
import java.util.UUID;

/** Message body shared with the worker (contracts/events/envelope.schema.json). */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID documentId,
        String documentCode,
        String idempotencyKey,
        UUID requestId,
        int attempt,
        BrokerKind broker,
        String occurredAt,
        Map<String, Object> payload) {

    public static final int SCHEMA_VERSION = 1;
    public static final String DOCUMENT_RECEIVED = "documento.recebido";
    public static final String DOCUMENT_PROGRESS = "documento.progresso";
    public static final String DOCUMENT_EXTRACTED = "documento.extraido";
}
