package com.bimd.fiscalpipeline.domain.dto;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;

/** Mirrors {@code BrokerConfig} in frontend/src/api/modules/types.ts. */
public record BrokerConfigResponse(
        BrokerKind kind,
        String label,
        String inputQueue,
        String outputQueue,
        String retryQueue,
        String deadLetterQueue,
        String consumerConfig,
        String retryPolicy) {

    public static BrokerConfigResponse of(BrokerKind kind) {
        return new BrokerConfigResponse(
                kind,
                kind.getLabel(),
                kind.getInputQueue(),
                kind.getOutputQueue(),
                kind.getRetryQueueLabel(),
                kind.getDeadLetterQueue(),
                kind.getConsumerConfig(),
                kind.getRetryPolicy());
    }
}
