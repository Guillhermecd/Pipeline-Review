package com.bimd.fiscalpipeline.service.outbox;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.OutboxEvent;
import com.bimd.fiscalpipeline.messaging.EventEnvelope;
import com.bimd.fiscalpipeline.repository.OutboxEventRepository;
import com.bimd.fiscalpipeline.util.TraceIds;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues the message that sends a document to the worker. It joins the caller's transaction, so
 * the message exists if and only if the change that needs it was committed.
 */
@Service
@RequiredArgsConstructor
public class OutboxWriter {

    private static final int FIRST_ATTEMPT = 1;
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /** Uses the document's current {@code requestId} and broker; the attempt count starts over. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueReceived(Document document, String now) {
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
        // The relay publishes outside the request; this keeps its span under the document's trace.
        event.setTraceparent(TraceIds.traceparent(document.getTraceId(), TraceIds.newSpanId()));
        event.setCreatedAt(now);
        outboxEventRepository.save(event);
    }
}
