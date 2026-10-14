package com.bimd.fiscalpipeline.service.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.bimd.fiscalpipeline.domain.enums.RuleCode;
import com.bimd.fiscalpipeline.util.CnpjCheckDigit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CnpjValidatorTest {

    private final CnpjCheckDigitRule rule = new CnpjCheckDigitRule();

    @ParameterizedTest
    @CsvSource({
        "11.222.333/0001-81, DV 81 confere",
        "11222333000181,     DV 81 confere",
        "60.701.190/0001-04, DV 04 confere",
        "27.365.041/0001-38, DV 38 confere",
    })
    void should_pass_when_numericCnpjHasTheRightCheckDigits(String cnpj, String detail) {
        RuleResult result = validate(cnpj);

        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).isEqualTo(detail);
        assertThat(result.code()).isEqualTo(RuleCode.CNPJ_CHECK_DIGIT);
    }

    @ParameterizedTest
    @CsvSource({
        "11.222.333/0001-82, 'DV calculado 81, lido 82'",
        "11.222.333/0001-91, 'DV calculado 81, lido 91'",
        "27.365.041/0001-39, 'DV calculado 38, lido 39'",
    })
    void should_fail_when_checkDigitsDoNotMatch(String cnpj, String detail) {
        RuleResult result = validate(cnpj);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo(detail);
    }

    /** The example published by the tax authority for the alphanumeric CNPJ. */
    @ParameterizedTest
    @ValueSource(strings = {"12.ABC.345/01DE-35", "12ABC34501DE35", "12.abc.345/01de-35"})
    void should_pass_when_cnpjIsAlphanumeric(String cnpj) {
        RuleResult result = validate(cnpj);

        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).isEqualTo("DV 35 confere");
    }

    @Test
    void should_fail_when_alphanumericCnpjHasWrongCheckDigits() {
        RuleResult result = validate("12.ABC.345/01DE-36");

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("DV calculado 35, lido 36");
    }

    @ParameterizedTest
    @CsvSource({
        "11.222.333/0001-8,     '13 caracteres, esperado 14'",
        "11.222.333/0001-811,   '15 caracteres, esperado 14'",
        "123,                   '3 caracteres, esperado 14'",
    })
    void should_fail_when_lengthIsWrong(String cnpj, String detail) {
        RuleResult result = validate(cnpj);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo(detail);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "../-"})
    void should_fail_when_cnpjIsMissing(String cnpj) {
        RuleResult result = validate(cnpj);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("0 caracteres, esperado 14");
    }

    /** Letters are not allowed in the check digits, and nothing but letters and digits anywhere. */
    @ParameterizedTest
    @ValueSource(strings = {"12.ABC.345/01DE-3X", "12.ÁBC.345/01DE-35", "11.222.333/0001-8*"})
    void should_fail_when_charactersAreNotValidForACnpj(String cnpj) {
        RuleResult result = validate(cnpj);

        assertThat(result.passed()).isFalse();
        assertThat(result.detail()).isEqualTo("caracteres inválidos para um CNPJ");
    }

    @Test
    void should_valueEachCharacterAsAsciiMinus48_when_computingCheckDigits() {
        assertThat(CnpjCheckDigit.compute("112223330001")).isEqualTo("81");
        assertThat(CnpjCheckDigit.compute("12ABC34501DE")).isEqualTo("35");
        // Remainder below 2 gives a zero digit.
        assertThat(CnpjCheckDigit.compute("607011900001")).isEqualTo("04");
    }

    private RuleResult validate(String cnpj) {
        return rule.validate(new FiscalDataSnapshot(cnpj, null, null, null, null, 0));
    }
}
