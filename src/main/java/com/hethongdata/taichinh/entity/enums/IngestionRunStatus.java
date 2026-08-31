package com.hethongdata.taichinh.entity.enums;

/** Values persisted in ingestion_runs.status. */
public enum IngestionRunStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED
}
