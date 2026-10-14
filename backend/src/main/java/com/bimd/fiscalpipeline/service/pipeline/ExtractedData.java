package com.bimd.fiscalpipeline.service.pipeline;

import com.bimd.fiscalpipeline.domain.enums.FieldKey;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The worker's answer after {@link ExtractionPayloadReader} made it safe to store: every value
 * fits its column, and what did not fit is null (fields) or left out (items).
 */
public record ExtractedData(
        String kind,
        String cnpj,
        String issuer,
        String issueDate,
        BigDecimal total,
        List<Item> items,
        Map<FieldKey, Confidence> confidences,
        String ocrEngine,
        String llmModel,
        Integer inputTokens,
        Integer outputTokens) {

    public record Item(String description, BigDecimal quantity, BigDecimal unitPrice) {
    }

    public record Confidence(BigDecimal value, String source) {
    }
}
