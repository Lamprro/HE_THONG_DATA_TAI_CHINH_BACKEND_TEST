package com.hethongdata.taichinh.dto.ingestion;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.UUID;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class IngestionExecutionResponse {

    private final UUID runId;

    private final UUID rawPayloadId;

    private final String status;

    private final int upstreamStatus;

    private final String contentType;

    private final String checksumSha256;

    private final boolean duplicateChecksum;
}
