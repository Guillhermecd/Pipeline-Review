package com.bimd.fiscalpipeline.messaging.rabbitmq;

import com.bimd.fiscalpipeline.messaging.PipelineMessageDispatcher;
import com.bimd.fiscalpipeline.messaging.Topology;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** The message is acknowledged after each method returns, so the state change is stored first. */
@Component
@RequiredArgsConstructor
public class RabbitPipelineListener {

    private final PipelineMessageDispatcher dispatcher;

    @RabbitListener(queues = Topology.RABBIT_PROGRESS_QUEUE)
    public void onProgress(Message message) {
        dispatcher.progress(body(message));
    }

    @RabbitListener(queues = Topology.RABBIT_OUTPUT_QUEUE)
    public void onExtracted(Message message) {
        dispatcher.extracted(body(message), headers(message));
    }

    @RabbitListener(queues = Topology.RABBIT_DEAD_LETTER_QUEUE)
    public void onDeadLetter(Message message) {
        dispatcher.deadLetter(body(message), headers(message));
    }

    private Map<String, String> headers(Message message) {
        Map<String, String> headers = new HashMap<>();
        message.getMessageProperties().getHeaders().forEach((name, value) -> headers.put(name, String.valueOf(value)));
        return headers;
    }

    private String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }
}
