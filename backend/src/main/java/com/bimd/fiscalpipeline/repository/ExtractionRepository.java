package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.Extraction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractionRepository extends JpaRepository<Extraction, UUID> {

    boolean existsByRequestId(UUID requestId);

    List<Extraction> findByDocumentId(UUID documentId);
}
