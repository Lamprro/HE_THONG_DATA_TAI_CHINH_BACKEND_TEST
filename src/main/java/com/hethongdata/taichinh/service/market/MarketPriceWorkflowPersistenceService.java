package com.hethongdata.taichinh.service.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.entity.MarketPriceEntity;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;
import com.hethongdata.taichinh.entity.validation.DataVersionEntity;
import com.hethongdata.taichinh.repository.jpa.market.MarketPriceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.master.SecurityJpaRepository;
import com.hethongdata.taichinh.repository.jpa.validation.DataVersionJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Atomic normalized write boundary for accepted QUOTE and OHLCV versions. */
@Service
public class MarketPriceWorkflowPersistenceService {
    private final DataVersionJpaRepository versions;
    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunJpaRepository runs;
    private final DataSourceJpaRepository sources;
    private final SecurityJpaRepository securities;
    private final MarketPriceJpaRepository prices;
    private final MarketPricePayloadParser parser;
    private final ObjectMapper mapper;

    public MarketPriceWorkflowPersistenceService(
            DataVersionJpaRepository versions, RawPayloadJpaRepository rawPayloads,
            IngestionRunJpaRepository runs, DataSourceJpaRepository sources,
            SecurityJpaRepository securities, MarketPriceJpaRepository prices,
            MarketPricePayloadParser parser, ObjectMapper mapper) {
        this.versions = versions;
        this.rawPayloads = rawPayloads;
        this.runs = runs;
        this.sources = sources;
        this.securities = securities;
        this.prices = prices;
        this.parser = parser;
        this.mapper = mapper;
    }

    @Transactional
    public int build(UUID versionId, IngestionRunEntity run) {
        DataVersionEntity version = activeVersion(versionId);
        List<RawPayloadEntity> payloads = rawPayloads.findByIngestionRunIdOrderByFetchedAtDesc(
                version.getIngestionRunId());
        if (payloads.isEmpty()) throw new IllegalStateException("Data version không có raw payload");
        if (payloads.stream().anyMatch(raw -> !isMarketPriceType(raw.getEntityType()))) {
            throw new IllegalStateException("Data version MARKET_PRICE chứa loại payload không phù hợp");
        }
        int inserted = 0, updated = 0, fetched = 0, skipped = 0;
        for (RawPayloadEntity raw : payloads) {
            var batch = parser.parse(raw.getPayload(), raw.getEntityType(),
                    raw.getSourceSymbol(), raw.getFetchedAt());
            SecurityEntity security = lockedSecurity(raw, batch.symbol());
            if (batch.rows().size() >= 100 && batch.rows().stream().allMatch(row -> "1d".equals(row.interval()))) {
                int[] counts = buildDailyBatch(raw, versionId, security.getId(), batch.rows());
                fetched += batch.rows().size(); inserted += counts[0]; updated += counts[1]; skipped += counts[2];
                continue;
            }
            for (var row : batch.rows()) {
                fetched++;
                Instant storageTimestamp = storageTimestamp(
                        security.getId(), raw.getDataSource().getId(), row);
                var existing = prices.findBySecurityIdAndPriceTimestampAndIntervalCodeAndDataSourceId(
                        security.getId(), storageTimestamp, row.interval(), raw.getDataSource().getId());
                if (existing.isPresent()) {
                    UUID priorRawId = existing.get().getRawPayloadId();
                    boolean stale = priorRawId != null && rawPayloads.findById(priorRawId)
                            .map(previous -> previous.getFetchedAt().isAfter(raw.getFetchedAt()))
                            .orElse(false);
                    if (stale) {
                        skipped++;
                    } else if (existing.get().applyCorrection(row.open(), row.high(), row.low(),
                            row.close(), row.adjustedClose(), row.referencePrice(),
                            row.ceilingPrice(), row.floorPrice(), row.volume(), row.tradingValue(),
                            row.foreignBuyVolume(), row.foreignSellVolume(), raw.getId(), versionId)) {
                        prices.saveAndFlush(existing.get());
                        updated++;
                    } else {
                        skipped++;
                    }
                } else {
                    prices.saveAndFlush(MarketPriceEntity.create(security.getId(), storageTimestamp,
                            row.interval(), row.open(), row.high(), row.low(), row.close(),
                            row.adjustedClose(), row.referencePrice(), row.ceilingPrice(),
                            row.floorPrice(), row.volume(), row.tradingValue(),
                            row.foreignBuyVolume(), row.foreignSellVolume(),
                            raw.getDataSource().getId(), raw.getId(), versionId));
                    inserted++;
                }
                reconcileCanonical(security.getId(), storageTimestamp, row.interval());
            }
        }
        run.markWorkflowSuccess(mapper.valueToTree(Map.of(
                "workflow", MarketPriceWorkflowService.WORKFLOW,
                "sourceVersionId", versionId, "skipped", skipped)),
                Instant.now(), fetched, inserted, updated);
        runs.save(run);
        version.markActivated();
        versions.save(version);
        return fetched;
    }

