package com.hethongdata.taichinh.repository.jpa.ingestion;

import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestionRunJpaRepository extends JpaRepository<IngestionRunEntity, UUID> {
    List<IngestionRunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);

    Optional<IngestionRunEntity> findTopByIngestionJobIdOrderByStartedAtDesc(UUID ingestionJobId);
}
