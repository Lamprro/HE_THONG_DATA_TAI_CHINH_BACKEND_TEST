package com.hethongdata.taichinh.dto.ingestion;

import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import java.time.Instant;
import java.util.UUID;

public record RawPayloadResponse(
        UUID id, UUID ingestionRunId, long dataSourceId, String externalKey, String entityType,
        String sourceSymbol, String sourceUrl, String contentType, Object body,
        String checksumSha256, Instant fetchedAt, Instant createdAt) {

    public static RawPayloadResponse from(RawPayloadEntity entity, boolean includeBody) {
        Object body = includeBody ? (entity.getPayload() != null ? entity.getPayload() : entity.getRawText()) : null;
        return new RawPayloadResponse(entity.getId(), entity.getIngestionRun().getId(),
                entity.getDataSource().getId(), entity.getExternalKey(), entity.getEntityType(),
                entity.getSourceSymbol(), entity.getSourceUrl(), entity.getContentType(), body,
                entity.getChecksumSha256(), entity.getFetchedAt(), entity.getCreatedAt());
    }
}
