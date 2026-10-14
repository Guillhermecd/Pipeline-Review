package com.bimd.fiscalpipeline.domain.dto;

import java.math.BigDecimal;

/** @param threshold confidence below which a document goes to human review, from 0 to 1 */
public record PipelineSettingsResponse(BigDecimal threshold) {
}
