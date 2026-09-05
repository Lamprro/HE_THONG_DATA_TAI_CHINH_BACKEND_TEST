package com.hethongdata.taichinh.dto.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class IngestionRunResponse {

    private final UUID id;

    private final UUID ingestionJobId;

    private final long dataSourceId;

    private final String triggerType;

    private final String status;

    private final Instant startedAt;

    private final Instant finishedAt;

    private final JsonNode requestQueryParams;

    private final int fetchedCount;

    private final int insertedCount;

    private final int updatedCount;

    private final int rejectedCount;

    private final int errorCount;

    private final String errorMessage;

    private final JsonNode metadata;

    private final String requestMethod;

    private final String requestUrl;

    private final Integer responseHttpStatus;

    private final String responseContentType;

    private final Instant createdAt;

    public static IngestionRunResponse from(IngestionRunEntity entity) {
        return new IngestionRunResponse(
                entity.getId(),
                entity.getIngestionJob() == null ? null : entity.getIngestionJob().getId(),
                entity.getDataSource().getId(),
                entity.getTriggerType(),
                entity.getStatus().name(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getRequestQueryParams(),
                entity.getFetchedCount(),
                entity.getInsertedCount(),
                entity.getUpdatedCount(),
                entity.getRejectedCount(),
                entity.getErrorCount(),
                entity.getErrorMessage(),
                entity.getMetadata(),
                entity.getRequestMethod(),
                entity.getRequestUrl(),
                entity.getResponseHttpStatus(),
                entity.getResponseContentType(),
                entity.getCreatedAt());
    }
}
