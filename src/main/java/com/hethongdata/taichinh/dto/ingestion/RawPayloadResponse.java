package com.hethongdata.taichinh.dto.ingestion;

import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;

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
public final class RawPayloadResponse {

    private final UUID id;

    private final UUID ingestionRunId;

    private final long dataSourceId;

    private final String externalKey;

    private final String entityType;

    private final String sourceSymbol;

    private final String sourceUrl;

    private final String contentType;

    private final Object body;

    private final String checksumSha256;

    private final Instant fetchedAt;

    private final Instant createdAt;

    public static RawPayloadResponse from(RawPayloadEntity entity, boolean includeBody) {
        Object body =
                includeBody
                        ? (entity.getPayload() != null ? entity.getPayload() : entity.getRawText())
                        : null;
        return new RawPayloadResponse(
                entity.getId(),
                entity.getIngestionRun().getId(),
                entity.getDataSource().getId(),
                entity.getExternalKey(),
                entity.getEntityType(),
                entity.getSourceSymbol(),
                entity.getSourceUrl(),
                entity.getContentType(),
                body,
                entity.getChecksumSha256(),
                entity.getFetchedAt(),
                entity.getCreatedAt());
    }
}
