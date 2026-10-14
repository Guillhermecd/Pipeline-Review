package com.bimd.fiscalpipeline.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.bimd.fiscalpipeline.domain.enums.RuleCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ItemsSumValidatorTest {

    private final ItemsSumRule rule = new ItemsSumRule();

    @ParameterizedTest
    @CsvSource({
        "271.30, 271.30",
        // One cent either way: lines rounded one by one against a sum rounded once.
        "271.30, 271.31",
        "271.30, 271.29",
        "0.00,   0.01",
    })
    void should_pass_when_sumIsWithinOneCentOfTheTotal(String itemsSum, String total) {
        assertThat(validate(itemsSum, total).passed()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "271.30, 271.32",
        "271.30, 271.28",
        "271.30, 283.80",
        "0.00,   0.02",
    })
    void should_fail_when_sumIsMoreThanOneCentAway(String itemsSum, String total) {
        assertThat(validate(itemsSum, total).passed()).isFalse();
    }

    @Test
    void should_showBothValuesInBrazilianFormat_when_describingTheResult() {
        RuleResult mismatch = validate("271.30", "283.80");
        RuleResult thousands = validate("1234567.80", "1234567.80");

        assertThat(mismatch.code()).isEqualTo(RuleCode.ITEMS_SUM_EQUALS_TOTAL);
        assertThat(mismatch.detail()).isEqualTo("R$ 271,30 vs R$ 283,80");
        assertThat(thousands.detail()).isEqualTo("R$ 1.234.567,80 vs R$ 1.234.567,80");
    }

    @Test
    void should_fail_when_totalIsMissing() {
        RuleResult result =
                rule.validate(new FiscalDataSnapshot(null, null, null, null, new BigDecimal("271.30"), 3));

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("R$ 271,30 vs total ausente");
    }

    @Test
    void should_treatMissingSumAsZero_when_documentHasNoItems() {
        RuleResult result = rule.validate(new FiscalDataSnapshot(null, null, null, new BigDecimal("10.00"), null, 0));

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("R$ 0,00 vs R$ 10,00");
    }

    private RuleResult validate(String itemsSum, String total) {
        return rule.validate(
                new FiscalDataSnapshot(null, null, null, new BigDecimal(total), new BigDecimal(itemsSum), 1));
    }
}
