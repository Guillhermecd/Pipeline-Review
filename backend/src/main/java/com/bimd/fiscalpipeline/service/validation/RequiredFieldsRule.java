package com.bimd.fiscalpipeline.service.validation;

import com.bimd.fiscalpipeline.domain.enums.RuleCode;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** CNPJ, issuer, issue date, total and at least one item. */
@Component
public class RequiredFieldsRule implements DocumentRule {

    private static final int REQUIRED = 5;

    @Override
    public RuleCode code() {
        return RuleCode.REQUIRED_FIELDS_PRESENT;
    }

    @Override
    public RuleResult validate(FiscalDataSnapshot data) {
        long present = Stream.of(
                        hasText(data.cnpj()),
                        hasText(data.issuer()),
                        hasText(data.issueDate()),
                        data.total() != null,
                        data.itemCount() > 0)
                .filter(Boolean::booleanValue)
                .count();
        return new RuleResult(code(), present == REQUIRED, "%d de %d presentes".formatted(present, REQUIRED));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
