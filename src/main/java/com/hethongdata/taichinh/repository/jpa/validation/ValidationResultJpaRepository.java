package com.hethongdata.taichinh.repository.jpa.validation;

import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ValidationResultJpaRepository extends JpaRepository<ValidationResultEntity, UUID> {
    boolean existsByRawPayloadId(UUID rawPayloadId);

    List<ValidationResultEntity> findByRawPayloadIdOrderByCheckedAtAsc(UUID rawPayloadId);

    List<ValidationResultEntity> findAllByOrderByCheckedAtDesc(Pageable pageable);
}
