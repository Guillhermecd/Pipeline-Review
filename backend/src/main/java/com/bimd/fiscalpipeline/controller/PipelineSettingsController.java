package com.bimd.fiscalpipeline.controller;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.dto.PipelineSettingsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/configuracao")
@RequiredArgsConstructor
public class PipelineSettingsController {

    private final PipelineProperties properties;

    @GetMapping
    public PipelineSettingsResponse current() {
        return new PipelineSettingsResponse(properties.reviewConfidenceThreshold());
    }
}
