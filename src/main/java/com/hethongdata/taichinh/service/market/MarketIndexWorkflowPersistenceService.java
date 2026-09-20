package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.IndexPriceEntity;
import com.hethongdata.taichinh.entity.MarketIndexEntity;
import com.hethongdata.taichinh.entity.SecurityIndexMembershipEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.IndexPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.MarketIndexJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.SecurityIndexMembershipJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Database commit boundary for the two market-index build jobs. */
@Service
public class MarketIndexWorkflowPersistenceService {
    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunJpaRepository runs;
    private final DataSourceJpaRepository sources;
    private final MarketIndexJpaRepository indices;
    private final IndexPriceJpaRepository prices;
    private final SecurityIndexMembershipJpaRepository memberships;
    private final SecurityJpaRepository securities;
    private final MarketIndexPayloadParser parser;
    private final ObjectMapper mapper;

    public MarketIndexWorkflowPersistenceService(
            DataVersionJpaRepository versions, RawPayloadJpaRepository rawPayloads,
            IngestionRunJpaRepository runs, DataSourceJpaRepository sources, MarketIndexJpaRepository indices,
            IndexPriceJpaRepository prices, SecurityIndexMembershipJpaRepository memberships,
            SecurityJpaRepository securities, MarketIndexPayloadParser parser, ObjectMapper mapper) {
        this.versions = versions;
        this.rawPayloads = rawPayloads;
        this.runs = runs;
        this.sources = sources;
        this.indices = indices;
        this.prices = prices;
        this.memberships = memberships;
        this.securities = securities;
        this.parser = parser;
        this.mapper = mapper;
    }

