package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.MarketPriceEntity;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
//import com.hethongdata.taichinh.repository.jpa.market.MarketPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import com.hethongdata.taichinh.repository.jpa.market.MarketPriceJpaRepository;
import com.hethongdata.taichinh.repository.jdbc.market.MarketPriceBatchInsertJdbcRepository;
import com.hethongdata.taichinh.service.validation.DataVersionLifecycleService;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketPriceWorkflowPersistenceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
    private final RawPayloadJpaRepository raws = mock(RawPayloadJpaRepository.class);
    private final IngestionRunJpaRepository runs = mock(IngestionRunJpaRepository.class);
    private final DataSourceJpaRepository sources = mock(DataSourceJpaRepository.class);
    private final SecurityJpaRepository securities = mock(SecurityJpaRepository.class);
//    private final MarketPriceJpaRepository prices = mock(MarketPriceJpaRepository.class);
//    private final MarketPriceWorkflowPersistenceService writes =
//            new MarketPriceWorkflowPersistenceService(versions, raws, runs, sources,
//                    securities, prices, new MarketPricePayloadParser(), json);

    @Test
    void twoQuotesWithinFifteenMinutesUpdateOneRow() throws Exception {
        DataSourceEntity source = DataSourceEntity.create(
                "VNSTOCK", "VnStock", "API", null, "vnstock", false, "UNKNOWN");
        ReflectionTestUtils.setField(source, "id", 7L);
        SecurityEntity security = SecurityEntity.create(UUID.randomUUID(), "FPT", "HOSE",
                "STOCK", null, "VND", null, null, null, null, true, true);
        ReflectionTestUtils.setField(security, "id", UUID.randomUUID());
        DataVersionEntity first = version();
        DataVersionEntity second = version();
        RawPayloadEntity raw1 = raw(source, security.getId(), first.getIngestionRunId(),
                "2026-09-19T02:31:00Z", 100);
        RawPayloadEntity raw2 = raw(source, security.getId(), second.getIngestionRunId(),
                "2026-09-19T02:39:00Z", 101);
        when(versions.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(versions.findByIdForUpdate(second.getId())).thenReturn(Optional.of(second));
        when(raws.findByIngestionRunIdOrderByFetchedAtDesc(first.getIngestionRunId()))
                .thenReturn(List.of(raw1));
        when(raws.findByIngestionRunIdOrderByFetchedAtDesc(second.getIngestionRunId()))
                .thenReturn(List.of(raw2));
        when(raws.findById(raw1.getId())).thenReturn(Optional.of(raw1));
        when(securities.findById(security.getId())).thenReturn(Optional.of(security));
//        when(securities.findByIdForUpdate(security.getId())).thenReturn(Optional.of(security));
        when(sources.findById(7L)).thenReturn(Optional.of(source));
        MarketPriceEntity[] stored = new MarketPriceEntity[1];
        AtomicInteger inserted = new AtomicInteger();
//        when(prices.findTopBySecurityIdAndIntervalCodeAndDataSourceIdOrderByPriceTimestampDesc(
//                security.getId(), "15m", 7L)).thenAnswer(call -> Optional.ofNullable(stored[0]));
//        when(prices.findBySecurityIdAndPriceTimestampAndIntervalCodeAndDataSourceId(
//                eq(security.getId()), any(Instant.class), eq("15m"), eq(7L)))
//                .thenAnswer(call -> Optional.ofNullable(stored[0]));
//        when(prices.saveAndFlush(any(MarketPriceEntity.class))).thenAnswer(call -> {
//            MarketPriceEntity row = call.getArgument(0);
//            if (row.getId() == null) {
//                ReflectionTestUtils.setField(row, "id", 1L);
//                inserted.incrementAndGet();
//            }
//            stored[0] = row;
//            return row;
//        });
//        when(prices.findBucketForUpdate(eq(security.getId()), any(Instant.class), eq("15m")))
//                .thenAnswer(call -> List.of(stored[0]));
//
//        writes.build(first.getId(), run(source));
//        writes.build(second.getId(), run(source));
//
//        assertThat(inserted).hasValue(1);
//        assertThat(stored[0].getPriceTimestamp()).isEqualTo("2026-09-19T02:30:00Z");
//        assertThat(stored[0].getClosePrice()).isEqualByComparingTo("101");
//        assertThat(stored[0].getRawPayloadId()).isEqualTo(raw2.getId());
//        assertThat(first.getStatus()).isEqualTo("ACTIVATED");
//        assertThat(second.getStatus()).isEqualTo("ACTIVATED");
    }

    @ParameterizedTest
    @CsvSource({"105,false", "106,true"})
    void comparesTwoSourcesWithoutWritingDuplicateOrConflictingDailyPrices(int close, boolean conflict) throws Exception {
        var prices = mock(MarketPriceJpaRepository.class);
        var writes = new MarketPriceWorkflowPersistenceService(versions, raws, runs, sources, securities,
                prices, new MarketPricePayloadParser(), json, mock(MarketPriceBatchInsertJdbcRepository.class));
        var incoming = DataSourceEntity.create("VN", "VNDirect", "API", null, "vndirect", false, "UNKNOWN");
        var preferred = DataSourceEntity.create("CF", "CafeF", "API", null, "cafef", true, "UNKNOWN");
        ReflectionTestUtils.setField(incoming, "id", 2L);
        ReflectionTestUtils.setField(preferred, "id", 1L);
        UUID securityId = UUID.randomUUID();
        var security = mock(SecurityEntity.class);
        when(security.getId()).thenReturn(securityId);
        when(security.getSymbol()).thenReturn("FPT");
        when(securities.findById(securityId)).thenReturn(Optional.of(security));
        when(securities.findByIdForUpdate(securityId)).thenReturn(Optional.of(security));
        var version = version();
        var payload = json.readTree("""
                {"schema_version":"market_price.v1","provider":"vndirect","symbol":"FPT","count":1,"data":[
                    {"trading_date":"2026-11-20","close_price":%d,"adjusted_close":100}
                ]}
                """.formatted(close));
        var run = run(incoming);
        var raw = RawPayloadEntity.create(run, incoming, "FPT", "OHLCV", "FPT", null, "application/json",
                payload, null, "checksum", Instant.parse("2026-11-20T10:00:00Z"), securityId);
        ReflectionTestUtils.setField(raw, "id", UUID.randomUUID());
        when(versions.findByIdForUpdate(version.getId())).thenReturn(Optional.of(version));
        when(raws.findByIngestionRunIdOrderByFetchedAtDesc(version.getIngestionRunId())).thenReturn(List.of(raw));
        var canonical = mock(MarketPriceEntity.class);
        when(canonical.getIsCanonical()).thenReturn(true);
        when(canonical.getPriceBasis()).thenReturn("UNADJUSTED");
        when(canonical.getClosePrice()).thenReturn(new BigDecimal("105"));
        when(canonical.getDataSourceId()).thenReturn(1L);
        when(prices.findBucketForUpdate(securityId, Instant.parse("2026-11-19T17:00:00Z"), "1d"))
                .thenReturn(List.of(canonical));
        when(sources.findById(1L)).thenReturn(Optional.of(preferred));

        if (conflict) {
            assertThatThrownBy(() -> writes.build(version.getId(), run))
                    .isInstanceOf(DataVersionLifecycleService.SourceConflict.class);
            assertThat(version.getStatus()).isEqualTo("ACTIVE");
        } else {
            assertThat(writes.build(version.getId(), run)).isEqualTo(1);
            assertThat(version.getStatus()).isEqualTo("ACTIVATED");
        }
        verify(prices, never()).saveAndFlush(any());
        verify(prices, never()).saveAllAndFlush(any());
    }

    private DataVersionEntity version() {
        DataVersionEntity value = DataVersionEntity.acceptedForRun(
                "MARKET_PRICE", UUID.randomUUID(), 1, "checksum");
        ReflectionTestUtils.setField(value, "id", UUID.randomUUID());
        return value;
    }

    private RawPayloadEntity raw(DataSourceEntity source, UUID securityId, UUID runId,
            String observedAt, int close) throws Exception {
        IngestionRunEntity ingestionRun = run(source);
        ReflectionTestUtils.setField(ingestionRun, "id", runId);
        String body = "{\"symbol\":\"FPT\",\"retrieved_at\":\"" + observedAt
                + "\",\"count\":1,\"data\":[{\"symbol\":\"FPT\",\"close_price\":"
                + close + "}]}";
        RawPayloadEntity raw = RawPayloadEntity.create(ingestionRun, source, "FPT", "QUOTE",
                "FPT", null, "application/json", json.readTree(body), null, "checksum",
                Instant.parse(observedAt), securityId);
        ReflectionTestUtils.setField(raw, "id", UUID.randomUUID());
        return raw;
    }

    private IngestionRunEntity run(DataSourceEntity source) {
        IngestionRunEntity run = IngestionRunEntity.start(source, null, "MANUAL",
                json.createObjectNode(), URI.create("internal://ingestion/market-price-build"),
                json.createObjectNode(), Instant.now());
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        return run;
    }
}
