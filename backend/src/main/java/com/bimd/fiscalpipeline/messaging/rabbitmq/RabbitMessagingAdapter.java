package com.bimd.fiscalpipeline.messaging.rabbitmq;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.messaging.MessagePublishException;
import com.bimd.fiscalpipeline.messaging.MessagingPort;
import com.bimd.fiscalpipeline.messaging.Topology;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RabbitMessagingAdapter implements MessagingPort {

    private final RabbitTemplate rabbitTemplate;
    private final PipelineProperties properties;

    @Override
    public BrokerKind kind() {
        return BrokerKind.RABBITMQ;
    }

    @Override
    public void publish(String destination, String key, String body, Map<String, String> headers) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        headers.forEach(messageProperties::setHeader);
        Message message = new Message(body.getBytes(StandardCharsets.UTF_8), messageProperties);
        try {
            // Send and wait on one dedicated channel: the confirm awaited is this message's.
            rabbitTemplate.invoke(operations -> {
                operations.send(Topology.RABBIT_EXCHANGE, destination, message);
                operations.waitForConfirmsOrDie(properties.publishTimeoutMs());
                return null;
            });
        } catch (RuntimeException e) {
            throw new MessagePublishException(destination, e);
        }
    }
}