    @Transactional
    public void noWork(IngestionRunEntity run) {
        run.markWorkflowSuccess(mapper.valueToTree(Map.of(
                "workflow", MarketPriceWorkflowService.WORKFLOW, "noWork", true)),
                Instant.now(), 0, 0, 0);
        runs.save(run);
    }

    private DataVersionEntity activeVersion(UUID id) {
        DataVersionEntity version = versions.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy data version"));
        if (!"MARKET_PRICE".equals(version.getDataDomain()) || !"ACTIVE".equals(version.getStatus())) {
            throw new IllegalStateException("Data version không còn ACTIVE thuộc MARKET_PRICE");
        }
        return version;
    }

    private SecurityEntity lockedSecurity(RawPayloadEntity raw, String symbol) {
        SecurityEntity found = raw.getSecurityId() == null
                ? securities.findBySymbolIgnoreCase(symbol)
                    .orElseThrow(() -> new IllegalArgumentException("Chưa có securities.symbol: " + symbol))
                : securities.findById(raw.getSecurityId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy security_id của raw payload"));
        if (!found.getSymbol().equalsIgnoreCase(symbol)) {
            throw new IllegalArgumentException("security_id không khớp symbol của payload");
        }
        return securities.findByIdForUpdate(found.getId()).orElseThrow();
    }

    private void reconcileCanonical(UUID securityId, Instant timestamp, String interval) {
        List<MarketPriceEntity> bucket = prices.findBucketForUpdate(securityId, timestamp, interval);
        if (bucket.isEmpty()) throw new IllegalStateException("Không tìm thấy giá vừa ghi");
        Map<Long, DataSourceEntity> sourceById = new HashMap<>();
        for (MarketPriceEntity row : bucket) {
            sourceById.computeIfAbsent(row.getDataSourceId(), this::source);
        }
        Comparator<MarketPriceEntity> preference = Comparator
                .comparing((MarketPriceEntity row) -> !sourceById.get(row.getDataSourceId()).isOfficial())
                .thenComparing(row -> sourceById.get(row.getDataSourceId()).getPriority())
                .thenComparing(MarketPriceEntity::getDataSourceId);
        MarketPriceEntity winner = bucket.stream().min(preference).orElseThrow();
        for (MarketPriceEntity row : bucket) row.setCanonical(false);
        prices.saveAllAndFlush(bucket);
        winner.setCanonical(true);
        prices.saveAndFlush(winner);
    }

    /** The security row is already locked across all writers. Load daily buckets once for backfill. */
    private int[] buildDailyBatch(RawPayloadEntity raw, UUID versionId, UUID securityId,
            List<MarketPricePayloadParser.PriceRow> rows) {
        Instant start=rows.stream().map(MarketPricePayloadParser.PriceRow::timestamp).min(Instant::compareTo).orElseThrow();
        Instant end=rows.stream().map(MarketPricePayloadParser.PriceRow::timestamp).max(Instant::compareTo).orElseThrow();
        Map<Instant,List<MarketPriceEntity>> buckets=new HashMap<>();
        for (var existing:prices.findBySecurityIdAndIntervalCodeAndPriceTimestampBetween(securityId,"1d",start,end))
            buckets.computeIfAbsent(existing.getPriceTimestamp(),ignored->new java.util.ArrayList<>()).add(existing);
        Map<Long,DataSourceEntity> sourceCache=new HashMap<>();
        Map<UUID,Instant> fetchedCache=new HashMap<>();
        int inserted=0,updated=0,skipped=0;
        for (var row:rows) {
            var bucket=buckets.computeIfAbsent(row.timestamp(),ignored->new java.util.ArrayList<>());
            var sameSource=bucket.stream().filter(p->p.getDataSourceId().equals(raw.getDataSource().getId())).findFirst();
            if (sameSource.isEmpty()) {
                var created=MarketPriceEntity.create(securityId,row.timestamp(),row.interval(),row.open(),row.high(),row.low(),
                        row.close(),row.adjustedClose(),row.referencePrice(),row.ceilingPrice(),row.floorPrice(),row.volume(),
                        row.tradingValue(),row.foreignBuyVolume(),row.foreignSellVolume(),raw.getDataSource().getId(),raw.getId(),versionId);
                // A new empty bucket has no canonical row to displace; one INSERT is sufficient.
                if (bucket.isEmpty()) created.setCanonical(true);
                bucket.add(prices.saveAndFlush(created));inserted++;
            } else {
                var existing=sameSource.get();UUID priorRaw=existing.getRawPayloadId();
                Instant previous=priorRaw==null?null:fetchedCache.computeIfAbsent(priorRaw,
                        id->rawPayloads.findById(id).map(RawPayloadEntity::getFetchedAt).orElse(Instant.MIN));
                if (previous!=null && previous.isAfter(raw.getFetchedAt())) skipped++;
                else if (existing.applyCorrection(row.open(),row.high(),row.low(),row.close(),row.adjustedClose(),
                        row.referencePrice(),row.ceilingPrice(),row.floorPrice(),row.volume(),row.tradingValue(),
                        row.foreignBuyVolume(),row.foreignSellVolume(),raw.getId(),versionId)) {
                    prices.saveAndFlush(existing);updated++;
                } else skipped++;
            }
            for (var p:bucket) sourceCache.computeIfAbsent(p.getDataSourceId(),this::source);
            var winner=bucket.stream().min(Comparator
                    .comparing((MarketPriceEntity p)->!sourceCache.get(p.getDataSourceId()).isOfficial())
                    .thenComparing(p->sourceCache.get(p.getDataSourceId()).getPriority())
                    .thenComparing(MarketPriceEntity::getDataSourceId)).orElseThrow();
            var displaced=bucket.stream().filter(p->p!=winner && Boolean.TRUE.equals(p.getIsCanonical())).toList();
            if (!displaced.isEmpty()) {
                displaced.forEach(p->p.setCanonical(false));prices.saveAllAndFlush(displaced);
            }
            if (!Boolean.TRUE.equals(winner.getIsCanonical())) {
                winner.setCanonical(true);prices.saveAndFlush(winner);
            }
        }
        return new int[]{inserted,updated,skipped};
    }

    private Instant storageTimestamp(UUID securityId, Long sourceId,
            MarketPricePayloadParser.PriceRow row) {
        if (!"15m".equals(row.interval())) return row.timestamp();
        var latest = prices.findTopBySecurityIdAndIntervalCodeAndDataSourceIdOrderByPriceTimestampDesc(
                securityId, row.interval(), sourceId);
        if (latest.isPresent() && !row.timestamp().isBefore(latest.get().getPriceTimestamp())
                && Duration.between(latest.get().getPriceTimestamp(), row.timestamp()).toMinutes() < 15) {
            return latest.get().getPriceTimestamp();
        }
        Instant minute = row.timestamp().truncatedTo(ChronoUnit.MINUTES);
        long minuteOfHour = minute.atZone(MarketPricePayloadParser.VIETNAM_ZONE).getMinute();
        return minute.minus(minuteOfHour % 15, ChronoUnit.MINUTES);
    }

    private DataSourceEntity source(Long sourceId) {
        return sources.findById(sourceId)
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy data source: " + sourceId));
    }

    private boolean isMarketPriceType(String value) {
        return "QUOTE".equalsIgnoreCase(value) || "OHLCV".equalsIgnoreCase(value);
    }
}
