package com.bimd.fiscalpipeline.domain.mapper;

import com.bimd.fiscalpipeline.domain.model.DocumentItem;
import com.bimd.fiscalpipeline.domain.model.FieldConfidence;
import com.bimd.fiscalpipeline.domain.model.FiscalData;
import com.bimd.fiscalpipeline.domain.model.ValidationResult;
import java.util.List;

/**
 * What a document has besides its own row, loaded together so the mapper needs no repository.
 *
 * @param fiscalData null until the extraction arrives
 */
public record DocumentDetails(
        FiscalData fiscalData,
        List<DocumentItem> items,
        List<FieldConfidence> confidences,
        List<ValidationResult> validations) {

    public static final DocumentDetails EMPTY = new DocumentDetails(null, List.of(), List.of(), List.of());
}
