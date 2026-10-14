package com.bimd.fiscalpipeline.messaging.rabbitmq;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.messaging.Topology;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    /** Declared when the first connection opens; every queue is bound by its own name. */
    @Bean
    public Declarables pipelineDeclarables() {
        BrokerKind rabbit = BrokerKind.RABBITMQ;
        DirectExchange exchange = new DirectExchange(Topology.RABBIT_EXCHANGE, true, false);
        List<Queue> queues = new ArrayList<>();
        for (String name : List.of(
                rabbit.getInputQueue(),
                rabbit.getOutputQueue(),
                rabbit.getProgressQueue(),
                rabbit.getDeadLetterQueue())) {
            queues.add(QueueBuilder.durable(name).build());
        }
        // One queue per delay: a single queue with per-message TTL would block short waits behind long ones.
        for (int delayMs : Topology.RETRY_BACKOFF_MS) {
            queues.add(QueueBuilder.durable(Topology.rabbitRetryQueue(delayMs))
                    .withArguments(Topology.rabbitRetryArguments(delayMs, rabbit.getInputQueue()))
                    .build());
        }

        List<Declarable> declarables = new ArrayList<>();
        declarables.add(exchange);
        for (Queue queue : queues) {
            declarables.add(queue);
            declarables.add(BindingBuilder.bind(queue).to(exchange).with(queue.getName()));
        }
        return new Declarables(declarables);
    }
}
