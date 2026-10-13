package com.bimd.fiscalpipeline.domain.dto;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.Outcome;
import com.bimd.fiscalpipeline.domain.enums.PipelineNode;
import com.bimd.fiscalpipeline.domain.enums.ReviewDecision;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mirrors the frontend's {@code PipelineDocument} (BACKEND.md §7.1). Money travels as decimal strings. */
public record DocumentResponse(
        UUID id,
        String code,
        String fileName,
        String kind,
        String issuer,
        String issueDate,
        String cnpj,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
        List<Item> items,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal itemsSum,
        String idempotencyKey,
        String traceId,
        BrokerKind broker,
        DocumentStatus status,
        Stage stage,
        double progress,
        Outcome outcome,
        ReviewDecision review,
        String reason,
        int attempts,
        String lastError,
        double confidence,
        Map<String, Double> fieldConfidence,
        List<Validation> validations,
        String receivedAt,
        String finishedAt,
        long durationMs) {

    public record Item(
            String description,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice) {
    }

    public record Stage(String label, PipelineNode node, ServiceName service) {
    }

    public record Validation(String name, boolean ok, String detail) {
    }
}
