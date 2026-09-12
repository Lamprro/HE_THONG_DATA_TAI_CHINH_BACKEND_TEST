package com.hethongdata.taichinh.repository.jpa.validation;

import com.hethongdata.taichinh.entity.validation.DataVersionEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DataVersionJpaRepository extends JpaRepository<DataVersionEntity, UUID> {
    Optional<DataVersionEntity> findByIngestionRunId(UUID ingestionRunId);

    List<DataVersionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<DataVersionEntity> findByDataDomainAndStatusOrderByCreatedAtAsc(String dataDomain, String status);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select version from DataVersionEntity version where version.id = :id")
    Optional<DataVersionEntity> findByIdForUpdate(@Param("id") UUID id);
}
