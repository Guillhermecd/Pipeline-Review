package com.bimd.fiscalpipeline.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OtherRulesTest {

    /** 01:30 UTC on the 14th is still 22:30 of the 13th in São Paulo. */
    private static final Clock LATE_EVENING_IN_BRAZIL =
            Clock.fixed(Instant.parse("2026-10-14T01:30:00Z"), ZoneOffset.UTC);

    private final IssueDateNotFutureRule dateRule = new IssueDateNotFutureRule(LATE_EVENING_IN_BRAZIL);
    private final RequiredFieldsRule requiredRule = new RequiredFieldsRule();

    @ParameterizedTest
    @CsvSource({"2026-10-13, 13/10/2026", "2026-09-28, 28/09/2026", "2020-01-05, 05/01/2020"})
    void should_pass_when_issueDateIsTodayOrEarlierInBrazil(String issueDate, String detail) {
        RuleResult result = dateRule.validate(withDate(issueDate));

        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).isEqualTo(detail);
    }

    /** The 14th has started in UTC but not in Brazil: a note dated the 14th is from the future. */
    @ParameterizedTest
    @CsvSource({"2026-10-14, 14/10/2026", "2027-01-01, 01/01/2027"})
    void should_fail_when_issueDateIsAfterTodayInBrazil(String issueDate, String detail) {
        RuleResult result = dateRule.validate(withDate(issueDate));

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo(detail);
    }

    @ParameterizedTest
    @ValueSource(strings = {"28/09/2026", "2026-13-40", "ontem"})
    void should_fail_when_issueDateCannotBeRead(String issueDate) {
        RuleResult result = dateRule.validate(withDate(issueDate));

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("data ausente");
    }

    @Test
    void should_fail_when_issueDateIsMissing() {
        assertThat(dateRule.validate(withDate(null)).passed()).isFalse();
    }

    @Test
    void should_pass_when_allFiveRequiredFieldsArePresent() {
        RuleResult result = requiredRule.validate(new FiscalDataSnapshot(
                "11.222.333/0001-81", "Mercado", "2026-10-02", new BigDecimal("95.97"), new BigDecimal("95.97"), 3));

        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).isEqualTo("5 de 5 presentes");
    }

    @Test
    void should_countWhatIsPresent_when_fieldsAreMissing() {
        RuleResult noItems = requiredRule.validate(new FiscalDataSnapshot(
                "11.222.333/0001-81", "Mercado", "2026-10-02", new BigDecimal("95.97"), BigDecimal.ZERO, 0));
        RuleResult onlyIssuer = requiredRule.validate(new FiscalDataSnapshot(" ", "Mercado", null, null, null, 0));
        RuleResult nothing = requiredRule.validate(new FiscalDataSnapshot(null, null, null, null, null, 0));

        assertThat(noItems.passed()).isFalse();
        assertThat(noItems.detail()).isEqualTo("4 de 5 presentes");
        assertThat(onlyIssuer.detail()).isEqualTo("1 de 5 presentes");
        assertThat(nothing.detail()).isEqualTo("0 de 5 presentes");
    }

    private FiscalDataSnapshot withDate(String issueDate) {
        return new FiscalDataSnapshot(null, null, issueDate, null, null, 0);
    }
}
