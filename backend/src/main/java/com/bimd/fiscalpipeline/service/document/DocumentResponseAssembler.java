package com.bimd.fiscalpipeline.service.document;

import com.bimd.fiscalpipeline.domain.dto.DocumentResponse;
import com.bimd.fiscalpipeline.domain.mapper.DocumentDetails;
import com.bimd.fiscalpipeline.domain.mapper.DocumentMapper;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.DocumentItem;
import com.bimd.fiscalpipeline.domain.model.FieldConfidence;
import com.bimd.fiscalpipeline.domain.model.FiscalData;
import com.bimd.fiscalpipeline.domain.model.ValidationResult;
import com.bimd.fiscalpipeline.repository.DocumentItemRepository;
import com.bimd.fiscalpipeline.repository.FieldConfidenceRepository;
import com.bimd.fiscalpipeline.repository.FiscalDataRepository;
import com.bimd.fiscalpipeline.repository.ValidationResultRepository;
import com.bimd.fiscalpipeline.util.IsoDates;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the response of one or many documents. The related rows are read with one query per
 * table for the whole list, not per document.
 */
@Component
@RequiredArgsConstructor
public class DocumentResponseAssembler {

    private final FiscalDataRepository fiscalDataRepository;
    private final DocumentItemRepository documentItemRepository;
    private final FieldConfidenceRepository fieldConfidenceRepository;
    private final ValidationResultRepository validationResultRepository;
    private final DocumentMapper documentMapper;
    private final Clock clock;

    @Transactional(readOnly = true)
    public DocumentResponse toResponse(Document document) {
        return toResponses(List.of(document)).getFirst();
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> toResponses(List<Document> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = documents.stream().map(Document::getId).toList();
        Map<UUID, FiscalData> fiscalData = fiscalDataRepository.findByDocumentIdIn(ids).stream()
                .collect(Collectors.toMap(FiscalData::getDocumentId, Function.identity()));
        Map<UUID, List<DocumentItem>> items = documentItemRepository.findByDocumentIdInOrderByLine(ids).stream()
                .collect(Collectors.groupingBy(DocumentItem::getDocumentId));
        Map<UUID, List<FieldConfidence>> confidences = fieldConfidenceRepository.findByDocumentIdIn(ids).stream()
                .collect(Collectors.groupingBy(FieldConfidence::getDocumentId));
        Map<UUID, List<ValidationResult>> validations = validationResultRepository.findByDocumentIdIn(ids).stream()
                .collect(Collectors.groupingBy(ValidationResult::getDocumentId));

        Instant now = clock.instant();
        return documents.stream()
                .map(document -> {
                    UUID id = document.getId();
                    DocumentDetails details = new DocumentDetails(
                            fiscalData.get(id),
                            items.getOrDefault(id, List.of()),
                            confidences.getOrDefault(id, List.of()),
                            validations.getOrDefault(id, List.of()));
                    // A document still in the pipeline has been running until now.
                    return documentMapper.toResponse(
                            document,
                            details,
                            millisSinceReceived(document, document.getFinishedAt(), now),
                            millisSinceReceived(document, document.getProcessedAt(), now));
                })
                .toList();
    }

    private static long millisSinceReceived(Document document, String end, Instant now) {
        return IsoDates.millisBetween(document.getReceivedAt(), end != null ? IsoDates.parse(end) : now);
    }
}
