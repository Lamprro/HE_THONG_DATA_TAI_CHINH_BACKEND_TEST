package com.hethongdata.taichinh.repository.jpa.ingestion;

import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestionJobJpaRepository extends JpaRepository<IngestionJobEntity, UUID> {
    List<IngestionJobEntity> findByActiveTrue();

    Optional<IngestionJobEntity> findByCodeIgnoreCase(String code);
}
