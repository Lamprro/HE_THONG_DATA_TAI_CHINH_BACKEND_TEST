package com.hethongdata.taichinh.entity.validation;

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
@Table(name = "data_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataVersionEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "data_domain")
    private String dataDomain;

    @Column(name = "version_code")
    private String versionCode;

    @Column(name = "status")
    private String status;

    @Column(name = "parent_data_version_id")
    private UUID parentDataVersionId;

    @Column(name = "ingestion_run_id")
    private UUID ingestionRunId;

    @Column(name = "effective_from")
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "row_count")
    private Long rowCount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum_sha256", columnDefinition = "char(64)")
    private String checksumSha256;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    /**
     * Creates the single accepted clean-data version for a fully validated ingestion run.
     *
     * <p>The checksum represents the ordered collection of raw payloads in the run, not one raw
     * payload.
     */
    public static DataVersionEntity acceptedForRun(
            String dataDomain, UUID ingestionRunId, long rawPayloadCount, String checksum) {
        DataVersionEntity entity = new DataVersionEntity();
        entity.dataDomain = dataDomain;
        entity.versionCode = "RUN-" + ingestionRunId;
        entity.status = "ACTIVE";
        entity.ingestionRunId = ingestionRunId;
        entity.rowCount = rawPayloadCount;
        entity.checksumSha256 = checksum;
        entity.effectiveFrom = Instant.now();
        entity.createdAt = entity.effectiveFrom;
        entity.activatedAt = entity.effectiveFrom;
        entity.notes = "Created after all raw payloads in the ingestion run passed validation";
        return entity;
    }
}
