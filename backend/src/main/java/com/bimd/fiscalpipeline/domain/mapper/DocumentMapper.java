package com.bimd.fiscalpipeline.domain.mapper;

import com.bimd.fiscalpipeline.domain.dto.DocumentResponse;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.model.Document;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Fiscal fields, items, per-field confidence and validations stay empty until the
 * extraction result is persisted (phase F4).
 */
@Mapper(componentModel = "spring")
public interface DocumentMapper {

    List<String> CONFIDENCE_FIELDS = List.of("cnpj", "issuer", "issueDate", "total", "items");

    @Mapping(target = "issuer", ignore = true)
    @Mapping(target = "issueDate", ignore = true)
    @Mapping(target = "cnpj", ignore = true)
    @Mapping(target = "total", ignore = true)
    @Mapping(target = "items", expression = "java(java.util.List.of())")
    @Mapping(target = "itemsSum", expression = "java(emptySum())")
    @Mapping(target = "stage", source = "document")
    @Mapping(target = "progress", source = "document.status.progress")
    @Mapping(target = "outcome", source = "document.status.outcome")
    @Mapping(target = "review", source = "document.status.review")
    @Mapping(target = "reason", source = "document.reason", defaultValue = "")
    @Mapping(target = "confidence", source = "document.confidence", defaultValue = "0")
    @Mapping(target = "fieldConfidence", expression = "java(emptyFieldConfidence())")
    @Mapping(target = "validations", expression = "java(java.util.List.of())")
    DocumentResponse toResponse(Document document, long durationMs);

    default DocumentResponse.Stage toStage(Document document) {
        DocumentStatus status = document.getStatus();
        String label = status.getLabelTemplate()
                .replace(DocumentStatus.ATTEMPT_PLACEHOLDER, String.valueOf(document.getAttempts()));
        return new DocumentResponse.Stage(label, status.getNode(), status.getService());
    }

    default BigDecimal emptySum() {
        return BigDecimal.ZERO.setScale(2);
    }

    default Map<String, Double> emptyFieldConfidence() {
        Map<String, Double> confidences = new LinkedHashMap<>();
        CONFIDENCE_FIELDS.forEach(field -> confidences.put(field, 0.0));
        return confidences;
    }
}
