package com.bimd.fiscalpipeline.domain.enums;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Fields that carry a confidence, with the name the frontend uses and the weight in the score
 * (BACKEND.md §6.5). The weights add up to 1.
 */
@Getter
@RequiredArgsConstructor
public enum FieldKey {
    CNPJ("cnpj", new BigDecimal("0.25")),
    ISSUER("issuer", new BigDecimal("0.15")),
    ISSUE_DATE("issueDate", new BigDecimal("0.15")),
    TOTAL("total", new BigDecimal("0.25")),
    ITEMS("items", new BigDecimal("0.20"));

    private final String jsonName;
    private final BigDecimal weight;
}
