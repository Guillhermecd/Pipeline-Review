package com.bimd.fiscalpipeline.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ReviewDecision;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import com.bimd.fiscalpipeline.domain.model.Review;
import com.bimd.fiscalpipeline.repository.PipelineEventRepository;
import com.bimd.fiscalpipeline.repository.ReviewRepository;
import com.bimd.fiscalpipeline.support.AbstractIntegrationTest;
import com.bimd.fiscalpipeline.support.FakeWorker.Received;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class ValidationDecisionIT extends AbstractIntegrationTest {

    @Autowired
    private PipelineEventRepository pipelineEventRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @ParameterizedTest
    @EnumSource(BrokerKind.class)
    void should_approveAutomatically_when_rulesPassAndConfidenceIsHigh(BrokerKind broker) throws Exception {
        String token = bearerToken();
        brokerService.switchTo(broker);

        UUID documentId = extract(token, worker().validPayload());

        Document document = awaitStatus(documentId, DocumentStatus.AUTO_APPROVED);
        assertThat(document.getFinishedAt()).isNotNull();
        fetch(documentId, token)
                .andExpect(jsonPath("$.status").value("AUTO_APPROVED"))
                .andExpect(jsonPath("$.outcome").value("auto"))
                .andExpect(jsonPath("$.review").doesNotExist())
                .andExpect(jsonPath("$.stage.label").value("Aprovado"))
                .andExpect(jsonPath("$.progress").value(1.0))
                .andExpect(jsonPath("$.reason").value("Confiança 95% e validações ok"))
                .andExpect(jsonPath("$.confidence").value(0.95))
                .andExpect(jsonPath("$.kind").value("NF-e"))
                .andExpect(jsonPath("$.cnpj").value("60.701.190/0001-04"))
                .andExpect(jsonPath("$.issuer").value("Papelaria Ponto Final"))
                .andExpect(jsonPath("$.issueDate").value("2026-09-28"))
                .andExpect(jsonPath("$.total").value("271.30"))
                .andExpect(jsonPath("$.itemsSum").value("271.30"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].description").value("Resma A4 75g"))
                .andExpect(jsonPath("$.items[0].quantity").value("5"))
                .andExpect(jsonPath("$.items[0].unitPrice").value("29.90"))
                .andExpect(jsonPath("$.fieldConfidence.cnpj").value(0.95))
                .andExpect(jsonPath("$.fieldConfidence.items").value(0.95))
                .andExpect(jsonPath("$.validations.length()").value(4))
                .andExpect(jsonPath("$.validations[0].name").value("Dígito verificador do CNPJ"))
                .andExpect(jsonPath("$.validations[0].ok").value(true))
                .andExpect(jsonPath("$.validations[0].detail").value("DV 04 confere"))
                .andExpect(jsonPath("$.validations[1].name").value("Soma dos itens = total"))
                .andExpect(jsonPath("$.validations[1].detail").value("R$ 271,30 vs R$ 271,30"))
                .andExpect(jsonPath("$.validations[2].name").value("Data não futura"))
                .andExpect(jsonPath("$.validations[2].detail").value("28/09/2026"))
                .andExpect(jsonPath("$.validations[3].name").value("Campos obrigatórios"))
                .andExpect(jsonPath("$.validations[3].detail").value("5 de 5 presentes"));
        assertThat(pipelineEventRepository.findByDocumentCodeOrderBySequenceAsc(document.getCode()))
                .extracting(PipelineEventRecord::getMessage)
                .containsSubsequence(
                        "consume " + broker.getOutputQueue(),
                        "validate cnpj.dv + soma.itens",
                        "score.confianca",
                        "UPDATE documento SET status",
                        "documento.aprovado (automático)");
    }

    @Test
    void should_goToReview_then_beApprovedAfterCorrection_when_itemsDoNotAddUpToTheTotal() throws Exception {
        String token = bearerToken();
        UUID documentId = extract(token, worker().examplePayload());

        Document document = awaitStatus(documentId, DocumentStatus.PENDING_REVIEW);
        String reason = "Validação falhou: Soma dos itens = total";
        // Not finished yet: the reviewer has the final word. The pipeline itself is done, though.
        assertThat(document.getFinishedAt()).isNull();
        String processedAt = document.getProcessedAt();
        assertThat(processedAt).isNotNull();
        fetch(documentId, token)
                .andExpect(jsonPath("$.outcome").value("review"))
                .andExpect(jsonPath("$.review").doesNotExist())
                .andExpect(jsonPath("$.finishedAt").doesNotExist())
                .andExpect(jsonPath("$.stage.label").value("Em revisão"))
                .andExpect(jsonPath("$.reason").value(reason))
                .andExpect(jsonPath("$.total").value("283.80"))
                .andExpect(jsonPath("$.validations[1].ok").value(false))
                .andExpect(jsonPath("$.validations[1].detail").value("R$ 271,30 vs R$ 283,80"));
        assertThat(pipelineEventRepository.findByDocumentCodeOrderBySequenceAsc(document.getCode()))
                .filteredOn(event -> event.getLevel() == EventLevel.WARN)
                .extracting(PipelineEventRecord::getMessage)
                .containsExactly("documento.em_revisao · " + reason);

        // The correction is validated and scored at once; the document stays in review.
        correct(documentId, token, "{\"total\":\"271.30\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.total").value("271.30"))
                .andExpect(jsonPath("$.validations[1].ok").value(true))
                .andExpect(jsonPath("$.validations[1].detail").value("R$ 271,30 vs R$ 271,30"))
                .andExpect(jsonPath("$.fieldConfidence.total").value(0.99))
                .andExpect(jsonPath("$.fieldConfidence.cnpj").value(0.95))
                // 0.95 everywhere except the total at 0.99: 0.95 + 0.04·0.25
                .andExpect(jsonPath("$.confidence").value(0.96));

        decide(documentId, token, "aprovar")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.outcome").value("review"))
                .andExpect(jsonPath("$.review").value("approved"))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.stage.label").value("Aprovado (revisão)"));
        Review review = reviewRepository.findByDocumentId(documentId).getFirst();
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.APPROVED);
        assertThat(review.getReviewerId()).isNotNull();
        assertThat(review.getCorrections()).isEqualTo(Map.of("total", Map.of("from", "283.80", "to", "271.30")));
        assertThat(pipelineEventRepository.findByDocumentCodeOrderBySequenceAsc(document.getCode()))
                .extracting(PipelineEventRecord::getMessage)
                .endsWith("revisão humana · documento.aprovado publicado");

        // A decided document accepts neither a second decision nor a correction.
        String finishedAt = documentRepository.findById(documentId).orElseThrow().getFinishedAt();
        String notInReview = "O documento %s não está em revisão (status atual: APPROVED)".formatted(document.getCode());
        decide(documentId, token, "aprovar")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(notInReview));
        decide(documentId, token, "rejeitar").andExpect(status().isConflict());
        correct(documentId, token, "{\"total\":\"1.00\"}").andExpect(status().isConflict());
        assertThat(reviewRepository.findByDocumentId(documentId)).hasSize(1);
        assertThat(documentRepository.findById(documentId).orElseThrow().getFinishedAt()).isEqualTo(finishedAt);
        // Neither the correction nor the decision moves the end of the automatic processing.
        assertThat(documentRepository.findById(documentId).orElseThrow().getProcessedAt()).isEqualTo(processedAt);
    }

    @Test
    void should_goToReview_then_beRejected_when_cnpjCheckDigitIsWrong() throws Exception {
        String token = bearerToken();
        ObjectNode payload = worker().validPayload();
        field(payload, "cnpj").put("value", "60.701.190/0001-05");
        UUID documentId = extract(token, payload);

        Document document = awaitStatus(documentId, DocumentStatus.PENDING_REVIEW);
        fetch(documentId, token)
                .andExpect(jsonPath("$.reason").value("Validação falhou: Dígito verificador do CNPJ"))
                .andExpect(jsonPath("$.validations[0].ok").value(false))
                .andExpect(jsonPath("$.validations[0].detail").value("DV calculado 04, lido 05"));

        // An alphanumeric CNPJ is a valid correction.
        correct(documentId, token, "{\"cnpj\":\"12.ABC.345/01DE-35\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cnpj").value("12.ABC.345/01DE-35"))
                .andExpect(jsonPath("$.validations[0].ok").value(true))
                .andExpect(jsonPath("$.validations[0].detail").value("DV 35 confere"))
                .andExpect(jsonPath("$.fieldConfidence.cnpj").value(0.99));

        decide(documentId, token, "rejeitar")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.review").value("rejected"))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                .andExpect(jsonPath("$.stage.label").value("Rejeitado"));
        Review review = reviewRepository.findByDocumentId(documentId).getFirst();
        assertThat(review.getDecision()).isEqualTo(ReviewDecision.REJECTED);
        assertThat(review.getCorrections())
                .isEqualTo(Map.of("cnpj", Map.of("from", "60.701.190/0001-05", "to", "12.ABC.345/01DE-35")));
        assertThat(pipelineEventRepository.findByDocumentCodeOrderBySequenceAsc(document.getCode()))
                .last()
                .satisfies(event -> {
                    assertThat(event.getMessage()).isEqualTo("revisão humana · documento.rejeitado");
                    assertThat(event.getLevel()).isEqualTo(EventLevel.WARN);
                });
    }

    @Test
    void should_goToReview_when_confidenceIsBelowTheThreshold() throws Exception {
        String token = bearerToken();
        ObjectNode payload = worker().validPayload();
        for (String name : new String[] {"cnpj", "issuer", "issueDate", "total"}) {
            field(payload, name).put("confidence", 0.78).put("source", "ocr");
        }
        payload.put("itemsConfidence", 0.78);
        UUID documentId = extract(token, payload);

        awaitStatus(documentId, DocumentStatus.PENDING_REVIEW);
        fetch(documentId, token)
                .andExpect(jsonPath("$.reason").value("Confiança 78% abaixo do limiar 85%"))
                .andExpect(jsonPath("$.confidence").value(0.78))
                .andExpect(jsonPath("$.validations[?(@.ok == false)]").isEmpty());

        // Approving with nothing corrected records an empty set of corrections.
        decide(documentId, token, "aprovar").andExpect(status().isOk());
        assertThat(reviewRepository.findByDocumentId(documentId).getFirst().getCorrections()).isEmpty();
    }

    /**
     * The listener retries until it succeeds, so data it cannot store must never make it throw:
     * the document would be stuck and the queue behind it too.
     */
    @ParameterizedTest
    @EnumSource(BrokerKind.class)
    void should_endInReviewInsteadOfGettingStuck_when_payloadIsHostile(BrokerKind broker) throws Exception {
        String token = bearerToken();
        brokerService.switchTo(broker);
        ObjectNode payload = worker().validPayload();
        payload.put("documentKind", "k".repeat(200));
        field(payload, "cnpj").putNull("value");
        field(payload, "issuer").put("value", "nome\u0000com nulo " + "x".repeat(400));
        field(payload, "issueDate").put("value", "28/09/2026");
        field(payload, "total").put("value", "283,80").put("confidence", 99).put("source", "s".repeat(80));
        ArrayNode items = payload.putArray("items");
        items.addObject().put("description", "d".repeat(900)).put("quantity", "99999999999").put("unitPrice", "1.00");
        items.addObject().put("description", "Sem preço").put("quantity", "1");
        items.add("texto solto");
        payload.put("itemsConfidence", "alta");
        payload.put("llmModel", "m".repeat(300));
        payload.putObject("tokens").put("input", "muitos");

        UUID documentId = extract(token, payload);

        awaitStatus(documentId, DocumentStatus.PENDING_REVIEW);
        fetch(documentId, token)
                .andExpect(jsonPath("$.reason")
                        .value("Validação falhou: Dígito verificador do CNPJ, Soma dos itens = total,"
                                + " Data não futura, Campos obrigatórios"))
                .andExpect(jsonPath("$.cnpj").doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.validations[3].detail").value("1 de 5 presentes"));

        // The queue is not blocked: the next document goes through.
        UUID next = extract(token, worker().validPayload());
        awaitStatus(next, DocumentStatus.AUTO_APPROVED);
    }

    @Test
    void should_rejectCorrection_when_bodyIsInvalid() throws Exception {
        String token = bearerToken();
        UUID documentId = extract(token, worker().examplePayload());
        awaitStatus(documentId, DocumentStatus.PENDING_REVIEW);

        correct(documentId, token, "{\"total\":\"271,30\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("total: deve ser um valor com ponto e duas casas decimais, como 283.80"));
        correct(documentId, token, "{\"total\":\"271.3\"}").andExpect(status().isBadRequest());
        correct(documentId, token, "{\"cnpj\":\"" + "1".repeat(19) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("cnpj: deve ter de 1 a 18 caracteres"));
        correct(documentId, token, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Informe o CNPJ, o total ou os dois para corrigir"));
        correct(documentId, token, "isto não é json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("O corpo da requisição é inválido ou tem um valor não aceito"));

        fetch(documentId, token).andExpect(jsonPath("$.total").value("283.80"));
    }

    @Test
    void should_refuseReviewActions_when_documentIsNotInReview() throws Exception {
        String token = bearerToken();
        UUID approved = extract(token, worker().validPayload());
        awaitStatus(approved, DocumentStatus.AUTO_APPROVED);
        UUID inFlight = uploadNewDocument(token);

        for (UUID documentId : new UUID[] {approved, inFlight}) {
            correct(documentId, token, "{\"total\":\"1.00\"}").andExpect(status().isConflict());
            decide(documentId, token, "aprovar").andExpect(status().isConflict());
            decide(documentId, token, "rejeitar").andExpect(status().isConflict());
        }
        UUID unknown = UUID.randomUUID();
        decide(unknown, token, "aprovar")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Documento %s não encontrado".formatted(unknown)));
        mockMvc.perform(post(DOCUMENTS_URL + "/nao-e-uuid/aprovar").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Valor inválido para 'id'"));
        mockMvc.perform(get("/api/v1/rota-que-nao-existe").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound());
        assertThat(reviewRepository.findByDocumentId(approved)).isEmpty();
    }

    @Test
    void should_exposeThreshold_when_settingsAreRequested() throws Exception {
        mockMvc.perform(get("/api/v1/configuracao").header(HttpHeaders.AUTHORIZATION, bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threshold").value(0.85));
    }

    /** Uploads a document and answers as the worker would, with the given extraction. */
    private UUID extract(String token, ObjectNode payload) throws Exception {
        UUID documentId = uploadNewDocument(token);
        Document document = documentRepository.findById(documentId).orElseThrow();
        Received input = worker().awaitInput(document.getBroker(), documentId, document.getCurrentRequestId());
        worker().extracted(input, 1, payload);
        return documentId;
    }

    private ObjectNode field(ObjectNode payload, String name) {
        return (ObjectNode) payload.get("fields").get(name);
    }

    private ResultActions fetch(UUID documentId, String token) throws Exception {
        return mockMvc.perform(get(DOCUMENTS_URL + "/" + documentId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }

    private ResultActions correct(UUID documentId, String token, String body) throws Exception {
        return mockMvc.perform(patch(DOCUMENTS_URL + "/" + documentId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions decide(UUID documentId, String token, String action) throws Exception {
        return mockMvc.perform(
                post(DOCUMENTS_URL + "/" + documentId + "/" + action).header(HttpHeaders.AUTHORIZATION, token));
    }
}
