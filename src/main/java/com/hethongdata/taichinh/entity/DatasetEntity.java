package com.hethongdata.taichinh.entity;

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
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "datasets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DatasetEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "code")
    private String code;

    @Column(name = "version")
    private String version;

    @Column(name = "feature_set_id")
    private UUID featureSetId;

    @Column(name = "data_version_id")
    private UUID dataVersionId;

    @Column(name = "split_strategy")
    private String splitStrategy;

    @Column(name = "train_start")
    private LocalDate trainStart;

    @Column(name = "train_end")
    private LocalDate trainEnd;

    @Column(name = "validation_start")
    private LocalDate validationStart;

    @Column(name = "validation_end")
    private LocalDate validationEnd;

    @Column(name = "test_start")
    private LocalDate testStart;

    @Column(name = "test_end")
    private LocalDate testEnd;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "target_name")
    private String targetName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_definition")
    private JsonNode targetDefinition;

    @Column(name = "dataset_uri")
    private String datasetUri;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum_sha256", columnDefinition = "char(64)")
    private String checksumSha256;

    @Column(name = "status")
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;
}
