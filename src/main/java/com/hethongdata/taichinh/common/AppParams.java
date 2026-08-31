package com.hethongdata.taichinh.common;

import java.util.Locale;
import java.util.Set;

/**
 * Central place for application-wide constants and small normalization helpers.
 * Provider-specific endpoint rules remain inside the corresponding HTTP adapter.
 */
public final class AppParams {
    public static final int DEFAULT_PAGE_LIMIT = 20;
    public static final int MAX_PAGE_LIMIT = 100;
    public static final int DEFAULT_INGESTION_TIMEOUT_SECONDS = 120;
    public static final short DEFAULT_MAX_RETRIES = 0;

    /** Query keys owned by this API, never forwarded to an upstream provider. */
    public static final Set<String> EXTERNAL_FRAMEWORK_QUERY_PARAMS = Set.of(
            "operation", "provider", "symbol", "start", "end", "interval");

    public static final Set<String> DATA_SOURCE_TYPES = Set.of(
            "API", "LIBRARY", "WEB", "RSS", "FILE", "MANUAL", "OTHER");
    public static final Set<String> LICENSE_STATUSES = Set.of(
            "UNKNOWN", "FREE", "LICENSED", "RESTRICTED", "INTERNAL");
    public static final Set<String> INGESTION_DATASET_TYPES = Set.of(
            "COMPANY", "SECURITY", "FINANCIAL_STATEMENT", "FINANCIAL_METRIC",
            "MARKET_PRICE", "MARKET_INDEX", "NEWS", "MACRO", "OTHER");

    private AppParams() {
    }

    public static String requiredTrimmed(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    public static String requiredUpper(String value, String fieldName) {
        return requiredTrimmed(value, fieldName).toUpperCase(Locale.ROOT);
    }

    public static int pageLimit(int requestedLimit) {
        return Math.clamp(requestedLimit, 1, MAX_PAGE_LIMIT);
    }
}
