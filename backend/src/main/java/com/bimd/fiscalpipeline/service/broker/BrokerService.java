package com.bimd.fiscalpipeline.service.broker;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.dto.BrokerConfigResponse;
import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * Holds the broker new documents enter on (BACKEND.md §5.1). It lives in memory and starts from
 * {@code BROKER_DEFAULT}; documents already in flight keep the broker recorded on them.
 */
@Service
public class BrokerService {

    private static final String NO_DOCUMENT_CODE = "—";

    private final AtomicReference<BrokerKind> active;
    private final PipelineEventPublisher eventPublisher;

    public BrokerService(PipelineProperties properties, PipelineEventPublisher eventPublisher) {
        this.active = new AtomicReference<>(properties.brokerDefault());
        this.eventPublisher = eventPublisher;
    }

    public BrokerKind active() {
        return active.get();
    }

    public BrokerConfigResponse current() {
        return BrokerConfigResponse.of(active.get());
    }

    public BrokerConfigResponse switchTo(BrokerKind kind) {
        BrokerKind previous = active.getAndSet(kind);
        if (previous != kind) {
            eventPublisher.publish(
                    ServiceName.API,
                    NO_DOCUMENT_CODE,
                    "broker alterado para %s · novos documentos usam %s"
                            .formatted(kind.getLabel(), kind.getInputQueue()),
                    EventLevel.OK);
        }
        return BrokerConfigResponse.of(kind);
    }
}
