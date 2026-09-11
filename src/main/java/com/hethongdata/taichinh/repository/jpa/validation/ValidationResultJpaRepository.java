package com.hethongdata.taichinh.repository.jpa.validation;

import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ValidationResultJpaRepository extends JpaRepository<ValidationResultEntity, UUID> {
    boolean existsByRawPayloadId(UUID rawPayloadId);

    List<ValidationResultEntity> findByRawPayloadIdOrderByCheckedAtAsc(UUID rawPayloadId);

    List<ValidationResultEntity> findAllByOrderByCheckedAtDesc(Pageable pageable);

    List<ValidationResultEntity> findByStatusAndHandlingStatusOrderByCheckedAtDesc(
            String status, String handlingStatus, Pageable pageable);

    @Query(
            "select count(raw) from RawPayloadEntity raw "
                    + "where raw.ingestionRun.id = :ingestionRunId "
                    + "and exists (select result from ValidationResultEntity result "
                    + "where result.rawPayloadId = raw.id)")
    long countValidatedRawPayloadsByIngestionRunId(
            @Param("ingestionRunId") UUID ingestionRunId);

    boolean existsByIngestionRunIdAndStatusAndSeverityIn(
            UUID ingestionRunId, String status, Collection<String> severities);

    boolean existsByIngestionRunIdAndStatusAndRuleCode(
            UUID ingestionRunId, String status, String ruleCode);
}
