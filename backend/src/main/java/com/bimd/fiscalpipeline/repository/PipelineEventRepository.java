package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.PipelineEventRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PipelineEventRepository extends JpaRepository<PipelineEventRecord, UUID> {

    List<PipelineEventRecord> findByDocumentCodeOrderBySequenceAsc(String documentCode);

    List<PipelineEventRecord> findAllByOrderBySequenceDesc(Pageable pageable);

    @Modifying
    @Query(
            value = "DELETE FROM pipeline_events WHERE id IN"
                    + " (SELECT id FROM pipeline_events ORDER BY sequence DESC OFFSET :kept)",
            nativeQuery = true)
    int deleteAllButLatest(@Param("kept") int kept);
}
