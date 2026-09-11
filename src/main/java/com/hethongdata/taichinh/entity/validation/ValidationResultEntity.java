package com.hethongdata.taichinh.entity.validation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "validation_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ValidationResultEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "validation_rule_id", nullable = false)
    private Long validationRuleId;

    @Column(name = "ingestion_run_id")
    private UUID ingestionRunId;

    @Column(name = "result_status", nullable = false)
    private String status;

    @Column(name = "rule_code", nullable = false)
    private String ruleCode;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "handling_status", nullable = false)
    private String handlingStatus;

    @Column(name = "observed_value")
    private String observedValue;

    @Column(name = "expected_value")
    private String expectedValue;

    @Column(name = "message")
    private String message;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(name = "raw_payload_id")
    private UUID rawPayloadId;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note")
    private String resolutionNote;

    public static ValidationResultEntity create(
            Long ruleId,
            String ruleCode,
            String severity,
            UUID ingestionRunId,
            UUID rawPayloadId,
            String status,
            String observedValue,
            String expectedValue,
            String message) {
        ValidationResultEntity entity = new ValidationResultEntity();
        entity.validationRuleId = ruleId;
        entity.ruleCode = ruleCode;
        entity.severity = severity;
        entity.ingestionRunId = ingestionRunId;
        entity.rawPayloadId = rawPayloadId;
        entity.status = status;
        entity.handlingStatus = "FAIL".equals(status) ? "OPEN" : "NOT_REQUIRED";
        entity.observedValue = observedValue;
        entity.expectedValue = expectedValue;
        entity.message = message;
        entity.checkedAt = Instant.now();
        return entity;
    }
}
