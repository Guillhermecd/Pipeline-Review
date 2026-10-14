package com.bimd.fiscalpipeline.service.scoring;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.service.validation.RuleResult;
import com.bimd.fiscalpipeline.util.BrazilianFormats;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Automatic approval or human review (BACKEND.md §6.5). A failed rule always sends the document
 * to review, however confident the extraction was; only then does the threshold count.
 */
@Component
@RequiredArgsConstructor
public class ReviewDecider {

    private final PipelineProperties properties;

    /** @param reason shown on screen as it is */
    public record Decision(DocumentStatus status, String reason) {
    }

    public Decision decide(List<RuleResult> results, BigDecimal score) {
        String failedRules = results.stream()
                .filter(result -> !result.passed())
                .map(result -> result.code().getDisplayName())
                .collect(Collectors.joining(", "));
        if (!failedRules.isEmpty()) {
            return new Decision(DocumentStatus.PENDING_REVIEW, "Validação falhou: " + failedRules);
        }
        BigDecimal threshold = properties.reviewConfidenceThreshold();
        if (score.compareTo(threshold) < 0) {
            return new Decision(
                    DocumentStatus.PENDING_REVIEW,
                    "Confiança %s abaixo do limiar %s"
                            .formatted(BrazilianFormats.percent(score), BrazilianFormats.percent(threshold)));
        }
        return new Decision(
                DocumentStatus.AUTO_APPROVED,
                "Confiança %s e validações ok".formatted(BrazilianFormats.percent(score)));
    }
}
