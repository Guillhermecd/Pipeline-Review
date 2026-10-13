package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByDocumentId(UUID documentId);
}
