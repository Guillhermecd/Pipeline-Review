package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.DocumentItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentItemRepository extends JpaRepository<DocumentItem, UUID> {

    List<DocumentItem> findByDocumentIdOrderByLine(UUID documentId);

    List<DocumentItem> findByDocumentIdInOrderByLine(Collection<UUID> documentIds);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM DocumentItem i WHERE i.documentId = :documentId")
    void deleteAllOf(@Param("documentId") UUID documentId);
}
