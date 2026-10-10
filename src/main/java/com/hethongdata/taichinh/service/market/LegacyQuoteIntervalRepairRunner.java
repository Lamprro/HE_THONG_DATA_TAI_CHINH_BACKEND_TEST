package com.hethongdata.taichinh.service.market;

import com.hethongdata.taichinh.entity.MarketPriceEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;
import com.hethongdata.taichinh.repository.jpa.market.MarketPriceJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Tiện ích sửa lỗi dữ liệu lịch sử chạy một lần (one-shot: đổi nhãn 15m thành snapshot), không thuộc luồng thu thập runtime. */
@Component
@ConditionalOnProperty(prefix = "financial.market-price", name = "repair-legacy-quote-intervals", havingValue = "true")
public class LegacyQuoteIntervalRepairRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyQuoteIntervalRepairRunner.class);

    private final MarketPriceJpaRepository prices;
    private final RawPayloadJpaRepository rawPayloads;
    private final MarketPricePayloadParser parser;

    public LegacyQuoteIntervalRepairRunner(MarketPriceJpaRepository prices,
            RawPayloadJpaRepository rawPayloads, MarketPricePayloadParser parser) {
        this.prices = prices;
        this.rawPayloads = rawPayloads;
        this.parser = parser;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<MarketPriceEntity> candidates = prices.findByIntervalCodeOrderByPriceTimestampAsc("15m");
        List<MarketPriceEntity> repaired = new ArrayList<>();
        int skipped = 0;
        for (MarketPriceEntity price : candidates) {
            var raw = price.getRawPayloadId() == null ? null
                    : rawPayloads.findById(price.getRawPayloadId()).orElse(null);
            if (raw == null || !"QUOTE".equalsIgnoreCase(raw.getEntityType())) {
                skipped++;
                continue;
            }
            try {
                var batch = parser.parse(raw.getPayload(), raw.getEntityType(), raw.getSourceSymbol(), raw.getFetchedAt());
                if (batch.rows().size() != 1) {
                    skipped++;
                    continue;
                }
                var observation = batch.rows().get(0);
                Long sourceId = raw.getDataSource().getId();
                if (!"snapshot".equals(observation.interval())
                        || prices.existsBySecurityIdAndPriceTimestampAndIntervalCodeAndDataSourceIdAndIdNot(
                                price.getSecurityId(), observation.timestamp(), "snapshot", sourceId, price.getId())) {
                    skipped++;
                    continue;
                }
                price.reclassifyAsSnapshot(observation.timestamp());
                repaired.add(price);
            } catch (RuntimeException exception) {
                skipped++;
                LOGGER.warn("Skipping legacy quote row id={} because its source payload could not be parsed", price.getId());
            }
        }
        prices.saveAllAndFlush(repaired);
        LOGGER.info("Legacy quote interval repair completed: candidates={}, repaired={}, skipped={}",
                candidates.size(), repaired.size(), skipped);
    }
}
