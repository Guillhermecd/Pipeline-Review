package com.bimd.fiscalpipeline.service.outbox;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the outbox and hands pending messages to the brokers (BACKEND.md §4, step 2). */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int MAX_PER_ROUND = 100;

    private final OutboxPublisher outboxPublisher;

    @Scheduled(fixedDelayString = "${app.pipeline.outbox-poll-ms}")
    public void publishPending() {
        // Each broker has its own line: one being down does not hold the other's documents.
        for (BrokerKind broker : BrokerKind.values()) {
            try {
                int sent = 0;
                while (sent < MAX_PER_ROUND && outboxPublisher.publishNext(broker)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("Outbox relay could not publish to {}; it will try again: {}", broker, e.getMessage());
            }
        }
    }
}
