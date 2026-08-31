package com.hethongdata.taichinh.repository.jpa.ingestion;

import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobJpaRepository extends JpaRepository<IngestionJobEntity, UUID> {
    List<IngestionJobEntity> findByActiveTrue();
}


