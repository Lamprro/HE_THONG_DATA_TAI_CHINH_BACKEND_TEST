package com.hethongdata.taichinh.repository.jpa.validation;

import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import java.util.UUID;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DataVersionJpaRepository extends JpaRepository<DataVersionEntity, UUID> {
    Optional<DataVersionEntity> findByIngestionRunId(UUID ingestionRunId);
    List<DataVersionEntity> findAllByOrderByCreatedAtDesc(org.springframework.data.domain.Pageable pageable);
}
