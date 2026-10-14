package com.bimd.fiscalpipeline.service.pipeline;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.Extraction;
import com.bimd.fiscalpipeline.domain.model.ProcessedEvent;
import com.bimd.fiscalpipeline.messaging.EventEnvelope;
import com.bimd.fiscalpipeline.messaging.ProgressPayload;
import com.bimd.fiscalpipeline.messaging.ProgressStage;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.repository.ExtractionRepository;
import com.bimd.fiscalpipeline.repository.ProcessedEventRepository;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies what the worker reports to the document. Every message passes the same guard
 * (BACKEND.md §6.3) inside the transaction that changes the state; a message that fails it is
 * dropped and acknowledged, because delivering it again would fail the same way.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineInboundService {

    private static final String UNKNOWN_ERROR = "erro desconhecido";
    private static final BigDecimal MILLIS_PER_SECOND = BigDecimal.valueOf(1000);
    private static final int SECOND_ATTEMPT = 2;

    private final DocumentRepository documentRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtractionRepository extractionRepository;
    private final PipelineStateService stateService;
    private final PipelineEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public void onProgress(EventEnvelope envelope, ProgressPayload progress) {
        DocumentStatus target =
                switch (progress.stage()) {
                    case OCR -> DocumentStatus.OCR;
                    case EXTRACTING -> DocumentStatus.EXTRACTING;
                    case RETRY_WAIT -> DocumentStatus.RETRY_WAIT;
                };
        claim(envelope, target).ifPresent(document -> {
            document.setAttempts(envelope.attempt());
            if (progress.stage() == ProgressStage.RETRY_WAIT) {
                document.setLastError(progress.error());
                eventPublisher.publish(
                        ServiceName.BROKER,
                        document.getCode(),
                        "retry +%ss → %s"
                                .formatted(seconds(progress.retryInMs()), document.getBroker().getRetryQueueLabel()),
                        EventLevel.WAIT);
            } else if (progress.stage() == ProgressStage.OCR) {
                eventPublisher.publish(
                        ServiceName.WORKER, document.getCode(), consumeMessage(document, envelope), EventLevel.OK);
            }
        });
    }

    /** Stores the worker's answer as received. Validation and scoring pick it up from EXTRACTED. */
    @Transactional
    public void onExtracted(EventEnvelope envelope) {
        claim(envelope, DocumentStatus.EXTRACTED).ifPresent(document -> {
            Map<String, Object> payload = envelope.payload();
            document.setAttempts(envelope.attempt());
            document.setKind(text(payload.get("documentKind")));
            extractionRepository.save(newExtraction(document, envelope, payload));
            eventPublisher.publish(
                    ServiceName.API,
                    document.getCode(),
                    "consume " + document.getBroker().getOutputQueue(),
                    EventLevel.OK);
        });
    }

    /**
     * @param error the worker's {@code x-error} header
     * @param attempts the worker's {@code x-attempts} header, or null when it is missing
     */
    @Transactional
    public void onDeadLetter(EventEnvelope envelope, String error, Integer attempts) {
        claim(envelope, DocumentStatus.DEAD_LETTERED).ifPresent(document -> {
            String lastError = error == null || error.isBlank() ? UNKNOWN_ERROR : error;
            document.setAttempts(attempts != null ? attempts : envelope.attempt());
            document.setLastError(lastError);
            document.setReason(lastError);
            document.setFinishedAt(now());
            eventPublisher.publish(
                    ServiceName.BROKER,
                    document.getCode(),
                    "DLQ após %d tentativas · %s".formatted(document.getAttempts(), lastError),
                    EventLevel.ERROR);
        });
    }

    /**
     * The guard: the event was not applied before, it belongs to the document's current request,
     * and the state machine allows the move. The document row is locked first, so two messages
     * about the same document are judged one after the other.
     */
    private Optional<Document> claim(EventEnvelope envelope, DocumentStatus target) {
        Optional<Document> found = documentRepository.findByIdForUpdate(envelope.documentId());
        if (found.isEmpty()) {
            log.debug("Dropped {}: document {} does not exist", envelope.eventType(), envelope.documentId());
            return Optional.empty();
        }
        Document document = found.get();
        UUID eventId = envelope.eventId();
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Dropped {} of {}: event {} was already applied", envelope.eventType(), document.getCode(), eventId);
            return Optional.empty();
        }
        if (!document.getCurrentRequestId().equals(envelope.requestId())) {
            log.debug("Dropped {} of {}: it answers an older request", envelope.eventType(), document.getCode());
            return Optional.empty();
        }
        if (envelope.attempt() < document.getAttempts() || !stateService.canMove(document.getStatus(), target)) {
            log.debug(
                    "Dropped {} of {}: {} (attempt {}) cannot go to {} (attempt {})",
                    envelope.eventType(),
                    document.getCode(),
                    document.getStatus(),
                    document.getAttempts(),
                    target,
                    envelope.attempt());
            return Optional.empty();
        }
        processedEventRepository.save(new ProcessedEvent(eventId, now()));
        stateService.move(document, target);
        return Optional.of(document);
    }

    private Extraction newExtraction(Document document, EventEnvelope envelope, Map<String, Object> payload) {
        Extraction extraction = new Extraction();
        extraction.setId(UUID.randomUUID());
        extraction.setDocumentId(document.getId());
        extraction.setRequestId(envelope.requestId());
        extraction.setRawResult(payload);
        extraction.setOcrEngine(text(payload.get("ocrEngine")));
        extraction.setLlmModel(text(payload.get("llmModel")));
        if (payload.get("tokens") instanceof Map<?, ?> tokens) {
            extraction.setInputTokens(count(tokens.get("input")));
            extraction.setOutputTokens(count(tokens.get("output")));
        }
        extraction.setCreatedAt(now());
        return extraction;
    }

    /** {@code consume {input queue}}, with {@code · tentativa n} from the second attempt on (§9.2). */
    private String consumeMessage(Document document, EventEnvelope envelope) {
        String message = "consume " + document.getBroker().getInputQueue();
        return envelope.attempt() >= SECOND_ATTEMPT ? message + " · tentativa " + envelope.attempt() : message;
    }

    private String seconds(Long millis) {
        long safeMillis = millis == null ? 0 : millis;
        return BigDecimal.valueOf(safeMillis).divide(MILLIS_PER_SECOND).stripTrailingZeros().toPlainString();
    }

    private String text(Object value) {
        return value instanceof String string ? string : null;
    }

    private Integer count(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String now() {
        return IsoDates.format(clock.instant());
    }
}
