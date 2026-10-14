package com.bimd.fiscalpipeline.messaging.kafka;

import com.bimd.fiscalpipeline.messaging.PipelineMessageDispatcher;
import com.bimd.fiscalpipeline.messaging.Topology;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** The offset is committed after each method returns, so the state change is stored first. */
@Component
@RequiredArgsConstructor
public class KafkaPipelineListener {

    private final PipelineMessageDispatcher dispatcher;

    @KafkaListener(topics = Topology.KAFKA_PROGRESS_TOPIC, groupId = Topology.API_CONSUMER_GROUP)
    public void onProgress(ConsumerRecord<String, String> record) {
        dispatcher.progress(record.value());
    }

    @KafkaListener(topics = Topology.KAFKA_OUTPUT_TOPIC, groupId = Topology.API_CONSUMER_GROUP)
    public void onExtracted(ConsumerRecord<String, String> record) {
        dispatcher.extracted(record.value(), headers(record));
    }

    @KafkaListener(topics = Topology.KAFKA_DEAD_LETTER_TOPIC, groupId = Topology.API_CONSUMER_GROUP)
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        dispatcher.deadLetter(record.value(), headers(record));
    }

    private Map<String, String> headers(ConsumerRecord<String, String> record) {
        Map<String, String> headers = new HashMap<>();
        record.headers()
                .forEach(header -> headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8)));
        return headers;
    }
}
