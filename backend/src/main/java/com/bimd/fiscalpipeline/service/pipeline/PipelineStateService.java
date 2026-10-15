package com.bimd.fiscalpipeline.service.pipeline;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.exception.IllegalStateTransitionException;
import com.bimd.fiscalpipeline.service.event.PipelineChange;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.time.Clock;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * The document state machine (BACKEND.md §6.2): the only place that changes a status.
 *
 * <p>Progress and results travel on different queues, so a result can arrive before the progress
 * message that precedes it. The table therefore lets a document skip forward (a result is
 * accepted from any in-flight state) and never lets it go back, which drops the late message.
 */
@Service
@RequiredArgsConstructor
public class PipelineStateService {

    private static final Map<DocumentStatus, Set<DocumentStatus>> ALLOWED = new EnumMap<>(DocumentStatus.class);

    static {
        allow(DocumentStatus.RECEIVED, DocumentStatus.QUEUED);
        allow(
                DocumentStatus.QUEUED,
                DocumentStatus.OCR,
                DocumentStatus.RETRY_WAIT,
                DocumentStatus.EXTRACTED,
                DocumentStatus.DEAD_LETTERED);
        allow(
                DocumentStatus.OCR,
                DocumentStatus.EXTRACTING,
                DocumentStatus.RETRY_WAIT,
                DocumentStatus.EXTRACTED,
                DocumentStatus.DEAD_LETTERED);
        allow(
                DocumentStatus.EXTRACTING,
                DocumentStatus.RETRY_WAIT,
                DocumentStatus.EXTRACTED,
                DocumentStatus.DEAD_LETTERED);
        allow(DocumentStatus.RETRY_WAIT, DocumentStatus.OCR, DocumentStatus.EXTRACTED, DocumentStatus.DEAD_LETTERED);
        allow(DocumentStatus.EXTRACTED, DocumentStatus.VALIDATING);
        allow(DocumentStatus.VALIDATING, DocumentStatus.AUTO_APPROVED, DocumentStatus.PENDING_REVIEW);
        allow(DocumentStatus.PENDING_REVIEW, DocumentStatus.APPROVED, DocumentStatus.REJECTED);
        allow(DocumentStatus.DEAD_LETTERED, DocumentStatus.QUEUED);
    }

    private final Clock clock;
    private final ApplicationEventPublisher applicationEvents;

    public boolean canMove(DocumentStatus from, DocumentStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public void move(Document document, DocumentStatus to) {
        DocumentStatus from = document.getStatus();
        if (!canMove(from, to)) {
            throw new IllegalStateTransitionException(document.getCode(), from, to);
        }
        document.setStatus(to);
        document.setUpdatedAt(IsoDates.format(clock.instant()));
        applicationEvents.publishEvent(new PipelineChange.DocumentChanged(document.getId()));
    }

    private static void allow(DocumentStatus from, DocumentStatus first, DocumentStatus... others) {
        ALLOWED.put(from, EnumSet.of(first, others));
    }
}
