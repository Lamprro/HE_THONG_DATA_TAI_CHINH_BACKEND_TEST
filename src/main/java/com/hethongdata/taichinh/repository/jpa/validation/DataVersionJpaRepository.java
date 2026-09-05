package com.hethongdata.taichinh.repository.jpa.validation;

import com.hethongdata.taichinh.entity.validation.DataVersionEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DataVersionJpaRepository extends JpaRepository<DataVersionEntity, UUID> {
    Optional<DataVersionEntity> findByIngestionRunId(UUID ingestionRunId);

    List<DataVersionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
