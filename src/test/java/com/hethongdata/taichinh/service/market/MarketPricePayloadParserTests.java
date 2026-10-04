package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketPricePayloadParserTests {
    private final ObjectMapper json = new ObjectMapper();
    private final MarketPricePayloadParser parser = new MarketPricePayloadParser();

    @Test
    void quoteRemainsAnExactTimestampedSnapshot() throws Exception {
        var payload = json.readTree("""
                {"symbol":"FPT","retrieved_at":"2026-09-19T02:37:44Z","count":1,"data":[{
                  "symbol":"FPT","price_timestamp":"2026-09-19T02:37:44Z",
                  "interval_code":"snapshot","open_price":100,"high_price":110,
                  "low_price":99,"close_price":105,"volume":1000
                }]}
                """);
        var batch = parser.parse(payload, "QUOTE", "FPT", Instant.EPOCH);
        assertThat(batch.rows()).hasSize(1);
        assertThat(batch.rows().getFirst().interval()).isEqualTo("snapshot");
        assertThat(batch.rows().getFirst().timestamp()).isEqualTo("2026-09-19T02:37:44Z");
    }

    @Test
    void ohlcvUsesDailyTimestamp() throws Exception {
        var payload = json.readTree("""
                {"symbol":"FPT","count":1,"data":[{
                  "trading_date":"2026-09-19","interval_code":"1d",
                  "open_price":100,"high_price":110,"low_price":99,"close_price":105
                }]}
                """);
        var row = parser.parse(payload, "OHLCV", "FPT", Instant.EPOCH).rows().getFirst();
        assertThat(row.interval()).isEqualTo("1d");
        assertThat(row.timestamp()).isEqualTo("2026-09-18T17:00:00Z");
    }

    @Test
    void rejectsInvalidOhlcAndSymbolMismatch() throws Exception {
        var invalid = json.readTree("""
                {"symbol":"VCB","count":1,"data":[{
                  "trading_date":"2026-09-19","open_price":100,"high_price":90,
                  "low_price":80,"close_price":95
                }]}
                """);
        assertThatThrownBy(() -> parser.parse(invalid, "OHLCV", "FPT", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsLegacyVndirectFieldNames() throws Exception {
        var payload = json.readTree("""
                {"symbol":"FPT","retrieved_at":"2026-09-19T02:37:44Z","count":1,"data":[{
                  "code":"FPT","date":"2026-09-19","open":100,"high":110,
                  "low":99,"close":105,"basicPrice":98,"nmVolume":1000,"nmValue":105000
                }]}
                """);
        var row = parser.parse(payload, "QUOTE", "FPT", Instant.EPOCH).rows().getFirst();
        assertThat(row.referencePrice()).isEqualByComparingTo("98");
        assertThat(row.volume()).isEqualByComparingTo("1000");
        assertThat(row.tradingValue()).isEqualByComparingTo("105000");
    }

    @Test
    void dailyVndirectTimestampUsesTradingDateInsteadOfTimeOnlyField() throws Exception {
        var payload = json.readTree("""
                {"symbol":"FPT","count":1,"data":[{
                  "code":"FPT","date":"2026-08-28","time":"15:08:06",
                  "open":72.2,"high":74.0,"low":72.2,"close":73.2,"nmVolume":1000
                }]}
                """);
        var row = parser.parse(payload, "OHLCV", "FPT", Instant.EPOCH).rows().getFirst();
        assertThat(row.timestamp()).isEqualTo("2026-08-27T17:00:00Z");
        assertThat(row.close()).isEqualByComparingTo("73.2");
    }

    @Test
    void dailyVnstockTimeUsesTradingDateRatherThanSevenAm() throws Exception {
        var payload = json.readTree("""
                {"provider":"vnstock","symbol":"FPT","count":1,"data":[{
                  "time":"2026-09-03T07:00:00.000","open":100,"high":110,
                  "low":99,"close":105
                }]}
                """);
        var row = parser.parse(payload, "OHLCV", "FPT", Instant.EPOCH).rows().getFirst();
        assertThat(row.timestamp()).isEqualTo("2026-09-02T17:00:00Z");
        assertThat(row.interval()).isEqualTo("1d");
    }

    @Test
    void normalizesLegacyProviderPriceUnitsButDoesNotRescaleMarketPriceV1() throws Exception {
        var legacyVndirect = json.readTree("""
                {"provider":"vndirect","dataset":"equity_ohlcv","symbol":"FPT","count":1,"data":[{
                  "code":"FPT","date":"2026-08-28","time":"15:08:06",
                  "open":72.2,"high":74.0,"low":72.2,"close":73.2,"basicPrice":72.2,
                  "nmVolume":1000,"nmValue":749755960000
                }]}
                """);
        var vndirectRow = parser.parse(legacyVndirect, "OHLCV", "FPT", Instant.EPOCH).rows().getFirst();
        assertThat(vndirectRow.close()).isEqualByComparingTo("73200");
        assertThat(vndirectRow.referencePrice()).isEqualByComparingTo("72200");
        assertThat(vndirectRow.tradingValue()).isEqualByComparingTo("749755960000");

        var normalized = json.readTree("""
                {"schema_version":"market_price.v1","provider":"vndirect","dataset":"equity_ohlcv",
                 "symbol":"FPT","count":1,"data":[{
                  "symbol":"FPT","price_timestamp":"2026-08-28T00:00:00+07:00","interval_code":"1d",
                  "open_price":72200,"high_price":74000,"low_price":72200,"close_price":73200,"volume":1000
                }]}
                """);
        var normalizedRow = parser.parse(normalized, "OHLCV", "FPT", Instant.EPOCH).rows().getFirst();
        assertThat(normalizedRow.close()).isEqualByComparingTo("73200");
    }

    @Test
    void deduplicatesIdenticalRowsButRejectsConflictingDuplicates() throws Exception {
        var duplicate = json.readTree("""
                {"symbol":"FPT","count":2,"data":[
                  {"date":"2026-08-28","open":72.2,"high":74,"low":72.2,"close":73.2,"volume":1000},
                  {"date":"2026-08-28","open":72.2,"high":74,"low":72.2,"close":73.2,"volume":1000}
                ]}
                """);
        assertThat(parser.parse(duplicate, "OHLCV", "FPT", Instant.EPOCH).rows()).hasSize(1);

        var conflicting = json.readTree("""
                {"symbol":"FPT","count":2,"data":[
                  {"date":"2026-08-28","open":72.2,"high":74,"low":72.2,"close":73.2,"volume":1000},
                  {"date":"2026-08-28","open":72.2,"high":74,"low":72.2,"close":73.3,"volume":1000}
                ]}
                """);
        assertThatThrownBy(() -> parser.parse(conflicting, "OHLCV", "FPT", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Xung đột dữ liệu trùng thời điểm");
    }
}
