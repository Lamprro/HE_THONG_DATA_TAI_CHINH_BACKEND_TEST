package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "predictions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "model_version_id")
    private UUID modelVersionId;

    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "benchmark_market_index_id")
    private UUID benchmarkMarketIndexId;

    @Column(name = "as_of_date")
    private LocalDate asOfDate;

    @Column(name = "horizon_sessions")
    private Short horizonSessions;

    @Column(name = "target_name")
    private String targetName;

    @Column(name = "predicted_label")
    private String predictedLabel;

    @Column(name = "probability")
    private BigDecimal probability;

    @Column(name = "confidence_score")
    private BigDecimal confidenceScore;

    @Column(name = "decision_threshold")
    private BigDecimal decisionThreshold;

    @Column(name = "data_version_id")
    private UUID dataVersionId;

    @Column(name = "feature_set_id")
    private UUID featureSetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot")
    private JsonNode inputSnapshot;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "created_at")
    private Instant createdAt;

}
