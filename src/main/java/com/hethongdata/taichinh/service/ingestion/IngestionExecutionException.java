package com.hethongdata.taichinh.service.ingestion;

import com.hethongdata.taichinh.application.port.error.ExternalErrorCategory;

import java.util.UUID;

public class IngestionExecutionException extends RuntimeException {

    private final UUID runId;
    private final ExternalErrorCategory category;
    private final Integer upstreamStatus;

    public IngestionExecutionException(
            UUID runId,
            ExternalErrorCategory category,
            Integer upstreamStatus,
            String message,
            Throwable cause) {
        super(message, cause);
        this.runId = runId;
        this.category = category;
        this.upstreamStatus = upstreamStatus;
    }

    public UUID runId() {
        return runId;
    }

    public ExternalErrorCategory category() {
        return category;
    }

    public Integer upstreamStatus() {
        return upstreamStatus;
    }
}
