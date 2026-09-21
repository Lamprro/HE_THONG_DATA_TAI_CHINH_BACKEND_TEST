package com.hethongdata.taichinh.service.financial;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Provider-specific ratio parser. Unknown codes are intentionally ignored by the build service. */
@Component
public class FinancialMetricPayloadParser {
    public List<ProviderMetricDraft> parse(RawPayloadEntity raw) {
        JsonNode root = raw.getPayload();
        if (root == null || !root.path("data").isArray()) throw new IllegalArgumentException("RATIO payload requires data array: " + raw.getId());
        String provider = root.path("provider").asText("").trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "vndirect" -> vndirect(root.path("data"), raw.getSourceSymbol());
            case "vnstock" -> vnstock(root.path("data"), raw.getSourceSymbol());
            default -> throw new IllegalArgumentException("Unsupported RATIO provider: " + provider);
        };
    }
    private List<ProviderMetricDraft> vndirect(JsonNode rows, String fallbackSymbol) {
        List<ProviderMetricDraft> result = new ArrayList<>();
        for (JsonNode row : rows) {
            String code = text(row, "ratioCode"); LocalDate date = date(text(row, "reportDate")); BigDecimal value = decimal(row.path("value"));
            if (code != null && date != null && value != null) result.add(new ProviderMetricDraft(symbol(row, fallbackSymbol), code, date, normalizeVndirect(code, value)));
        }
        return result;
    }
    /* VNStock output differs by provider release; these documented aliases cover its observed rows without treating arbitrary fields as metrics. */
    private List<ProviderMetricDraft> vnstock(JsonNode rows, String fallbackSymbol) {
        List<ProviderMetricDraft> result = new ArrayList<>();
        for (JsonNode row : rows) {
            String code = text(row, "ratio_code", "ratioCode", "code"); LocalDate date = date(text(row, "report_date", "reportDate", "date"));
            BigDecimal value = decimal(first(row, "value", "ratio_value"));
            if (code != null && date != null && value != null) result.add(new ProviderMetricDraft(symbol(row, fallbackSymbol), code, date, value));
        }
        return result;
    }
    private static BigDecimal normalizeVndirect(String code, BigDecimal value) {
        return switch (code) { case "ROAE_TR_AVG5Q", "ROAA_TR_AVG5Q", "DIVIDEND_YIELD" -> value.movePointRight(2); default -> value; };
    }
    private static JsonNode first(JsonNode node, String... names) { for (String name : names) if (node.hasNonNull(name)) return node.get(name); return null; }
    private static String text(JsonNode node, String... names) { JsonNode value = first(node, names); return value == null ? null : value.asText().trim(); }
    private static BigDecimal decimal(JsonNode node) { try { return node == null || node.isNull() ? null : node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText()); } catch (NumberFormatException e) { return null; } }
    private static LocalDate date(String value) { try { return value == null || value.isBlank() ? null : LocalDate.parse(value); } catch (RuntimeException e) { return null; } }
    private static String symbol(JsonNode row, String fallback) { String value = text(row, "code", "symbol"); return value == null || value.isBlank() ? fallback : value; }
    public record ProviderMetricDraft(String symbol, String code, LocalDate asOfDate, BigDecimal value) {}
}
