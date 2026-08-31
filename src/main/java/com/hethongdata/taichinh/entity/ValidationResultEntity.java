package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

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

}
