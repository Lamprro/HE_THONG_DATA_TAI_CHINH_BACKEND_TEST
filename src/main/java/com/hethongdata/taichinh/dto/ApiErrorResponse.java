package com.hethongdata.taichinh.dto;

import java.time.Instant;
import java.util.UUID;

public record ApiErrorResponse(
        Instant timestamp, String category, UUID runId, Integer upstreamStatus, String message) {
}
