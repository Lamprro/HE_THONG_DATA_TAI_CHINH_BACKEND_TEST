package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "news_article_companies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsArticleCompanyEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "news_article_id", nullable = false)
    private UUID newsArticleId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "relevance_score")
    private BigDecimal relevanceScore;

    @Column(name = "match_method")
    private String matchMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "match_evidence", columnDefinition = "jsonb")
    private JsonNode matchEvidence;

    @Column(name = "created_at")
    private Instant createdAt;

    public static NewsArticleCompanyEntity create(
            UUID newsArticleId, UUID companyId, UUID securityId, BigDecimal relevanceScore,
            String matchMethod, JsonNode matchEvidence) {
        NewsArticleCompanyEntity entity = new NewsArticleCompanyEntity();
        entity.newsArticleId = newsArticleId;
        entity.companyId = companyId;
        entity.securityId = securityId;
        entity.relevanceScore = relevanceScore;
        entity.matchMethod = matchMethod;
        entity.matchEvidence = matchEvidence;
        entity.createdAt = Instant.now();
        return entity;
    }
}
