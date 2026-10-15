package com.bimd.fiscalpipeline.service.trace;

import com.bimd.fiscalpipeline.domain.dto.TraceResponse;
import com.bimd.fiscalpipeline.domain.enums.PipelineNode;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.enums.SpanStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns a trace from the Jaeger query API into what the Traces screen draws.
 *
 * <p>Only the spans the pipeline opened by hand are kept: they are the ones that carry
 * {@code pipeline.node}. The agent also records every JDBC statement, HTTP call and broker
 * operation, a few hundred spans per document that would bury the steps the screen is about.
 */
@Component
public class JaegerTraceMapper {

    private static final String WORKER_SERVICE = "fiscal-worker";
    private static final String WAIT = "wait";
    private static final String ERROR_STATUS = "ERROR";
    private static final long MICROS_PER_MILLI = 1000;

    /** @param trace one element of {@code data} in the Jaeger response */
    public TraceResponse toResponse(String traceId, JsonNode trace) {
        JsonNode processes = trace.path("processes");
        List<Mapped> mapped = new ArrayList<>();
        for (JsonNode span : trace.path("spans")) {
            map(span, processes).ifPresent(mapped::add);
        }
        if (mapped.isEmpty()) {
            return new TraceResponse(traceId, 0, List.of());
        }
        mapped.sort(Comparator.comparingLong(Mapped::startMicros));
        long start = mapped.getFirst().startMicros();
        long end = mapped.stream().mapToLong(span -> span.startMicros() + span.durationMicros()).max().orElse(start);
        List<TraceResponse.Span> spans = mapped.stream()
                .map(span -> new TraceResponse.Span(
                        span.service(),
                        span.node(),
                        span.name(),
                        span.stage(),
                        millis(span.startMicros() - start),
                        millis(span.durationMicros()),
                        span.status()))
                .toList();
        return new TraceResponse(traceId, millis(end - start), spans);
    }

    private Optional<Mapped> map(JsonNode span, JsonNode processes) {
        Map<String, JsonNode> tags = new HashMap<>();
        span.path("tags").forEach(tag -> tags.put(tag.path("key").asText(), tag.path("value")));
        Optional<PipelineNode> node = node(tags.get(PipelineTracer.NODE_ATTRIBUTE));
        if (node.isEmpty()) {
            return Optional.empty();
        }
        String process = processes.path(span.path("processID").asText()).path("serviceName").asText();
        return Optional.of(new Mapped(
                service(tags, process),
                node.get(),
                span.path("operationName").asText(),
                text(tags.get(PipelineTracer.STAGE_ATTRIBUTE)),
                span.path("startTime").asLong(),
                span.path("duration").asLong(),
                status(tags)));
    }

    /** Database and broker spans are shown as such, whichever process opened them. */
    private ServiceName service(Map<String, JsonNode> tags, String process) {
        if (tags.containsKey("db.system")) {
            return ServiceName.DB;
        }
        if (tags.containsKey("messaging.system")) {
            return ServiceName.BROKER;
        }
        return WORKER_SERVICE.equals(process) ? ServiceName.WORKER : ServiceName.API;
    }

    private SpanStatus status(Map<String, JsonNode> tags) {
        JsonNode error = tags.get("error");
        boolean failed = error != null && error.asBoolean(false);
        if (failed || ERROR_STATUS.equals(text(tags.get("otel.status_code")))) {
            return SpanStatus.ERROR;
        }
        return WAIT.equals(text(tags.get("pipeline.span.status"))) ? SpanStatus.WAIT : SpanStatus.OK;
    }

    private Optional<PipelineNode> node(JsonNode value) {
        String code = text(value);
        return Arrays.stream(PipelineNode.values())
                .filter(node -> node.getCode().equals(code))
                .findFirst();
    }

    private String text(JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText();
    }

    private long millis(long micros) {
        return Math.round((double) micros / MICROS_PER_MILLI);
    }

    private record Mapped(
            ServiceName service,
            PipelineNode node,
            String name,
            String stage,
            long startMicros,
            long durationMicros,
            SpanStatus status) {
    }
}
