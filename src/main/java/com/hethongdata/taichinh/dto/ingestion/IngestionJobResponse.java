package com.hethongdata.taichinh.dto.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import java.time.Instant;
import java.util.UUID;

public record IngestionJobResponse(
        UUID id, long dataSourceId, String dataSourceCode, String code, String name,
        String datasetType, String cronExpression, JsonNode parameters, short maxRetries,
        int timeoutSeconds, boolean active, Instant createdAt) {

    public static IngestionJobResponse from(IngestionJobEntity entity) {
        return new IngestionJobResponse(entity.getId(), entity.getDataSource().getId(),
                entity.getDataSource().getCode(), entity.getCode(), entity.getName(), entity.getDatasetType(),
                entity.getCronExpression(), entity.getParameters(), entity.getMaxRetries(),
                entity.getTimeoutSeconds(), entity.isActive(), entity.getCreatedAt());
    }
}
