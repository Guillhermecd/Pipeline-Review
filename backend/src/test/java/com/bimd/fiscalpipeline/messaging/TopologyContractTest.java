package com.bimd.fiscalpipeline.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The backend's queue names and arguments must be the ones in contracts/topology.json. The
 * worker runs the same check: RabbitMQ refuses a queue declared twice with different arguments,
 * so a change has to land in the contract and on both sides at once.
 */
class TopologyContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode contract;

    @BeforeAll
    static void readContract() throws IOException {
        contract = MAPPER.readTree(Path.of("..", "contracts", "topology.json").toFile());
    }

    @Test
    void should_matchContract_when_comparingRetryBackoff() {
        List<Integer> backoff = MAPPER.convertValue(contract.get("retryBackoffMs"), new TypeReference<>() {
        });

        assertThat(Topology.RETRY_BACKOFF_MS).isEqualTo(backoff);
    }

    @Test
    void should_matchContract_when_comparingKafkaTopics() {
        JsonNode kafka = contract.get("kafka");
        JsonNode topics = kafka.get("topics");

        assertThat(kafka.get("partitions").asInt()).isEqualTo(Topology.KAFKA_PARTITIONS);
        assertThat(topics.get("input").asText()).isEqualTo(BrokerKind.KAFKA.getInputQueue());
        assertThat(topics.get("retry").asText()).isEqualTo(Topology.KAFKA_RETRY_TOPIC);
        assertThat(topics.get("output").asText()).isEqualTo(BrokerKind.KAFKA.getOutputQueue());
        assertThat(topics.get("progress").asText()).isEqualTo(BrokerKind.KAFKA.getProgressQueue());
        assertThat(topics.get("deadLetter").asText()).isEqualTo(BrokerKind.KAFKA.getDeadLetterQueue());
        assertThat(topics).hasSize(5);
    }

    @Test
    void should_matchContract_when_comparingRabbitQueuesAndArguments() {
        JsonNode rabbit = contract.get("rabbitmq");
        JsonNode queues = rabbit.get("queues");

        assertThat(rabbit.get("exchange").asText()).isEqualTo(Topology.RABBIT_EXCHANGE);
        assertThat(rabbit.get("exchangeType").asText()).isEqualTo("direct");
        assertThat(rabbit.get("prefetch").asInt()).isEqualTo(Topology.RABBIT_PREFETCH);
        assertThat(queues.get("input").asText()).isEqualTo(BrokerKind.RABBITMQ.getInputQueue());
        assertThat(queues.get("output").asText()).isEqualTo(BrokerKind.RABBITMQ.getOutputQueue());
        assertThat(queues.get("progress").asText()).isEqualTo(BrokerKind.RABBITMQ.getProgressQueue());
        assertThat(queues.get("deadLetter").asText()).isEqualTo(BrokerKind.RABBITMQ.getDeadLetterQueue());
        assertThat(queues).hasSize(4);

        List<Map<String, Object>> expected = MAPPER.convertValue(rabbit.get("retryQueues"), new TypeReference<>() {
        });
        List<Map<String, Object>> declared = Topology.RETRY_BACKOFF_MS.stream()
                .map(delayMs -> Map.<String, Object>of(
                        "name", Topology.rabbitRetryQueue(delayMs),
                        "arguments", Topology.rabbitRetryArguments(delayMs, BrokerKind.RABBITMQ.getInputQueue())))
                .toList();
        assertThat(declared).isEqualTo(expected);
    }
}
