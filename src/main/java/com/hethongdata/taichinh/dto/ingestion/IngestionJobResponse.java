package com.hethongdata.taichinh.dto.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;

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
public final class IngestionJobResponse {

    private final UUID id;

    private final long dataSourceId;

    private final String dataSourceCode;

    private final String code;

    private final String name;

    private final String datasetType;

    private final String cronExpression;

    private final JsonNode parameters;

    private final short maxRetries;

    private final int timeoutSeconds;

    private final boolean active;

    private final Instant createdAt;

    public static IngestionJobResponse from(IngestionJobEntity entity) {
        return new IngestionJobResponse(
                entity.getId(),
                entity.getDataSource().getId(),
                entity.getDataSource().getCode(),
                entity.getCode(),
                entity.getName(),
                entity.getDatasetType(),
                entity.getCronExpression(),
                entity.getParameters(),
                entity.getMaxRetries(),
                entity.getTimeoutSeconds(),
                entity.isActive(),
                entity.getCreatedAt());
    }
}
