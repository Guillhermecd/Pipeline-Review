package com.bimd.fiscalpipeline.messaging.kafka;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.messaging.MessagePublishException;
import com.bimd.fiscalpipeline.messaging.MessagingPort;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaMessagingAdapter implements MessagingPort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PipelineProperties properties;

    @Override
    public BrokerKind kind() {
        return BrokerKind.KAFKA;
    }

    @Override
    public void publish(String destination, String key, String body, Map<String, String> headers) {
        ProducerRecord<String, String> record = new ProducerRecord<>(destination, key, body);
        headers.forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
        try {
            // With acks=all the future completes only after the in-sync replicas have the record.
            kafkaTemplate.send(record).get(properties.publishTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagePublishException(destination, e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new MessagePublishException(destination, e);
        }
    }
}
