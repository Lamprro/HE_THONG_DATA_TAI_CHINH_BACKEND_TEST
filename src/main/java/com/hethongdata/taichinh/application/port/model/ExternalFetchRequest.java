package com.hethongdata.taichinh.application.port.model;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

public record ExternalFetchRequest(
        ExternalOperation operation,
        String provider,
        String symbol,
        LocalDate startDate,
        LocalDate endDate,
        String interval,
        Map<String, String> parameters) {

    public ExternalFetchRequest {
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }
        provider = normalizeProvider(provider);
        symbol = normalizeSymbol(symbol);
        interval = normalizeOptional(interval);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);

        if ((operation == ExternalOperation.QUOTE || operation == ExternalOperation.OHLCV)
                && symbol == null) {
            throw new IllegalArgumentException("symbol is required for " + operation);
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }
        if (startDate != null && endDate != null && startDate.plusYears(10).isBefore(endDate)) {
            throw new IllegalArgumentException("date range must not exceed 10 years");
        }
    }

    private static String normalizeProvider(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? "vnstock" : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9._-]{1,20}")) {
            throw new IllegalArgumentException("symbol contains unsupported characters");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
