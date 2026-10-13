package com.bimd.fiscalpipeline.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Document lifecycle. Each status carries what the screens show for it (BACKEND.md §6.2),
 * so the frontend never derives stage, progress or outcome on its own.
 */
@Getter
@RequiredArgsConstructor
public enum DocumentStatus {
    RECEIVED("Recebido", PipelineNode.API, ServiceName.API, 0.10, null, null),
    QUEUED("Na fila", PipelineNode.BROKER, ServiceName.BROKER, 0.20, null, null),
    OCR("OCR", PipelineNode.WORKER, ServiceName.WORKER, 0.45, null, null),
    EXTRACTING("Extração", PipelineNode.WORKER, ServiceName.WORKER, 0.65, null, null),
    RETRY_WAIT("Retry {n}/3", PipelineNode.RETRY, ServiceName.BROKER, 0.50, null, null),
    EXTRACTED("Validação", PipelineNode.BROKER, ServiceName.BROKER, 0.80, null, null),
    VALIDATING("Validação", PipelineNode.VALIDATION, ServiceName.API, 0.90, null, null),
    AUTO_APPROVED("Aprovado", PipelineNode.VALIDATION, ServiceName.API, 1, Outcome.AUTO, null),
    PENDING_REVIEW("Em revisão", PipelineNode.VALIDATION, ServiceName.API, 1, Outcome.REVIEW, null),
    APPROVED(
            "Aprovado (revisão)", PipelineNode.VALIDATION, ServiceName.API, 1, Outcome.REVIEW, ReviewDecision.APPROVED),
    REJECTED("Rejeitado", PipelineNode.VALIDATION, ServiceName.API, 1, Outcome.REVIEW, ReviewDecision.REJECTED),
    DEAD_LETTERED("DLQ", PipelineNode.RETRY, ServiceName.BROKER, 1, Outcome.DLQ, null);

    public static final String ATTEMPT_PLACEHOLDER = "{n}";

    private final String labelTemplate;
    private final PipelineNode node;
    private final ServiceName service;
    private final double progress;
    private final Outcome outcome;
    private final ReviewDecision review;
}
