package com.bimd.fiscalpipeline.domain.mapper;

import com.bimd.fiscalpipeline.domain.dto.DocumentResponse;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.FieldKey;
import com.bimd.fiscalpipeline.domain.model.Document;
import com.bimd.fiscalpipeline.domain.model.DocumentItem;
import com.bimd.fiscalpipeline.domain.model.FieldConfidence;
import com.bimd.fiscalpipeline.domain.model.FiscalData;
import com.bimd.fiscalpipeline.domain.model.ValidationResult;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Until the extraction arrives the fiscal fields are null and the lists are empty. */
@Mapper(componentModel = "spring")
public interface DocumentMapper {

    @Mapping(target = "issuer", source = "details.fiscalData.issuer")
    @Mapping(target = "issueDate", source = "details.fiscalData.issueDate")
    @Mapping(target = "cnpj", source = "details.fiscalData.cnpj")
    @Mapping(target = "total", source = "details.fiscalData.total")
    @Mapping(target = "items", source = "details.items")
    @Mapping(target = "itemsSum", expression = "java(toItemsSum(details.fiscalData()))")
    @Mapping(target = "stage", source = "document")
    @Mapping(target = "progress", source = "document.status.progress")
    @Mapping(target = "outcome", source = "document.status.outcome")
    @Mapping(target = "review", source = "document.status.review")
    @Mapping(target = "reason", source = "document.reason", defaultValue = "")
    @Mapping(target = "confidence", source = "document.confidence", defaultValue = "0")
    @Mapping(target = "fieldConfidence", source = "details.confidences")
    @Mapping(target = "validations", source = "details.validations")
    DocumentResponse toResponse(Document document, DocumentDetails details, long durationMs, long processingMs);

    default DocumentResponse.Stage toStage(Document document) {
        DocumentStatus status = document.getStatus();
        String label = status.getLabelTemplate()
                .replace(DocumentStatus.ATTEMPT_PLACEHOLDER, String.valueOf(document.getAttempts()));
        return new DocumentResponse.Stage(label, status.getNode(), status.getService());
    }

    /** {@code 5.000} is stored; {@code "5"} goes out, as the worker sent it. */
    default DocumentResponse.Item toItem(DocumentItem item) {
        BigDecimal quantity = item.getQuantity().stripTrailingZeros();
        if (quantity.scale() < 0) {
            quantity = quantity.setScale(0);
        }
        return new DocumentResponse.Item(item.getDescription(), quantity, item.getUnitPrice());
    }

    default BigDecimal toItemsSum(FiscalData fiscalData) {
        return fiscalData == null || fiscalData.getItemsSum() == null
                ? BigDecimal.ZERO.setScale(2)
                : fiscalData.getItemsSum();
    }

    /** Every field is present, in the order the screen lists them; zero when there is no value. */
    default Map<String, Double> toFieldConfidence(List<FieldConfidence> confidences) {
        Map<String, Double> byName = new LinkedHashMap<>();
        for (FieldKey field : FieldKey.values()) {
            byName.put(field.getJsonName(), 0.0);
        }
        if (confidences != null) {
            confidences.forEach(confidence ->
                    byName.put(confidence.getField().getJsonName(), confidence.getConfidence().doubleValue()));
        }
        return byName;
    }

    default List<DocumentResponse.Validation> toValidations(List<ValidationResult> results) {
        if (results == null) {
            return List.of();
        }
        return results.stream()
                .sorted(Comparator.comparing(ValidationResult::getRuleCode))
                .map(result -> new DocumentResponse.Validation(
                        result.getRuleCode().getDisplayName(), result.isPassed(), result.getDetail()))
                .toList();
    }
}
