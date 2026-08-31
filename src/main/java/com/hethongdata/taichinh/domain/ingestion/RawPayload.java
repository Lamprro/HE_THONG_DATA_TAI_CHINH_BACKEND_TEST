package com.hethongdata.taichinh.domain.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record RawPayload(
        UUID id,
        UUID ingestionRunId,
        long dataSourceId,
        String externalKey,
        String entityType,
        String sourceSymbol,
        String sourceUrl,
        String contentType,
        JsonNode payload,
        String rawText,
        String checksumSha256,
        Instant fetchedAt,
        Instant createdAt) {

    public boolean jsonPayload() {
        return payload != null;
    }
}
