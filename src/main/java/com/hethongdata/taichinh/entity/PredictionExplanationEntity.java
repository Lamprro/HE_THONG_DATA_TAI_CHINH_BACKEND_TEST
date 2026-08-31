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
@Table(name = "prediction_explanations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PredictionExplanationEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "prediction_id")
    private UUID predictionId;

    @Column(name = "explanation_type")
    private String explanationType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "top_factors")
    private JsonNode topFactors;

    @Column(name = "narrative")
    private String narrative;

    @Column(name = "created_at")
    private Instant createdAt;

}
