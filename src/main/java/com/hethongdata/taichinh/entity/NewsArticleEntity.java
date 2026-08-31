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
@Table(name = "news_articles")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NewsArticleEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "data_source_id")
    private Long dataSourceId;

    @Column(name = "raw_payload_id")
    private UUID rawPayloadId;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "canonical_url")
    private String canonicalUrl;

    @Column(name = "url_hash")
    private String urlHash;

    @Column(name = "title")
    private String title;

    @Column(name = "sapo")
    private String sapo;

    @Column(name = "content_text")
    private String contentText;

    @Column(name = "author")
    private String author;

    @Column(name = "language")
    private String language;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "crawled_at")
    private Instant crawledAt;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "duplicate_of_news_article_id")
    private UUID duplicateOfNewsArticleId;

    @Column(name = "dedup_status")
    private String dedupStatus;

    @Column(name = "is_deleted_source")
    private Boolean isDeletedSource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private JsonNode metadata;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

}
