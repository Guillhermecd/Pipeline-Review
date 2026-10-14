package com.bimd.fiscalpipeline.service.scoring;

import com.bimd.fiscalpipeline.domain.enums.FieldKey;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Weighted average of the per-field confidences (BACKEND.md §6.5). */
@Component
public class ConfidenceScorer {

    /** The scale of {@code documents.confidence}: the value compared is the value stored. */
    public static final int SCALE = 4;

    /** A field without a confidence counts as zero. */
    public BigDecimal score(Map<FieldKey, BigDecimal> confidences) {
        BigDecimal score = BigDecimal.ZERO;
        for (FieldKey field : FieldKey.values()) {
            score = score.add(confidences.getOrDefault(field, BigDecimal.ZERO).multiply(field.getWeight()));
        }
        return score.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
