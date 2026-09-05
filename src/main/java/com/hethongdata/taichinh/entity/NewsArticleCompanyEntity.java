package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

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
@IdClass(NewsArticleCompanyEntityId.class)
public class NewsArticleCompanyEntity {

    @Id
    @Column(name = "news_article_id")
    private UUID newsArticleId;

    @Id
    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "relevance_score")
    private BigDecimal relevanceScore;

    @Column(name = "match_method")
    private String matchMethod;

    @Column(name = "created_at")
    private Instant createdAt;
}
