package com.bimd.fiscalpipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.awaitility.Awaitility.await;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.repository.ExtractionRepository;
import com.bimd.fiscalpipeline.repository.ProcessedEventRepository;
import com.bimd.fiscalpipeline.support.FakeWorker.Received;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.repository.OutboxEventRepository;
import com.bimd.fiscalpipeline.repository.PipelineEventRepository;
import com.bimd.fiscalpipeline.support.AbstractIntegrationTest;
import com.bimd.fiscalpipeline.util.Sha256;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class IdempotencyIT extends AbstractIntegrationTest {

    private static final int CONCURRENT_UPLOADS = 6;
    private static final Duration QUIET = Duration.ofSeconds(2);

    @Autowired
    private ExtractionRepository extractionRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;


    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PipelineEventRepository pipelineEventRepository;

    @Test
    void should_returnConflictAndCreateNothing_when_sameFileIsUploadedAgain() throws Exception {
        String token = bearerToken();
        byte[] content = uniqueContent();
        String hash = Sha256.hex(content);

        String firstBody = mockMvc.perform(upload("nfe.pdf", content, token))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode first = objectMapper.readTree(firstBody);
        UUID documentId = UUID.fromString(first.get("id").asText());
        String code = first.get("code").asText();
        long documentsAfterFirst = documentRepository.count();
        long outboxAfterFirst = outboxEventRepository.count();

        // Same bytes under another name: the key is the content, not the file name.
        mockMvc.perform(upload("copia-da-nfe.pdf", content, token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("idempotency_key sha256:" + hash.substring(0, 8) + "… já existe"))
                .andExpect(jsonPath("$.idempotencyKey").value("sha256:" + hash))
                .andExpect(jsonPath("$.documentId").value(documentId.toString()))
                .andExpect(jsonPath("$.code").value(code));

        assertThat(documentRepository.count()).isEqualTo(documentsAfterFirst);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxAfterFirst);
        assertThat(outboxEventRepository.findByDocumentId(documentId)).hasSize(1);

        // The duplicate is logged against no document ("—"), the accepted upload against its code.
        assertThat(pipelineEventRepository.findByDocumentCodeOrderByOccurredAtAsc(code))
                .filteredOn(event -> event.getMessage().equals("POST /api/v1/documentos"))
                .hasSize(1);
        List<PipelineEventRecord> duplicates = pipelineEventRepository.findByDocumentCodeOrderByOccurredAtAsc("—");
        assertThat(duplicates)
                .filteredOn(event -> event.getMessage()
                        .equals("409 duplicado · sha256:" + hash.substring(0, 8) + " já processado"))
                .singleElement()
                .satisfies(event -> assertThat(event.getLevel()).isEqualTo(EventLevel.WARN));
    }

    @Test
    void should_createSeparateDocuments_when_filesDiffer() throws Exception {
        String token = bearerToken();

        String firstCode = codeOf(mockMvc.perform(upload("a.pdf", uniqueContent(), token))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());
        String secondCode = codeOf(mockMvc.perform(upload("a.pdf", uniqueContent(), token))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString());

        assertThat(secondCode).isNotEqualTo(firstCode);
    }

    /**
     * Delivery is at least once on every hop: the broker may hand the same result over again, and
     * a relay that died after publishing makes the worker answer the same request twice.
     */
    @ParameterizedTest
    @EnumSource(BrokerKind.class)
    void should_storeOneExtraction_when_resultIsDeliveredMoreThanOnce(BrokerKind broker) throws Exception {
        brokerService.switchTo(broker);
        UUID documentId = uploadNewDocument(bearerToken());
        UUID requestId = documentRepository.findById(documentId).orElseThrow().getCurrentRequestId();
        Received input = worker().awaitInput(broker, documentId, requestId);

        ObjectNode result = worker().extracted(input, 1);
        awaitStatus(documentId, DocumentStatus.AUTO_APPROVED);
        // The same message again, then a second answer to the same request.
        worker().resend(input, result);
        worker().extracted(input, 1);

        await().during(QUIET)
                .atMost(PIPELINE_TIMEOUT)
                .until(() -> extractionRepository.findByDocumentId(documentId).size() == 1);
        assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                .isEqualTo(DocumentStatus.AUTO_APPROVED);
        assertThat(processedEventRepository.existsById(UUID.fromString(result.get("eventId").asText())))
                .isTrue();
    }

    /** The pre-check alone cannot stop a race; the UNIQUE constraint has to. */
    @Test
    void should_acceptExactlyOne_when_sameFileIsUploadedConcurrently() throws Exception {
        String token = bearerToken();
        byte[] content = uniqueContent();
        String idempotencyKey = "sha256:" + Sha256.hex(content);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_UPLOADS);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < CONCURRENT_UPLOADS; i++) {
                Callable<Integer> task = () -> {
                    start.await();
                    return mockMvc.perform(upload("nfe.pdf", content, token))
                            .andReturn()
                            .getResponse()
                            .getStatus();
                };
                results.add(executor.submit(task));
            }
            start.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(statuses).filteredOn(code -> code == HttpStatus.ACCEPTED.value()).hasSize(1);
            assertThat(statuses)
                    .filteredOn(code -> code == HttpStatus.CONFLICT.value())
                    .hasSize(CONCURRENT_UPLOADS - 1);
        } finally {
            executor.shutdownNow();
        }

        UUID documentId = documentRepository.findByIdempotencyKey(idempotencyKey).orElseThrow().getId();
        assertThat(outboxEventRepository.findByDocumentId(documentId)).hasSize(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder upload(
            String fileName, byte[] content, String token) {
        return multipart(DOCUMENTS_URL)
                        .file(new MockMultipartFile("file", fileName, "application/pdf", content))
                        .header(HttpHeaders.AUTHORIZATION, token);
    }

    private String codeOf(String body) throws Exception {
        return objectMapper.readTree(body).get("code").asText();
    }

}
