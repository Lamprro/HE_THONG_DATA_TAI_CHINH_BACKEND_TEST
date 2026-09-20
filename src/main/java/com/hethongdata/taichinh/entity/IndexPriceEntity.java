package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "index_prices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IndexPriceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "market_index_id")
    private UUID marketIndexId;

    @Column(name = "open_value")
    private BigDecimal openValue;

    @Column(name = "high_value")
    private BigDecimal highValue;

    @Column(name = "low_value")
    private BigDecimal lowValue;

    @Column(name = "close_value")
    private BigDecimal closeValue;

    @Column(name = "volume")
    private BigDecimal volume;

    @Column(name = "trading_value")
    private BigDecimal tradingValue;

    @Column(name = "data_source_id")
    private Long dataSourceId;

    @Column(name = "raw_payload_id")
    private UUID rawPayloadId;

    @Column(name = "data_version_id")
    private UUID dataVersionId;

    @Column(name = "is_canonical")
    private Boolean isCanonical;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "price_timestamp")
    private Instant priceTimestamp;

    @Column(name = "interval_code")
    private String intervalCode;

    public static IndexPriceEntity create(
            UUID marketIndexId,
            Instant priceTimestamp,
            String intervalCode,
            BigDecimal openValue,
            BigDecimal highValue,
            BigDecimal lowValue,
            BigDecimal closeValue,
            BigDecimal volume,
            BigDecimal tradingValue,
            Long dataSourceId,
            UUID rawPayloadId,
            UUID dataVersionId) {
        IndexPriceEntity entity = new IndexPriceEntity();
        entity.marketIndexId = marketIndexId;
        entity.priceTimestamp = priceTimestamp;
        entity.intervalCode = intervalCode;
        entity.applyValues(
                openValue, highValue, lowValue, closeValue, volume, tradingValue,
                rawPayloadId, dataVersionId);
        entity.dataSourceId = dataSourceId;
        entity.isCanonical = false;
        entity.createdAt = Instant.now();
        return entity;
    }

    public void applyCorrection(
            BigDecimal openValue,
            BigDecimal highValue,
            BigDecimal lowValue,
            BigDecimal closeValue,
            BigDecimal volume,
            BigDecimal tradingValue,
            UUID rawPayloadId,
            UUID dataVersionId) {
        applyValues(
                openValue, highValue, lowValue, closeValue, volume, tradingValue,
                rawPayloadId, dataVersionId);
    }

    public void setCanonical(boolean canonical) {
        this.isCanonical = canonical;
    }

    private void applyValues(
            BigDecimal openValue,
            BigDecimal highValue,
            BigDecimal lowValue,
            BigDecimal closeValue,
            BigDecimal volume,
            BigDecimal tradingValue,
            UUID rawPayloadId,
            UUID dataVersionId) {
        this.openValue = openValue;
        this.highValue = highValue;
        this.lowValue = lowValue;
        this.closeValue = closeValue;
        this.volume = volume;
        this.tradingValue = tradingValue;
        this.rawPayloadId = rawPayloadId;
        this.dataVersionId = dataVersionId;
    }
}
