package com.bimd.fiscalpipeline.service.document;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.dto.DocumentResponse;
import com.bimd.fiscalpipeline.domain.dto.DocumentUpload;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.mapper.DocumentMapper;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.exception.DocumentNotFoundException;
import com.bimd.fiscalpipeline.exception.DocumentTooLargeException;
import com.bimd.fiscalpipeline.exception.DuplicateDocumentException;
import com.bimd.fiscalpipeline.exception.InvalidDocumentFileException;
import com.bimd.fiscalpipeline.exception.UnsupportedDocumentTypeException;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.service.storage.StorageService;
import com.bimd.fiscalpipeline.util.IsoDates;
import com.bimd.fiscalpipeline.util.Sha256;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final String IDEMPOTENCY_PREFIX = "sha256:";
    private static final int SHORT_HASH_LENGTH = 8;
    private static final String NO_DOCUMENT_CODE = "—";
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MAX_LIST_LIMIT = 100;
    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final String IMAGE_MIME_PREFIX = "image/";

    private final DocumentRepository documentRepository;
    private final DocumentRegistrar documentRegistrar;
    private final ReprocessService reprocessService;
    private final StorageService storageService;
    private final PipelineEventPublisher eventPublisher;
    private final DocumentMapper documentMapper;
    private final PipelineProperties properties;
    private final Clock clock;

    /**
     * The file's SHA-256 is the idempotency key. The object goes to storage before the database
     * transaction: a retried upload overwrites the same key, while a committed document whose
     * file never arrived could not be recovered.
     */
    public DocumentResponse upload(UUID userId, DocumentUpload upload) {
        validate(upload);
        String hash = Sha256.hex(upload.content());
        String idempotencyKey = IDEMPOTENCY_PREFIX + hash;
        documentRepository.findByIdempotencyKey(idempotencyKey).ifPresent(existing -> {
            throw duplicateOf(existing, hash);
        });

        String fileKey = "documents/%s/%s".formatted(hash, sanitize(upload.fileName()));
        storageService.upload(fileKey, upload.mimeType(), upload.content());
        try {
            return toResponse(documentRegistrar.register(userId, upload, idempotencyKey, fileKey));
        } catch (DataIntegrityViolationException e) {
            // Two uploads of the same file raced; the UNIQUE constraint picked the winner.
            Document winner = documentRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            throw duplicateOf(winner, hash);
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listRecent(int limit) {
        int safeLimit = Math.clamp(limit, 1, MAX_LIST_LIMIT);
        return documentRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, safeLimit)).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse getById(UUID id) {
        return documentRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    public DocumentResponse reprocess(UUID id) {
        return toResponse(reprocessService.reprocess(id));
    }

    private void validate(DocumentUpload upload) {
        if (upload.content().length == 0) {
            throw new InvalidDocumentFileException("File is empty");
        }
        if (upload.fileName() == null || upload.fileName().isBlank()) {
            throw new InvalidDocumentFileException("File name is required");
        }
        if (upload.fileName().length() > MAX_FILE_NAME_LENGTH) {
            throw new InvalidDocumentFileException(
                    "File name exceeds %d characters".formatted(MAX_FILE_NAME_LENGTH));
        }
        if (upload.content().length > properties.uploadMaxSizeBytes()) {
            throw new DocumentTooLargeException(properties.uploadMaxSizeMb());
        }
        if (!isSupported(upload.mimeType())) {
            throw new UnsupportedDocumentTypeException(upload.mimeType());
        }
    }

    private boolean isSupported(String mimeType) {
        return mimeType != null && (mimeType.equals(PDF_MIME_TYPE) || mimeType.startsWith(IMAGE_MIME_PREFIX));
    }

    private DuplicateDocumentException duplicateOf(Document existing, String hash) {
        String shortHash = hash.substring(0, SHORT_HASH_LENGTH);
        // No new document exists to attach the event to; the screen shows a dash, as the mock does.
        eventPublisher.publish(
                ServiceName.API,
                NO_DOCUMENT_CODE,
                "409 duplicado · sha256:%s já processado".formatted(shortHash),
                EventLevel.WARN);
        return new DuplicateDocumentException(
                existing.getIdempotencyKey(), shortHash, existing.getId(), existing.getCode());
    }

    private DocumentResponse toResponse(Document document) {
        Instant end = document.getFinishedAt() != null ? IsoDates.parse(document.getFinishedAt()) : clock.instant();
        return documentMapper.toResponse(document, IsoDates.millisBetween(document.getReceivedAt(), end));
    }

    private String sanitize(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
