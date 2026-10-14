package com.bimd.fiscalpipeline.service.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import com.bimd.fiscalpipeline.domain.enums.FieldKey;
import com.bimd.fiscalpipeline.domain.enums.RuleCode;
import com.bimd.fiscalpipeline.service.scoring.ReviewDecider.Decision;
import com.bimd.fiscalpipeline.service.validation.RuleResult;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoringTest {

    private final ConfidenceScorer scorer = new ConfidenceScorer();

    @Test
    void should_weightEachField_when_scoring() {
        Map<FieldKey, BigDecimal> confidences = new EnumMap<>(FieldKey.class);
        confidences.put(FieldKey.CNPJ, new BigDecimal("1.0"));
        confidences.put(FieldKey.TOTAL, new BigDecimal("0.8"));
        confidences.put(FieldKey.ITEMS, new BigDecimal("0.5"));
        confidences.put(FieldKey.ISSUE_DATE, new BigDecimal("0.6"));
        confidences.put(FieldKey.ISSUER, new BigDecimal("0.4"));

        // 1.0·0.25 + 0.8·0.25 + 0.5·0.20 + 0.6·0.15 + 0.4·0.15
        assertThat(scorer.score(confidences)).isEqualTo(new BigDecimal("0.7000"));
    }

    @Test
    void should_countMissingFieldAsZero_when_scoring() {
        assertThat(scorer.score(Map.of(FieldKey.CNPJ, BigDecimal.ONE))).isEqualTo(new BigDecimal("0.2500"));
        assertThat(scorer.score(Map.of())).isEqualTo(new BigDecimal("0.0000"));
    }

    @Test
    void should_haveWeightsThatAddUpToOne() {
        BigDecimal total = Arrays.stream(FieldKey.values()).map(FieldKey::getWeight).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(total).isEqualByComparingTo(BigDecimal.ONE);
    }

    @ParameterizedTest
    @CsvSource({
        "0.9400, 0.85, Confiança 94% e validações ok",
        "0.8500, 0.85, Confiança 85% e validações ok",
        "1.0000, 0.85, Confiança 100% e validações ok",
        "0.6000, 0.60, Confiança 60% e validações ok",
    })
    void should_approveAutomatically_when_rulesPassAndScoreReachesThreshold(
            String score, String threshold, String reason) {
        Decision decision = decider(threshold).decide(allPassed(), new BigDecimal(score));

        assertThat(decision).isEqualTo(new Decision(DocumentStatus.AUTO_APPROVED, reason));
    }

    @ParameterizedTest
    @CsvSource({
        "0.7800, 0.85, Confiança 78% abaixo do limiar 85%",
        // Rounds to the threshold on screen, but is still below it.
        "0.8499, 0.85, Confiança 85% abaixo do limiar 85%",
        "0.0000, 0.85, Confiança 0% abaixo do limiar 85%",
        "0.9400, 0.95, Confiança 94% abaixo do limiar 95%",
    })
    void should_sendToReview_when_scoreIsBelowThreshold(String score, String threshold, String reason) {
        Decision decision = decider(threshold).decide(allPassed(), new BigDecimal(score));

        assertThat(decision).isEqualTo(new Decision(DocumentStatus.PENDING_REVIEW, reason));
    }

    /** A failed rule wins over any confidence. */
    @Test
    void should_sendToReviewNamingTheRules_when_rulesFail() {
        List<RuleResult> results = List.of(
                new RuleResult(RuleCode.CNPJ_CHECK_DIGIT, false, "DV calculado 38, lido 39"),
                new RuleResult(RuleCode.ITEMS_SUM_EQUALS_TOTAL, false, "R$ 271,30 vs R$ 283,80"),
                new RuleResult(RuleCode.ISSUE_DATE_NOT_FUTURE, true, "28/09/2026"),
                new RuleResult(RuleCode.REQUIRED_FIELDS_PRESENT, true, "5 de 5 presentes"));

        Decision decision = decider("0.85").decide(results, new BigDecimal("0.9900"));

        assertThat(decision)
                .isEqualTo(new Decision(
                        DocumentStatus.PENDING_REVIEW,
                        "Validação falhou: Dígito verificador do CNPJ, Soma dos itens = total"));
    }

    private ReviewDecider decider(String threshold) {
        return new ReviewDecider(new PipelineProperties(10, BrokerKind.KAFKA, new BigDecimal(threshold), 500, 10_000));
    }

    private List<RuleResult> allPassed() {
        return Arrays.stream(RuleCode.values())
                .map(code -> new RuleResult(code, true, "ok"))
                .toList();
    }
}
