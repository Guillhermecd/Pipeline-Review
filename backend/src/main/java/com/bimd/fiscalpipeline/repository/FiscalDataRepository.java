package com.bimd.fiscalpipeline.repository;

import com.bimd.fiscalpipeline.domain.model.FiscalData;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalDataRepository extends JpaRepository<FiscalData, UUID> {

    List<FiscalData> findByDocumentIdIn(Collection<UUID> documentIds);
}
