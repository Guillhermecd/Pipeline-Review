package com.bimd.fiscalpipeline.service.pipeline;

import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.DocumentItem;
import com.bimd.fiscalpipeline.domain.model.Extraction;
import com.bimd.fiscalpipeline.domain.model.FieldConfidence;
import com.bimd.fiscalpipeline.domain.model.FiscalData;
import com.bimd.fiscalpipeline.messaging.EventEnvelope;
import com.bimd.fiscalpipeline.repository.DocumentItemRepository;
import com.bimd.fiscalpipeline.repository.ExtractionRepository;
import com.bimd.fiscalpipeline.repository.FieldConfidenceRepository;
import com.bimd.fiscalpipeline.repository.FiscalDataRepository;
import com.bimd.fiscalpipeline.service.event.PipelineEventPublisher;
import com.bimd.fiscalpipeline.service.scoring.ReviewDecider;
import com.bimd.fiscalpipeline.service.scoring.ReviewDecider.Decision;
import com.bimd.fiscalpipeline.service.validation.DocumentEvaluator;
import com.bimd.fiscalpipeline.service.validation.DocumentEvaluator.Evaluation;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a document from EXTRACTED to its decision: stores what the worker read, validates it,
 * scores it and approves it or sends it to review, all in the caller's transaction (BACKEND.md
 * §4, step 5).
 */
@Service
@RequiredArgsConstructor
public class ExtractionResultService {

    private static final int MONEY_SCALE = 2;
    /** NUMERIC(14,2) holds 12 integer digits. */
    private static final BigDecimal MONEY_LIMIT = BigDecimal.TEN.pow(12);

    private final ExtractionPayloadReader payloadReader;
    private final ExtractionRepository extractionRepository;
    private final FiscalDataRepository fiscalDataRepository;
    private final DocumentItemRepository documentItemRepository;
    private final FieldConfidenceRepository fieldConfidenceRepository;
    private final DocumentEvaluator documentEvaluator;
    private final ReviewDecider reviewDecider;
    private final PipelineStateService stateService;
    private final PipelineEventPublisher eventPublisher;
    private final Clock clock;

    /** @param document already moved to EXTRACTED by the state guard */
    @Transactional(propagation = Propagation.MANDATORY)
    public void process(Document document, EventEnvelope envelope) {
        String now = IsoDates.format(clock.instant());
        ExtractedData data = payloadReader.read(envelope.payload());
        document.setKind(data.kind());
        extractionRepository.save(newExtraction(document, envelope, data, now));
        store(document.getId(), data);

        stateService.move(document, DocumentStatus.VALIDATING);
        Evaluation evaluation = documentEvaluator.evaluate(document);
        log(document, ServiceName.API, "validate cnpj.dv + soma.itens", EventLevel.OK);
        Decision decision = reviewDecider.decide(evaluation.results(), evaluation.score());
        log(document, ServiceName.API, "score.confianca", EventLevel.OK);

        stateService.move(document, decision.status());
        document.setReason(decision.reason());
        // A document sent to review is not finished: the reviewer has the final word and closes it.
        if (decision.status() == DocumentStatus.AUTO_APPROVED) {
            document.setFinishedAt(now);
        }
        log(document, ServiceName.DB, "UPDATE documento SET status", EventLevel.OK);
        if (decision.status() == DocumentStatus.AUTO_APPROVED) {
            log(document, ServiceName.API, "documento.aprovado (automático)", EventLevel.OK);
        } else {
            log(document, ServiceName.API, "documento.em_revisao · " + decision.reason(), EventLevel.WARN);
        }
    }

    private void store(UUID documentId, ExtractedData data) {
        BigDecimal itemsSum = BigDecimal.ZERO;
        documentItemRepository.deleteAllOf(documentId);
        int line = 1;
        for (ExtractedData.Item item : data.items()) {
            DocumentItem row = new DocumentItem();
            row.setId(UUID.randomUUID());
            row.setDocumentId(documentId);
            row.setLine(line++);
            row.setDescription(item.description());
            row.setQuantity(item.quantity());
            row.setUnitPrice(item.unitPrice());
            documentItemRepository.save(row);
            itemsSum = itemsSum.add(item.quantity().multiply(item.unitPrice()));
        }

        FiscalData fiscalData = new FiscalData();
        fiscalData.setDocumentId(documentId);
        fiscalData.setCnpj(data.cnpj());
        fiscalData.setIssuer(data.issuer());
        fiscalData.setIssueDate(data.issueDate());
        fiscalData.setTotal(data.total());
        // The lines are added in full and the sum is rounded once, as a note prints it. A sum too
        // large for the column is left out; the sum rule then fails and a person looks at it.
        BigDecimal rounded = itemsSum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        fiscalData.setItemsSum(rounded.compareTo(MONEY_LIMIT) < 0 ? rounded : null);
        fiscalDataRepository.save(fiscalData);

        data.confidences()
                .forEach((field, confidence) -> fieldConfidenceRepository.save(
                        new FieldConfidence(documentId, field, confidence.value(), confidence.source())));
    }

    private Extraction newExtraction(Document document, EventEnvelope envelope, ExtractedData data, String now) {
        Extraction extraction = new Extraction();
        extraction.setId(UUID.randomUUID());
        extraction.setDocumentId(document.getId());
        extraction.setRequestId(envelope.requestId());
        extraction.setRawResult(envelope.payload());
        extraction.setOcrEngine(data.ocrEngine());
        extraction.setLlmModel(data.llmModel());
        extraction.setInputTokens(data.inputTokens());
        extraction.setOutputTokens(data.outputTokens());
        extraction.setCreatedAt(now);
        return extraction;
    }

    private void log(Document document, ServiceName service, String message, EventLevel level) {
        eventPublisher.publish(service, document.getCode(), message, level);
    }
}
