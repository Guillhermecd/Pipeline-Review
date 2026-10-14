package com.bimd.fiscalpipeline.support;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.messaging.MessagingRouter;
import com.bimd.fiscalpipeline.messaging.Topology;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Stands in for the Python worker: reads what the API put on the input queue and answers with
 * the messages the real worker would send, built by the test.
 */
public class FakeWorker {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final long POLL_MS = 250;
    private static final Path EXTRACTED_EXAMPLE = Path.of("..", "contracts", "examples", "documento.extraido.json");

    private final MessagingRouter messagingRouter;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String kafkaBootstrapServers;

    public FakeWorker(
            MessagingRouter messagingRouter,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            String kafkaBootstrapServers) {
        this.messagingRouter = messagingRouter;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
    }

    /** A message as it sits on a queue. */
    public record Received(ObjectNode envelope, Map<String, String> headers) {

        public UUID requestId() {
            return UUID.fromString(envelope.get("requestId").asText());
        }
    }

    /** Waits for the input message of one request of a document. */
    public Received awaitInput(BrokerKind broker, UUID documentId, UUID requestId) {
        return broker == BrokerKind.KAFKA ? awaitKafkaInput(documentId, requestId) : awaitRabbitInput(documentId, requestId);
    }

    public void progress(Received input, String stage, int attempt) {
        send(input, true, answer(input, "documento.progresso", attempt)
                .set("payload", objectMapper.createObjectNode().put("stage", stage).put("attempt", attempt)));
    }

    public void retryWait(Received input, int attempt, String error, long retryInMs) {
        ObjectNode payload = objectMapper
                .createObjectNode()
                .put("stage", "RETRY_WAIT")
                .put("attempt", attempt)
                .put("error", error)
                .put("retryInMs", retryInMs);
        send(input, true, answer(input, "documento.progresso", attempt)
                .set("payload", payload));
    }

    /** Sends a result and returns it, so a test can deliver the very same message again. */
    public ObjectNode extracted(Received input, int attempt) {
        ObjectNode result = answer(input, "documento.extraido", attempt).set("payload", extractionPayload());
        resend(input, result);
        return result;
    }

    public void resend(Received input, ObjectNode result) {
        send(input, false, result);
    }

    /** The input message goes to the dead-letter queue as it came, with the reason in the headers. */
    public void deadLetter(Received input, int attempts, String error) {
        ObjectNode original = input.envelope().deepCopy().put("attempt", attempts);
        BrokerKind broker = BrokerKind.fromCode(original.get("broker").asText());
        Map<String, String> headers = new HashMap<>(input.headers());
        headers.put(Topology.ERROR_HEADER, error);
        headers.put("x-error-class", "TransientError");
        headers.put(Topology.ATTEMPTS_HEADER, String.valueOf(attempts));
        messagingRouter
                .portFor(broker)
                .publish(broker.getDeadLetterQueue(), original.get("documentId").asText(), json(original), headers);
    }

    private ObjectNode answer(Received input, String eventType, int attempt) {
        return input.envelope()
                .deepCopy()
                .put("eventId", UUID.randomUUID().toString())
                .put("eventType", eventType)
                .put("attempt", attempt);
    }

    private void send(Received input, boolean progress, ObjectNode envelope) {
        BrokerKind broker = BrokerKind.fromCode(envelope.get("broker").asText());
        String destination = progress ? broker.getProgressQueue() : broker.getOutputQueue();
        Map<String, String> headers = new HashMap<>();
        if (input.headers().containsKey(Topology.TRACEPARENT_HEADER)) {
            headers.put(Topology.TRACEPARENT_HEADER, input.headers().get(Topology.TRACEPARENT_HEADER));
        }
        messagingRouter
                .portFor(broker)
                .publish(destination, envelope.get("documentId").asText(), json(envelope), headers);
    }

    private Received awaitKafkaInput(UUID documentId, UUID requestId) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "fake-worker-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        long deadline = System.nanoTime() + WAIT.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(BrokerKind.KAFKA.getInputQueue()));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(POLL_MS))) {
                    ObjectNode envelope = parse(record.value());
                    if (matches(envelope, documentId, requestId)) {
                        Map<String, String> headers = new HashMap<>();
                        record.headers()
                                .forEach(header ->
                                        headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8)));
                        return new Received(envelope, headers);
                    }
                }
            }
        }
        throw new AssertionError("No input message for document " + documentId + " on Kafka");
    }

    /** Messages of other documents are taken off the queue too; no real worker is waiting for them. */
    private Received awaitRabbitInput(UUID documentId, UUID requestId) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            Message message = rabbitTemplate.receive(BrokerKind.RABBITMQ.getInputQueue(), POLL_MS);
            if (message == null) {
                continue;
            }
            ObjectNode envelope = parse(new String(message.getBody(), StandardCharsets.UTF_8));
            if (matches(envelope, documentId, requestId)) {
                Map<String, String> headers = new HashMap<>();
                message.getMessageProperties().getHeaders().forEach((name, value) -> headers.put(name, String.valueOf(value)));
                return new Received(envelope, headers);
            }
        }
        throw new AssertionError("No input message for document " + documentId + " on RabbitMQ");
    }

    private boolean matches(ObjectNode envelope, UUID documentId, UUID requestId) {
        return envelope != null
                && documentId.toString().equals(envelope.path("documentId").asText())
                && requestId.toString().equals(envelope.path("requestId").asText());
    }

    private JsonNode extractionPayload() {
        try {
            return objectMapper.readTree(EXTRACTED_EXAMPLE.toFile()).get("payload");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + EXTRACTED_EXAMPLE, e);
        }
    }

    private ObjectNode parse(String body) {
        try {
            return objectMapper.readTree(body) instanceof ObjectNode object ? object : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String json(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
