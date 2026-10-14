package com.bimd.fiscalpipeline.service.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.bimd.fiscalpipeline.domain.dto.TraceResponse;
import com.bimd.fiscalpipeline.domain.enums.PipelineNode;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import com.bimd.fiscalpipeline.domain.enums.SpanStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runs the mapper over a response recorded from a real Jaeger: one document that failed twice
 * with an LLM timeout and was approved on the third attempt. A few of the agent's automatic
 * spans were kept in the file on purpose.
 */
class JaegerTraceMapperTest {

    private static final String TRACE_ID = "8839e77a756402f89166050d04edab22";

    private static JsonNode trace;

    private final JaegerTraceMapper mapper = new JaegerTraceMapper();

    @BeforeAll
    static void readRecordedTrace() throws IOException {
        try (InputStream file = JaegerTraceMapperTest.class.getResourceAsStream("/jaeger/trace.json")) {
            trace = new ObjectMapper().readTree(file).path("data").path(0);
        }
    }

    @Test
    void should_keepOnlyPipelineSpansInOrder_when_traceHasAutomaticSpansToo() {
        TraceResponse response = mapper.toResponse(TRACE_ID, trace);

        assertThat(trace.path("spans")).hasSize(23);
        assertThat(response.traceId()).isEqualTo(TRACE_ID);
        assertThat(response.spans())
                .extracting(TraceResponse.Span::name)
                .containsExactly(
                        "POST /api/v1/documentos",
                        "INSERT documento ON CONFLICT (idempotency_key)",
                        "publish documentos.recebidos",
                        "consume documentos.recebidos",
                        "ocr.pdf_text",
                        "llm.extract → LLM 504 DEADLINE_EXCEEDED",
                        "retry +1s → documentos.recebidos-retry",
                        "consume documentos.recebidos · tentativa 2",
                        "ocr.pdf_text",
                        "llm.extract → LLM 504 DEADLINE_EXCEEDED",
                        "retry +2s → documentos.recebidos-retry",
                        "consume documentos.recebidos · tentativa 3",
                        "ocr.pdf_text",
                        "llm.extract (json schema)",
                        "publish documentos.extraidos",
                        "consume documentos.extraidos",
                        "validate cnpj.dv + soma.itens",
                        "score.confianca",
                        "UPDATE documento SET status");
    }

    @Test
    void should_nameTheServiceByWhatTheSpanIs_when_mapping() {
        List<TraceResponse.Span> spans = mapper.toResponse(TRACE_ID, trace).spans();

        assertThat(spans)
                .extracting(TraceResponse.Span::service)
                .containsExactly(
                        ServiceName.API,
                        ServiceName.DB,
                        ServiceName.BROKER,
                        ServiceName.WORKER,
                        ServiceName.WORKER,
                        ServiceName.WORKER,
                        ServiceName.BROKER,
                        ServiceName.WORKER,
                        ServiceName.WORKER,
                        ServiceName.WORKER,
                        ServiceName.BROKER,
                        ServiceName.WORKER,
                        ServiceName.WORKER,
                        ServiceName.WORKER,
                        ServiceName.BROKER,
                        ServiceName.API,
                        ServiceName.API,
                        ServiceName.API,
                        ServiceName.DB);
    }

    @Test
    void should_readNodeStageAndStatus_when_mapping() {
        List<TraceResponse.Span> spans = mapper.toResponse(TRACE_ID, trace).spans();

        TraceResponse.Span upload = spans.getFirst();
        assertThat(upload.node()).isEqualTo(PipelineNode.API);
        assertThat(upload.stage()).isEqualTo("Recebido");
        assertThat(upload.status()).isEqualTo(SpanStatus.OK);

        TraceResponse.Span failedCall = spans.get(5);
        assertThat(failedCall.node()).isEqualTo(PipelineNode.WORKER);
        assertThat(failedCall.stage()).isEqualTo("Extração");
        assertThat(failedCall.status()).isEqualTo(SpanStatus.ERROR);

        TraceResponse.Span wait = spans.get(6);
        assertThat(wait.node()).isEqualTo(PipelineNode.RETRY);
        assertThat(wait.stage()).isEqualTo("Retry 1/3");
        assertThat(wait.status()).isEqualTo(SpanStatus.WAIT);

        TraceResponse.Span decision = spans.getLast();
        assertThat(decision.node()).isEqualTo(PipelineNode.VALIDATION);
        assertThat(decision.stage()).isEqualTo("Score");
        assertThat(spans).filteredOn(span -> span.status() == SpanStatus.ERROR).hasSize(2);
        assertThat(spans).filteredOn(span -> span.status() == SpanStatus.WAIT).hasSize(2);
    }

    /** Jaeger reports microseconds from the epoch; the screen wants milliseconds from the start. */
    @Test
    void should_convertTimesToMillisecondsFromTheStart_when_mapping() {
        TraceResponse response = mapper.toResponse(TRACE_ID, trace);
        List<TraceResponse.Span> spans = response.spans();

        assertThat(spans.getFirst().startMs()).isZero();
        assertThat(spans.getFirst().durationMs()).isEqualTo(316);
        assertThat(spans.get(6).startMs()).isEqualTo(30_637);
        assertThat(spans.get(6).durationMs()).isEqualTo(1_013);
        // Up to the end of the span that finishes last, which is not the one that starts last.
        assertThat(response.durationMs()).isBetween(83_500L, 83_600L);
    }

    @Test
    void should_returnNoSpans_when_traceHasNothingFromThePipeline() throws IOException {
        JsonNode onlyAutomatic = new ObjectMapper()
                .readTree("{\"spans\":[{\"operationName\":\"SELECT\",\"startTime\":1,\"duration\":2,"
                        + "\"tags\":[{\"key\":\"db.system\",\"value\":\"postgresql\"},"
                        + "{\"key\":\"pipeline.node\",\"value\":\"not-a-node\"}],\"processID\":\"p1\"}],"
                        + "\"processes\":{\"p1\":{\"serviceName\":\"fiscal-api\"}}}");

        TraceResponse response = mapper.toResponse(TRACE_ID, onlyAutomatic);

        assertThat(response.spans()).isEmpty();
        assertThat(response.durationMs()).isZero();
    }
}
