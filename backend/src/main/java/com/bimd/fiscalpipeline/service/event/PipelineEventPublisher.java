package com.bimd.fiscalpipeline.service.event;

import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import com.bimd.fiscalpipeline.repository.PipelineEventRepository;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single exit for the events the screens show (BACKEND.md §6.6): each one is stored for the
 * history and announced to the live stream. The simulator mirror hooks in here in a later phase.
 */
@Service
@RequiredArgsConstructor
public class PipelineEventPublisher {

    /** How much history the "Eventos" card can ever ask for. */
    private static final int KEPT_EVENTS = 500;

    private final PipelineEventRepository pipelineEventRepository;
    private final ApplicationEventPublisher applicationEvents;
    private final Clock clock;

    public void publish(ServiceName service, String documentCode, String message, EventLevel level) {
        PipelineEventRecord event = new PipelineEventRecord();
        event.setId(UUID.randomUUID());
        event.setOccurredAt(IsoDates.format(clock.instant()));
        event.setService(service);
        event.setDocumentCode(documentCode);
        event.setMessage(message);
        event.setLevel(level);
        pipelineEventRepository.save(event);
        applicationEvents.publishEvent(new PipelineChange.EventRecorded(event));
    }

    /** The table is a rolling log, not an audit trail: only the latest events are kept. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void dropOldEvents() {
        pipelineEventRepository.deleteAllButLatest(KEPT_EVENTS);
    }
}
