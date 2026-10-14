package com.bimd.fiscalpipeline.service.document;

import com.bimd.fiscalpipeline.domain.dto.DocumentCorrectionRequest;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.FieldKey;
import com.bimd.fiscalpipeline.domain.enums.ReviewDecision;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.FieldConfidence;
import com.bimd.fiscalpipeline.domain.model.FiscalData;
import com.bimd.fiscalpipeline.domain.model.Review;
import com.bimd.fiscalpipeline.exception.DocumentNotFoundException;
import com.bimd.fiscalpipeline.exception.DocumentNotPendingReviewException;
import com.bimd.fiscalpipeline.exception.EmptyCorrectionException;
import com.bimd.fiscalpipeline.repository.DocumentRepository;
import com.bimd.fiscalpipeline.repository.ExtractionRepository;
import com.bimd.fiscalpipeline.repository.FieldConfidenceRepository;
import com.bimd.fiscalpipeline.repository.FiscalDataRepository;
import com.bimd.fiscalpipeline.repository.ReviewRepository;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.service.pipeline.PipelineStateService;
import com.bimd.fiscalpipeline.service.validation.DocumentEvaluator;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human review: correcting fields, then approving or rejecting (BACKEND.md §6.5). */
@Service
@RequiredArgsConstructor
public class DocumentReviewService {

    /** A person typed it: at least this sure, never less than the extraction already was. */
    private static final BigDecimal REVIEWED_CONFIDENCE = new BigDecimal("0.9900");

    private static final String REVIEW_SOURCE = "review";

    private final DocumentRepository documentRepository;
    private final FiscalDataRepository fiscalDataRepository;
    private final FieldConfidenceRepository fieldConfidenceRepository;
    private final ExtractionRepository extractionRepository;
    private final ReviewRepository reviewRepository;
    private final DocumentEvaluator documentEvaluator;
    private final PipelineStateService stateService;
    private final PipelineEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Applies the correction and recomputes rules and score right away. The status does not
     * change: the document stays in review until someone approves or rejects it.
     */
    @Transactional
    public Document correct(UUID documentId, DocumentCorrectionRequest correction) {
        if (correction.cnpj() == null && correction.total() == null) {
            throw new EmptyCorrectionException();
        }
        Document document = pendingReview(documentId);
        FiscalData fiscalData = fiscalDataRepository.findById(documentId).orElseGet(() -> {
            FiscalData created = new FiscalData();
            created.setDocumentId(documentId);
            return created;
        });
        if (correction.cnpj() != null) {
            fiscalData.setCnpj(correction.cnpj().trim());
            markReviewed(documentId, FieldKey.CNPJ);
        }
        if (correction.total() != null) {
            fiscalData.setTotal(new BigDecimal(correction.total()));
            markReviewed(documentId, FieldKey.TOTAL);
        }
        fiscalDataRepository.saveAndFlush(fiscalData);
        documentEvaluator.evaluate(document);
        document.setUpdatedAt(now());
        return document;
    }

    @Transactional
    public Document approve(UUID documentId, UUID reviewerId) {
        return decide(documentId, reviewerId, ReviewDecision.APPROVED, DocumentStatus.APPROVED);
    }

    @Transactional
    public Document reject(UUID documentId, UUID reviewerId) {
        return decide(documentId, reviewerId, ReviewDecision.REJECTED, DocumentStatus.REJECTED);
    }

    private Document decide(UUID documentId, UUID reviewerId, ReviewDecision decision, DocumentStatus status) {
        Document document = pendingReview(documentId);
        stateService.move(document, status);
        // The reviewer has the final word: the document is finished now, not when it entered review.
        document.setFinishedAt(now());

        Review review = new Review();
        review.setId(UUID.randomUUID());
        review.setDocumentId(documentId);
        review.setReviewerId(reviewerId);
        review.setDecision(decision);
        review.setCorrections(correctionsOf(documentId, document.getCurrentRequestId()));
        review.setReviewedAt(now());
        reviewRepository.save(review);

        boolean approved = decision == ReviewDecision.APPROVED;
        eventPublisher.publish(
                ServiceName.API,
                document.getCode(),
                approved ? "revisão humana · documento.aprovado publicado" : "revisão humana · documento.rejeitado",
                approved ? EventLevel.OK : EventLevel.WARN);
        return document;
    }

    private Document pendingReview(UUID documentId) {
        Document document = documentRepository
                .findByIdForUpdate(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.getStatus() != DocumentStatus.PENDING_REVIEW) {
            throw new DocumentNotPendingReviewException(document.getCode(), document.getStatus());
        }
        return document;
    }

    private void markReviewed(UUID documentId, FieldKey field) {
        BigDecimal current = fieldConfidenceRepository
                .findById(new FieldConfidence.Key(documentId, field))
                .map(FieldConfidence::getConfidence)
                .orElse(BigDecimal.ZERO);
        fieldConfidenceRepository.saveAndFlush(
                new FieldConfidence(documentId, field, current.max(REVIEWED_CONFIDENCE), REVIEW_SOURCE));
    }

    /**
     * What the reviewer changed, found by comparing the stored fields with what the worker sent.
     * Computed at decision time, so several corrections of the same field count once.
     */
    private Map<String, Object> correctionsOf(UUID documentId, UUID requestId) {
        Map<String, Object> corrections = new LinkedHashMap<>();
        FiscalData current = fiscalDataRepository.findById(documentId).orElse(null);
        Map<?, ?> extractedFields = extractionRepository.findByDocumentId(documentId).stream()
                .filter(extraction -> extraction.getRequestId().equals(requestId))
                .findFirst()
                .map(extraction -> extraction.getRawResult().get("fields"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .orElse(Map.of());
        if (current == null) {
            return corrections;
        }
        addCorrection(corrections, documentId, FieldKey.CNPJ, extractedValue(extractedFields, "cnpj"), current.getCnpj());
        String total = current.getTotal() == null ? null : current.getTotal().toPlainString();
        addCorrection(corrections, documentId, FieldKey.TOTAL, extractedValue(extractedFields, "total"), total);
        return corrections;
    }

    /** Only fields a reviewer touched count; a value the pipeline dropped by itself is not a correction. */
    private void addCorrection(
            Map<String, Object> corrections, UUID documentId, FieldKey field, String from, String to) {
        boolean reviewed = fieldConfidenceRepository
                .findById(new FieldConfidence.Key(documentId, field))
                .map(confidence -> REVIEW_SOURCE.equals(confidence.getSource()))
                .orElse(false);
        if (reviewed && !Objects.equals(from, to)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", from);
            change.put("to", to);
            corrections.put(field.getJsonName(), change);
        }
    }

    private String extractedValue(Map<?, ?> fields, String name) {
        return fields.get(name) instanceof Map<?, ?> field && field.get("value") instanceof String value
                ? value
                : null;
    }

    private String now() {
        return IsoDates.format(clock.instant());
    }
}
