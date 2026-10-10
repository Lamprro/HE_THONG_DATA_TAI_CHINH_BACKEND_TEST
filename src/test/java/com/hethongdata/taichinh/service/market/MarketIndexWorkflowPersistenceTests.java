package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.IndexPriceEntity;
import com.hethongdata.taichinh.entity.MarketIndexEntity;
import com.hethongdata.taichinh.entity.SecurityIndexMembershipEntity;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.IndexPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.MarketIndexJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.SecurityIndexMembershipJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MarketIndexWorkflowPersistenceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final DataVersionJpaRepository versions = mock(DataVersionJpaRepository.class);
    private final RawPayloadJpaRepository rawPayloads = mock(RawPayloadJpaRepository.class);
    private final IngestionRunJpaRepository runs = mock(IngestionRunJpaRepository.class);
    private final DataSourceJpaRepository sources = mock(DataSourceJpaRepository.class);
    private final MarketIndexJpaRepository indices = mock(MarketIndexJpaRepository.class);
    private final IndexPriceJpaRepository prices = mock(IndexPriceJpaRepository.class);
    private final SecurityIndexMembershipJpaRepository memberships = mock(SecurityIndexMembershipJpaRepository.class);
    private final SecurityJpaRepository securities = mock(SecurityJpaRepository.class);
    private final MarketIndexWorkflowPersistenceService writes = new MarketIndexWorkflowPersistenceService(
            versions, rawPayloads, runs, sources, indices, prices, memberships, securities,
            new MarketIndexPayloadParser(), json);

    @Test
    void correctionUpdatesOneSourceRowAndLatestProvenance() throws Exception {
        DataSourceEntity source = source();
        MarketIndexEntity index = MarketIndexEntity.create("VN30", "VN30", "HOSE", "VND", "", false, true);
        ReflectionTestUtils.setField(index, "id", UUID.randomUUID());
        DataVersionEntity first = version();
        DataVersionEntity correction = version();
        RawPayloadEntity raw1 = raw(source, "9");
        RawPayloadEntity raw2 = raw(source, "10");
        when(versions.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(versions.findByIdForUpdate(correction.getId())).thenReturn(Optional.of(correction));
        when(rawPayloads.countByIngestionRunId(first.getIngestionRunId())).thenReturn(1L);
        when(rawPayloads.countByIngestionRunId(correction.getIngestionRunId())).thenReturn(1L);
        when(rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                first.getIngestionRunId(), "INDEX_OHLCV")).thenReturn(List.of(raw1));
        when(rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                correction.getIngestionRunId(), "INDEX_OHLCV")).thenReturn(List.of(raw2));
        when(indices.findByCodeForUpdate("VN30")).thenReturn(Optional.of(index));
        when(sources.findById(7L)).thenReturn(Optional.of(source));
        IndexPriceEntity[] stored = new IndexPriceEntity[1];
        AtomicInteger inserts = new AtomicInteger();
        when(prices.saveAndFlush(any(IndexPriceEntity.class))).thenAnswer(call -> {
            IndexPriceEntity row = call.getArgument(0);
            if (row.getId() == null) {
                ReflectionTestUtils.setField(row, "id", 1L);
                inserts.incrementAndGet();
            }
            stored[0] = row;
            return row;
        });
        when(prices.findByMarketIndexIdAndPriceTimestampAndIntervalCodeAndDataSourceId(
                eq(index.getId()), any(Instant.class), eq("1d"), eq(7L)))
                .thenAnswer(call -> Optional.ofNullable(stored[0]));
        when(prices.findBucketForUpdate(eq(index.getId()), any(Instant.class), eq("1d")))
                .thenAnswer(call -> List.of(stored[0]));

        writes.buildPrices(first.getId(), run(source));
        writes.buildPrices(correction.getId(), run(source));

        assertThat(inserts).hasValue(1);
        assertThat(stored[0].getCloseValue()).isEqualByComparingTo("10");
        assertThat(stored[0].getRawPayloadId()).isEqualTo(raw2.getId());
        assertThat(stored[0].getDataVersionId()).isEqualTo(correction.getId());
        assertThat(stored[0].getIsCanonical()).isTrue();
        assertThat(first.getStatus()).isEqualTo("ACTIVATED");
        assertThat(correction.getStatus()).isEqualTo("ACTIVATED");
    }

    @Test
    void invalidPriceNeverWritesOrActivatesVersion() throws Exception {
        DataSourceEntity source = source();
        DataVersionEntity version = version();
        RawPayloadEntity raw = raw(source, "11");
        when(versions.findByIdForUpdate(version.getId())).thenReturn(Optional.of(version));
        when(rawPayloads.countByIngestionRunId(version.getIngestionRunId())).thenReturn(1L);
        when(rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                version.getIngestionRunId(), "INDEX_OHLCV")).thenReturn(List.of(raw));

        assertThatThrownBy(() -> writes.buildPrices(version.getId(), run(source)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(version.getStatus()).isEqualTo("ACTIVE");
        verifyNoInteractions(prices);
    }

    @Test
    void officialSourceRemainsSoleCanonicalRow() throws Exception {
        DataSourceEntity source = source();
        DataSourceEntity official = DataSourceEntity.create("EXCHANGE", "Exchange", "API",
                null, "exchange", true, "UNKNOWN");
        ReflectionTestUtils.setField(official, "id", 2L);
        MarketIndexEntity index = MarketIndexEntity.create("VN30", "VN30", "HOSE", "VND", "", false, true);
        ReflectionTestUtils.setField(index, "id", UUID.randomUUID());
        DataVersionEntity version = version();
        RawPayloadEntity raw = raw(source, "9");
        IndexPriceEntity officialBar = IndexPriceEntity.create(index.getId(),
                Instant.parse("2026-09-16T17:00:00Z"), "1d",
                new java.math.BigDecimal("8"), new java.math.BigDecimal("10"),
                new java.math.BigDecimal("7"), new java.math.BigDecimal("9"),
                null, null, 2L, UUID.randomUUID(), UUID.randomUUID());
        ReflectionTestUtils.setField(officialBar, "id", 2L);
        officialBar.setCanonical(true);
        when(versions.findByIdForUpdate(version.getId())).thenReturn(Optional.of(version));
        when(rawPayloads.countByIngestionRunId(version.getIngestionRunId())).thenReturn(1L);
        when(rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                version.getIngestionRunId(), "INDEX_OHLCV")).thenReturn(List.of(raw));
        when(indices.findByCodeForUpdate("VN30")).thenReturn(Optional.of(index));
        when(prices.findByMarketIndexIdAndPriceTimestampAndIntervalCodeAndDataSourceId(
                eq(index.getId()), any(Instant.class), eq("1d"), eq(7L)))
                .thenReturn(Optional.empty());
        IndexPriceEntity[] newBar = new IndexPriceEntity[1];
        when(prices.saveAndFlush(any(IndexPriceEntity.class))).thenAnswer(call -> {
            IndexPriceEntity row = call.getArgument(0);
            if (row.getDataSourceId().equals(7L)) newBar[0] = row;
            return row;
        });
        when(prices.findBucketForUpdate(eq(index.getId()), any(Instant.class), eq("1d")))
                .thenAnswer(call -> List.of(officialBar, newBar[0]));
        when(sources.findById(2L)).thenReturn(Optional.of(official));
        when(sources.findById(7L)).thenReturn(Optional.of(source));

        writes.buildPrices(version.getId(), run(source));

        assertThat(officialBar.getIsCanonical()).isTrue();
        assertThat(newBar[0].getIsCanonical()).isFalse();
    }

    @Test
    void nextSnapshotClosesRemovedMemberAndKeepsWeightHistory() throws Exception {
        DataSourceEntity source = source();
        MarketIndexEntity index = MarketIndexEntity.create("VN30", "VN30", "HOSE", "VND", "", false, true);
        ReflectionTestUtils.setField(index, "id", UUID.randomUUID());
        DataVersionEntity first = version();
        DataVersionEntity second = version();
        when(versions.findByIdForUpdate(first.getId())).thenReturn(Optional.of(first));
        when(versions.findByIdForUpdate(second.getId())).thenReturn(Optional.of(second));
        when(rawPayloads.countByIngestionRunId(first.getIngestionRunId())).thenReturn(1L);
        when(rawPayloads.countByIngestionRunId(second.getIngestionRunId())).thenReturn(1L);
        when(rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                first.getIngestionRunId(), "INDEX_MEMBERS"))
                .thenReturn(List.of(rawMembers(source, "2026-09-17",
                        "[{\"symbol\":\"FPT\",\"weight\":0.05},{\"symbol\":\"HPG\",\"weight\":0.1}]")));
        when(rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                second.getIngestionRunId(), "INDEX_MEMBERS"))
                .thenReturn(List.of(rawMembers(source, "2026-09-18",
                        "[{\"symbol\":\"FPT\",\"weight\":0.06},{\"symbol\":\"VCB\",\"weight\":0.2}]")));
        when(indices.findByCodeForUpdate("VN30")).thenReturn(Optional.of(index));
        SecurityEntity fpt = security("FPT"), hpg = security("HPG"), vcb = security("VCB");
        when(securities.findBySymbolIgnoreCase("FPT")).thenReturn(Optional.of(fpt));
        when(securities.findBySymbolIgnoreCase("HPG")).thenReturn(Optional.of(hpg));
        when(securities.findBySymbolIgnoreCase("VCB")).thenReturn(Optional.of(vcb));
        List<SecurityIndexMembershipEntity> stored = new ArrayList<>();
        when(memberships.findOpenForUpdate(index.getId())).thenAnswer(call -> stored.stream()
                .filter(row -> row.getEffectiveTo() == null).toList());
        when(memberships.findTopByMarketIndexIdOrderByEffectiveFromDesc(index.getId()))
                .thenAnswer(call -> stored.stream()
                        .max(java.util.Comparator.comparing(SecurityIndexMembershipEntity::getEffectiveFrom)));
        when(memberships.findByMarketIndexIdAndSecurityIdAndEffectiveFrom(
                eq(index.getId()), any(UUID.class), any(LocalDate.class))).thenAnswer(call -> stored.stream()
                        .filter(row -> row.getSecurityId().equals(call.getArgument(1))
                                && row.getEffectiveFrom().equals(call.getArgument(2)))
                        .findFirst());
        when(memberships.save(any(SecurityIndexMembershipEntity.class))).thenAnswer(call -> {
            SecurityIndexMembershipEntity row = call.getArgument(0);
            if (!stored.contains(row)) stored.add(row);
            return row;
        });
        when(memberships.saveAndFlush(any(SecurityIndexMembershipEntity.class))).thenAnswer(call -> {
            SecurityIndexMembershipEntity row = call.getArgument(0);
            if (!stored.contains(row)) stored.add(row);
            return row;
        });

        writes.buildMemberships(first.getId(), run(source));
        writes.buildMemberships(second.getId(), run(source));

        assertThat(stored).hasSize(4);
        assertThat(stored.stream().filter(row -> row.getSecurityId().equals(fpt.getId())).toList())
                .extracting(SecurityIndexMembershipEntity::getEffectiveFrom)
                .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 18));
        assertThat(stored.stream().filter(row -> row.getSecurityId().equals(hpg.getId())).findFirst()
                .orElseThrow().getEffectiveTo()).isEqualTo(LocalDate.of(2026, 9, 17));
        assertThat(stored.stream().filter(row -> row.getSecurityId().equals(vcb.getId())).findFirst()
                .orElseThrow().getEffectiveTo()).isNull();
        assertThat(first.getStatus()).isEqualTo("ACTIVATED");
        assertThat(second.getStatus()).isEqualTo("ACTIVATED");
    }

    private DataSourceEntity source() {
        var source = DataSourceEntity.create("VNSTOCK", "VnStock", "API", null,
                "vnstock", false, "UNKNOWN");
        ReflectionTestUtils.setField(source, "id", 7L);
        return source;
    }

    private DataVersionEntity version() {
        var version = DataVersionEntity.acceptedForRun("MARKET_INDEX", UUID.randomUUID(), 1, "checksum");
        ReflectionTestUtils.setField(version, "id", UUID.randomUUID());
        return version;
    }

    private IngestionRunEntity run(DataSourceEntity source) {
        var run = IngestionRunEntity.start(source, null, "MANUAL", json.createObjectNode(),
                URI.create("internal://ingestion/index-price-build"), json.createObjectNode(), Instant.now());
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        return run;
    }

    private RawPayloadEntity raw(DataSourceEntity source, String close) throws Exception {
        String body = "{\"symbol\":\"VN30\",\"data\":[{\"date\":\"2026-09-17\","
                + "\"open\":8,\"high\":10,\"low\":7,\"close\":" + close + "}]}";
        var raw = RawPayloadEntity.create(run(source), source, "vnstock:index:VN30",
                "INDEX_OHLCV", "VN30", null, "application/json", json.readTree(body),
                null, "checksum", Instant.now(), null);
        ReflectionTestUtils.setField(raw, "id", UUID.randomUUID());
        return raw;
    }

    private RawPayloadEntity rawMembers(DataSourceEntity source, String date, String members)
            throws Exception {
        var raw = RawPayloadEntity.create(run(source), source, "vnstock:members:VN30",
                "INDEX_MEMBERS", "VN30", null, "application/json",
                json.readTree("{\"symbol\":\"VN30\",\"snapshot_date\":\"" + date
                        + "\",\"data\":" + members + "}"), null, "checksum", Instant.now(), null);
        ReflectionTestUtils.setField(raw, "id", UUID.randomUUID());
        return raw;
    }

    private SecurityEntity security(String symbol) {
        var security = SecurityEntity.create(UUID.randomUUID(), symbol, "HOSE", "STOCK", null,
                "VND", null, null, null, null, false, true);
        ReflectionTestUtils.setField(security, "id", UUID.randomUUID());
        return security;
    }
}
