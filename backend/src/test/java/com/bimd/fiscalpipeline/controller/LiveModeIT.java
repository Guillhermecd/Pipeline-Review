package com.bimd.fiscalpipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.support.AbstractIntegrationTest;
import com.bimd.fiscalpipeline.support.FakeWorker.Received;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/** What the frontend needs to run against the real API: the event stream, the file link, traces. */
class LiveModeIT extends AbstractIntegrationTest {

    private static final String STREAM_URL = "/api/v1/eventos";

    @Autowired
    private PipelineEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void should_sendSnapshotThenEachChange_when_clientFollowsTheStream() throws Exception {
        String token = bearerToken();
        UUID earlier = uploadNewDocument(token);
        awaitStatus(earlier, DocumentStatus.QUEUED);

        MockHttpServletResponse stream = openStream(token);

        // The connection opens with the whole state.
        assertThat(stream.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        String snapshot = stream.getContentAsString(StandardCharsets.UTF_8);
        assertThat(snapshot).startsWith("event:snapshot");
        assertThat(snapshot)
                .contains("\"id\":\"" + earlier + "\"")
                .contains("\"threshold\":0.85")
                .contains("\"broker\":{\"kind\":\"kafka\"")
                .contains("\"simulator\":{\"connected\":false")
                .contains("\"message\":\"publish documentos.recebidos\"");
        // HH:mm:ss, for the "Eventos" card.
        assertThat(snapshot).containsPattern("\"time\":\"\\d{2}:\\d{2}:\\d{2}\"");

        // Then one event per change, as the document moves.
        UUID documentId = uploadNewDocument(token);
        Document document = documentRepository.findById(documentId).orElseThrow();
        Received input = worker().awaitInput(BrokerKind.KAFKA, documentId, document.getCurrentRequestId());
        worker().extracted(input, 1);
        awaitStatus(documentId, DocumentStatus.AUTO_APPROVED);

        await().atMost(PIPELINE_TIMEOUT)
                .untilAsserted(() -> assertThat(stream.getContentAsString(StandardCharsets.UTF_8).substring(snapshot.length()))
                        .contains("event:document")
                        .contains("\"id\":\"" + documentId + "\",\"code\":\"" + document.getCode() + "\"")
                        .contains("\"status\":\"QUEUED\"")
                        .contains("\"status\":\"AUTO_APPROVED\"")
                        .contains("event:event")
                        .contains("\"message\":\"documento.aprovado (automático)\"")
                        .contains("event:envelope")
                        .contains("\"eventType\":\"documento.recebido\",\"documentId\":\"" + documentId + "\"")
                        .contains("\"eventType\":\"documento.extraido\",\"documentId\":\"" + documentId + "\""));
        // Intermediate states that only exist inside the transaction never reach the screen.
        assertThat(stream.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("\"status\":\"VALIDATING\"");

        brokerService.switchTo(BrokerKind.RABBITMQ);
        await().atMost(PIPELINE_TIMEOUT)
                .untilAsserted(() -> assertThat(stream.getContentAsString(StandardCharsets.UTF_8).substring(snapshot.length()))
                        .contains("event:broker")
                        .contains("{\"kind\":\"rabbitmq\",\"label\":\"RabbitMQ\""));
    }

    /**
     * Message listeners retry until they succeed. If a failed attempt reached the screen, the same
     * change would show up once per attempt, and a change that never happened would show up too.
     */
    @Test
    void should_notSendWhatWasRolledBack_when_transactionFails() throws Exception {
        MockHttpServletResponse stream = openStream(bearerToken());
        String rolledBack = "rolled back " + UUID.randomUUID();
        String committed = "committed " + UUID.randomUUID();

        transactionTemplate.executeWithoutResult(transaction -> {
            eventPublisher.publish(ServiceName.API, "—", rolledBack, EventLevel.OK);
            transaction.setRollbackOnly();
        });
        transactionTemplate.executeWithoutResult(
                transaction -> eventPublisher.publish(ServiceName.API, "—", committed, EventLevel.OK));

        await().atMost(PIPELINE_TIMEOUT)
                .untilAsserted(() -> assertThat(stream.getContentAsString(StandardCharsets.UTF_8)).contains(committed));
        assertThat(stream.getContentAsString(StandardCharsets.UTF_8)).doesNotContain(rolledBack);
    }

    @Test
    void should_acceptTokenInQueryString_when_openingTheStream() throws Exception {
        String token = bearerToken().substring("Bearer ".length());

        mockMvc.perform(get(STREAM_URL).param("access_token", token))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());
        mockMvc.perform(get(STREAM_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(STREAM_URL).param("access_token", "not-a-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void should_linkToTheUploadedFile_when_previewIsRequested() throws Exception {
        String token = bearerToken();
        byte[] content = uniqueContent();
        String uploaded = mockMvc.perform(MockMvcRequestBuilders.multipart(DOCUMENTS_URL)
                        .file(new MockMultipartFile("file", "nota.pdf", "application/pdf", content))
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String documentId = objectMapper.readTree(uploaded).get("id").asText();

        String body = mockMvc.perform(
                        get(DOCUMENTS_URL + "/" + documentId + "/arquivo").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String url = objectMapper.readTree(body).get("url").asText();
        Instant expiresAt = Instant.parse(objectMapper.readTree(body).get("expiresAt").asText());

        // The link works by itself, with no credentials, and serves the bytes that were uploaded.
        HttpResponse<byte[]> file = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(content);
        assertThat(file.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(expiresAt).isAfter(Instant.now());

        mockMvc.perform(get(DOCUMENTS_URL + "/" + UUID.randomUUID() + "/arquivo")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_explainItself_when_traceCannotBeRead() throws Exception {
        String token = bearerToken();

        // Jaeger is not running in the tests.
        mockMvc.perform(get("/api/v1/traces/" + "a".repeat(32)).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Jaeger indisponível: não foi possível consultar o trace"));
        // Anything that is not a trace id is refused before it reaches Jaeger.
        mockMvc.perform(get("/api/v1/traces/..%2Fapi%2Fservices").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/traces/nao-e-um-trace").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.startsWith("Trace nao-e-um-trace ainda não disponível")));
    }

    private MockHttpServletResponse openStream(String token) throws Exception {
        return mockMvc.perform(get(STREAM_URL).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(request().asyncStarted())
                .andReturn()
                .getResponse();
    }
}
