package com.hethongdata.taichinh.entity.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.entity.enums.IngestionRunStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ingestion_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestionRunEntity {

    @Id @UuidGenerator private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ingestion_job_id")
    private IngestionJobEntity ingestionJob;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_id", nullable = false)
    private DataSourceEntity dataSource;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionRunStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_query_params", nullable = false, columnDefinition = "jsonb")
    private JsonNode requestQueryParams;

    @Column(name = "fetched_count", nullable = false)
    private int fetchedCount;

    @Column(name = "inserted_count", nullable = false)
    private int insertedCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Column(name = "error_message")
    private String errorMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode metadata;

    @Column(name = "request_method")
    private String requestMethod;

    @Column(name = "request_url")
    private String requestUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_headers", nullable = false, columnDefinition = "jsonb")
    private JsonNode requestHeaders;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_body", columnDefinition = "jsonb")
    private JsonNode requestBody;

    @Column(name = "response_http_status")
    private Integer responseHttpStatus;

    @Column(name = "response_content_type")
    private String responseContentType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_headers", nullable = false, columnDefinition = "jsonb")
    private JsonNode responseHeaders;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_snapshot", columnDefinition = "jsonb")
    private JsonNode responseSnapshot;

    @Column(name = "response_text")
    private String responseText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static IngestionRunEntity start(
            DataSourceEntity dataSource,
            IngestionJobEntity ingestionJob,
            String triggerType,
            JsonNode requestQueryParams,
            URI requestUri,
            JsonNode emptyJson,
            Instant startedAt) {
        IngestionRunEntity entity = new IngestionRunEntity();
        entity.dataSource = dataSource;
        entity.ingestionJob = ingestionJob;
        entity.triggerType = triggerType;
        entity.status = IngestionRunStatus.RUNNING;
        entity.startedAt = startedAt;
        entity.requestQueryParams = requestQueryParams;
        entity.metadata = emptyJson;
        entity.requestHeaders = emptyJson;
        entity.responseHeaders = emptyJson;
        entity.requestMethod = "GET";
        entity.requestUrl = requestUri.toString();
        entity.createdAt = startedAt;
        return entity;
    }

    public void markSuccess(
            int responseStatus,
            String contentType,
            JsonNode headers,
            JsonNode responseJson,
            String responseText,
            JsonNode metadata,
            Instant finishedAt) {
        status = IngestionRunStatus.SUCCESS;
        this.finishedAt = finishedAt;
        fetchedCount = 1;
        insertedCount = 1;
        responseHttpStatus = responseStatus;
        responseContentType = contentType;
        responseHeaders = headers;
        responseSnapshot = responseJson;
        this.responseText = responseText;
        this.metadata = metadata;
    }

    public void markFailed(
            int responseStatus,
            String contentType,
            JsonNode headers,
            JsonNode responseJson,
            String responseText,
            String message,
            JsonNode metadata,
            Instant finishedAt) {
        status = IngestionRunStatus.FAILED;
        this.finishedAt = finishedAt;
        errorCount = 1;
        errorMessage = message;
        responseHttpStatus = responseStatus;
        responseContentType = contentType;
        responseHeaders = headers;
        responseSnapshot = responseJson;
        this.responseText = responseText;
        this.metadata = metadata;
    }
}
