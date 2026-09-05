package com.hethongdata.taichinh.repository.jpa.validation;

import com.hethongdata.taichinh.entity.validation.QuarantinedRecordEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface QuarantinedRecordJpaRepository
        extends JpaRepository<QuarantinedRecordEntity, UUID> {
    boolean existsByRawPayloadIdAndReasonCode(UUID rawPayloadId, String reasonCode);

    List<QuarantinedRecordEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
