package com.hethongdata.taichinh.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Executes the code registered by validation rules. */
@Service
public class ValidationRuleExecutionService {
    private final RawPayloadJpaRepository rawPayloads;

    public ValidationRuleExecutionService(RawPayloadJpaRepository rawPayloads) {
        this.rawPayloads = rawPayloads;
    }

    public Outcome execute(ValidationRuleEntity rule, RawPayloadEntity raw) {
        return switch (rule.getExecutorKey()) {
            case "PRICE_NON_NEGATIVE" -> nonNegative(raw.getPayload());
            case "PRICE_OHLC_VALID" -> ohlc(raw.getPayload());
            case "MARKET_VOLUME_NON_NEGATIVE" -> nonNegativeVolume(raw.getPayload());
            case "STATEMENT_REQUIRED_KEYS" -> required(raw.getPayload());
            case "STATEMENT_ITEM_CODE_REQUIRED" -> itemCode(raw.getPayload());
            case "NEWS_TITLE_REQUIRED" -> title(raw.getPayload());
            case "NEWS_URL_REQUIRED" -> url(raw.getPayload());
            case "NEWS_DUPLICATE_HASH" -> duplicateNews(raw);
            case "RAW_ENVELOPE_REQUIRED" -> envelope(raw.getPayload());
            case "DATA_COUNT_MATCH" -> dataCount(raw.getPayload());
            case "RAW_ERROR_MESSAGE" -> errorMessage(raw.getPayload(), raw.getRawText());
            default -> new Outcome("SKIP", null, null,
                    "No executor registered for " + rule.getExecutorKey());
        };
    }

    private Outcome nonNegative(JsonNode payload) {
        for (JsonNode object : objects(payload))
            for (String key : List.of("open", "high", "low", "close", "price",
                    "open_price", "high_price", "low_price", "close_price",
                    "GiaMoCua", "GiaCaoNhat", "GiaThapNhat", "GiaDongCua")) {
                BigDecimal number = decimal(object.get(key));
                if (number != null && number.signum() < 0)
                    return fail(key + "=" + number, ">= 0", "Negative price is invalid");
            }
        return new Outcome("PASS", null, null, "No negative price found");
    }

    private Outcome ohlc(JsonNode payload) {
        boolean candidate = false;
        for (JsonNode object : objects(payload)) {
            BigDecimal low = firstDecimal(object, "low", "low_price", "GiaThapNhat");
            BigDecimal high = firstDecimal(object, "high", "high_price", "GiaCaoNhat");
            if (low == null || high == null) continue;
            candidate = true;
            if (low.compareTo(high) > 0)
                return fail("low=" + low + ", high=" + high, "low <= high", "OHLC bounds are invalid");
            for (BigDecimal value : new BigDecimal[] {
                    firstDecimal(object, "open", "open_price", "GiaMoCua"),
                    firstDecimal(object, "close", "close_price", "GiaDongCua") })
                if (value != null && (value.compareTo(low) < 0 || value.compareTo(high) > 0))
                    return fail("price=" + value, "between low and high", "OHLC price is outside its range");
        }
        return candidate ? new Outcome("PASS", null, null, "OHLC bounds are valid")
                : new Outcome("SKIP", null, null, "No OHLC object found");
    }

    private Outcome nonNegativeVolume(JsonNode payload) {
        for (JsonNode object : objects(payload))
            for (String key : List.of("volume", "volume_accumulated", "KhoiLuongKhopLenh", "KLThoaThuan")) {
                BigDecimal number = decimal(object.get(key));
                if (number != null && number.signum() < 0)
                    return fail(key + "=" + number, ">= 0", "Negative volume is invalid");
            }
        return new Outcome("PASS", null, null, "No negative volume found");
    }

    private Outcome required(JsonNode payload) {
        return payload == null || payload.isNull() || payload.isMissingNode() || payload.isEmpty()
                ? fail("empty payload", "non-empty financial statement", "Financial statement payload is empty")
                : new Outcome("PASS", null, null, "Financial statement payload is present");
    }

    private Outcome title(JsonNode payload) {
        List<JsonNode> items = newsItems(payload);
        if (items.isEmpty()) return fail("no news item", "at least one news item", "News payload has no item to validate");
        for (JsonNode item : items)
            if (text(item, "title", "headline").isBlank())
                return fail("missing title", "title or headline", "A news item has no title");
        return new Outcome("PASS", null, null, "Every news item has a title");
    }

