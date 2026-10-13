package com.bimd.fiscalpipeline.domain.model;

import com.bimd.fiscalpipeline.domain.enums.EventLevel;
import com.bimd.fiscalpipeline.domain.enums.ServiceName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One line of the "Eventos" card. */
@Entity
@Table(name = "pipeline_events")
@Getter
@Setter
@NoArgsConstructor
public class PipelineEventRecord {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false, length = 24)
    private String occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ServiceName service;

    @Column(name = "document_code", nullable = false, length = 16)
    private String documentCode;

    @Column(nullable = false, length = 500)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private EventLevel level;
}
