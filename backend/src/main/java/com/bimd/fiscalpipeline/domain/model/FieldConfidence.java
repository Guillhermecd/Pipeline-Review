package com.bimd.fiscalpipeline.domain.model;

import com.bimd.fiscalpipeline.domain.enums.FieldKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** How sure the pipeline is about one field, and where the value came from. */
@Entity
@Table(name = "field_confidences")
@IdClass(FieldConfidence.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FieldConfidence {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FieldKey field;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal confidence;

    /** {@code pdf_text}, {@code ocr}, {@code qr_code}, {@code llm} or {@code review}. */
    @Column(nullable = false, length = 16)
    private String source;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID documentId;
        private FieldKey field;
    }
}
