package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "news_ai_analyses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsAiAnalysisEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "news_article_id")
    private UUID newsArticleId;

    @Column(name = "llm_run_id")
    private UUID llmRunId;

    @Column(name = "analysis_version")
    private String analysisVersion;

    @Column(name = "summary")
    private String summary;

    @Column(name = "sentiment_label")
    private String sentimentLabel;

    @Column(name = "sentiment_score")
    private BigDecimal sentimentScore;

    @Column(name = "impact_direction")
    private String impactDirection;

    @Column(name = "impact_horizon")
    private String impactHorizon;

    @Column(name = "confidence_score")
    private BigDecimal confidenceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "topics")
    private JsonNode topics;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entities")
    private JsonNode entities;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_output")
    private JsonNode structuredOutput;

    @Column(name = "quality_status")
    private String qualityStatus;

    @Column(name = "created_at")
    private Instant createdAt;

}
