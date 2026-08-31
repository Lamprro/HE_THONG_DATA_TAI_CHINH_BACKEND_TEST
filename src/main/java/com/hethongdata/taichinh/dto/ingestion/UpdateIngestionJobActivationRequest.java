package com.hethongdata.taichinh.dto.ingestion;

import jakarta.validation.constraints.NotNull;

/** Separate activation command so scheduling state is never changed implicitly. */
public record UpdateIngestionJobActivationRequest(@NotNull Boolean active) {
}
