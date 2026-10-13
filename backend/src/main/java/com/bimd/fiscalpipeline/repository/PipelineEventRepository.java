package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineEventRepository extends JpaRepository<PipelineEventRecord, UUID> {

    List<PipelineEventRecord> findByDocumentCodeOrderByOccurredAtAsc(String documentCode);
}
