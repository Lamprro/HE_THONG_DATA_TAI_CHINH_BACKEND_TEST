package com.hethongdata.taichinh.repository.jpa.ingestion;

import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestionRunJpaRepository extends JpaRepository<IngestionRunEntity, UUID> {
    List<IngestionRunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);

    Optional<IngestionRunEntity> findTopByIngestionJobIdOrderByStartedAtDesc(UUID ingestionJobId);

    /** Serializes the final validation decision and data-version creation for one ingestion run. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from IngestionRunEntity run where run.id = :runId")
    Optional<IngestionRunEntity> findByIdForUpdate(@Param("runId") UUID runId);
}
