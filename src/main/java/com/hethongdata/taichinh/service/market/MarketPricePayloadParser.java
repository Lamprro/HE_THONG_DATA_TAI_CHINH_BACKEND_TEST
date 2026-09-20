package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses normalized market_price.v1 responses and compatible legacy provider envelopes. */
@Component
public class MarketPricePayloadParser {
    public static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public PriceBatch parse(JsonNode payload, String entityType, String sourceSymbol,
            Instant fetchedAt) {
        if (!"QUOTE".equalsIgnoreCase(entityType) && !"OHLCV".equalsIgnoreCase(entityType)) {
            throw new IllegalArgumentException("Chỉ hỗ trợ payload QUOTE hoặc OHLCV");
        }
        if (payload == null || !payload.isObject() || !payload.path("data").isArray()
                || payload.path("data").isEmpty()) {
            throw new IllegalArgumentException("Payload giá thiếu mảng data không rỗng");
        }
        JsonNode data = payload.path("data");
        if (payload.has("count") && (!payload.path("count").canConvertToInt()
                || payload.path("count").asInt() != data.size())) {
            throw new IllegalArgumentException("count không khớp số dòng data");
        }
        String envelopeSymbol = text(payload, "symbol");
        String symbol = normalizedSymbol(envelopeSymbol == null ? sourceSymbol : envelopeSymbol);
        if (sourceSymbol != null && !sourceSymbol.equalsIgnoreCase(symbol)) {
            throw new IllegalArgumentException("Mã trong payload khác source_symbol");
        }
        boolean quote = "QUOTE".equalsIgnoreCase(entityType);
        List<PriceRow> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode row : data) {
            if (!row.isObject()) throw new IllegalArgumentException("Dòng giá phải là object");
            String rowSymbol = text(row, "symbol", "Symbol", "code", "ticker");
            if (rowSymbol != null && !symbol.equals(normalizedSymbol(rowSymbol))) {
                throw new IllegalArgumentException("Dòng dữ liệu chứa mã khác mã yêu cầu");
            }
            String interval = quote ? "15m" : normalizedInterval(text(row, "interval_code", "interval"));
            Instant timestamp = quote
                    ? quoteTimestamp(row, payload, fetchedAt).truncatedTo(ChronoUnit.MINUTES)
                    : dailyTimestamp(row);
            if (!keys.add(timestamp + ":" + interval)) {
                throw new IllegalArgumentException("Trùng thời điểm/interval trong cùng payload: " + timestamp);
            }
            BigDecimal open = decimal(row, false, "open_price", "open", "GiaMoCua");
            BigDecimal high = decimal(row, false, "high_price", "high", "GiaCaoNhat");
            BigDecimal low = decimal(row, false, "low_price", "low", "GiaThapNhat");
            BigDecimal close = decimal(row, true, "close_price", "close", "price", "GiaDongCua");
            validateOhlc(open, high, low, close, timestamp);
            BigDecimal volume = decimal(row, false, "volume", "volume_accumulated",
                    "nmVolume", "KhoiLuongKhopLenh");
            BigDecimal tradingValue = decimal(row, false, "trading_value", "total_value",
                    "nmValue", "GiaTriKhopLenh");
            BigDecimal foreignBuy = decimal(row, false, "foreign_buy_volume", "foreignBuyVolume");
            BigDecimal foreignSell = decimal(row, false, "foreign_sell_volume", "foreignSellVolume");
            nonNegative(volume, "volume");
            nonNegative(tradingValue, "trading_value");
            nonNegative(foreignBuy, "foreign_buy_volume");
            nonNegative(foreignSell, "foreign_sell_volume");
            rows.add(new PriceRow(timestamp, interval, open, high, low, close,
                    decimal(row, false, "adjusted_close", "adjusted_close_price", "adClose", "GiaDieuChinh"),
                    decimal(row, false, "reference_price", "basicPrice"),
                    decimal(row, false, "ceiling_price", "ceilingPrice"),
                    decimal(row, false, "floor_price", "floorPrice"),
                    volume, tradingValue, foreignBuy, foreignSell));
        }
        return new PriceBatch(symbol, List.copyOf(rows));
    }

    private Instant quoteTimestamp(JsonNode row, JsonNode payload, Instant fetchedAt) {
        JsonNode value = field(row, "price_timestamp", "observed_at", "timestamp");
        if (value == null) value = field(payload, "retrieved_at");
        return value == null ? fetchedAt : timestamp(value);
    }

    private Instant dailyTimestamp(JsonNode row) {
        JsonNode value = field(row, "price_timestamp", "trading_date", "time", "date", "timestamp", "Ngay");
        if (value == null) throw new IllegalArgumentException("Thiếu ngày/thời điểm giao dịch");
        String raw = value.asText().trim();
        try {
            return LocalDate.parse(raw, DateTimeFormatter.ofPattern("dd/MM/uuuu"))
                    .atStartOfDay(VIETNAM_ZONE).toInstant();
        } catch (DateTimeParseException ignored) {
            return timestamp(value);
        }
    }

    private Instant timestamp(JsonNode value) {
        String raw = value.asText().trim();
        if (raw.matches("-?\\d{10,13}")) {
            long epoch = Long.parseLong(raw);
            return raw.replace("-", "").length() >= 13
                    ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        try { return Instant.parse(raw); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(raw).toInstant(); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(raw).atZone(VIETNAM_ZONE).toInstant(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(raw).atStartOfDay(VIETNAM_ZONE).toInstant(); }
        catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Thời điểm giá không hợp lệ: " + raw, exception);
        }
    }

    private String normalizedInterval(String value) {
        if (value == null || value.equalsIgnoreCase("1D") || value.equalsIgnoreCase("daily")) return "1d";
        throw new IllegalArgumentException("OHLCV hiện chỉ hỗ trợ interval 1d");
    }

    private String normalizedSymbol(String value) {
        if (value == null || !value.trim().toUpperCase(Locale.ROOT).matches("[A-Z0-9._-]{1,20}")) {
            throw new IllegalArgumentException("Thiếu mã chứng khoán hợp lệ");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void validateOhlc(BigDecimal open, BigDecimal high, BigDecimal low,
            BigDecimal close, Instant timestamp) {
        nonNegative(open, "open_price");
        nonNegative(high, "high_price");
        nonNegative(low, "low_price");
        nonNegative(close, "close_price");
        if (high != null && low != null && high.compareTo(low) < 0
                || high != null && open != null && high.compareTo(open) < 0
                || high != null && high.compareTo(close) < 0
                || low != null && open != null && low.compareTo(open) > 0
                || low != null && low.compareTo(close) > 0) {
            throw new IllegalArgumentException("Quan hệ OHLC không hợp lệ tại " + timestamp);
        }
    }

    private void nonNegative(BigDecimal value, String name) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(name + " không được âm");
        }
    }

    private BigDecimal decimal(JsonNode row, boolean required, String... names) {
        JsonNode value = field(row, names);
        if (value == null) {
            if (required) throw new IllegalArgumentException("Thiếu trường " + names[0]);
            return null;
        }
        if (!value.isNumber() && !value.isTextual()) {
            throw new IllegalArgumentException("Trường " + names[0] + " phải là số");
        }
        try { return new BigDecimal(value.asText().trim()); }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Trường " + names[0] + " phải là số hữu hạn", exception);
        }
    }

    private JsonNode field(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) return value;
        }
        return null;
    }

    private String text(JsonNode node, String... names) {
        JsonNode value = field(node, names);
        return value == null ? null : value.asText().trim();
    }

    public record PriceBatch(String symbol, List<PriceRow> rows) {}
    public record PriceRow(Instant timestamp, String interval, BigDecimal open,
            BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal adjustedClose,
            BigDecimal referencePrice, BigDecimal ceilingPrice, BigDecimal floorPrice,
            BigDecimal volume, BigDecimal tradingValue, BigDecimal foreignBuyVolume,
            BigDecimal foreignSellVolume) {}
}
