package com.hethongdata.taichinh.repository.jpa.validation;

import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ValidationResultJpaRepository extends JpaRepository<ValidationResultEntity, UUID> {
    boolean existsByRawPayloadId(UUID rawPayloadId);
    List<ValidationResultEntity> findByRawPayloadIdOrderByCheckedAtAsc(UUID rawPayloadId);
    List<ValidationResultEntity> findAllByOrderByCheckedAtDesc(org.springframework.data.domain.Pageable pageable);
}
