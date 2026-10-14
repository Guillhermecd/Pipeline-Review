package com.bimd.fiscalpipeline.service.trace;

import com.bimd.fiscalpipeline.domain.enums.PipelineNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The spans the Traces screen shows (BACKEND.md §9.2). Everything here uses only the
 * OpenTelemetry API: with the Java agent attached the spans are exported, without it (tests,
 * plain {@code spring-boot:run}) every call is a no-op and the pipeline behaves the same.
 */
@Component
public class PipelineTracer {

    public static final String TRACEPARENT = "traceparent";
    public static final String NODE_ATTRIBUTE = "pipeline.node";
    public static final String STAGE_ATTRIBUTE = "pipeline.stage";
    private static final String DB_SYSTEM = "db.system";
    private static final String MESSAGING_SYSTEM = "messaging.system";
    private static final String POSTGRESQL = "postgresql";

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    /** How the Traces screen colours a span: by the service it belongs to. */
    public enum Kind {
        /** Shown as the service that created it (api). */
        SERVICE,
        /** Shown as the database. */
        DATABASE,
        /** Shown as the broker. */
        MESSAGING
    }

    private final Tracer tracer = GlobalOpenTelemetry.getTracer("fiscal-pipeline");

    /** Runs {@code body} inside a span; an exception marks the span as failed and is rethrown. */
    public <T> T inSpan(String name, Kind kind, PipelineNode node, String stage, Supplier<T> body) {
        Span span = tracer.spanBuilder(name)
                .setAttribute(NODE_ATTRIBUTE, node.getCode())
                .setAttribute(STAGE_ATTRIBUTE, stage)
                .startSpan();
        if (kind == Kind.DATABASE) {
            span.setAttribute(DB_SYSTEM, POSTGRESQL);
        } else if (kind == Kind.MESSAGING) {
            span.setAttribute(MESSAGING_SYSTEM, "broker");
        }
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void inSpan(String name, Kind kind, PipelineNode node, String stage, Runnable body) {
        inSpan(name, kind, node, stage, () -> {
            body.run();
            return null;
        });
    }

    /** Labels the span that is already open, such as the HTTP request span the agent created. */
    public void tagCurrentSpan(PipelineNode node, String stage) {
        Span.current().setAttribute(NODE_ATTRIBUTE, node.getCode()).setAttribute(STAGE_ATTRIBUTE, stage);
    }

    /** The trace the current code runs in, when there is one being recorded. */
    public Optional<SpanContext> currentSpan() {
        SpanContext context = Span.current().getSpanContext();
        return context.isValid() ? Optional.of(context) : Optional.empty();
    }

    /** {@code traceparent} for whoever continues from the current span, when there is one. */
    public Optional<String> currentTraceparent() {
        return currentSpan()
                .map(context -> "00-%s-%s-01".formatted(context.getTraceId(), context.getSpanId()));
    }

    /**
     * Makes the trace of a {@code traceparent} the current one until the scope is closed. Work
     * done outside the request that started the trace (the outbox relay, a message listener)
     * joins it this way; otherwise the trace would break in two.
     */
    public Scope continueFrom(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Context.current().makeCurrent();
        }
        Context context = W3CTraceContextPropagator.getInstance()
                .extract(Context.current(), Map.of(TRACEPARENT, traceparent), MAP_GETTER);
        return context.makeCurrent();
    }
}
