package com.bimd.fiscalpipeline.service.document;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.exception.DocumentNotFoundException;
import com.bimd.fiscalpipeline.exception.DocumentNotDeadLetteredException;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.service.outbox.OutboxWriter;
import com.bimd.fiscalpipeline.service.pipeline.PipelineStateService;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends a dead-lettered document through the pipeline again (BACKEND.md §5.6). Same document,
 * idempotency key, trace and broker; a new {@code requestId}, so an answer still on its way from
 * the failed run no longer matches and is dropped.
 */
@Service
@RequiredArgsConstructor
public class ReprocessService {

    private final DocumentRepository documentRepository;
    private final PipelineStateService stateService;
    private final OutboxWriter outboxWriter;
    private final PipelineEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public Document reprocess(UUID documentId) {
        Document document = documentRepository
                .findByIdForUpdate(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        // RECEIVED also goes to QUEUED (the relay does it), so the state machine alone is not enough here.
        if (document.getStatus() != DocumentStatus.DEAD_LETTERED) {
            throw new DocumentNotDeadLetteredException(document.getCode(), document.getStatus());
        }
        stateService.move(document, DocumentStatus.QUEUED);
        document.setCurrentRequestId(UUID.randomUUID());
        document.setAttempts(0);
        document.setLastError(null);
        document.setReason(null);
        document.setFinishedAt(null);
        document.setProcessedAt(null);

        outboxWriter.enqueueReceived(document, IsoDates.format(clock.instant()));
        eventPublisher.publish(
                ServiceName.API,
                document.getCode(),
                "POST /documentos/%s/reprocessar · mesma idempotency_key".formatted(document.getCode()),
                EventLevel.OK);
        return document;
    }
}
