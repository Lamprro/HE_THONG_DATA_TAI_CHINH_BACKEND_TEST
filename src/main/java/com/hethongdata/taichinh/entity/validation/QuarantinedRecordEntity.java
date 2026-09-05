package com.hethongdata.taichinh.entity.validation;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "quarantined_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QuarantinedRecordEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "ingestion_run_id")
    private UUID ingestionRunId;

    @Column(name = "raw_payload_id")
    private UUID rawPayloadId;

    @Column(name = "data_version_id")
    private UUID dataVersionId;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_key")
    private String entityKey;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "reason_detail")
    private String reasonDetail;

    @Column(name = "severity")
    private String severity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_snapshot")
    private JsonNode payloadSnapshot;

    @Column(name = "status")
    private String status;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "created_at")
    private Instant createdAt;

    public static QuarantinedRecordEntity open(
            UUID ingestionRunId,
            UUID rawPayloadId,
            String entityType,
            String entityKey,
            String reasonCode,
            String reasonDetail,
            String severity,
            JsonNode payloadSnapshot) {
        QuarantinedRecordEntity entity = new QuarantinedRecordEntity();
        entity.ingestionRunId = ingestionRunId;
        entity.rawPayloadId = rawPayloadId;
        entity.entityType = entityType;
        entity.entityKey = entityKey;
        entity.reasonCode = reasonCode;
        entity.reasonDetail = reasonDetail;
        entity.severity = severity;
        entity.payloadSnapshot = payloadSnapshot;
        entity.status = "OPEN";
        entity.createdAt = Instant.now();
        return entity;
    }
}
