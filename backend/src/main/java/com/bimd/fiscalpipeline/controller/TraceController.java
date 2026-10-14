package com.bimd.fiscalpipeline.controller;

import com.bimd.fiscalpipeline.domain.dto.TraceResponse;
import com.bimd.fiscalpipeline.service.trace.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/traces")
@RequiredArgsConstructor
public class TraceController {

    private final TraceService traceService;

    @GetMapping("/{traceId}")
    public TraceResponse getTrace(@PathVariable String traceId) {
        return traceService.getTrace(traceId);
    }
}
