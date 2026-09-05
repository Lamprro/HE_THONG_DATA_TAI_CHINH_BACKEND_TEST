package com.hethongdata.taichinh.entity.validation;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    public static DataVersionEntity accepted(String dataDomain, String versionCode, UUID ingestionRunId, String checksum) {
        DataVersionEntity entity = new DataVersionEntity();
        entity.dataDomain = dataDomain; entity.versionCode = versionCode; entity.status = "ACTIVE";
        entity.ingestionRunId = ingestionRunId; entity.rowCount = 1L; entity.checksumSha256 = checksum;
        entity.effectiveFrom = Instant.now(); entity.createdAt = entity.effectiveFrom; entity.activatedAt = entity.effectiveFrom;
        entity.notes = "Created after Phase 2 validation passed";
        return entity;
    }

}
