package com.hethongdata.taichinh.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.validation.ValidationExecutionResponse;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.entity.validation.QuarantinedRecordEntity;
import com.hethongdata.taichinh.entity.validation.ValidationResultEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.QuarantinedRecordJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationResultJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.ValidationRuleJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationJobService {
    private final RawPayloadJpaRepository rawPayloads;
    private final ValidationRuleJpaRepository rules;
    private final ValidationResultJpaRepository results;
    private final DataVersionJpaRepository versions;
    private final QuarantinedRecordJpaRepository quarantines;

    public ValidationJobService(RawPayloadJpaRepository rawPayloads, ValidationRuleJpaRepository rules,
            ValidationResultJpaRepository results, DataVersionJpaRepository versions, QuarantinedRecordJpaRepository quarantines) {
        this.rawPayloads = rawPayloads; this.rules = rules; this.results = results; this.versions = versions; this.quarantines = quarantines;
    }

    @Transactional
    public ValidationExecutionResponse validate(UUID rawPayloadId) {
        RawPayloadEntity raw = rawPayloads.findById(rawPayloadId).orElseThrow(() -> new IllegalArgumentException("Raw payload was not found"));
        if (results.existsByRawPayloadId(rawPayloadId)) return existing(raw);
        int passed = 0, failed = 0, skipped = 0; UUID quarantineId = null; boolean blockingFailure = false; boolean duplicate = false;
        for (ValidationRuleEntity rule : rules.findByIsActiveTrueOrderByIdAsc()) {
            if (!applies(rule, raw)) continue;
            Outcome outcome = evaluate(rule, raw);
            results.save(ValidationResultEntity.create(rule.getId(), raw.getIngestionRun().getId(), raw.getId(), raw.getEntityType(),
                    raw.getExternalKey(), outcome.status, outcome.observed, outcome.expected, outcome.message));
            if ("PASS".equals(outcome.status)) passed++; else if ("SKIP".equals(outcome.status)) skipped++; else {
                failed++; boolean critical = "CRITICAL".equals(rule.getSeverity());
                blockingFailure |= critical || "ERROR".equals(rule.getSeverity());
                duplicate |= "NEWS_DUPLICATE_HASH".equals(rule.getCode());
                if (critical && !quarantines.existsByRawPayloadIdAndReasonCode(raw.getId(), rule.getCode())) {
                    quarantineId = quarantines.save(QuarantinedRecordEntity.open(raw.getIngestionRun().getId(), raw.getId(), raw.getEntityType(),
                            raw.getExternalKey(), rule.getCode(), outcome.message, rule.getSeverity(), raw.getPayload())).getId();
                }
            }
        }
        UUID versionId = null;
        if (!blockingFailure && !duplicate) versionId = versions.findByIngestionRunId(raw.getIngestionRun().getId())
                .orElseGet(() -> versions.save(DataVersionEntity.accepted(domain(raw), "RAW-" + raw.getId(), raw.getIngestionRun().getId(), raw.getChecksumSha256()))).getId();
        return new ValidationExecutionResponse(raw.getId(), raw.getIngestionRun().getId(), duplicate ? "DUPLICATE" : (blockingFailure ? "REJECTED" : "ACCEPTED"),
                passed, failed, skipped, versionId, quarantineId, false);
    }

    @Transactional
    public List<ValidationExecutionResponse> validatePending(int limit) {
        return rawPayloads.findUnvalidated(PageRequest.of(0, Math.max(1, Math.min(limit, 100)))).stream().map(raw -> validate(raw.getId())).toList();
    }

    private ValidationExecutionResponse existing(RawPayloadEntity raw) {
        var existing = results.findByRawPayloadIdOrderByCheckedAtAsc(raw.getId()); int pass = 0, fail = 0, skip = 0;
        for (var result : existing) { if ("PASS".equals(result.getStatus())) pass++; else if ("FAIL".equals(result.getStatus())) fail++; else skip++; }
        var version = versions.findByIngestionRunId(raw.getIngestionRun().getId()).map(DataVersionEntity::getId).orElse(null);
        return new ValidationExecutionResponse(raw.getId(), raw.getIngestionRun().getId(), version == null ? "REJECTED" : "ACCEPTED", pass, fail, skip, version, null, true);
    }

    private boolean applies(ValidationRuleEntity rule, RawPayloadEntity raw) {
        return switch (rule.getExecutorKey()) {
            case "RAW_ERROR_MESSAGE" -> true;
            case "RAW_ENVELOPE_REQUIRED", "DATA_COUNT_MATCH" -> hasDataEnvelope(raw.getPayload());
            default -> rule.getDataDomain().equals(domain(raw));
        };
    }
    private String domain(RawPayloadEntity raw) { return switch (raw.getEntityType().toUpperCase(Locale.ROOT)) { case "QUOTE", "OHLCV", "RATIO" -> "MARKET_PRICE"; case "FINANCIAL_STATEMENT" -> "FINANCIAL_STATEMENT"; case "NEWS", "NEWS_COMPANY" -> "NEWS"; default -> "RAW"; }; }

    private Outcome evaluate(ValidationRuleEntity rule, RawPayloadEntity raw) {
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
            default -> new Outcome("SKIP", null, null, "No executor registered for " + rule.getExecutorKey());
        };
    }

    private Outcome nonNegative(JsonNode payload) { for (JsonNode object : objects(payload)) for (String key : List.of("open", "high", "low", "close", "price", "open_price", "high_price", "low_price", "close_price", "GiaMoCua", "GiaCaoNhat", "GiaThapNhat", "GiaDongCua")) { BigDecimal number = decimal(object.get(key)); if (number != null && number.signum() < 0) return fail(key + "=" + number, ">= 0", "Negative price is invalid"); } return new Outcome("PASS", null, null, "No negative price found"); }
    private Outcome ohlc(JsonNode payload) { boolean candidate = false; for (JsonNode object : objects(payload)) { BigDecimal low = firstDecimal(object, "low", "low_price", "GiaThapNhat"); BigDecimal high = firstDecimal(object, "high", "high_price", "GiaCaoNhat"); if (low == null || high == null) continue; candidate = true; if (low.compareTo(high) > 0) return fail("low=" + low + ", high=" + high, "low <= high", "OHLC bounds are invalid"); for (BigDecimal value : new BigDecimal[] { firstDecimal(object, "open", "open_price", "GiaMoCua"), firstDecimal(object, "close", "close_price", "GiaDongCua") }) if (value != null && (value.compareTo(low) < 0 || value.compareTo(high) > 0)) return fail("price=" + value, "between low and high", "OHLC price is outside its range"); } return candidate ? new Outcome("PASS", null, null, "OHLC bounds are valid") : new Outcome("SKIP", null, null, "No OHLC object found"); }
    private Outcome nonNegativeVolume(JsonNode payload) { for (JsonNode object : objects(payload)) for (String key : List.of("volume", "volume_accumulated", "KhoiLuongKhopLenh", "KLThoaThuan")) { BigDecimal number = decimal(object.get(key)); if (number != null && number.signum() < 0) return fail(key + "=" + number, ">= 0", "Negative volume is invalid"); } return new Outcome("PASS", null, null, "No negative volume found"); }
    private Outcome required(JsonNode payload) { return payload == null || payload.isNull() || payload.isMissingNode() || payload.isEmpty() ? fail("empty payload", "non-empty financial statement", "Financial statement payload is empty") : new Outcome("PASS", null, null, "Financial statement payload is present"); }
    private Outcome title(JsonNode payload) {
        List<JsonNode> items = newsItems(payload);
        if (items.isEmpty()) return fail("no news item", "at least one news item", "News payload has no item to validate");
        for (JsonNode item : items) {
            if (text(item, "title", "headline").isBlank()) return fail("missing title", "title or headline", "A news item has no title");
        }
        return new Outcome("PASS", null, null, "Every news item has a title");
    }
    private Outcome url(JsonNode payload) {
        List<JsonNode> items = newsItems(payload);
        if (items.isEmpty()) return fail("no news item", "at least one linked news item", "News payload has no item to validate");
        for (JsonNode item : items) {
            String value = text(item, "url", "link", "href");
            if (!isHttpUrl(value)) return fail(value.isBlank() ? "missing link" : value, "valid http(s) URL", "A news item has no valid source link");
        }
        return new Outcome("PASS", null, null, "Every news item has a valid source link");
    }
    private Outcome itemCode(JsonNode payload) { JsonNode data = payload == null ? null : payload.path("data"); if (!data.isArray()) return fail("data is not an array", "array of statement items", "Financial statement data is malformed"); for (JsonNode item : data) if (item.path("itemCode").asText().isBlank()) return fail("missing itemCode", "non-blank itemCode", "Financial statement item has no code"); return new Outcome("PASS", null, null, "All financial statement items have itemCode"); }
    private Outcome envelope(JsonNode payload) { if (!hasDataEnvelope(payload) || payload.path("provider").asText().isBlank() || payload.path("dataset").asText().isBlank() || payload.path("retrieved_at").asText().isBlank()) return fail("incomplete envelope", "provider, dataset, retrieved_at and data", "Data-bearing raw response envelope is incomplete"); return new Outcome("PASS", null, null, "Data-bearing raw response envelope is complete"); }
    private Outcome dataCount(JsonNode payload) { if (payload == null || !payload.path("data").isArray() || !payload.has("count")) return new Outcome("SKIP", null, null, "Payload does not expose count/data array"); return payload.path("count").asInt(-1) == payload.path("data").size() ? new Outcome("PASS", null, null, "Count matches data array") : fail("count=" + payload.path("count").asText(), "data.size=" + payload.path("data").size(), "Envelope count does not match data array"); }
    private Outcome duplicateNews(RawPayloadEntity raw) { return rawPayloads.existsByDataSourceIdAndChecksumSha256AndIdNot(raw.getDataSource().getId(), raw.getChecksumSha256(), raw.getId())
            ? fail(raw.getChecksumSha256(), "unique checksum per source", "Duplicate news raw payload; no new data version will be created")
            : new Outcome("PASS", null, null, "News checksum is unique for this source"); }
    private Outcome errorMessage(JsonNode payload, String rawText) {
        if (payload != null && payload.isObject()) for (String field : List.of("error", "errors", "failed")) if (payload.hasNonNull(field)) return fail(field, "successful data payload", "Payload contains an upstream error marker");
        String lower = payload == null && rawText != null ? rawText.toLowerCase(Locale.ROOT) : "";
        return lower.contains("\"error\"") || lower.contains("\"errors\"") || lower.contains("\"failed\"") ? fail("provider error marker", "successful data payload", "Payload contains an upstream error message") : new Outcome("PASS", null, null, "No upstream error marker");
    }
    private boolean hasDataEnvelope(JsonNode payload) { return payload != null && payload.isObject() && payload.hasNonNull("data"); }
    private List<JsonNode> newsItems(JsonNode payload) {
        if (payload == null || !payload.isObject()) return List.of();
        if (payload.path("data").isArray()) { List<JsonNode> items = new ArrayList<>(); payload.path("data").elements().forEachRemaining(item -> { if (item.isObject()) items.add(item); }); return items; }
        return List.of(payload);
    }
    private String text(JsonNode item, String... keys) { for (String key : keys) if (item.path(key).isTextual() && !item.path(key).asText().isBlank()) return item.path(key).asText().trim(); return ""; }
    private boolean isHttpUrl(String value) { try { URI uri = URI.create(value); return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()); } catch (IllegalArgumentException exception) { return false; } }
    private List<JsonNode> objects(JsonNode node) { List<JsonNode> values = new ArrayList<>(); collect(node, values); return values; }
    private void collect(JsonNode node, List<JsonNode> values) { if (node == null) return; if (node.isObject()) { values.add(node); node.elements().forEachRemaining(child -> collect(child, values)); } else if (node.isArray()) node.elements().forEachRemaining(child -> collect(child, values)); }
    private BigDecimal decimal(JsonNode node) { try { return node != null && !node.isNull() && node.isValueNode() ? new BigDecimal(node.asText()) : null; } catch (NumberFormatException ignored) { return null; } }
    private BigDecimal firstDecimal(JsonNode node, String... keys) { for (String key : keys) { BigDecimal value = decimal(node.get(key)); if (value != null) return value; } return null; }
    private Outcome fail(String observed, String expected, String message) { return new Outcome("FAIL", observed, expected, message); }
    private record Outcome(String status, String observed, String expected, String message) { }
}
