package com.hethongdata.taichinh.service.ingestion;

import java.util.UUID;

public record IngestionResult(
        UUID runId,
        UUID rawPayloadId,
        String status,
        int upstreamStatus,
        String contentType,
        String checksumSha256,
        boolean duplicateChecksum) {
}