    private Outcome url(JsonNode payload) {
        List<JsonNode> items = newsItems(payload);
        if (items.isEmpty()) return fail("no news item", "at least one linked news item", "News payload has no item to validate");
        for (JsonNode item : items) {
            String value = text(item, "url", "link", "href");
            if (!isHttpUrl(value)) return fail(value.isBlank() ? "missing link" : value,
                    "valid http(s) URL", "A news item has no valid source link");
        }
        return new Outcome("PASS", null, null, "Every news item has a valid source link");
    }

    private Outcome itemCode(JsonNode payload) {
        JsonNode data = payload == null ? null : payload.path("data");
        if (!data.isArray()) return fail("data is not an array", "array of statement items", "Financial statement data is malformed");
        for (JsonNode item : data)
            if (item.path("itemCode").asText().isBlank())
                return fail("missing itemCode", "non-blank itemCode", "Financial statement item has no code");
        return new Outcome("PASS", null, null, "All financial statement items have itemCode");
    }

    private Outcome envelope(JsonNode payload) {
        if (!hasDataEnvelope(payload) || payload.path("provider").asText().isBlank()
                || payload.path("dataset").asText().isBlank() || payload.path("retrieved_at").asText().isBlank())
            return fail("incomplete envelope", "provider, dataset, retrieved_at and data", "Data-bearing raw response envelope is incomplete");
        return new Outcome("PASS", null, null, "Data-bearing raw response envelope is complete");
    }

    private Outcome dataCount(JsonNode payload) {
        if (payload == null || !payload.path("data").isArray() || !payload.has("count"))
            return new Outcome("SKIP", null, null, "Payload does not expose count/data array");
        return payload.path("count").asInt(-1) == payload.path("data").size()
                ? new Outcome("PASS", null, null, "Count matches data array")
                : fail("count=" + payload.path("count").asText(), "data.size=" + payload.path("data").size(), "Envelope count does not match data array");
    }

    private Outcome duplicateNews(RawPayloadEntity raw) {
        return rawPayloads.existsByDataSourceIdAndChecksumSha256AndIdNot(
                        raw.getDataSource().getId(), raw.getChecksumSha256(), raw.getId())
                ? fail(raw.getChecksumSha256(), "unique checksum per source", "Duplicate news raw payload; no new data version will be created")
                : new Outcome("PASS", null, null, "News checksum is unique for this source");
    }

    private Outcome errorMessage(JsonNode payload, String rawText) {
        if (payload != null && payload.isObject())
            for (String field : List.of("error", "errors", "failed"))
                if (payload.hasNonNull(field)) return fail(field, "successful data payload", "Payload contains an upstream error marker");
        String lower = payload == null && rawText != null ? rawText.toLowerCase(Locale.ROOT) : "";
        return lower.contains("\"error\"") || lower.contains("\"errors\"") || lower.contains("\"failed\"")
                ? fail("provider error marker", "successful data payload", "Payload contains an upstream error message")
                : new Outcome("PASS", null, null, "No upstream error marker");
    }

    private boolean hasDataEnvelope(JsonNode payload) {
        return payload != null && payload.isObject() && payload.hasNonNull("data");
    }

    private List<JsonNode> newsItems(JsonNode payload) {
        if (payload == null || !payload.isObject()) return List.of();
        if (payload.path("data").isArray()) {
            List<JsonNode> items = new ArrayList<>();
            payload.path("data").elements().forEachRemaining(item -> { if (item.isObject()) items.add(item); });
            return items;
        }
        return List.of(payload);
    }

    private String text(JsonNode item, String... keys) {
        for (String key : keys)
            if (item.path(key).isTextual() && !item.path(key).asText().isBlank()) return item.path(key).asText().trim();
        return "";
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        } catch (IllegalArgumentException exception) { return false; }
    }

    private List<JsonNode> objects(JsonNode node) {
        List<JsonNode> values = new ArrayList<>();
        collect(node, values);
        return values;
    }

    private void collect(JsonNode node, List<JsonNode> values) {
        if (node == null) return;
        if (node.isObject()) {
            values.add(node);
            node.elements().forEachRemaining(child -> collect(child, values));
        } else if (node.isArray()) node.elements().forEachRemaining(child -> collect(child, values));
    }

    private BigDecimal decimal(JsonNode node) {
        try { return node != null && !node.isNull() && node.isValueNode() ? new BigDecimal(node.asText()) : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private BigDecimal firstDecimal(JsonNode node, String... keys) {
        for (String key : keys) {
            BigDecimal value = decimal(node.get(key));
            if (value != null) return value;
        }
        return null;
    }

    private Outcome fail(String observed, String expected, String message) {
        return new Outcome("FAIL", observed, expected, message);
    }

    public record Outcome(String status, String observed, String expected, String message) {}
}
