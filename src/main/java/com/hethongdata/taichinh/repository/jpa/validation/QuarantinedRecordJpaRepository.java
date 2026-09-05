package com.hethongdata.taichinh.repository.jpa.validation;

import com.hethongdata.taichinh.entity.validation.QuarantinedRecordEntity;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QuarantinedRecordJpaRepository extends JpaRepository<QuarantinedRecordEntity, UUID> {
    boolean existsByRawPayloadIdAndReasonCode(UUID rawPayloadId, String reasonCode);
    List<QuarantinedRecordEntity> findAllByOrderByCreatedAtDesc(org.springframework.data.domain.Pageable pageable);
}
