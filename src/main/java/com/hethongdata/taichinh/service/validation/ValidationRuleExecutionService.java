package com.hethongdata.taichinh.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.MarketIndexJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.entity.MarketIndexEntity;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Arrays;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.io.StringReader;
import java.io.IOException;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.util.regex.Pattern;

/** Executes the code registered by validation rules. */
@Service
public class ValidationRuleExecutionService {
    private final RawPayloadJpaRepository rawPayloads;
    private final MarketIndexJpaRepository marketIndices;
    private final SecurityJpaRepository securities;

    public ValidationRuleExecutionService(
            RawPayloadJpaRepository rawPayloads,
            MarketIndexJpaRepository marketIndices,
            SecurityJpaRepository securities) {
        this.rawPayloads = rawPayloads;
        this.marketIndices = marketIndices;
        this.securities = securities;
    }

    public Outcome execute(ValidationRuleEntity rule, RawPayloadEntity raw) {
        return switch (rule.getExecutorKey()) {
            case "PRICE_NON_NEGATIVE" -> nonNegative(raw.getPayload());
            case "PRICE_OHLC_VALID" -> ohlc(raw.getPayload());
            case "MARKET_VOLUME_NON_NEGATIVE" -> nonNegativeVolume(raw.getPayload());
            case "STATEMENT_REQUIRED_KEYS" -> required(raw.getPayload());
            case "STATEMENT_ITEM_CODE_REQUIRED" -> itemCode(raw.getPayload());
            case "NEWS_TITLE_REQUIRED" -> title(raw.getPayload(), rule.getRuleConfig());
            case "NEWS_URL_REQUIRED" -> url(raw.getPayload(), rule.getRuleConfig());
            case "NEWS_PAYLOAD_STRUCTURE" -> newsStructure(raw.getPayload(), rule.getRuleConfig());
            case "NEWS_PUBLISHED_AT_VALID" -> newsDate(raw.getPayload(), rule.getRuleConfig());
            case "NEWS_SYMBOL_MATCH" -> newsSymbol(raw, rule.getRuleConfig());
            case "NEWS_URL_DUPLICATE_IN_BATCH" -> newsDuplicateUrl(raw.getPayload(), rule.getRuleConfig());
            case "NEWS_DATA_METADATA_REQUIRED" -> newsMetadata(raw.getPayload());
            case "NEWS_DATA_URL_VALID" -> newsDataUrls(raw.getPayload(), rule.getRuleConfig());
            case "NEWS_DATA_HTTP_SUCCESS" -> newsHttpStatus(raw.getPayload(), rule.getRuleConfig());
            case "NEWS_DATA_CONTENT_TYPE_VALID" -> newsContentType(raw, rule.getRuleConfig());
            case "NEWS_DATA_RAW_TEXT_REQUIRED" -> newsRawText(raw.getRawText());
            case "NEWS_DATA_HTML_STRUCTURE" -> newsHtml(raw.getRawText());
            case "NEWS_DATA_BLOCK_PAGE_DETECTED" -> newsBlockPage(raw.getRawText(), rule.getRuleConfig());
            case "NEWS_DUPLICATE_HASH" -> duplicateNews(raw);
            case "RAW_ENVELOPE_REQUIRED" -> envelope(raw.getPayload());
            case "DATA_COUNT_MATCH" -> dataCount(raw.getPayload());
            case "RAW_ERROR_MESSAGE" -> errorMessage(raw.getPayload(), raw.getRawText());
            case "INDEX_OHLCV_PAYLOAD_VALID" -> indexOhlcv(raw);
            case "INDEX_MEMBERS_PAYLOAD_VALID" -> indexMembers(raw);
            default -> new Outcome("SKIP", null, null,
                    "No executor registered for " + rule.getExecutorKey());
        };
    }

