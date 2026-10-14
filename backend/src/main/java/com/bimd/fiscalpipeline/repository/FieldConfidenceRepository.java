package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.FieldConfidence;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FieldConfidenceRepository extends JpaRepository<FieldConfidence, FieldConfidence.Key> {

    List<FieldConfidence> findByDocumentId(UUID documentId);

    List<FieldConfidence> findByDocumentIdIn(Collection<UUID> documentIds);
}
