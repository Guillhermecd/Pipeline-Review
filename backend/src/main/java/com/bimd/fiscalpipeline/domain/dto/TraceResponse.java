package com.bimd.fiscalpipeline.domain.dto;

import com.bimd.fiscalpipeline.domain.enums.PipelineNode;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.enums.SpanStatus;
import java.util.List;

/**
 * Mirrors what the Traces screen draws (BACKEND.md §9.3).
 *
 * @param durationMs from the start of the first span to the end of the last one
 */
public record TraceResponse(String traceId, long durationMs, List<Span> spans) {

    /** @param startMs relative to the start of the trace */
    public record Span(
            ServiceName service,
            PipelineNode node,
            String name,
            String stage,
            long startMs,
            long durationMs,
            SpanStatus status) {
    }
}
