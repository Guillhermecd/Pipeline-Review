package com.bimd.fiscalpipeline.service.pipeline;

import com.bimd.fiscalpipeline.domain.enums.FieldKey;
import com.bimd.fiscalpipeline.service.pipeline.ExtractedData.Confidence;
import com.bimd.fiscalpipeline.service.pipeline.ExtractedData.Item;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads the payload of {@code documento.extraido} without trusting it.
 *
 * <p>This runs inside a message listener that retries until it succeeds, so it must never throw
 * on the data: a value that is missing, malformed or too large for its column becomes null and
 * the validation rules send the document to review.
 */
@Component
public class ExtractionPayloadReader {

    private static final int KIND_LENGTH = 32;
    private static final int CNPJ_LENGTH = 18;
    private static final int ISSUER_LENGTH = 255;
    private static final int DESCRIPTION_LENGTH = 500;
    private static final int SOURCE_LENGTH = 16;
    private static final int ENGINE_LENGTH = 32;
    private static final int MODEL_LENGTH = 64;
    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    /** NUMERIC(14,2) holds 12 integer digits; NUMERIC(12,3) holds 9. */
    private static final BigDecimal MONEY_LIMIT = BigDecimal.TEN.pow(12);

    private static final BigDecimal QUANTITY_LIMIT = BigDecimal.TEN.pow(9);
    private static final int CONFIDENCE_SCALE = 4;
    private static final String UNKNOWN_SOURCE = "llm";

    public ExtractedData read(Map<String, Object> payload) {
        Map<?, ?> fields = map(payload.get("fields"));
        Map<FieldKey, Confidence> confidences = new EnumMap<>(FieldKey.class);

        String cnpj = fitting(value(fields, "cnpj"), CNPJ_LENGTH);
        String issuer = truncated(value(fields, "issuer"), ISSUER_LENGTH);
        String issueDate = date(value(fields, "issueDate"));
        BigDecimal total = decimal(value(fields, "total"), MONEY_SCALE, MONEY_LIMIT);
        confidences.put(FieldKey.CNPJ, confidence(fields, "cnpj", cnpj != null));
        confidences.put(FieldKey.ISSUER, confidence(fields, "issuer", issuer != null));
        confidences.put(FieldKey.ISSUE_DATE, confidence(fields, "issueDate", issueDate != null));
        confidences.put(FieldKey.TOTAL, confidence(fields, "total", total != null));

        List<Item> items = items(payload.get("items"));
        BigDecimal itemsConfidence = items.isEmpty() ? BigDecimal.ZERO : ratio(payload.get("itemsConfidence"));
        confidences.put(FieldKey.ITEMS, new Confidence(itemsConfidence, UNKNOWN_SOURCE));

        Map<?, ?> tokens = map(payload.get("tokens"));
        return new ExtractedData(
                truncated(payload.get("documentKind"), KIND_LENGTH),
                cnpj,
                issuer,
                issueDate,
                total,
                items,
                confidences,
                truncated(payload.get("ocrEngine"), ENGINE_LENGTH),
                truncated(payload.get("llmModel"), MODEL_LENGTH),
                count(tokens.get("input")),
                count(tokens.get("output")));
    }

    /** A value that was dropped cannot keep the confidence the worker gave it. */
    private Confidence confidence(Map<?, ?> fields, String name, boolean kept) {
        Map<?, ?> field = map(fields.get(name));
        String source = truncated(field.get("source"), SOURCE_LENGTH);
        BigDecimal value = kept ? ratio(field.get("confidence")) : BigDecimal.ZERO;
        return new Confidence(value, source == null ? UNKNOWN_SOURCE : source);
    }

    private List<Item> items(Object raw) {
        List<Item> items = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return items;
        }
        for (Object element : list) {
            Map<?, ?> item = map(element);
            String description = truncated(item.get("description"), DESCRIPTION_LENGTH);
            BigDecimal quantity = decimal(item.get("quantity"), QUANTITY_SCALE, QUANTITY_LIMIT);
            BigDecimal unitPrice = decimal(item.get("unitPrice"), MONEY_SCALE, MONEY_LIMIT);
            if (description != null && quantity != null && unitPrice != null) {
                items.add(new Item(description, quantity, unitPrice));
            }
        }
        return items;
    }

    private Object value(Map<?, ?> fields, String name) {
        return map(fields.get(name)).get("value");
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string.trim() : null;
    }

    private String truncated(Object value, int maxLength) {
        String text = text(value);
        return text == null || text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /** For identifiers: half a CNPJ is worse than none. */
    private String fitting(Object value, int maxLength) {
        String text = text(value);
        return text == null || text.length() > maxLength ? null : text;
    }

    private String date(Object value) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Non-negative, within the column's scale and size; anything else is null. */
    private BigDecimal decimal(Object value, int scale, BigDecimal limit) {
        String text = text(value);
        if (text == null) {
            return null;
        }
        BigDecimal number;
        try {
            number = new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
        if (number.signum() < 0 || number.compareTo(limit) >= 0 || number.stripTrailingZeros().scale() > scale) {
            return null;
        }
        return number.setScale(scale, RoundingMode.UNNECESSARY);
    }

    private BigDecimal ratio(Object value) {
        if (!(value instanceof Number number)) {
            return BigDecimal.ZERO;
        }
        double ratio = number.doubleValue();
        if (Double.isNaN(ratio)) {
            return BigDecimal.ZERO;
        }
        double clamped = Math.clamp(ratio, 0.0, 1.0);
        return BigDecimal.valueOf(clamped).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    }

    private Integer count(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
