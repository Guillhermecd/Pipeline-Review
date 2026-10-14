package com.bimd.fiscalpipeline.domain.dto;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import jakarta.validation.constraints.NotNull;

public record BrokerSwitchRequest(@NotNull BrokerKind kind) {
}
