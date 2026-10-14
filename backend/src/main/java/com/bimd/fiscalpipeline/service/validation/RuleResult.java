package com.bimd.fiscalpipeline.service.validation;

import com.bimd.fiscalpipeline.domain.enums.RuleCode;

/** @param detail shown on the review screen as it is */
public record RuleResult(RuleCode code, boolean passed, String detail) {
}
