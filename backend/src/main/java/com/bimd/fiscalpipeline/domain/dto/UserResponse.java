package com.bimd.fiscalpipeline.domain.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String name,
        String email,
        Instant createdAt,
        Instant updatedAt) {
}
