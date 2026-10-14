package com.bimd.fiscalpipeline.messaging;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import java.util.Map;

/** The broker as the services see it. Kafka and RabbitMQ adapters implement the same contract. */
public interface MessagingPort {

    BrokerKind kind();

    /**
     * Sends {@code body} to a topic (Kafka) or routing key (RabbitMQ) and returns only after the
     * broker confirmed the write.
     *
     * @throws MessagePublishException when the broker did not confirm in time
     */
    void publish(String destination, String key, String body, Map<String, String> headers);
}
