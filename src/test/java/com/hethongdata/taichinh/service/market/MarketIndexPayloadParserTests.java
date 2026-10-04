package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.integration.python.PythonExternalFinancialDataAdapter;
import com.hethongdata.taichinh.integration.python.PythonFinancialDataProperties;
import com.hethongdata.taichinh.integration.python.SensitiveHeaderSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketIndexPayloadParserTests {
    private final MarketIndexPayloadParser parser = new MarketIndexPayloadParser();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsDailyBarAndNormalizesVietnamDate() throws Exception {
        var batch = parser.prices(json.readTree("""
                {"symbol":"VN30","interval":"1D","count":1,"data":[
                  {"time":"2026-09-17","open":1900.12,"high":1920.50,
                   "low":1895.30,"close":1912.45,"volume":123456789,
                   "trading_value":4567890000000}]}
                """), "VN30");
        assertThat(batch.indexCode()).isEqualTo("VN30");
        assertThat(batch.rows()).hasSize(1);
        assertThat(batch.rows().getFirst().timestamp()).isEqualTo(Instant.parse("2026-09-16T17:00:00Z"));
        assertThat(batch.rows().getFirst().interval()).isEqualTo("1d");
        assertThat(batch.rows().getFirst().close()).isEqualByComparingTo("1912.45");
        assertThat(batch.rows().getFirst().tradingValue()).isEqualByComparingTo("4567890000000");
    }

    @Test
    void dailyIndexTimestampIgnoresProviderSevenAmClock() throws Exception {
        var batch = parser.prices(json.readTree("""
                {"symbol":"HNXINDEX","count":1,"data":[
                  {"time":"09/25/2026 07:00:00","open":200,"high":210,
                   "low":199,"close":205}]}
                """), "HNXINDEX");
        assertThat(batch.rows().getFirst().timestamp())
                .isEqualTo(Instant.parse("2026-09-24T17:00:00Z"));
    }

    @Test
    void supportsInstantOffsetLocalTimeAndEpoch() {
        Instant expected = Instant.parse("2026-09-17T00:00:00Z");
        assertThat(parser.timestamp(json.valueToTree("2026-09-17T00:00:00Z"))).isEqualTo(expected);
        assertThat(parser.timestamp(json.valueToTree("2026-09-17T07:00:00+07:00"))).isEqualTo(expected);
        assertThat(parser.timestamp(json.valueToTree("2026-09-17T07:00:00"))).isEqualTo(expected);
        assertThat(parser.timestamp(json.valueToTree(1789603200L))).isEqualTo(expected);
        assertThat(parser.timestamp(json.valueToTree(1789603200000L))).isEqualTo(expected);
    }

    @Test
    void rejectsBadOhlcDuplicateAndNegativeVolume() throws Exception {
        assertThatThrownBy(() -> parser.prices(json.readTree("""
                {"symbol":"VN30","data":[{"date":"2026-09-17","open":10,
                "high":9,"low":8,"close":9}]}
                """), "VN30")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.prices(json.readTree("""
                {"symbol":"VN30","data":[
                  {"date":"2026-09-17","open":8,"high":10,"low":7,"close":9},
                  {"time":"2026-09-17T00:00:00+07:00","open":8,"high":10,"low":7,"close":9}]}
                """), "VN30")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.prices(json.readTree("""
                {"symbol":"VN30","data":[{"date":"2026-09-17","open":8,
                "high":10,"low":7,"close":9,"volume":-1}]}
                """), "VN30")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.prices(json.readTree("""
                {"symbol":"VN30","interval":"15m","data":[{"date":"2026-09-17",
                "open":8,"high":10,"low":7,"close":9}]}
                """), "VN30")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsMembershipPercentAndRejectsConflictingDuplicate() throws Exception {
        var snapshot = parser.members(json.readTree("""
                {"symbol":"VN30","data":[{"ticker":"fpt","weight_percent":5.5},
                {"stockCode":"HPG","ratio":0.08}]}
                """), "VN30", Instant.parse("2026-09-17T02:00:00Z"));
        assertThat(snapshot.snapshotDate()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(snapshot.rows()).hasSize(2);
        assertThat(snapshot.rows().getFirst().symbol()).isEqualTo("FPT");
        assertThat(snapshot.rows().getFirst().weight()).isEqualByComparingTo(new BigDecimal("0.055"));
        assertThatThrownBy(() -> parser.members(json.readTree("""
                {"symbol":"VN30","data":[{"symbol":"FPT","weight":0.05},
                {"ticker":"fpt","weight":0.06}]}
                """), "VN30", Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adapterBuildsIndexUrisAndRejectsOtherProviders() {
        var properties = new PythonFinancialDataProperties();
        properties.setBaseUrl(URI.create("https://python.example"));
        var adapter = new PythonExternalFinancialDataAdapter(
                RestClient.create(), properties, new SensitiveHeaderSanitizer());
        assertThat(adapter.resolveUri(new ExternalFetchRequest(ExternalOperation.INDEX_OHLCV,
                "vnstock", "VN30", LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 17),
                "1D", Map.of())).toString())
                .isEqualTo("https://python.example/api/v1/vnstock/indices/VN30/ohlcv?start=2026-09-10&end=2026-09-17");
        assertThat(adapter.resolveUri(new ExternalFetchRequest(ExternalOperation.INDEX_MEMBERS,
                "vnstock", "VNINDEX", null, null, null, Map.of())).toString())
                .isEqualTo("https://python.example/api/v1/vnstock/indices/VNINDEX/members");
        assertThatThrownBy(() -> adapter.resolveUri(new ExternalFetchRequest(
                ExternalOperation.INDEX_OHLCV, "cafef", "VN30", null, null, null, Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalFetchRequest(
                ExternalOperation.INDEX_MEMBERS, "vnstock", "../VN30", null, null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
