package com.bimd.fiscalpipeline.domain.model;

import com.bimd.fiscalpipeline.domain.enums.RuleCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The latest outcome of one rule for a document; rewritten every time the document is validated. */
@Entity
@Table(name = "validation_results")
@IdClass(ValidationResult.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResult {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_code", length = 40)
    private RuleCode ruleCode;

    @Column(nullable = false)
    private boolean passed;

    @Column(nullable = false)
    private String detail;

    @Column(name = "created_at", nullable = false, length = 24)
    private String createdAt;

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID documentId;
        private RuleCode ruleCode;
    }
}
