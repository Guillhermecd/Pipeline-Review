package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.ProcessedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
