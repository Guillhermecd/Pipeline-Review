package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.ValidationResult;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ValidationResultRepository extends JpaRepository<ValidationResult, ValidationResult.Key> {

    List<ValidationResult> findByDocumentId(UUID documentId);

    List<ValidationResult> findByDocumentIdIn(Collection<UUID> documentIds);
}
