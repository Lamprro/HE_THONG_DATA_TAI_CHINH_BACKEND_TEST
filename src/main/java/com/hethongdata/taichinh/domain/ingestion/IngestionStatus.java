package com.hethongdata.taichinh.domain.ingestion;

public enum IngestionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED
}
