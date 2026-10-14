package com.bimd.fiscalpipeline.service.validation;

import java.math.BigDecimal;

/**
 * What the rules look at. Any field may be null: the worker sends null for what it could not
 * read, and a value that does not fit its type is stored as missing.
 *
 * @param issueDate {@code yyyy-MM-dd}
 * @param itemsSum sum of quantity × unit price over the items, rounded to cents
 */
public record FiscalDataSnapshot(
        String cnpj, String issuer, String issueDate, BigDecimal total, BigDecimal itemsSum, int itemCount) {
}
