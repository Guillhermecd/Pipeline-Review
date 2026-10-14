package com.bimd.fiscalpipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import com.bimd.fiscalpipeline.repository.ExtractionRepository;
import com.bimd.fiscalpipeline.repository.PipelineEventRepository;
import com.bimd.fiscalpipeline.support.AbstractIntegrationTest;
import com.bimd.fiscalpipeline.support.FakeWorker.Received;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

class RetryBackoffIT extends AbstractIntegrationTest {

    private static final String ERROR = "LLM 503 Service Unavailable";
    private static final List<Long> BACKOFF_MS = List.of(1000L, 2000L, 4000L);
    private static final int ATTEMPTS_BEFORE_DEAD_LETTER = 4;
    private static final Duration QUIET = Duration.ofSeconds(2);

    @Autowired
    private ExtractionRepository extractionRepository;

    @Autowired
    private PipelineEventRepository pipelineEventRepository;

    @ParameterizedTest
    @EnumSource(BrokerKind.class)
    void should_deadLetterAfterThreeRetries_and_dropLateResult_when_reprocessed(BrokerKind broker) throws Exception {
        String token = bearerToken();
        brokerService.switchTo(broker);
        UUID documentId = uploadNewDocument(token);
        UUID firstRequestId = documentRepository.findById(documentId).orElseThrow().getCurrentRequestId();
        Received input = worker().awaitInput(broker, documentId, firstRequestId);
        awaitStatus(documentId, DocumentStatus.QUEUED);

        // Three transient failures, each one announced with its wait.
        for (int attempt = 1; attempt <= BACKOFF_MS.size(); attempt++) {
            worker().progress(input, "OCR", attempt);
            awaitStatus(documentId, DocumentStatus.OCR);
            worker().progress(input, "EXTRACTING", attempt);
            awaitStatus(documentId, DocumentStatus.EXTRACTING);
            worker().retryWait(input, attempt, ERROR, BACKOFF_MS.get(attempt - 1));
            Document waiting = awaitStatus(documentId, DocumentStatus.RETRY_WAIT);
            assertThat(waiting.getAttempts()).isEqualTo(attempt);
            assertThat(waiting.getLastError()).isEqualTo(ERROR);
        }
        mockMvc.perform(get(DOCUMENTS_URL + "/" + documentId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.stage.label").value("Retry 3/3"))
                .andExpect(jsonPath("$.stage.node").value("retry"));

        // The fourth failure ends in the dead-letter queue.
        worker().progress(input, "OCR", ATTEMPTS_BEFORE_DEAD_LETTER);
        awaitStatus(documentId, DocumentStatus.OCR);
        worker().deadLetter(input, ATTEMPTS_BEFORE_DEAD_LETTER, ERROR);
        Document dead = awaitStatus(documentId, DocumentStatus.DEAD_LETTERED);
        assertThat(dead.getAttempts()).isEqualTo(ATTEMPTS_BEFORE_DEAD_LETTER);
        assertThat(dead.getLastError()).isEqualTo(ERROR);
        assertThat(dead.getFinishedAt()).isNotNull();
        mockMvc.perform(get(DOCUMENTS_URL + "/" + documentId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.outcome").value("dlq"))
                .andExpect(jsonPath("$.reason").value(ERROR));
        List<PipelineEventRecord> events = pipelineEventRepository.findByDocumentCodeOrderByOccurredAtAsc(dead.getCode());
        assertThat(events)
                .filteredOn(event -> event.getLevel() == EventLevel.WAIT)
                .extracting(PipelineEventRecord::getMessage)
                .containsExactly(
                        "retry +1s → " + broker.getRetryQueueLabel(),
                        "retry +2s → " + broker.getRetryQueueLabel(),
                        "retry +4s → " + broker.getRetryQueueLabel());
        assertThat(events)
                .filteredOn(event -> event.getLevel() == EventLevel.ERROR)
                .extracting(PipelineEventRecord::getMessage)
                .containsExactly("DLQ após 4 tentativas · " + ERROR);

        // Reprocessing keeps the document, its key, trace and broker, under a new request.
        mockMvc.perform(post(DOCUMENTS_URL + "/" + documentId + "/reprocessar").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.broker").value(broker.getCode()))
                .andExpect(jsonPath("$.traceId").value(dead.getTraceId()))
                .andExpect(jsonPath("$.idempotencyKey").value(dead.getIdempotencyKey()));
        Document requeued = documentRepository.findById(documentId).orElseThrow();
        UUID secondRequestId = requeued.getCurrentRequestId();
        assertThat(secondRequestId).isNotEqualTo(firstRequestId);
        assertThat(requeued.getLastError()).isNull();
        Received secondInput = worker().awaitInput(broker, documentId, secondRequestId);
        assertThat(secondInput.envelope().get("attempt").asInt()).isEqualTo(1);
        assertThat(secondInput.envelope().get("eventType").asText()).isEqualTo("documento.recebido");
        assertThat(secondInput.headers().get("traceparent")).startsWith("00-" + dead.getTraceId() + "-");

        // A result still on its way from the failed run answers the old request: it is dropped.
        worker().extracted(input, ATTEMPTS_BEFORE_DEAD_LETTER);
        await().during(QUIET)
                .atMost(PIPELINE_TIMEOUT)
                .until(() -> documentRepository.findById(documentId).orElseThrow().getStatus() == DocumentStatus.QUEUED
                        && extractionRepository.findByDocumentId(documentId).isEmpty());

        worker().extracted(secondInput, 1);
        Document extracted = awaitStatus(documentId, DocumentStatus.EXTRACTED);
        assertThat(extracted.getKind()).isEqualTo("NF-e");
        assertThat(extractionRepository.findByDocumentId(documentId))
                .singleElement()
                .satisfies(extraction -> assertThat(extraction.getRequestId()).isEqualTo(secondRequestId));
    }

    @Test
    void should_refuseReprocess_when_documentIsNotDeadLettered() throws Exception {
        String token = bearerToken();
        UUID documentId = uploadNewDocument(token);
        UUID requestId = documentRepository.findById(documentId).orElseThrow().getCurrentRequestId();

        mockMvc.perform(post(DOCUMENTS_URL + "/" + documentId + "/reprocessar").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isConflict());

        assertThat(documentRepository.findById(documentId).orElseThrow().getCurrentRequestId())
                .isEqualTo(requestId);
    }

    @Test
    void should_returnNotFound_when_reprocessingUnknownDocument() throws Exception {
        mockMvc.perform(post(DOCUMENTS_URL + "/" + UUID.randomUUID() + "/reprocessar")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isNotFound());
    }
}
