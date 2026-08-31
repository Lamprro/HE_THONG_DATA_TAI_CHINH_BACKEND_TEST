package com.hethongdata.taichinh.domain.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record IngestionRun(
        UUID id,
        long dataSourceId,
        String triggerType,
        IngestionStatus status,
        Instant startedAt,
        Instant finishedAt,
        JsonNode requestQueryParams,
        int fetchedCount,
        int insertedCount,
        int updatedCount,
        int rejectedCount,
        int errorCount,
        String errorMessage,
        JsonNode metadata,
        String requestMethod,
        String requestUrl,
        Integer responseHttpStatus,
        String responseContentType,
        Instant createdAt) {
}
