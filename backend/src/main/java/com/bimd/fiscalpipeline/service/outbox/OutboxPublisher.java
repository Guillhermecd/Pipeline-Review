package com.bimd.fiscalpipeline.service.outbox;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.OutboxEvent;
import com.bimd.fiscalpipeline.messaging.MessagingRouter;
import com.bimd.fiscalpipeline.messaging.Topology;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.repository.OutboxEventRepository;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.service.pipeline.PipelineStateService;
import com.bimd.fiscalpipeline.util.IsoDates;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sends one outbox message per transaction. */
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final DocumentRepository documentRepository;
    private final MessagingRouter messagingRouter;
    private final PipelineStateService stateService;
    private final PipelineEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * Publishes the oldest pending message of a broker and marks it only after the broker
     * confirmed. If the process dies in between, the row is still pending and is sent again:
     * delivery is at least once, and the consumers drop the repeat.
     *
     * <p>The document row stays locked while the message goes out, so the worker's first answer
     * waits for this transaction and never finds the document still in RECEIVED.
     *
     * @return false when there was nothing to send
     */
    @Transactional
    public boolean publishNext(BrokerKind broker) {
        Optional<OutboxEvent> pending = outboxEventRepository.lockNextPending(broker.name());
        if (pending.isEmpty()) {
            return false;
        }
        OutboxEvent event = pending.get();
        Document document = documentRepository.findByIdForUpdate(event.getDocumentId()).orElseThrow();

        messagingRouter
                .portFor(broker)
                .publish(
                        event.getDestination(),
                        document.getId().toString(),
                        toJson(event.getPayload()),
                        Map.of(Topology.TRACEPARENT_HEADER, event.getTraceparent()));

        event.setPublishedAt(IsoDates.format(clock.instant()));
        if (stateService.canMove(document.getStatus(), DocumentStatus.QUEUED)) {
            stateService.move(document, DocumentStatus.QUEUED);
        }
        eventPublisher.publish(
                ServiceName.BROKER, document.getCode(), "publish " + event.getDestination(), EventLevel.OK);
        return true;
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload is not serialisable", e);
        }
    }
}
