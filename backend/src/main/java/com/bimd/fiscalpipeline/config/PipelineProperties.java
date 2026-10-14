package com.bimd.fiscalpipeline.config;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of {@code app.pipeline.*}. */
@ConfigurationProperties(prefix = "app.pipeline")
public record PipelineProperties(
        int uploadMaxSizeMb,
        BrokerKind brokerDefault,
        BigDecimal reviewConfidenceThreshold,
        long outboxPollMs,
        long publishTimeoutMs) {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    public long uploadMaxSizeBytes() {
        return uploadMaxSizeMb * BYTES_PER_MB;
    }
}