    private Outcome indexOhlcv(RawPayloadEntity raw) {
        JsonNode payload = raw.getPayload();
        if (payload == null || !payload.isObject() || !payload.path("data").isArray()
                || payload.path("data").isEmpty()) {
            return fail("data", "non-empty array", "Index OHLCV payload is empty");
        }
        String symbol = payload.path("symbol").asText(raw.getSourceSymbol()).toUpperCase(Locale.ROOT);
        if (marketIndices != null && marketIndices.findByCodeIgnoreCase(symbol).isEmpty()) {
            return fail("symbol", symbol, "Market index was not found");
        }
        if (!"1D".equalsIgnoreCase(payload.path("interval").asText("1D"))) {
            return fail("interval", "1D", "Only daily index bars are supported");
        }
        java.util.Set<String> timestamps = new java.util.HashSet<>();
        for (JsonNode row : payload.path("data")) {
            String timestamp = row.has("time") ? row.path("time").asText() : row.path("date").asText();
            if (timestamp.isBlank() || !timestamps.add(timestamp)) {
                return fail("time", timestamp, "Duplicate or missing index timestamp");
            }
            BigDecimal open = decimal(row.get("open"));
            BigDecimal high = decimal(row.get("high"));
            BigDecimal low = decimal(row.get("low"));
            BigDecimal close = decimal(row.get("close"));
            if (open == null || high == null || low == null || close == null) {
                return fail("ohlc", "open/high/low/close", "Index OHLC fields are required");
            }
            if (low.compareTo(high) > 0 || open.compareTo(low) < 0 || open.compareTo(high) > 0
                    || close.compareTo(low) < 0 || close.compareTo(high) > 0) {
                return fail("ohlc", "valid bounds", "Index OHLC bounds are invalid");
            }
        }
        return new Outcome("PASS", null, null, "Index OHLCV payload is valid");
    }

