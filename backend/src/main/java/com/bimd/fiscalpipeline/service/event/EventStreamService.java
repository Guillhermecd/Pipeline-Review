package com.bimd.fiscalpipeline.service.event;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.dto.BrokerConfigResponse;
import com.bimd.fiscalpipeline.domain.dto.PipelineStream;
import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.repository.PipelineEventRepository;
import com.bimd.fiscalpipeline.service.broker.BrokerService;
import com.bimd.fiscalpipeline.service.document.DocumentResponseAssembler;
import com.bimd.fiscalpipeline.service.event.PipelineChange.BrokerSwitched;
import com.bimd.fiscalpipeline.service.event.PipelineChange.DocumentChanged;
import com.bimd.fiscalpipeline.service.event.PipelineChange.EnvelopeSeen;
import com.bimd.fiscalpipeline.service.event.PipelineChange.EventRecorded;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Feeds the {@code /eventos} stream (BACKEND.md §7.2): the whole state when a client connects,
 * then one event per change.
 *
 * <p>Changes are sent only after their transaction commits. A listener that fails and is retried
 * would otherwise show the same change several times, and a rolled-back one would show something
 * that never happened. Callers outside a transaction are sent at once.
 */
@Service
@RequiredArgsConstructor
public class EventStreamService {

    private static final int SNAPSHOT_DOCUMENTS = 40;
    private static final int SNAPSHOT_EVENTS = 80;
    /** The "Eventos" card shows the time of day; UTC would be three hours off for who is reading. */
    private static final DateTimeFormatter TIME_OF_DAY =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("America/Sao_Paulo"));

    private final EventStreamHub hub;
    private final DocumentRepository documentRepository;
    private final PipelineEventRepository pipelineEventRepository;
    private final DocumentResponseAssembler responseAssembler;
    private final BrokerService brokerService;
    private final PipelineProperties properties;

    @Transactional(readOnly = true)
    public SseEmitter connect() {
        return hub.open(PipelineStream.SNAPSHOT, snapshot());
    }

    @Transactional(readOnly = true)
    public PipelineStream.Snapshot snapshot() {
        return new PipelineStream.Snapshot(
                responseAssembler.toResponses(
                        documentRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, SNAPSHOT_DOCUMENTS))),
                pipelineEventRepository.findAllByOrderBySequenceDesc(PageRequest.of(0, SNAPSHOT_EVENTS)).stream()
                        .map(this::toEvent)
                        .toList(),
                brokerService.current(),
                properties.reviewConfidenceThreshold(),
                // The mirror to the messaging simulator is not connected yet.
                new PipelineStream.Simulator(false, properties.messagingSimulatorUrl(), 0));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDocumentChanged(DocumentChanged change) {
        documentRepository
                .findById(change.documentId())
                .ifPresent(document ->
                        hub.broadcast(PipelineStream.DOCUMENT, responseAssembler.toResponse(document)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEventRecorded(EventRecorded change) {
        hub.broadcast(PipelineStream.EVENT, toEvent(change.event()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEnvelopeSeen(EnvelopeSeen change) {
        hub.broadcast(PipelineStream.ENVELOPE, change.envelope());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onBrokerSwitched(BrokerSwitched change) {
        hub.broadcast(PipelineStream.BROKER, BrokerConfigResponse.of(change.broker()));
    }

    private PipelineStream.Event toEvent(PipelineEventRecord event) {
        return new PipelineStream.Event(
                event.getId(),
                TIME_OF_DAY.format(IsoDates.parse(event.getOccurredAt())),
                event.getService(),
                event.getDocumentCode(),
                event.getMessage(),
                event.getLevel());
    }
}
