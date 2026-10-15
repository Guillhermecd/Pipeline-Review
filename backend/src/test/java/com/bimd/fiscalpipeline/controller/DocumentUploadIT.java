package com.bimd.fiscalpipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.OutboxEvent;
import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.repository.OutboxEventRepository;
import com.bimd.fiscalpipeline.repository.PipelineEventRepository;
import com.bimd.fiscalpipeline.support.AbstractIntegrationTest;
import com.bimd.fiscalpipeline.support.FakeWorker.Received;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;
import com.bimd.fiscalpipeline.util.Sha256;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

class DocumentUploadIT extends AbstractIntegrationTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };



    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PipelineEventRepository pipelineEventRepository;

    @Autowired
    private S3Client s3Client;

    @Value("${app.storage.bucket}")
    private String bucket;

    @Test
    void should_storeDocumentOutboxAndFile_when_uploadIsAccepted() throws Exception {
        byte[] content = uniqueContent();
        String expectedKey = "sha256:" + Sha256.hex(content);

        String body = mockMvc.perform(multipart(DOCUMENTS_URL)
                        .file(new MockMultipartFile("file", "nfe papelaria.pdf", "application/pdf", content))
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.startsWith("DOC-")))
                .andExpect(jsonPath("$.fileName").value("nfe papelaria.pdf"))
                .andExpect(jsonPath("$.idempotencyKey").value(expectedKey))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.stage.label").value("Recebido"))
                .andExpect(jsonPath("$.stage.node").value("api"))
                .andExpect(jsonPath("$.stage.service").value("api"))
                .andExpect(jsonPath("$.progress").value(0.10))
                .andExpect(jsonPath("$.broker").value("kafka"))
                .andExpect(jsonPath("$.outcome").doesNotExist())
                .andExpect(jsonPath("$.itemsSum").value("0.00"))
                .andExpect(jsonPath("$.reason").value(""))
                .andExpect(jsonPath("$.traceId").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.receivedAt")
                        .value(org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode response = objectMapper.readTree(body);
        UUID documentId = UUID.fromString(response.get("id").asText());

        // The relay takes the message from the outbox to the input topic and the document to QUEUED.
        Document document = awaitStatus(documentId, DocumentStatus.QUEUED);
        assertThat(document.getIdempotencyKey()).isEqualTo(expectedKey);
        assertThat(document.getBroker()).isEqualTo(BrokerKind.KAFKA);

        List<OutboxEvent> outbox = outboxEventRepository.findByDocumentId(documentId);
        assertThat(outbox).hasSize(1);
        OutboxEvent event = outbox.getFirst();
        assertThat(event.getEventType()).isEqualTo("documento.recebido");
        assertThat(event.getDestination()).isEqualTo("documentos.recebidos");
        assertThat(event.getPublishedAt()).isNotNull();
        Received queued = worker().awaitInput(BrokerKind.KAFKA, documentId, document.getCurrentRequestId());
        assertThat(objectMapper.convertValue(queued.envelope(), MAP_TYPE)).isEqualTo(event.getPayload());
        assertThat(queued.headers()).containsEntry("traceparent", event.getTraceparent());
        assertThat(event.getTraceparent()).matches("00-" + document.getTraceId() + "-[0-9a-f]{16}-01");
        assertThat(event.getPayload())
                .containsEntry("eventId", event.getId().toString())
                .containsEntry("documentId", documentId.toString())
                .containsEntry("documentCode", document.getCode())
                .containsEntry("idempotencyKey", expectedKey)
                .containsEntry("requestId", document.getCurrentRequestId().toString())
                .containsEntry("attempt", 1)
                .containsEntry("broker", "kafka")
                .containsEntry("schemaVersion", 1);
        assertThat(event.getPayload().get("payload")).asInstanceOf(
                        org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("fileKey", document.getFileKey())
                .containsEntry("mimeType", "application/pdf")
                .containsEntry("fileName", "nfe papelaria.pdf");

        ResponseBytes<GetObjectResponse> stored = s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(document.getFileKey()).build());
        assertThat(stored.asByteArray()).isEqualTo(content);

        List<PipelineEventRecord> events =
                pipelineEventRepository.findByDocumentCodeOrderBySequenceAsc(document.getCode());
        assertThat(events)
                .extracting(PipelineEventRecord::getMessage, PipelineEventRecord::getLevel)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("POST /api/v1/documentos", EventLevel.OK),
                        org.assertj.core.groups.Tuple.tuple("publish documentos.recebidos", EventLevel.OK));
    }

    @Test
    void should_returnDocument_when_fetchedById() throws Exception {
        String token = bearerToken();
        String body = mockMvc.perform(multipart(DOCUMENTS_URL)
                        .file(new MockMultipartFile("file", "cupom.png", "image/png", uniqueContent()))
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        mockMvc.perform(get(DOCUMENTS_URL + "/" + id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.fileName").value("cupom.png"));

        mockMvc.perform(get(DOCUMENTS_URL).param("limit", "1").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    void should_returnNotFound_when_documentDoesNotExist() throws Exception {
        mockMvc.perform(get(DOCUMENTS_URL + "/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_rejectUpload_when_fileTypeIsNotSupported() throws Exception {
        long before = documentRepository.count();

        mockMvc.perform(multipart(DOCUMENTS_URL)
                        .file(new MockMultipartFile("file", "planilha.csv", "text/csv", uniqueContent()))
                        .header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(documentRepository.count()).isEqualTo(before);
    }

    @Test
    void should_rejectUpload_when_userIsNotAuthenticated() throws Exception {
        mockMvc.perform(multipart(DOCUMENTS_URL)
                        .file(new MockMultipartFile("file", "nfe.pdf", "application/pdf", uniqueContent())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void should_ignoreQueryStringToken_when_routeIsNotTheEventStream() throws Exception {
        String token = bearerToken().substring("Bearer ".length());

        mockMvc.perform(get(DOCUMENTS_URL).param("access_token", token))
                .andExpect(status().isUnauthorized());
    }

}
