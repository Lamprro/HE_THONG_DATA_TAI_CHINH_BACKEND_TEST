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
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "dataset_samples")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DatasetSampleEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "as_of_date")
    private LocalDate asOfDate;

    @Column(name = "split_name")
    private String splitName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_values")
    private JsonNode featureValues;

    @Column(name = "target_label")
    private String targetLabel;

    @Column(name = "target_value")
    private BigDecimal targetValue;

    @Column(name = "created_at")
    private Instant createdAt;
}
