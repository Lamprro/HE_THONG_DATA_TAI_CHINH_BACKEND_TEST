package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses the Python envelopes once for validation and for the later build workflow. */
@Component
public class MarketIndexPayloadParser {
    public static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public PriceBatch prices(JsonNode payload, String sourceCode) {
        JsonNode data = rows(payload);
        String code = indexCode(payload, sourceCode);
        String interval = text(payload, "interval");
        if (interval == null) interval = "1D";
        if (!"1D".equalsIgnoreCase(interval)) {
            throw new IllegalArgumentException("Chỉ hỗ trợ nến chỉ số theo ngày (1D)");
        }
        List<PriceBar> bars = new ArrayList<>();
        Set<Instant> timestamps = new HashSet<>();
        for (JsonNode row : data) {
            if (!row.isObject()) throw new IllegalArgumentException("Dòng giá chỉ số phải là object");
            Instant timestamp = timestamp(field(row, "time", "date", "timestamp", "trading_date"));
            if (!timestamps.add(timestamp)) {
                throw new IllegalArgumentException("Trùng thời điểm nến chỉ số trong cùng payload: " + timestamp);
            }
            BigDecimal open = requiredDecimal(row, "open", "open_value", "open_price");
            BigDecimal high = requiredDecimal(row, "high", "high_value", "high_price");
            BigDecimal low = requiredDecimal(row, "low", "low_value", "low_price");
            BigDecimal close = requiredDecimal(row, "close", "close_value", "close_price");
            BigDecimal volume = optionalDecimal(row, "volume");
            BigDecimal value = optionalDecimal(row, "trading_value", "tradingValue", "value");
            if (open.signum() < 0 || high.signum() < 0 || low.signum() < 0 || close.signum() < 0
                    || high.compareTo(open.max(close).max(low)) < 0
                    || low.compareTo(open.min(close).min(high)) > 0) {
                throw new IllegalArgumentException("OHLC của chỉ số không hợp lệ tại " + timestamp);
            }
            if ((volume != null && volume.signum() < 0) || (value != null && value.signum() < 0)) {
                throw new IllegalArgumentException("Khối lượng hoặc giá trị giao dịch âm tại " + timestamp);
            }
            bars.add(new PriceBar(timestamp, "1d", open, high, low, close, volume, value));
        }
        return new PriceBatch(code, List.copyOf(bars));
    }

    public MemberSnapshot members(JsonNode payload, String sourceCode, Instant fetchedAt) {
        JsonNode data = rows(payload);
        String code = indexCode(payload, sourceCode);
        String dateText = text(payload, "snapshot_date", "snapshotDate", "effective_date", "as_of_date");
        LocalDate date = dateText == null ? fetchedAt.atZone(VIETNAM_ZONE).toLocalDate()
                : timestampTextToDate(dateText);
        Map<String, Member> bySymbol = new LinkedHashMap<>();
        for (JsonNode row : data) {
            if (!row.isObject()) throw new IllegalArgumentException("Dòng thành viên phải là object");
            String symbol = text(row, "symbol", "ticker", "code", "organ_code", "stock_code", "stockCode");
            if (symbol == null || !symbol.toUpperCase(Locale.ROOT).matches("[A-Z0-9._-]{1,20}")) {
                throw new IllegalArgumentException("Mã chứng khoán thành viên không hợp lệ");
            }
            symbol = symbol.toUpperCase(Locale.ROOT);
            BigDecimal weight;
            if (field(row, "weight_percent", "weightPercent") != null) {
                weight = decimal(field(row, "weight_percent", "weightPercent"), "weight_percent")
                        .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            } else {
                weight = optionalDecimal(row, "weight", "ratio", "proportion");
            }
            if (weight != null && (weight.signum() < 0 || weight.compareTo(BigDecimal.ONE) > 0)) {
                throw new IllegalArgumentException("Tỷ trọng thành viên ngoài khoảng 0..1: " + symbol);
            }
            if (weight != null) weight = weight.setScale(8, RoundingMode.HALF_UP);
            Member prior = bySymbol.putIfAbsent(symbol, new Member(symbol, weight));
            if (prior != null && !equalValue(prior.weight(), weight)) {
                throw new IllegalArgumentException("Trùng mã thành viên với tỷ trọng khác nhau: " + symbol);
            }
        }
        return new MemberSnapshot(code, date, List.copyOf(bySymbol.values()));
    }

