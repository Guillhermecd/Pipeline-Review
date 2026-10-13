package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.Document;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByIdempotencyKey(String idempotencyKey);

    List<Document> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query(value = "SELECT nextval('document_code_seq')", nativeQuery = true)
    long nextCodeNumber();
}
