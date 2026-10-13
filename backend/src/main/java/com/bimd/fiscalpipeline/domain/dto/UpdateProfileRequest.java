package com.bimd.fiscalpipeline.domain.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(@NotBlank String name) {
}
