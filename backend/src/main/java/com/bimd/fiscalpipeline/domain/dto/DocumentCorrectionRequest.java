package com.bimd.fiscalpipeline.domain.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A reviewer's correction; a field left out is not changed. The messages are shown to the reviewer.
 *
 * @param total decimal string with a dot and two places ({@code "283.80"}), as money travels
 */
public record DocumentCorrectionRequest(
        @Size(min = 1, max = 18, message = "deve ter de 1 a 18 caracteres") String cnpj,
        @Pattern(
                        regexp = "^\\d{1,12}\\.\\d{2}$",
                        message = "deve ser um valor com ponto e duas casas decimais, como 283.80")
                String total) {
}