    private Outcome indexMembers(RawPayloadEntity raw) {
        JsonNode payload = raw.getPayload();
        if (payload == null || !payload.path("data").isArray() || payload.path("data").isEmpty()) {
            return fail("data", "non-empty array", "Index membership payload is empty");
        }
        java.util.Set<String> symbols = new java.util.HashSet<>();
        for (JsonNode row : payload.path("data")) {
            String symbol = firstText(row, "symbol", "ticker", "stockCode");
            if (symbol == null || !symbols.add(symbol.toUpperCase(Locale.ROOT))) {
                return fail("symbol", symbol, "Duplicate or missing membership symbol");
            }
            if (securities != null && securities.findBySymbolIgnoreCase(symbol).isEmpty()) {
                return fail("symbol", symbol, "security was not found");
            }
        }
        return new Outcome("PASS", null, null, "Index membership payload is valid");
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.hasNonNull(key) && !node.path(key).asText().isBlank()) return node.path(key).asText().trim();
        }
        return null;
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

    private Outcome title(JsonNode payload, JsonNode config) {
        List<JsonNode> items = newsItems(payload, config);
        if (items.isEmpty()) return fail("no news item", "at least one news item", "News payload has no item to validate");
        String[] fields = configStrings(config, "fields");
        for (int i = 0; i < items.size(); i++)
            if (text(items.get(i), fields).isBlank())
                return fail("payload.data[" + i + "]", String.join("/", fields), "News item title is missing");
        return new Outcome("PASS", null, null, "Every news item has a title");
    }

    private Outcome url(JsonNode payload, JsonNode config) {
        List<JsonNode> items = newsItems(payload, config);
        if (items.isEmpty()) return fail("no news item", "at least one linked news item", "News payload has no item to validate");
        String[] fields = configStrings(config, "fields");
        for (int i = 0; i < items.size(); i++) {
            String value = text(items.get(i), fields);
            if (!isHttpUrl(value, config)) return fail("payload.data[" + i + "].url",
                    "absolute HTTP(S) URL with host", "News item link is missing or invalid");
        }
        return new Outcome("PASS", null, null, "Every news item has a valid source link");
    }

    private Outcome newsStructure(JsonNode payload, JsonNode config) {
        String field = config.path("dataField").asText("data");
        if (payload == null || !payload.isObject() || !payload.path(field).isArray()
                || payload.path(field).isEmpty())
            return fail("payload." + field, "non-empty array of news objects", "News list is missing or malformed");
        for (int i = 0; i < payload.path(field).size(); i++)
            if (!payload.path(field).get(i).isObject())
                return fail("payload." + field + "[" + i + "]", "object", "News list contains a non-object item");
        return new Outcome("PASS", null, null, "News list structure is valid");
    }

    private Outcome newsDate(JsonNode payload, JsonNode config) {
        String field = config.required("field").asText();
        DateTimeFormatter format = DateTimeFormatter.ofPattern(config.required("format").asText())
                .withResolverStyle(ResolverStyle.STRICT);
        ZoneId zone = ZoneId.of(config.required("zone").asText());
        List<JsonNode> items = newsItems(payload, config);
        boolean checked = false;
        for (int i = 0; i < items.size(); i++) {
            JsonNode value = items.get(i).path(field);
            if (value.isMissingNode() || value.isNull()) continue;
            try {
                if (!value.isTextual()) throw new DateTimeParseException("Expected text", "", 0);
                LocalDateTime.parse(value.asText().trim(), format).atZone(zone);
                checked = true;
            } catch (DateTimeParseException exception) {
                return fail("payload.data[" + i + "]." + field, config.path("format").asText(),
                        "Invalid news publication date");
            }
        }
        return new Outcome(checked ? "PASS" : "SKIP", null, null,
                checked ? "Provided publication dates are valid" : "No publication date provided");
    }

    private Outcome newsSymbol(RawPayloadEntity raw, JsonNode config) {
        String expected = raw.getSourceSymbol();
        if (expected == null || expected.isBlank())
            return new Outcome("SKIP", null, null, "Source is not scoped to a symbol");
        String field = config.required("field").asText();
        List<JsonNode> items = newsItems(raw.getPayload(), config);
        if (items.isEmpty()) return new Outcome("SKIP", null, null, "No news items to compare");
        for (int i = 0; i < items.size(); i++)
            if (!expected.trim().equalsIgnoreCase(text(items.get(i), field)))
                return fail("payload.data[" + i + "]." + field, expected, "News symbol differs from source_symbol");
        return new Outcome("PASS", null, null, "News symbols match the source");
    }

    private Outcome newsDuplicateUrl(JsonNode payload, JsonNode config) {
        var seen = new HashMap<String, Integer>();
        List<JsonNode> items = newsItems(payload, config);
        String[] fields = configStrings(config, "fields");
        List<String> prefixes = Arrays.asList(configStrings(config, "ignoreQueryPrefixes"));
        List<String> names = Arrays.asList(configStrings(config, "ignoreQueryNames"));
        for (int i = 0; i < items.size(); i++) {
            String value = text(items.get(i), fields);
            if (!isHttpUrl(value, config)) continue; // NEWS_URL_REQUIRED reports invalid links.
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
            String query = uri.getRawQuery() == null ? "" : Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(part -> {
                        String name = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                        return !names.contains(name) && prefixes.stream().noneMatch(name::startsWith);
                    })
                    .sorted().collect(java.util.stream.Collectors.joining("&"));
            String path = uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            String key = scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                    + (port == -1 ? "" : ":" + port) + path + (query.isEmpty() ? "" : "?" + query);
            Integer previous = seen.putIfAbsent(key, i);
            if (previous != null)
                return fail("payload.data[" + i + "].url", "unique URL in this payload",
                        "Duplicates payload.data[" + previous + "].url after URL normalization");
        }
        return new Outcome(seen.isEmpty() ? "SKIP" : "PASS", null, null, "No repeated valid URL within the news list");
    }

    private Outcome newsMetadata(JsonNode payload) {
        if (payload == null || !payload.isObject())
            return fail("payload", "object", "NEWS_DATA metadata must be an object");
        for (String field : List.of("requested_url", "final_url"))
            if (!payload.path(field).isTextual() || payload.path(field).asText().isBlank())
                return fail("payload." + field, "non-empty string", "Missing page URL metadata");
        if (!payload.path("http_status").isIntegralNumber() || !payload.path("http_status").canConvertToInt())
            return fail("payload.http_status", "integer", "Invalid source HTTP status type");
        if (!payload.path("textual").isBoolean())
            return fail("payload.textual", "boolean", "Missing or invalid textual flag");
        return new Outcome("PASS", null, null, "NEWS_DATA metadata types are valid");
    }

    private Outcome newsDataUrls(JsonNode payload, JsonNode config) {
        for (String field : configStrings(config, "fields"))
            if (payload == null || !isHttpUrl(text(payload, field), config))
                return fail("payload." + field, "absolute HTTP(S) URL with host", "Invalid source page URL");
        return new Outcome("PASS", null, null, "Requested and final page URLs are valid");
    }

    private Outcome newsHttpStatus(JsonNode payload, JsonNode config) {
        int min = config.required("minimum").intValue();
        int max = config.required("maximum").intValue();
        if (min < 200 || max > 299 || min > max) throw new IllegalStateException("Invalid success HTTP range");
        JsonNode status = payload == null ? null : payload.get("http_status");
        if (status == null || !status.isIntegralNumber() || !status.canConvertToInt()
                || status.intValue() < min || status.intValue() > max)
            return fail("payload.http_status=" + status, min + ".." + max, "Source page HTTP request did not succeed");
        return new Outcome("PASS", null, null, "Source page HTTP status is successful");
    }

    private Outcome newsContentType(RawPayloadEntity raw, JsonNode config) {
        String type = raw.getContentType() == null ? "" : raw.getContentType().split(";", 2)[0].trim();
        JsonNode flag = raw.getPayload() == null ? null : raw.getPayload().get("textual");
        if (flag == null || !flag.isBoolean() || !flag.booleanValue()
                || Arrays.stream(configStrings(config, "allowed")).noneMatch(type::equalsIgnoreCase))
            return fail("content_type=" + type + ", payload.textual=" + flag,
                    String.join(", ", configStrings(config, "allowed")) + "; textual=true",
                    "Source page is not supported HTML text");
        return new Outcome("PASS", null, null, "Source page content type is HTML text");
    }

    private Outcome newsRawText(String html) {
        return html == null || html.isBlank()
                ? fail("raw_text", "non-empty HTML text", "Source page body is empty")
                : new Outcome("PASS", null, null, "Source page body is present");
    }

    private Outcome newsHtml(String html) {
        if (html == null || !Pattern.compile("(?is)<(?:!doctype\\s+html\\b|html\\b|body\\b)").matcher(html).find())
            return fail("raw_text", "HTML document markup", "Source body is not an HTML document");
        HtmlSummary parsed = parseHtml(html);
        return parsed.body.isEmpty()
                ? fail("raw_text", "visible body text", "HTML has no visible body text")
                : new Outcome("PASS", null, null, "HTML document contains visible body text");
    }

    private Outcome newsBlockPage(String html, JsonNode config) {
        if (html == null || html.isBlank()) return new Outcome("SKIP", null, null, "No page body to inspect");
        HtmlSummary parsed = parseHtml(html);
        String heading = parsed.heading.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String marker : configStrings(config, "titleMarkers"))
            if (heading.contains(marker.toLowerCase(Locale.ROOT)))
                return fail("raw_text:title/h1", "article page", "Possible block/error page marker: " + marker);
        return new Outcome("PASS", null, null, "No configured block page marker in title/h1");
    }

    /** Parses locally; never fetches links, loads resources, or executes page scripts. */
    private HtmlSummary parseHtml(String html) {
        HtmlSummary summary = new HtmlSummary();
        try {
            new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
                boolean body;
                int heading;
                int ignored;
                public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                    if (tag == HTML.Tag.BODY) body = true;
                    if (tag == HTML.Tag.TITLE || tag == HTML.Tag.H1) heading++;
                    if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) ignored++;
                }
                public void handleEndTag(HTML.Tag tag, int position) {
                    if (tag == HTML.Tag.BODY) body = false;
                    if (tag == HTML.Tag.TITLE || tag == HTML.Tag.H1) heading = Math.max(0, heading - 1);
                    if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) ignored = Math.max(0, ignored - 1);
                }
                public void handleText(char[] text, int position) {
                    String value = new String(text).replace('\u00a0', ' ').trim();
                    if (ignored == 0 && !value.isBlank()) {
                        if (body) summary.body.append(value).append(' ');
                        if (heading > 0) summary.heading.append(value).append(' ');
                    }
                }
            }, true);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot parse in-memory HTML", exception);
        }
        return summary;
    }

    private static class HtmlSummary {
        final StringBuilder body = new StringBuilder();
        final StringBuilder heading = new StringBuilder();
    }

    private String[] configStrings(JsonNode config, String field) {
        JsonNode array = config == null ? null : config.get(field);
        if (array == null || !array.isArray() || array.isEmpty())
            throw new IllegalStateException("Missing non-empty rule_config." + field);
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (!value.isTextual() || value.asText().isBlank())
                throw new IllegalStateException("Invalid rule_config." + field);
            values.add(value.asText());
        }
        return values.toArray(String[]::new);
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
        return payload.path("count").isIntegralNumber()
                        && payload.path("count").canConvertToLong()
                        && payload.path("count").asLong(-1) == payload.path("data").size()
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

    private List<JsonNode> newsItems(JsonNode payload, JsonNode config) {
        if (payload == null || !payload.isObject()) return List.of();
        String field = config == null ? "data" : config.path("dataField").asText("data");
        if (payload.path(field).isArray()) {
            List<JsonNode> items = new ArrayList<>();
            payload.path(field).elements().forEachRemaining(items::add);
            return items;
        }
        return List.of();
    }

    private String text(JsonNode item, String... keys) {
        for (String key : keys)
            if (item.path(key).isTextual() && !item.path(key).asText().isBlank()) return item.path(key).asText().trim();
        return "";
    }

    private boolean isHttpUrl(String value, JsonNode config) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535))
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && Arrays.stream(configStrings(config, "schemes"))
                            .anyMatch(scheme -> scheme.equalsIgnoreCase(uri.getScheme()));
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