    private JsonNode rows(JsonNode payload) {
        if (payload == null || !payload.isObject() || !payload.path("data").isArray()
                || payload.path("data").isEmpty()) {
            throw new IllegalArgumentException("Payload chỉ số thiếu mảng data không rỗng");
        }
        JsonNode data = payload.path("data");
        if (payload.has("count") && (!payload.path("count").canConvertToInt()
                || payload.path("count").asInt() != data.size())) {
            throw new IllegalArgumentException("count không khớp số dòng data");
        }
        return data;
    }

    private String indexCode(JsonNode payload, String sourceCode) {
        String declared = text(payload, "symbol", "index_code", "indexCode");
        String code = declared == null ? sourceCode : declared;
        if (code == null || !code.toUpperCase(Locale.ROOT).matches("[A-Z0-9._-]{1,20}")) {
            throw new IllegalArgumentException("Thiếu mã chỉ số hợp lệ");
        }
        if (sourceCode != null && declared != null && !sourceCode.equalsIgnoreCase(declared)) {
            throw new IllegalArgumentException("Mã chỉ số trong payload khác mã của yêu cầu");
        }
        return code.toUpperCase(Locale.ROOT);
    }

    public Instant timestamp(JsonNode value) {
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Thiếu thời điểm giao dịch của chỉ số");
        }
        String text = value.asText().trim();
        if (text.matches("-?\\d{10,13}")) {
            long epoch = Long.parseLong(text);
            return text.replace("-", "").length() >= 13
                    ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        try { return Instant.parse(text); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(text).toInstant(); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(text).atZone(VIETNAM_ZONE).toInstant(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(text).atStartOfDay(VIETNAM_ZONE).toInstant(); }
        catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Thời điểm chỉ số không hợp lệ: " + text, exception);
        }
    }

    private LocalDate timestampTextToDate(String value) {
        try { return LocalDate.parse(value); } catch (DateTimeParseException ignored) { }
        return timestamp(com.fasterxml.jackson.databind.node.TextNode.valueOf(value))
                .atZone(VIETNAM_ZONE).toLocalDate();
    }

    private JsonNode field(JsonNode row, String... names) {
        if (row == null) return null;
        for (String name : names) {
            JsonNode value = row.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value;
        }
        return null;
    }

    private String text(JsonNode row, String... names) {
        JsonNode value = field(row, names);
        return value == null ? null : value.asText().trim();
    }

    private BigDecimal requiredDecimal(JsonNode row, String... names) {
        JsonNode value = field(row, names);
        if (value == null) throw new IllegalArgumentException("Thiếu trường số: " + names[0]);
        return decimal(value, names[0]);
    }

    private BigDecimal optionalDecimal(JsonNode row, String... names) {
        JsonNode value = field(row, names);
        return value == null ? null : decimal(value, names[0]);
    }

    private BigDecimal decimal(JsonNode value, String field) {
        if (!value.isNumber() && !value.isTextual()) {
            throw new IllegalArgumentException("Trường " + field + " phải là số");
        }
        try { return new BigDecimal(value.asText().trim()); }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Trường " + field + " phải là số hữu hạn", exception);
        }
    }

    private boolean equalValue(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    public record PriceBatch(String indexCode, List<PriceBar> rows) {}
    public record PriceBar(Instant timestamp, String interval, BigDecimal open, BigDecimal high,
                           BigDecimal low, BigDecimal close, BigDecimal volume, BigDecimal tradingValue) {}
    public record MemberSnapshot(String indexCode, LocalDate snapshotDate, List<Member> rows) {}
    public record Member(String symbol, BigDecimal weight) {}
}
