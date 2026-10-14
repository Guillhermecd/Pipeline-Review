package com.bimd.fiscalpipeline.service.validation;

import com.bimd.fiscalpipeline.domain.enums.RuleCode;

/**
 * One validation rule. A new rule is a new class; the existing ones do not change.
 *
 * <p>A rule never throws on the data: a missing or malformed value is a failed rule, not an error.
 */
public interface DocumentRule {

    RuleCode code();

    RuleResult validate(FiscalDataSnapshot data);
}
