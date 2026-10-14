package com.bimd.fiscalpipeline.messaging;

import java.util.List;
import java.util.Map;

/**
 * Broker objects shared with the worker. The worker declares the same ones, and RabbitMQ answers
 * 406 to whoever declares a queue second with different arguments, so both sides are checked
 * against {@code contracts/topology.json} by their tests.
 */
public final class Topology {

    public static final String API_CONSUMER_GROUP = "fiscal-api";

    public static final int KAFKA_PARTITIONS = 3;
    public static final String KAFKA_INPUT_TOPIC = "documentos.recebidos";
    public static final String KAFKA_RETRY_TOPIC = "documentos.recebidos-retry";
    public static final String KAFKA_OUTPUT_TOPIC = "documentos.extraidos";
    public static final String KAFKA_PROGRESS_TOPIC = "documentos.progresso";
    public static final String KAFKA_DEAD_LETTER_TOPIC = "documentos.recebidos.DLT";

    public static final String RABBIT_EXCHANGE = "documentos";
    public static final int RABBIT_PREFETCH = 10;
    public static final String RABBIT_INPUT_QUEUE = "extracao.q";
    public static final String RABBIT_OUTPUT_QUEUE = "validacao.q";
    public static final String RABBIT_PROGRESS_QUEUE = "progresso.q";
    public static final String RABBIT_DEAD_LETTER_QUEUE = "extracao.dlq";

    /** Wait before each retry; a failure on the attempt after the last one goes to the dead-letter queue. */
    public static final List<Integer> RETRY_BACKOFF_MS = List.of(1000, 2000, 4000);

    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String ERROR_HEADER = "x-error";
    public static final String ATTEMPTS_HEADER = "x-attempts";

    private static final int MILLIS_PER_SECOND = 1000;

    private Topology() {
    }

    public static String rabbitRetryQueue(int delayMs) {
        return "extracao.retry.%ds".formatted(delayMs / MILLIS_PER_SECOND);
    }

    /** When the TTL expires the message is dead-lettered back to the input queue. */
    public static Map<String, Object> rabbitRetryArguments(int delayMs, String inputQueue) {
        return Map.of(
                "x-message-ttl", delayMs,
                "x-dead-letter-exchange", RABBIT_EXCHANGE,
                "x-dead-letter-routing-key", inputQueue);
    }
}
