package com.hethongdata.taichinh.dto.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import java.time.Instant;
import java.util.UUID;

public record IngestionRunResponse(
        UUID id, UUID ingestionJobId, long dataSourceId, String triggerType, String status,
        Instant startedAt, Instant finishedAt, JsonNode requestQueryParams, int fetchedCount,
        int insertedCount, int updatedCount, int rejectedCount, int errorCount,
        String errorMessage, JsonNode metadata, String requestMethod, String requestUrl,
        Integer responseHttpStatus, String responseContentType, Instant createdAt) {

    public static IngestionRunResponse from(IngestionRunEntity entity) {
        return new IngestionRunResponse(entity.getId(),
                entity.getIngestionJob() == null ? null : entity.getIngestionJob().getId(),
                entity.getDataSource().getId(), entity.getTriggerType(), entity.getStatus().name(),
                entity.getStartedAt(), entity.getFinishedAt(), entity.getRequestQueryParams(),
                entity.getFetchedCount(), entity.getInsertedCount(), entity.getUpdatedCount(),
                entity.getRejectedCount(), entity.getErrorCount(), entity.getErrorMessage(), entity.getMetadata(),
                entity.getRequestMethod(), entity.getRequestUrl(), entity.getResponseHttpStatus(),
                entity.getResponseContentType(), entity.getCreatedAt());
    }
}
