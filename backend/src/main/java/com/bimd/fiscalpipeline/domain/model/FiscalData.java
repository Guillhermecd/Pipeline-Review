package com.bimd.fiscalpipeline.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The fiscal fields of a document as they stand now: extracted, then possibly corrected in review. */
@Entity
@Table(name = "fiscal_data")
@Getter
@Setter
@NoArgsConstructor
public class FiscalData {

    @Id
    @Column(name = "document_id")
    private UUID documentId;

    /** Text, not a number: CNPJ can be alphanumeric. */
    @Column(length = 18)
    private String cnpj;

    private String issuer;

    @Column(name = "issue_date", length = 10)
    private String issueDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal total;

    @Column(name = "items_sum", precision = 14, scale = 2)
    private BigDecimal itemsSum;
}
