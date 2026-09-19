package com.hethongdata.taichinh.application.port.model;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable provider-neutral fetch parameters. Construction normalizes identifiers and validates
 * required fields and date ranges; it does not validate financial payload quality.
 */
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
            throw new IllegalArgumentException("Thiếu tham số operation.");
        }
        provider = normalizeProvider(provider);
        symbol = normalizeSymbol(symbol);
        interval = normalizeOptional(interval);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);

        if (requiresSymbol(operation) && symbol == null) {
            throw new IllegalArgumentException("Thiếu mã chứng khoán symbol cho thao tác " + operation + ".");
        }
        if (requiresProvider(operation) && provider == null) {
            throw new IllegalArgumentException("Thiếu nhà cung cấp provider cho thao tác " + operation + ".");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Ngày bắt đầu không được sau ngày kết thúc.");
        }
        if (startDate != null && endDate != null && startDate.plusYears(10).isBefore(endDate)) {
            throw new IllegalArgumentException("Khoảng thời gian truy vấn không được vượt quá 10 năm.");
        }
    }

    private static boolean requiresSymbol(ExternalOperation operation) {
        return switch (operation) {
            case QUOTE,
                            OHLCV,
                            INDEX_OHLCV,
                            INDEX_LATEST,
                            INDEX_MEMBERS,
                            COMPANY,
                            FINANCIAL_STATEMENT,
                            RATIO,
                            MANAGEMENT,
                            SUBSIDIARIES,
                            NEWS,
                            EVENTS,
                            NEWS_COMPANY ->
                    true;
            default -> false;
        };
    }

    private static boolean requiresProvider(ExternalOperation operation) {
        return switch (operation) {
            case HEALTH,
                            PROVIDERS,
                            NEWS_STATUS,
                            NEWS_SITES,
                            NEWS_LATEST,
                            NEWS_HISTORY,
                            NEWS_COMPANY,
                            PROXY_PROVIDERS,
                            FETCH_URL ->
                    false;
            default -> true;
        };
    }

    private static String normalizeProvider(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizeSymbol(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9._-]{1,20}")) {
            throw new IllegalArgumentException("Mã chứng khoán chứa ký tự không được hỗ trợ.");
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
