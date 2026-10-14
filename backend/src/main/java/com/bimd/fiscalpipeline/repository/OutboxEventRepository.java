package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.OutboxEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByDocumentId(UUID documentId);

    /**
     * Oldest unpublished message of a broker, locked until the transaction ends. SKIP LOCKED lets
     * a second relay instance take the next row instead of waiting for, or re-sending, this one.
     */
    @Query(
            value = "SELECT * FROM outbox_events WHERE published_at IS NULL AND broker = :broker"
                    + " ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    Optional<OutboxEvent> lockNextPending(@Param("broker") String broker);
}
