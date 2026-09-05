package com.hethongdata.taichinh.entity.ingestion;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "raw_payloads")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawPayloadEntity {

    @Id @UuidGenerator private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ingestion_run_id", nullable = false)
    private IngestionRunEntity ingestionRun;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_id", nullable = false)
    private DataSourceEntity dataSource;

    @Column(name = "external_key")
    private String externalKey;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "source_symbol")
    private String sourceSymbol;

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "content_type")
    private String contentType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "raw_text")
    private String rawText;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum_sha256", columnDefinition = "char(64)")
    private String checksumSha256;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static RawPayloadEntity create(
            IngestionRunEntity ingestionRun,
            DataSourceEntity dataSource,
            String externalKey,
            String entityType,
            String sourceSymbol,
            String sourceUrl,
            String contentType,
            JsonNode payload,
            String rawText,
            String checksumSha256,
            Instant fetchedAt,
            UUID securityId) {
        RawPayloadEntity entity = new RawPayloadEntity();
        entity.ingestionRun = ingestionRun;
        entity.dataSource = dataSource;
        entity.externalKey = externalKey;
        entity.entityType = entityType;
        entity.sourceSymbol = sourceSymbol;
        entity.sourceUrl = sourceUrl;
        entity.contentType = contentType;
        entity.payload = payload;
        entity.rawText = rawText;
        entity.checksumSha256 = checksumSha256;
        entity.fetchedAt = fetchedAt;
        entity.securityId = securityId;
        entity.createdAt = Instant.now();
        return entity;
    }
}
