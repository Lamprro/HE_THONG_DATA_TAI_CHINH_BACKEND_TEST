package com.hethongdata.taichinh.repository.jpa.ingestion;

import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionRunJpaRepository extends JpaRepository<IngestionRunEntity, UUID> {
    List<IngestionRunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
    Optional<IngestionRunEntity> findTopByIngestionJobIdOrderByStartedAtDesc(UUID ingestionJobId);
}


