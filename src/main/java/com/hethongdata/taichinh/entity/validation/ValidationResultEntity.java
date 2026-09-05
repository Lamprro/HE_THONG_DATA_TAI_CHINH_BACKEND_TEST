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

    @Column(name = "validation_rule_id")
    private Long validationRuleId;

    @Column(name = "ingestion_run_id")
    private UUID ingestionRunId;

    @Column(name = "data_version_id")
    private UUID dataVersionId;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_key")
    private String entityKey;

    @Column(name = "status")
    private String status;

    @Column(name = "observed_value")
    private String observedValue;

    @Column(name = "expected_value")
    private String expectedValue;

    @Column(name = "message")
    private String message;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "raw_payload_id")
    private UUID rawPayloadId;

    public static ValidationResultEntity create(
            Long ruleId,
            UUID ingestionRunId,
            UUID rawPayloadId,
            String entityType,
            String entityKey,
            String status,
            String observedValue,
            String expectedValue,
            String message) {
        ValidationResultEntity entity = new ValidationResultEntity();
        entity.validationRuleId = ruleId;
        entity.ingestionRunId = ingestionRunId;
        entity.rawPayloadId = rawPayloadId;
        entity.entityType = entityType;
        entity.entityKey = entityKey;
        entity.status = status;
        entity.observedValue = observedValue;
        entity.expectedValue = expectedValue;
        entity.message = message;
        entity.checkedAt = Instant.now();
        return entity;
    }
}
