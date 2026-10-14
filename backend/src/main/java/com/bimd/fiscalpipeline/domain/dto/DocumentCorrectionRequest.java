package com.bimd.fiscalpipeline.domain.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A reviewer's correction; a field left out is not changed.
 *
 * @param total decimal string with a dot and two places ({@code "283.80"}), as money travels
 */
public record DocumentCorrectionRequest(
        @Size(min = 1, max = 18, message = "cnpj must have 1 to 18 characters") String cnpj,
        @Pattern(regexp = "^\\d{1,12}\\.\\d{2}$", message = "total must be a decimal like 283.80") String total) {

    @AssertTrue(message = "send cnpj, total or both")
    public boolean isNotEmpty() {
        return cnpj != null || total != null;
    }
}
