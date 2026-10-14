package com.bimd.fiscalpipeline.service.document;

import com.bimd.fiscalpipeline.domain.dto.DocumentUpload;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.service.broker.BrokerService;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.service.outbox.OutboxWriter;
import com.bimd.fiscalpipeline.util.IsoDates;
import com.bimd.fiscalpipeline.util.TraceIds;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes an accepted upload: the document, its outbox message and the log event commit
 * together or not at all, so a document never exists without the message that moves it on.
 */
@Service
@RequiredArgsConstructor
public class DocumentRegistrar {

    private static final String CODE_PREFIX = "DOC-";
    private static final String UPLOAD_SPAN_NAME = "POST /api/v1/documentos";

    private final DocumentRepository documentRepository;
    private final OutboxWriter outboxWriter;
    private final PipelineEventPublisher eventPublisher;
    private final BrokerService brokerService;
    private final Clock clock;

    @Transactional
    public Document register(UUID userId, DocumentUpload upload, String idempotencyKey, String fileKey) {
        String now = IsoDates.format(clock.instant());
        Document document = documentRepository.saveAndFlush(
                newDocument(userId, upload, idempotencyKey, fileKey, now));
        outboxWriter.enqueueReceived(document, now);
        eventPublisher.publish(ServiceName.API, document.getCode(), UPLOAD_SPAN_NAME, EventLevel.OK);
        return document;
    }

    private Document newDocument(
            UUID userId, DocumentUpload upload, String idempotencyKey, String fileKey, String now) {
        Document document = new Document();
        document.setId(UUID.randomUUID());
        document.setCode(CODE_PREFIX + documentRepository.nextCodeNumber());
        document.setUserId(userId);
        document.setFileName(upload.fileName());
        document.setFileKey(fileKey);
        document.setMimeType(upload.mimeType());
        document.setIdempotencyKey(idempotencyKey);
        // The broker active now is recorded on the document and used for its whole cycle.
        document.setBroker(brokerService.active());
        document.setStatus(DocumentStatus.RECEIVED);
        document.setTraceId(TraceIds.newTraceId());
        document.setCurrentRequestId(UUID.randomUUID());
        document.setAttempts(0);
        document.setReceivedAt(now);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return document;
    }
}
