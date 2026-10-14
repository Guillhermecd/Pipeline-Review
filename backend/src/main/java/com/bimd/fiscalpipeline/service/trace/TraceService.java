package com.bimd.fiscalpipeline.service.trace;

import com.bimd.fiscalpipeline.config.PipelineProperties;
import com.bimd.fiscalpipeline.domain.dto.TraceResponse;
import com.bimd.fiscalpipeline.exception.TraceBackendUnavailableException;
import com.bimd.fiscalpipeline.exception.TraceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Reads a document's trace from the Jaeger query API (BACKEND.md §9.3). */
@Slf4j
@Service
public class TraceService {

    private static final String TRACE_ID_PATTERN = "[0-9a-f]{32}";

    private final RestClient jaeger;
    private final JaegerTraceMapper mapper;

    public TraceService(RestClient.Builder builder, PipelineProperties properties, JaegerTraceMapper mapper) {
        this.jaeger = builder.baseUrl(properties.jaegerQueryUrl()).build();
        this.mapper = mapper;
    }

    /**
     * @throws TraceNotFoundException when Jaeger does not have the trace. Spans are exported in
     *     batches, so a trace only a few seconds old may not be there yet
     * @throws TraceBackendUnavailableException when Jaeger cannot be reached
     */
    public TraceResponse getTrace(String traceId) {
        // The id goes into a URL of another service: nothing but a trace id may pass.
        if (traceId == null || !traceId.matches(TRACE_ID_PATTERN)) {
            throw new TraceNotFoundException(traceId);
        }
        JsonNode body;
        try {
            body = jaeger.get().uri("/api/traces/{traceId}", traceId).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new TraceNotFoundException(traceId);
            }
            log.warn("Jaeger answered {} for trace {}", e.getStatusCode(), traceId);
            throw new TraceBackendUnavailableException(e);
        } catch (RestClientException e) {
            log.warn("Jaeger could not be reached: {}", e.getMessage());
            throw new TraceBackendUnavailableException(e);
        }
        JsonNode trace = body == null ? null : body.path("data").path(0);
        if (trace == null || trace.isMissingNode()) {
            throw new TraceNotFoundException(traceId);
        }
        return mapper.toResponse(traceId, trace);
    }
}
