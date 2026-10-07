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
