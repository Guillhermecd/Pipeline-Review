package com.bimd.fiscalpipeline.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.exception.IllegalStateTransitionException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PipelineStateServiceTest {

    private final PipelineStateService service =
            new PipelineStateService(Clock.fixed(Instant.parse("2026-10-13T13:20:00Z"), ZoneOffset.UTC));

    @ParameterizedTest
    @CsvSource({
        "RECEIVED, QUEUED",
        "QUEUED, OCR",
        "RETRY_WAIT, OCR",
        "OCR, EXTRACTING",
        "OCR, RETRY_WAIT",
        "EXTRACTING, RETRY_WAIT",
        "EXTRACTING, EXTRACTED",
        "EXTRACTED, VALIDATING",
        "VALIDATING, AUTO_APPROVED",
        "VALIDATING, PENDING_REVIEW",
        "QUEUED, DEAD_LETTERED",
        "OCR, DEAD_LETTERED",
        "EXTRACTING, DEAD_LETTERED",
        "RETRY_WAIT, DEAD_LETTERED",
        "PENDING_REVIEW, APPROVED",
        "PENDING_REVIEW, REJECTED",
        "DEAD_LETTERED, QUEUED",
    })
    void should_allowMove_when_transitionIsInTheSpecification(DocumentStatus from, DocumentStatus to) {
        assertThat(service.canMove(from, to)).isTrue();
    }

    /** The result travels on another queue and may overtake the progress message before it. */
    @ParameterizedTest
    @EnumSource(
            value = DocumentStatus.class,
            names = {"QUEUED", "OCR", "RETRY_WAIT"})
    void should_acceptResult_when_progressMessageHasNotArrivedYet(DocumentStatus from) {
        assertThat(service.canMove(from, DocumentStatus.EXTRACTED)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "EXTRACTED, OCR",
        "EXTRACTED, EXTRACTING",
        "EXTRACTED, EXTRACTED",
        "EXTRACTED, RETRY_WAIT",
        "EXTRACTED, DEAD_LETTERED",
        "DEAD_LETTERED, OCR",
        "DEAD_LETTERED, RETRY_WAIT",
        "DEAD_LETTERED, EXTRACTED",
        "DEAD_LETTERED, DEAD_LETTERED",
        "RECEIVED, OCR",
        "RECEIVED, EXTRACTED",
        "QUEUED, QUEUED",
        "AUTO_APPROVED, QUEUED",
        "AUTO_APPROVED, EXTRACTED",
        "APPROVED, REJECTED",
        "REJECTED, APPROVED",
        "PENDING_REVIEW, QUEUED",
        "RETRY_WAIT, RETRY_WAIT",
    })
    void should_refuseMove_when_transitionGoesBackOrLeavesAFinalState(DocumentStatus from, DocumentStatus to) {
        assertThat(service.canMove(from, to)).isFalse();
    }

    @Test
    void should_changeStatusAndUpdatedAt_when_moveIsAllowed() {
        Document document = document(DocumentStatus.QUEUED);

        service.move(document, DocumentStatus.OCR);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.OCR);
        assertThat(document.getUpdatedAt()).isEqualTo("2026-10-13T13:20:00.000Z");
    }

    @Test
    void should_throwAndKeepStatus_when_moveIsNotAllowed() {
        Document document = document(DocumentStatus.AUTO_APPROVED);

        assertThatThrownBy(() -> service.move(document, DocumentStatus.QUEUED))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessage("O documento DOC-1007 não pode passar de AUTO_APPROVED para QUEUED");

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.AUTO_APPROVED);
    }

    private Document document(DocumentStatus status) {
        Document document = new Document();
        document.setCode("DOC-1007");
        document.setStatus(status);
        return document;
    }
}
