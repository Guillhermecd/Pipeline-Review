package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.Document;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdempotencyKey(String idempotencyKey);

    List<Document> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Every status change reads the document through here, so two messages about the same
     * document (or a message and the outbox relay) are applied one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Document d WHERE d.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "SELECT nextval('document_code_seq')", nativeQuery = true)
    long nextCodeNumber();
}
