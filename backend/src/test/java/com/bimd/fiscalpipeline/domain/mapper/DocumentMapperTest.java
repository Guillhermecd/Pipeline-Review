package com.bimd.fiscalpipeline.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.bimd.fiscalpipeline.domain.dto.DocumentResponse;
import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.Outcome;
import com.bimd.fiscalpipeline.domain.enums.PipelineNode;
import com.bimd.fiscalpipeline.domain.enums.ReviewDecision;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;

class DocumentMapperTest {

    private final DocumentMapper mapper = Mappers.getMapper(DocumentMapper.class);

    /** The table of BACKEND.md §6.2: what each status shows on screen. */
    @ParameterizedTest
    @CsvSource({
        "RECEIVED,       Recebido,           API,        API,    0.10, ,       ",
        "QUEUED,         Na fila,            BROKER,     BROKER, 0.20, ,       ",
        "OCR,            OCR,                WORKER,     WORKER, 0.45, ,       ",
        "EXTRACTING,     Extração,           WORKER,     WORKER, 0.65, ,       ",
        "RETRY_WAIT,     Retry 2/3,          RETRY,      BROKER, 0.50, ,       ",
        "EXTRACTED,      Validação,          BROKER,     BROKER, 0.80, ,       ",
        "VALIDATING,     Validação,          VALIDATION, API,    0.90, ,       ",
        "AUTO_APPROVED,  Aprovado,           VALIDATION, API,    1,    AUTO,   ",
        "PENDING_REVIEW, Em revisão,         VALIDATION, API,    1,    REVIEW, ",
        "APPROVED,       Aprovado (revisão), VALIDATION, API,    1,    REVIEW, APPROVED",
        "REJECTED,       Rejeitado,          VALIDATION, API,    1,    REVIEW, REJECTED",
        "DEAD_LETTERED,  DLQ,                RETRY,      BROKER, 1,    DLQ,    "
    })
    void should_exposeStageProgressAndOutcome_forEachStatus(
            DocumentStatus status,
            String label,
            PipelineNode node,
            ServiceName service,
            double progress,
            Outcome outcome,
            ReviewDecision review) {
        Document document = document(status);
        document.setAttempts(2);

        DocumentResponse response = mapper.toResponse(document, 0);

        assertThat(response.stage()).isEqualTo(new DocumentResponse.Stage(label, node, service));
        assertThat(response.progress()).isEqualTo(progress);
        assertThat(response.outcome()).isEqualTo(outcome);
        assertThat(response.review()).isEqualTo(review);
    }

    @Test
    void should_fillNeutralDefaults_when_documentHasNoExtractionYet() {
        DocumentResponse response = mapper.toResponse(document(DocumentStatus.RECEIVED), 1500);

        assertThat(response.reason()).isEmpty();
        assertThat(response.confidence()).isZero();
        assertThat(response.items()).isEmpty();
        assertThat(response.validations()).isEmpty();
        assertThat(response.itemsSum()).isEqualTo(new BigDecimal("0.00"));
        assertThat(response.fieldConfidence())
                .containsOnlyKeys("cnpj", "issuer", "issueDate", "total", "items")
                .containsValue(0.0);
        assertThat(response.durationMs()).isEqualTo(1500);
        assertThat(response.lastError()).isNull();
    }

    @Test
    void should_copyStoredValues_when_documentHasThem() {
        Document document = document(DocumentStatus.PENDING_REVIEW);
        document.setReason("Confiança 78% abaixo do limiar 85%");
        document.setConfidence(new BigDecimal("0.7800"));
        document.setFinishedAt("2026-10-13T13:20:04.000Z");

        DocumentResponse response = mapper.toResponse(document, 4000);

        assertThat(response.id()).isEqualTo(document.getId());
        assertThat(response.code()).isEqualTo("DOC-1007");
        assertThat(response.broker()).isEqualTo(BrokerKind.RABBITMQ);
        assertThat(response.reason()).isEqualTo("Confiança 78% abaixo do limiar 85%");
        assertThat(response.confidence()).isEqualTo(0.78);
        assertThat(response.finishedAt()).isEqualTo("2026-10-13T13:20:04.000Z");
    }

    private Document document(DocumentStatus status) {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setCode("DOC-1007");
        document.setFileName("nfe.pdf");
        document.setIdempotencyKey("sha256:abc");
        document.setTraceId("0af7651916cd43dd8448eb211c80319c");
        document.setBroker(BrokerKind.RABBITMQ);
        document.setStatus(status);
        document.setReceivedAt("2026-10-13T13:20:00.000Z");
        return document;
    }
}