    @Transactional
    public int buildPrices(UUID versionId, IngestionRunEntity run) {
        DataVersionEntity version = activeVersion(versionId);
        List<RawPayloadEntity> payloads = rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                version.getIngestionRunId(), "INDEX_OHLCV");
        if (payloads.isEmpty()) throw new IllegalStateException("Version không có raw INDEX_OHLCV");
        if (payloads.size() != rawPayloads.countByIngestionRunId(version.getIngestionRunId())) {
            throw new IllegalStateException("Một data version chứa nhiều loại raw payload");
        }
        int inserted = 0, updated = 0, fetched = 0;
        for (RawPayloadEntity raw : payloads) {
            var batch = parser.prices(raw.getPayload(), raw.getSourceSymbol());
            // This master-row lock serializes concurrent writers before their first source-row insert.
            MarketIndexEntity index = lockedIndex(batch.indexCode());
            for (var bar : batch.rows()) {
                fetched++;
                var existing = prices.findByMarketIndexIdAndPriceTimestampAndIntervalCodeAndDataSourceId(
                        index.getId(), bar.timestamp(), bar.interval(), raw.getDataSource().getId());
                if (existing.isPresent()) {
                    UUID previousRawId = existing.get().getRawPayloadId();
                    boolean stale = previousRawId != null && rawPayloads.findById(previousRawId)
                            .map(previous -> previous.getFetchedAt().isAfter(raw.getFetchedAt()))
                            .orElse(false);
                    if (!stale) {
                        existing.get().applyCorrection(bar.open(), bar.high(), bar.low(), bar.close(),
                                bar.volume(), bar.tradingValue(), raw.getId(), version.getId());
                        prices.saveAndFlush(existing.get());
                        updated++;
                    }
                } else {
                    prices.saveAndFlush(IndexPriceEntity.create(index.getId(), bar.timestamp(), bar.interval(),
                            bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
                            bar.tradingValue(), raw.getDataSource().getId(), raw.getId(), version.getId()));
                    inserted++;
                }
                reconcileCanonical(index.getId(), bar.timestamp(), bar.interval());
            }
        }
        run.markWorkflowSuccess(mapper.valueToTree(Map.of(
                "workflow", "INDEX_PRICE_BUILD", "sourceVersionId", versionId)),
                Instant.now(), fetched, inserted, updated);
        runs.save(run);
        version.markActivated();
        versions.save(version);
        return fetched;
    }

    @Transactional
    public int buildMemberships(UUID versionId, IngestionRunEntity run) {
        DataVersionEntity version = activeVersion(versionId);
        List<RawPayloadEntity> payloads = rawPayloads.findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
                version.getIngestionRunId(), "INDEX_MEMBERS");
        if (payloads.isEmpty()) throw new IllegalStateException("Version không có raw INDEX_MEMBERS");
        if (payloads.size() != rawPayloads.countByIngestionRunId(version.getIngestionRunId())) {
            throw new IllegalStateException("Một data version chứa nhiều loại raw payload");
        }
        int inserted = 0, updated = 0, fetched = 0;
        for (RawPayloadEntity raw : payloads) {
            var snapshot = parser.members(raw.getPayload(), raw.getSourceSymbol(), raw.getFetchedAt());
            MarketIndexEntity index = lockedIndex(snapshot.indexCode());
            LocalDate date = snapshot.snapshotDate();
            var newest = memberships.findTopByMarketIndexIdOrderByEffectiveFromDesc(index.getId());
            if (newest.isPresent() && newest.get().getEffectiveFrom().isAfter(date)) {
                throw new IllegalArgumentException("Snapshot cũ hơn lịch sử membership đã xử lý");
            }
            Map<UUID, BigDecimal> desired = new HashMap<>();
            for (var member : snapshot.rows()) {
                SecurityEntity security = securities.findBySymbolIgnoreCase(member.symbol())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Chưa có securities.symbol: " + member.symbol()));
                desired.put(security.getId(), member.weight());
            }
            fetched += desired.size();
            Map<UUID, SecurityIndexMembershipEntity> open = new HashMap<>();
            for (SecurityIndexMembershipEntity row : memberships.findOpenForUpdate(index.getId())) {
                if (open.putIfAbsent(row.getSecurityId(), row) != null) {
                    throw new IllegalStateException("Nhiều membership đang mở cho cùng index/security");
                }
            }
            for (var entry : desired.entrySet()) {
                UUID securityId = entry.getKey();
                BigDecimal weight = entry.getValue();
                SecurityIndexMembershipEntity current = open.remove(securityId);
                if (current == null) {
                    var sameDay = memberships.findByMarketIndexIdAndSecurityIdAndEffectiveFrom(
                            index.getId(), securityId, date);
                    if (sameDay.isPresent()) {
                        sameDay.get().reopenSameDay(weight);
                        memberships.save(sameDay.get());
                        updated++;
                    } else {
                        memberships.save(SecurityIndexMembershipEntity.open(
                                securityId, index.getId(), date, weight));
                        inserted++;
                    }
                } else if (!equalWeight(current.getWeight(), weight)) {
                    if (current.getEffectiveFrom().isAfter(date)) {
                        throw new IllegalArgumentException("Snapshot cũ hơn membership đang mở");
                    }
                    if (current.getEffectiveFrom().equals(date)) {
                        current.correctSameDay(weight);
                        memberships.save(current);
                        updated++;
                    } else {
                        current.close(date.minusDays(1));
                        memberships.saveAndFlush(current);
                        memberships.save(SecurityIndexMembershipEntity.open(
                                securityId, index.getId(), date, weight));
                        inserted++;
                        updated++;
                    }
                }
            }
            for (SecurityIndexMembershipEntity removed : open.values()) {
                if (!removed.getEffectiveFrom().isBefore(date)) {
                    throw new IllegalArgumentException("Không thể đóng membership trong chính ngày mở; cần đối soát snapshot");
                }
                removed.close(date.minusDays(1));
                memberships.save(removed);
                updated++;
            }
        }
        run.markWorkflowSuccess(mapper.valueToTree(Map.of(
                "workflow", "INDEX_MEMBERSHIP_BUILD", "sourceVersionId", versionId)),
                Instant.now(), fetched, inserted, updated);
        runs.save(run);
        version.markActivated();
        versions.save(version);
        return fetched;
    }

    @Transactional
    public void noWork(IngestionRunEntity run, String workflow) {
        run.markWorkflowSuccess(mapper.valueToTree(Map.of("workflow", workflow, "noWork", true)),
                Instant.now(), 0, 0, 0);
        runs.save(run);
    }

    private DataVersionEntity activeVersion(UUID id) {
        DataVersionEntity version = versions.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy data version"));
        if (!"MARKET_INDEX".equals(version.getDataDomain()) || !"ACTIVE".equals(version.getStatus())) {
            throw new IllegalStateException("Data version không còn ACTIVE thuộc MARKET_INDEX");
        }
        return version;
    }

    private MarketIndexEntity lockedIndex(String code) {
        return indices.findByCodeForUpdate(code)
                .orElseThrow(() -> new IllegalArgumentException("Chưa có market_indices.code: " + code));
    }

    private void reconcileCanonical(UUID indexId, Instant timestamp, String interval) {
        List<IndexPriceEntity> bucket = prices.findBucketForUpdate(indexId, timestamp, interval);
        if (bucket.isEmpty()) throw new IllegalStateException("Không tìm thấy nến chỉ số vừa ghi");
        Map<Long, DataSourceEntity> sourceById = new HashMap<>();
        for (IndexPriceEntity row : bucket) {
            sourceById.computeIfAbsent(row.getDataSourceId(), this::source);
        }
        Comparator<IndexPriceEntity> preference = Comparator
                .comparing((IndexPriceEntity row) -> !sourceById.get(row.getDataSourceId()).isOfficial())
                .thenComparing(row -> sourceById.get(row.getDataSourceId()).getPriority())
                .thenComparing(IndexPriceEntity::getDataSourceId);
        IndexPriceEntity winner = bucket.stream().min(preference).orElseThrow();
        for (IndexPriceEntity row : bucket) row.setCanonical(false);
        prices.saveAllAndFlush(bucket);
        winner.setCanonical(true);
        prices.saveAndFlush(winner);
    }

    private DataSourceEntity source(Long sourceId) {
        return sources.findById(sourceId)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy data source: " + sourceId));
    }

    private boolean equalWeight(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }
}
