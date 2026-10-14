package com.bimd.fiscalpipeline.service.validation;

import com.bimd.fiscalpipeline.domain.enums.FieldKey;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.FieldConfidence;
import com.bimd.fiscalpipeline.domain.model.FiscalData;
import com.bimd.fiscalpipeline.domain.model.ValidationResult;
import com.bimd.fiscalpipeline.repository.DocumentItemRepository;
import com.bimd.fiscalpipeline.repository.FieldConfidenceRepository;
import com.bimd.fiscalpipeline.repository.FiscalDataRepository;
import com.bimd.fiscalpipeline.repository.ValidationResultRepository;
import com.bimd.fiscalpipeline.service.scoring.ConfidenceScorer;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs every rule and the score over what is stored for a document, and stores the outcome. Used
 * when the extraction arrives and again after each correction in review, so both always show
 * numbers computed the same way.
 */
@Service
public class DocumentEvaluator {

    private final List<DocumentRule> rules;
    private final FiscalDataRepository fiscalDataRepository;
    private final DocumentItemRepository documentItemRepository;
    private final FieldConfidenceRepository fieldConfidenceRepository;
    private final ValidationResultRepository validationResultRepository;
    private final ConfidenceScorer confidenceScorer;
    private final Clock clock;

    public DocumentEvaluator(
            List<DocumentRule> rules,
            FiscalDataRepository fiscalDataRepository,
            DocumentItemRepository documentItemRepository,
            FieldConfidenceRepository fieldConfidenceRepository,
            ValidationResultRepository validationResultRepository,
            ConfidenceScorer confidenceScorer,
            Clock clock) {
        // The order of RuleCode is the order on screen and in the review reason.
        this.rules = rules.stream().sorted(Comparator.comparing(DocumentRule::code)).toList();
        this.fiscalDataRepository = fiscalDataRepository;
        this.documentItemRepository = documentItemRepository;
        this.fieldConfidenceRepository = fieldConfidenceRepository;
        this.validationResultRepository = validationResultRepository;
        this.confidenceScorer = confidenceScorer;
        this.clock = clock;
    }

    public record Evaluation(List<RuleResult> results, BigDecimal score) {
    }

    /** Rewrites the document's validation results and its confidence. The status is the caller's call. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Evaluation evaluate(Document document) {
        FiscalDataSnapshot snapshot = snapshotOf(document);
        String now = IsoDates.format(clock.instant());
        List<RuleResult> results = rules.stream().map(rule -> rule.validate(snapshot)).toList();
        for (RuleResult result : results) {
            // Same key as the previous run: the row is updated in place.
            validationResultRepository.save(
                    new ValidationResult(document.getId(), result.code(), result.passed(), result.detail(), now));
        }

        Map<FieldKey, BigDecimal> confidences = new EnumMap<>(FieldKey.class);
        for (FieldConfidence confidence : fieldConfidenceRepository.findByDocumentId(document.getId())) {
            confidences.put(confidence.getField(), confidence.getConfidence());
        }
        BigDecimal score = confidenceScorer.score(confidences);
        document.setConfidence(score);
        return new Evaluation(results, score);
    }

    private FiscalDataSnapshot snapshotOf(Document document) {
        FiscalData data = fiscalDataRepository.findById(document.getId()).orElseGet(FiscalData::new);
        int itemCount = documentItemRepository.findByDocumentIdOrderByLine(document.getId()).size();
        return new FiscalDataSnapshot(
                data.getCnpj(), data.getIssuer(), data.getIssueDate(), data.getTotal(), data.getItemsSum(), itemCount);
    }
}
