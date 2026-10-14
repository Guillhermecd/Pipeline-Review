package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.Review;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    List<Review> findByDocumentId(UUID documentId);
}
