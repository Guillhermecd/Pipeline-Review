package com.bimd.fiscalpipeline.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** What the worker answered for one request, kept as received. One per {@code requestId}. */
@Entity
@Table(name = "extractions")
@Getter
@Setter
@NoArgsConstructor
public class Extraction {

    @Id
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_result", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rawResult;

    @Column(name = "ocr_engine", length = 32)
    private String ocrEngine;

    @Column(name = "llm_model", length = 64)
    private String llmModel;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "created_at", nullable = false, length = 24)
    private String createdAt;
}
