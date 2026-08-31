package com.hethongdata.taichinh.dto.ingestion;

import java.util.UUID;

public record IngestionExecutionResponse(
        UUID runId,
        UUID rawPayloadId,
        String status,
        int upstreamStatus,
        String contentType,
        String checksumSha256,
        boolean duplicateChecksum) {
}
