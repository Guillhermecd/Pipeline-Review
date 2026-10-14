package com.bimd.fiscalpipeline.service.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.Extraction;
import com.bimd.fiscalpipeline.domain.model.ProcessedEvent;
import com.bimd.fiscalpipeline.messaging.EventEnvelope;
import com.bimd.fiscalpipeline.messaging.ProgressPayload;
import com.bimd.fiscalpipeline.messaging.ProgressStage;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.repository.ExtractionRepository;
import com.bimd.fiscalpipeline.repository.ProcessedEventRepository;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PipelineInboundServiceTest {

    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final String NOW = "2026-10-13T13:20:00.000Z";

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtractionRepository extractionRepository;

    @Mock
    private PipelineEventPublisher eventPublisher;

    private PipelineInboundService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-13T13:20:00Z"), ZoneOffset.UTC);
        service = new PipelineInboundService(
                documentRepository,
                processedEventRepository,
                extractionRepository,
                new PipelineStateService(clock),
                eventPublisher,
                clock);
    }

    @Test
    void should_moveToOcrAndRecordEvent_when_progressPassesTheGuard() {
        Document document = stored(DocumentStatus.QUEUED, 0);
        EventEnvelope envelope = envelope(REQUEST_ID, 1, Map.of());

        service.onProgress(envelope, new ProgressPayload(ProgressStage.OCR, 1, null, null));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.OCR);
        assertThat(document.getAttempts()).isEqualTo(1);
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo(envelope.eventId());
        assertThat(processed.getValue().getProcessedAt()).isEqualTo(NOW);
        verify(eventPublisher).publish(ServiceName.WORKER, "DOC-1007", "consume documentos.recebidos", EventLevel.OK);
    }

    @Test
    void should_nameTheAttempt_when_ocrStartsAgainAfterARetry() {
        stored(DocumentStatus.RETRY_WAIT, 1);

        service.onProgress(envelope(REQUEST_ID, 2, Map.of()), new ProgressPayload(ProgressStage.OCR, 2, null, null));

        verify(eventPublisher)
                .publish(ServiceName.WORKER, "DOC-1007", "consume documentos.recebidos · tentativa 2", EventLevel.OK);
    }

    @Test
    void should_keepErrorAndAnnounceWait_when_workerWillRetry() {
        Document document = stored(DocumentStatus.EXTRACTING, 2);

        service.onProgress(
                envelope(REQUEST_ID, 2, Map.of()),
                new ProgressPayload(ProgressStage.RETRY_WAIT, 2, "429 rate limit excedido", 2000L));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.RETRY_WAIT);
        assertThat(document.getAttempts()).isEqualTo(2);
        assertThat(document.getLastError()).isEqualTo("429 rate limit excedido");
        verify(eventPublisher)
                .publish(ServiceName.BROKER, "DOC-1007", "retry +2s → documentos.recebidos-retry", EventLevel.WAIT);
    }

    @Test
    void should_dropMessage_when_eventWasAlreadyApplied() {
        Document document = stored(DocumentStatus.QUEUED, 0);
        EventEnvelope envelope = envelope(REQUEST_ID, 1, Map.of());
        when(processedEventRepository.existsById(envelope.eventId())).thenReturn(true);

        service.onProgress(envelope, new ProgressPayload(ProgressStage.OCR, 1, null, null));

        assertUntouched(document, DocumentStatus.QUEUED);
    }

    @Test
    void should_dropMessage_when_itAnswersAnOlderRequest() {
        Document document = stored(DocumentStatus.QUEUED, 0);

        service.onExtracted(envelope(UUID.randomUUID(), 1, Map.of("documentKind", "NF-e")));

        assertUntouched(document, DocumentStatus.QUEUED);
        verifyNoInteractions(extractionRepository);
    }

    @Test
    void should_dropMessage_when_transitionIsNotAllowed() {
        Document document = stored(DocumentStatus.EXTRACTED, 1);

        service.onProgress(envelope(REQUEST_ID, 1, Map.of()), new ProgressPayload(ProgressStage.EXTRACTING, 1, null, null));

        assertUntouched(document, DocumentStatus.EXTRACTED);
    }

    @Test
    void should_dropMessage_when_itBelongsToAnEarlierAttempt() {
        Document document = stored(DocumentStatus.RETRY_WAIT, 2);

        service.onProgress(envelope(REQUEST_ID, 1, Map.of()), new ProgressPayload(ProgressStage.OCR, 1, null, null));

        assertUntouched(document, DocumentStatus.RETRY_WAIT);
    }

    @Test
    void should_dropMessage_when_documentDoesNotExist() {
        when(documentRepository.findByIdForUpdate(DOCUMENT_ID)).thenReturn(Optional.empty());

        service.onDeadLetter(envelope(REQUEST_ID, 4, Map.of()), "LLM 503 Service Unavailable", 4);

        verifyNoInteractions(processedEventRepository, eventPublisher);
    }

    @Test
    void should_storeExtraction_when_resultOvertakesTheProgressMessage() {
        Document document = stored(DocumentStatus.OCR, 1);
        Map<String, Object> payload = Map.of(
                "documentKind", "NFC-e",
                "ocrEngine", "paddleocr",
                "llmModel", "gemini-3.5-flash-lite",
                "tokens", Map.of("input", 250, "output", 165));

        service.onExtracted(envelope(REQUEST_ID, 1, payload));

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.EXTRACTED);
        assertThat(document.getKind()).isEqualTo("NFC-e");
        ArgumentCaptor<Extraction> saved = ArgumentCaptor.forClass(Extraction.class);
        verify(extractionRepository).save(saved.capture());
        Extraction extraction = saved.getValue();
        assertThat(extraction.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(extraction.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(extraction.getRawResult()).isEqualTo(payload);
        assertThat(extraction.getOcrEngine()).isEqualTo("paddleocr");
        assertThat(extraction.getLlmModel()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(extraction.getInputTokens()).isEqualTo(250);
        assertThat(extraction.getOutputTokens()).isEqualTo(165);
        verify(eventPublisher).publish(ServiceName.API, "DOC-1007", "consume documentos.extraidos", EventLevel.OK);
    }

    @Test
    void should_finishAsDeadLettered_when_messageArrivesOnTheDeadLetterQueue() {
        Document document = stored(DocumentStatus.OCR, 4);

        service.onDeadLetter(envelope(REQUEST_ID, 4, Map.of()), "LLM 503 Service Unavailable", 4);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.DEAD_LETTERED);
        assertThat(document.getAttempts()).isEqualTo(4);
        assertThat(document.getLastError()).isEqualTo("LLM 503 Service Unavailable");
        assertThat(document.getReason()).isEqualTo("LLM 503 Service Unavailable");
        assertThat(document.getFinishedAt()).isEqualTo(NOW);
        verify(eventPublisher)
                .publish(
                        ServiceName.BROKER,
                        "DOC-1007",
                        "DLQ após 4 tentativas · LLM 503 Service Unavailable",
                        EventLevel.ERROR);
    }

    @Test
    void should_fallBackToEnvelope_when_deadLetterHeadersAreMissing() {
        Document document = stored(DocumentStatus.QUEUED, 0);

        service.onDeadLetter(envelope(REQUEST_ID, 1, Map.of()), null, null);

        assertThat(document.getAttempts()).isEqualTo(1);
        assertThat(document.getLastError()).isEqualTo("erro desconhecido");
    }

    private void assertUntouched(Document document, DocumentStatus status) {
        assertThat(document.getStatus()).isEqualTo(status);
        verify(processedEventRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    private Document stored(DocumentStatus status, int attempts) {
        Document document = new Document();
        document.setId(DOCUMENT_ID);
        document.setCode("DOC-1007");
        document.setBroker(BrokerKind.KAFKA);
        document.setStatus(status);
        document.setAttempts(attempts);
        document.setCurrentRequestId(REQUEST_ID);
        when(documentRepository.findByIdForUpdate(DOCUMENT_ID)).thenReturn(Optional.of(document));
        return document;
    }

    private EventEnvelope envelope(UUID requestId, int attempt, Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID(),
                "documento.progresso",
                EventEnvelope.SCHEMA_VERSION,
                DOCUMENT_ID,
                "DOC-1007",
                "sha256:" + "a".repeat(64),
                requestId,
                attempt,
                BrokerKind.KAFKA,
                NOW,
                payload);
    }
}
