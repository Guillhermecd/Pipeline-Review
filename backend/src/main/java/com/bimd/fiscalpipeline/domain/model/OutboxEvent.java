package com.bimd.fiscalpipeline.domain.model;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Message waiting to leave for the broker. Written in the same transaction as the state
 * change it announces; {@code payload} holds the full envelope so the relay publishes it as is.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    /** Also the envelope's {@code eventId}. */
    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BrokerKind broker;

    @Column(nullable = false, length = 80)
    private String destination;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(nullable = false, length = 55)
    private String traceparent;

    @Column(name = "created_at", nullable = false, length = 24)
    private String createdAt;

    @Column(name = "published_at", length = 24)
    private String publishedAt;
}
