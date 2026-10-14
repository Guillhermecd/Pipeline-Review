package com.bimd.fiscalpipeline.messaging;

import com.bimd.fiscalpipeline.service.pipeline.PipelineInboundService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Entry point for the worker's messages, whichever broker they came from: reads the envelope and
 * calls the service, like a controller.
 *
 * <p>A message that cannot be read is logged and dropped. Throwing would make the broker deliver
 * it again, and it would fail the same way every time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineMessageDispatcher {

    private final PipelineInboundService inboundService;
    private final ObjectMapper objectMapper;

    public void progress(String body) {
        read(body, EventEnvelope.DOCUMENT_PROGRESS).ifPresent(envelope -> {
            ProgressPayload payload;
            try {
                payload = objectMapper.convertValue(envelope.payload(), ProgressPayload.class);
            } catch (IllegalArgumentException e) {
                log.warn("Dropped a progress message of {} with an unreadable payload", envelope.documentCode());
                return;
            }
            if (payload.stage() == null) {
                log.warn("Dropped a progress message of {} without a stage", envelope.documentCode());
                return;
            }
            inboundService.onProgress(envelope, payload);
        });
    }

    public void extracted(String body) {
        read(body, EventEnvelope.DOCUMENT_EXTRACTED).ifPresent(inboundService::onExtracted);
    }

    /** The body is the input message as the worker received it; the reason comes in the headers. */
    public void deadLetter(String body, Map<String, String> headers) {
        read(body, null)
                .ifPresent(envelope -> inboundService.onDeadLetter(
                        envelope, headers.get(Topology.ERROR_HEADER), attempts(headers)));
    }

    private Optional<EventEnvelope> read(String body, String expectedType) {
        EventEnvelope envelope;
        try {
            // PostgreSQL stores neither a NUL byte in text nor \u0000 in jsonb. Left in, the insert would
            // fail on every delivery of the same message.
            envelope = objectMapper.readValue(body.replace("\\u0000", "").replace("\u0000", ""), EventEnvelope.class);
        } catch (JsonProcessingException e) {
            log.warn("Dropped a message that is not a valid envelope: {}", e.getOriginalMessage());
            return Optional.empty();
        }
        if (envelope == null
                || envelope.eventId() == null
                || envelope.documentId() == null
                || envelope.requestId() == null
                || envelope.payload() == null) {
            log.warn("Dropped a message whose envelope lacks eventId, documentId, requestId or payload");
            return Optional.empty();
        }
        if (expectedType != null && !expectedType.equals(envelope.eventType())) {
            log.warn("Dropped {} of {}: expected {}", envelope.eventType(), envelope.documentCode(), expectedType);
            return Optional.empty();
        }
        return Optional.of(envelope);
    }

    private Integer attempts(Map<String, String> headers) {
        String value = headers.get(Topology.ATTEMPTS_HEADER);
        try {
            return value == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
