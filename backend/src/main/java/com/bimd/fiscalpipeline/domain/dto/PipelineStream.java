package com.bimd.fiscalpipeline.domain.dto;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** What travels on {@code GET /api/v1/eventos} (BACKEND.md §7.2), one record per event type. */
public final class PipelineStream {

    public static final String SNAPSHOT = "snapshot";
    public static final String DOCUMENT = "document";
    public static final String EVENT = "event";
    public static final String ENVELOPE = "envelope";
    public static final String BROKER = "broker";

    private PipelineStream() {
    }

    /** The whole state, sent when a client connects. Lists are newest first. */
    public record Snapshot(
            List<DocumentResponse> documents,
            List<Event> events,
            BrokerConfigResponse broker,
            BigDecimal threshold,
            Simulator simulator) {
    }

    /** @param time {@code HH:mm:ss} in Brazil time, as the "Eventos" card shows it */
    public record Event(
            UUID id, String time, ServiceName service, String documentCode, String message, EventLevel level) {
    }

    /** The "Último envelope" card: the latest message published or consumed by the API. */
    public record Envelope(
            String eventType,
            UUID documentId,
            String idempotencyKey,
            int attempt,
            String traceparent,
            BrokerKind broker) {
    }

    public record Simulator(boolean connected, String url, long mirroredEvents) {
    }
}
