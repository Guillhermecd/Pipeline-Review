package com.bimd.fiscalpipeline.service.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.dto.DocumentUpload;
import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.mapper.DocumentDetails;
import com.bimd.fiscalpipeline.domain.mapper.DocumentMapper;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.exception.DocumentTooLargeException;
import com.bimd.fiscalpipeline.exception.DuplicateDocumentException;
import com.bimd.fiscalpipeline.exception.InvalidDocumentFileException;
import com.bimd.fiscalpipeline.exception.UnsupportedDocumentTypeException;
import com.bimd.fiscalpipeline.repository.DocumentItemRepository;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.repository.FieldConfidenceRepository;
import com.bimd.fiscalpipeline.repository.FiscalDataRepository;
import com.bimd.fiscalpipeline.repository.ValidationResultRepository;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.service.storage.StorageService;
import com.bimd.fiscalpipeline.util.Sha256;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final byte[] CONTENT = "%PDF-1.4 nota".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = Sha256.hex(CONTENT);
    private static final String IDEMPOTENCY_KEY = "sha256:" + HASH;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentRegistrar documentRegistrar;

    @Mock
    private ReprocessService reprocessService;

    @Mock
    private StorageService storageService;

    @Mock
    private PipelineEventPublisher eventPublisher;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentReviewService reviewService;

    @Mock
    private FiscalDataRepository fiscalDataRepository;

    @Mock
    private DocumentItemRepository documentItemRepository;

    @Mock
    private FieldConfidenceRepository fieldConfidenceRepository;

    @Mock
    private ValidationResultRepository validationResultRepository;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        PipelineProperties properties =
                new PipelineProperties(1, BrokerKind.KAFKA, new BigDecimal("0.85"), 500, 10_000);
        Clock clock = Clock.fixed(Instant.parse("2026-10-13T13:20:00Z"), ZoneOffset.UTC);
        service = new DocumentService(
                documentRepository,
                documentRegistrar,
                reprocessService,
                storageService,
                eventPublisher,
                reviewService,
                new DocumentResponseAssembler(
                        fiscalDataRepository,
                        documentItemRepository,
                        fieldConfidenceRepository,
                        validationResultRepository,
                        documentMapper,
                        clock),
                properties);
    }

    @Test
    void should_storeFileThenRegister_when_uploadIsNew() {
        DocumentUpload upload = new DocumentUpload("nota fiscal.pdf", "application/pdf", CONTENT);
        Document registered = existingDocument();
        when(documentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(documentRegistrar.register(eq(USER_ID), eq(upload), eq(IDEMPOTENCY_KEY), anyString()))
                .thenReturn(registered);

        service.upload(USER_ID, upload);

        String expectedFileKey = "documents/" + HASH + "/nota_fiscal.pdf";
        verify(storageService).upload(expectedFileKey, "application/pdf", CONTENT);
        verify(documentRegistrar).register(USER_ID, upload, IDEMPOTENCY_KEY, expectedFileKey);
        // 13:20:00 (fixed clock) minus 13:19:58 (receivedAt).
        verify(documentMapper).toResponse(registered, DocumentDetails.EMPTY, 2000L);
    }

    @Test
    void should_rejectAsDuplicate_when_hashAlreadyExists() {
        when(documentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existingDocument()));

        assertThatThrownBy(() -> service.upload(USER_ID, new DocumentUpload("nota.pdf", "application/pdf", CONTENT)))
                .isInstanceOfSatisfying(DuplicateDocumentException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("DOC-1001");
                    assertThat(ex.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
                    assertThat(ex.getMessage())
                            .isEqualTo("idempotency_key sha256:" + HASH.substring(0, 8) + "… já existe");
                });

        verifyNoInteractions(storageService, documentRegistrar);
        verify(eventPublisher).publish(
                ServiceName.API,
                "—",
                "409 duplicado · sha256:" + HASH.substring(0, 8) + " já processado",
                EventLevel.WARN);
    }

    @Test
    void should_rejectAsDuplicate_when_concurrentUploadWinsTheRace() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("uq_documents_idempotency_key");
        when(documentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingDocument()));
        when(documentRegistrar.register(any(), any(), anyString(), anyString())).thenThrow(violation);

        assertThatThrownBy(() -> service.upload(USER_ID, new DocumentUpload("nota.pdf", "application/pdf", CONTENT)))
                .isInstanceOf(DuplicateDocumentException.class);
    }

    @Test
    void should_rethrow_when_integrityViolationIsNotADuplicate() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("fk_documents_user");
        when(documentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(documentRegistrar.register(any(), any(), anyString(), anyString())).thenThrow(violation);

        assertThatThrownBy(() -> service.upload(USER_ID, new DocumentUpload("nota.pdf", "application/pdf", CONTENT)))
                .isSameAs(violation);
    }

    @Test
    void should_rejectUpload_when_typeIsNotImageOrPdf() {
        assertThatThrownBy(() -> service.upload(USER_ID, new DocumentUpload("dados.csv", "text/csv", CONTENT)))
                .isInstanceOf(UnsupportedDocumentTypeException.class);
        assertThatThrownBy(() -> service.upload(USER_ID, new DocumentUpload("dados", null, CONTENT)))
                .isInstanceOf(UnsupportedDocumentTypeException.class);

        verifyNoInteractions(storageService, documentRegistrar);
    }

    @Test
    void should_acceptImages_when_typeStartsWithImage() {
        DocumentUpload upload = new DocumentUpload("cupom.jpg", "image/jpeg", CONTENT);
        when(documentRepository.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(documentRegistrar.register(any(), any(), anyString(), anyString())).thenReturn(existingDocument());

        service.upload(USER_ID, upload);

        verify(storageService).upload(anyString(), eq("image/jpeg"), eq(CONTENT));
    }

    @Test
    void should_rejectUpload_when_fileExceedsLimit() {
        byte[] oversized = new byte[1024 * 1024 + 1];

        assertThatThrownBy(() -> service.upload(USER_ID, new DocumentUpload("grande.pdf", "application/pdf", oversized)))
                .isInstanceOf(DocumentTooLargeException.class)
                .hasMessage("O arquivo excede o limite de 1 MB");

        verify(storageService, never()).upload(anyString(), anyString(), any());
    }

    @Test
    void should_acceptUpload_when_fileIsExactlyAtLimit() {
        byte[] atLimit = new byte[1024 * 1024];
        when(documentRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(documentRegistrar.register(any(), any(), anyString(), anyString())).thenReturn(existingDocument());

        service.upload(USER_ID, new DocumentUpload("limite.pdf", "application/pdf", atLimit));

        verify(storageService).upload(anyString(), anyString(), eq(atLimit));
    }

    @Test
    void should_rejectUpload_when_fileIsEmptyOrUnnamed() {
        assertThatThrownBy(() -> service.upload(USER_ID, new DocumentUpload("vazio.pdf", "application/pdf", new byte[0])))
                .isInstanceOf(InvalidDocumentFileException.class);
        assertThatThrownBy(() -> service.upload(USER_ID, new DocumentUpload(" ", "application/pdf", CONTENT)))
                .isInstanceOf(InvalidDocumentFileException.class);
        assertThatThrownBy(() -> service.upload(USER_ID, new DocumentUpload("a".repeat(256), "application/pdf", CONTENT)))
                .isInstanceOf(InvalidDocumentFileException.class);
    }

    private Document existingDocument() {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setCode("DOC-1001");
        document.setIdempotencyKey(IDEMPOTENCY_KEY);
        document.setReceivedAt("2026-10-13T13:19:58.000Z");
        return document;
    }
}
