package com.hethongdata.taichinh.application.port.model;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExternalFetchResponse(
        ExternalOperation operation,
        String provider,
        URI sourceUri,
        int httpStatus,
        String contentType,
        Map<String, List<String>> responseHeaders,
        String rawBody,
        Instant fetchedAt) {

    public ExternalFetchResponse {
        if (operation == null || sourceUri == null || fetchedAt == null) {
            throw new IllegalArgumentException("operation, sourceUri and fetchedAt are required");
        }
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("invalid HTTP status: " + httpStatus);
        }
        provider = provider == null ? "unknown" : provider;
        contentType = contentType == null ? "application/octet-stream" : contentType;
        responseHeaders = responseHeaders == null ? Map.of() : Map.copyOf(responseHeaders);
        rawBody = rawBody == null ? "" : rawBody;
    }

    public boolean isSuccessful() {
        return httpStatus >= 200 && httpStatus < 300;
    }
}
