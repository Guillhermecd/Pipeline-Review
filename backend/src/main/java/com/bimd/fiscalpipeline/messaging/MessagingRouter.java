package com.bimd.fiscalpipeline.messaging;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Picks the adapter of the broker a document entered on (BACKEND.md §5.1). */
@Component
public class MessagingRouter {

    private final Map<BrokerKind, MessagingPort> ports = new EnumMap<>(BrokerKind.class);

    public MessagingRouter(List<MessagingPort> adapters) {
        adapters.forEach(adapter -> ports.put(adapter.kind(), adapter));
    }

    public MessagingPort portFor(BrokerKind broker) {
        MessagingPort port = ports.get(broker);
        if (port == null) {
            throw new IllegalStateException("No messaging adapter for " + broker);
        }
        return port;
    }
}
