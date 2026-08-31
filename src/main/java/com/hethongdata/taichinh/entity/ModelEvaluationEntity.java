package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "model_evaluations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelEvaluationEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "model_version_id")
    private UUID modelVersionId;

    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(name = "split_name")
    private String splitName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics")
    private JsonNode metrics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confusion_matrix")
    private JsonNode confusionMatrix;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "baseline_metrics")
    private JsonNode baselineMetrics;

    @Column(name = "acceptance_status")
    private String acceptanceStatus;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    @Column(name = "notes")
    private String notes;

}
