package com.bimd.fiscalpipeline.domain.enums;

import com.bimd.fiscalpipeline.messaging.Topology;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Queue names and the texts of the broker card are part of the frontend contract (BACKEND.md
 * §5.2); the texts are the ones in {@code frontend/src/api/mock/fixtures.ts}.
 */
@Getter
@RequiredArgsConstructor
public enum BrokerKind {
    KAFKA(
            "kafka",
            "Kafka",
            Topology.KAFKA_INPUT_TOPIC,
            Topology.KAFKA_OUTPUT_TOPIC,
            Topology.KAFKA_PROGRESS_TOPIC,
            Topology.KAFKA_DEAD_LETTER_TOPIC,
            Topology.KAFKA_RETRY_TOPIC,
            "consumer group extrator-py · 3 partições",
            "Retry topics não bloqueantes (@RetryableTopic no Spring, retry topic no consumer Python) com backoff"
                    + " exponencial. Após a 3ª falha a mensagem vai para o DLT com os headers da exceção e o"
                    + " traceparent original, e pode ser reprocessada pela API."),
    RABBITMQ(
            "rabbitmq",
            "RabbitMQ",
            Topology.RABBIT_INPUT_QUEUE,
            Topology.RABBIT_OUTPUT_QUEUE,
            Topology.RABBIT_PROGRESS_QUEUE,
            Topology.RABBIT_DEAD_LETTER_QUEUE,
            "extracao.retry (TTL)",
            "exchange documentos · prefetch 10",
            "Fila de espera com TTL por tentativa e dead-letter-exchange apontando de volta para extracao.q. O"
                    + " header x-death conta as tentativas; após a 3ª falha a mensagem é roteada para"
                    + " extracao.dlq e pode ser reprocessada pela API.");

    @JsonValue
    private final String code;

    private final String label;
    private final String inputQueue;
    private final String outputQueue;
    private final String progressQueue;
    private final String deadLetterQueue;
    /** What the screen shows as the retry queue; RabbitMQ has one queue per delay behind it. */
    private final String retryQueueLabel;

    private final String consumerConfig;
    private final String retryPolicy;

    @JsonCreator
    public static BrokerKind fromCode(String code) {
        return Arrays.stream(values())
                .filter(kind -> kind.code.equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown broker: " + code));
    }
}
