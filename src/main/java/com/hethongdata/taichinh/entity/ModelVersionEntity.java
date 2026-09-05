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
import java.util.UUID;

@Entity
@Table(name = "model_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelVersionEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "model_code")
    private String modelCode;

    @Column(name = "version")
    private String version;

    @Column(name = "model_type")
    private String modelType;

    @Column(name = "framework")
    private String framework;

    @Column(name = "algorithm")
    private String algorithm;

    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(name = "feature_set_id")
    private UUID featureSetId;

    @Column(name = "target_name")
    private String targetName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_definition")
    private JsonNode targetDefinition;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hyperparameters")
    private JsonNode hyperparameters;

    @Column(name = "artifact_uri")
    private String artifactUri;

    @Column(name = "artifact_hash")
    private String artifactHash;

    @Column(name = "status")
    private String status;

    @Column(name = "trained_at")
    private Instant trainedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "created_at")
    private Instant createdAt;
}
