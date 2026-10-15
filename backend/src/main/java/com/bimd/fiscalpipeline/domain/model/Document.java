package com.bimd.fiscalpipeline.domain.model;

import com.bimd.fiscalpipeline.domain.enums.BrokerKind;
import com.bimd.fiscalpipeline.domain.enums.DocumentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Dates are ISO 8601 UTC strings, as the project stores them (BACKEND.md §1). */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
public class Document {

    @Id
    private UUID id;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_key", nullable = false, length = 512)
    private String fileKey;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "idempotency_key", nullable = false, length = 80)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BrokerKind broker;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DocumentStatus status;

    @Column(length = 32)
    private String kind;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "trace_id", nullable = false, length = 32)
    private String traceId;

    @Column(name = "current_request_id", nullable = false)
    private UUID currentRequestId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "received_at", nullable = false, length = 24)
    private String receivedAt;

    @Column(name = "finished_at", length = 24)
    private String finishedAt;

    /** When the pipeline itself finished: its decision or the DLQ. Unlike finishedAt, not moved by a reviewer. */
    @Column(name = "processed_at", length = 24)
    private String processedAt;

    @Column(name = "created_at", nullable = false, length = 24)
    private String createdAt;

    @Column(name = "updated_at", nullable = false, length = 24)
    private String updatedAt;
}
