package com.bimd.fiscalpipeline.messaging;

/** Payload of {@code documento.progresso}; {@code error} and {@code retryInMs} come with RETRY_WAIT. */
public record ProgressPayload(ProgressStage stage, int attempt, String error, Long retryInMs) {
}
