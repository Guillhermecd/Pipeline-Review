package com.bimd.fiscalpipeline.domain.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Validation rules, in the order the review screen lists them (BACKEND.md §6.4). */
@Getter
@RequiredArgsConstructor
public enum RuleCode {
    CNPJ_CHECK_DIGIT("Dígito verificador do CNPJ"),
    ITEMS_SUM_EQUALS_TOTAL("Soma dos itens = total"),
    ISSUE_DATE_NOT_FUTURE("Data não futura"),
    REQUIRED_FIELDS_PRESENT("Campos obrigatórios");

    /** Shown on screen as it is. */
    private final String displayName;
}
