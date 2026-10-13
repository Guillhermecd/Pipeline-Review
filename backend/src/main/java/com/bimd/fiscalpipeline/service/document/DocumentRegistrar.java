package com.bimd.fiscalpipeline.service.document;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.dto.DocumentUpload;
import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.OutboxEvent;
import com.bimd.fiscalpipeline.messaging.EventEnvelope;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.repository.OutboxEventRepository;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.util.IsoDates;
import com.bimd.fiscalpipeline.util.TraceIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an accepted upload: the document, its outbox message and the log event commit
 * together or not at all, so a document never exists without the message that moves it on.
 */
@Service
@RequiredArgsConstructor
public class DocumentRegistrar {

    private static final String CODE_PREFIX = "DOC-";
    private static final String UPLOAD_SPAN_NAME = "POST /api/v1/documentos";
    private static final int FIRST_ATTEMPT = 1;
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final DocumentRepository documentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PipelineEventPublisher eventPublisher;
    private final PipelineProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public Document register(UUID userId, DocumentUpload upload, String idempotencyKey, String fileKey) {
        String now = IsoDates.format(clock.instant());
        Document document = documentRepository.saveAndFlush(
                newDocument(userId, upload, idempotencyKey, fileKey, now));
        outboxEventRepository.save(receivedEvent(document, now));
        eventPublisher.publish(ServiceName.API, document.getCode(), UPLOAD_SPAN_NAME, EventLevel.OK);
        return document;
    }

    private Document newDocument(
            UUID userId, DocumentUpload upload, String idempotencyKey, String fileKey, String now) {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setCode(CODE_PREFIX + documentRepository.nextCodeNumber());
        document.setUserId(userId);
        document.setFileName(upload.fileName());
        document.setFileKey(fileKey);
        document.setMimeType(upload.mimeType());
        document.setIdempotencyKey(idempotencyKey);
        document.setBroker(properties.brokerDefault());
        document.setStatus(DocumentStatus.RECEIVED);
        document.setTraceId(TraceIds.newTraceId());
        document.setCurrentRequestId(UUID.randomUUID());
        document.setAttempts(0);
        document.setReceivedAt(now);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return document;
    }

    private OutboxEvent receivedEvent(Document document, String now) {
        BrokerKind broker = document.getBroker();
        UUID eventId = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(
                eventId,
                EventEnvelope.DOCUMENT_RECEIVED,
                EventEnvelope.SCHEMA_VERSION,
                document.getId(),
                document.getCode(),
                document.getIdempotencyKey(),
                document.getCurrentRequestId(),
                FIRST_ATTEMPT,
                broker,
                now,
                Map.of(
                        "fileKey", document.getFileKey(),
                        "mimeType", document.getMimeType(),
                        "fileName", document.getFileName()));

        OutboxEvent event = new OutboxEvent();
        event.setId(eventId);
        event.setDocumentId(document.getId());
        event.setEventType(envelope.eventType());
        event.setBroker(broker);
        event.setDestination(broker.getInputQueue());
        event.setPayload(objectMapper.convertValue(envelope, JSON_OBJECT));
        // The relay publishes outside the request; this keeps its span under the upload's trace.
        event.setTraceparent(TraceIds.traceparent(document.getTraceId(), TraceIds.newSpanId()));
        event.setCreatedAt(now);
        return event;
    }
}
