package com.bimd.fiscalpipeline.messaging.kafka;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.messaging.Topology;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaTopologyConfig {

    private static final int REPLICAS = 1;
    private static final long RETRY_INITIAL_INTERVAL_MS = 1000;
    private static final double RETRY_MULTIPLIER = 2;
    private static final long RETRY_MAX_ELAPSED_MS = 15_000;

    /** Created at startup when missing; an unreachable broker only logs a warning. */
    @Bean
    public KafkaAdmin.NewTopics pipelineTopics() {
        BrokerKind kafka = BrokerKind.KAFKA;
        return new KafkaAdmin.NewTopics(Stream.of(
                        kafka.getInputQueue(),
                        Topology.KAFKA_RETRY_TOPIC,
                        kafka.getOutputQueue(),
                        kafka.getProgressQueue(),
                        kafka.getDeadLetterQueue())
                .map(KafkaTopologyConfig::topic)
                .toArray(NewTopic[]::new));
    }

    /**
     * Messages that cannot be read are dropped by the listener itself, so what reaches here is an
     * infrastructure failure: retry with backoff for a while, then log and move on.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(RETRY_INITIAL_INTERVAL_MS, RETRY_MULTIPLIER);
        backOff.setMaxElapsedTime(RETRY_MAX_ELAPSED_MS);
        return new DefaultErrorHandler(backOff);
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name)
                .partitions(Topology.KAFKA_PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }
}
