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
@Table(name = "market_prices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketPriceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "open_price")
    private BigDecimal openPrice;

    @Column(name = "high_price")
    private BigDecimal highPrice;

    @Column(name = "low_price")
    private BigDecimal lowPrice;

    @Column(name = "close_price")
    private BigDecimal closePrice;

    @Column(name = "adjusted_close")
    private BigDecimal adjustedClose;

    @Column(name = "reference_price")
    private BigDecimal referencePrice;

    @Column(name = "ceiling_price")
    private BigDecimal ceilingPrice;

    @Column(name = "floor_price")
    private BigDecimal floorPrice;

    @Column(name = "volume")
    private BigDecimal volume;

    @Column(name = "trading_value")
    private BigDecimal tradingValue;

    @Column(name = "foreign_buy_volume")
    private BigDecimal foreignBuyVolume;

    @Column(name = "foreign_sell_volume")
    private BigDecimal foreignSellVolume;

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

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "price_timestamp")
    private Instant priceTimestamp;

    @Column(name = "interval_code")
    private String intervalCode;

    public static MarketPriceEntity create(
            UUID securityId,
            Instant priceTimestamp,
            String intervalCode,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal adjustedClose,
            BigDecimal referencePrice,
            BigDecimal ceilingPrice,
            BigDecimal floorPrice,
            BigDecimal volume,
            BigDecimal tradingValue,
            BigDecimal foreignBuyVolume,
            BigDecimal foreignSellVolume,
            Long dataSourceId,
            UUID rawPayloadId,
            UUID dataVersionId) {
        MarketPriceEntity entity = new MarketPriceEntity();
        entity.securityId = securityId;
        entity.priceTimestamp = priceTimestamp;
        entity.intervalCode = intervalCode;
        entity.dataSourceId = dataSourceId;
        entity.isCanonical = false;
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        entity.applyValues(openPrice, highPrice, lowPrice, closePrice, adjustedClose,
                referencePrice, ceilingPrice, floorPrice, volume, tradingValue,
                foreignBuyVolume, foreignSellVolume, rawPayloadId, dataVersionId, false);
        return entity;
    }

    /** Merges a newer observation so a partial quote does not erase known fields. */
    public boolean applyCorrection(
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal adjustedClose,
            BigDecimal referencePrice,
            BigDecimal ceilingPrice,
            BigDecimal floorPrice,
            BigDecimal volume,
            BigDecimal tradingValue,
            BigDecimal foreignBuyVolume,
            BigDecimal foreignSellVolume,
            UUID rawPayloadId,
            UUID dataVersionId) {
        return applyValues(openPrice, highPrice, lowPrice, closePrice, adjustedClose,
                referencePrice, ceilingPrice, floorPrice, volume, tradingValue,
                foreignBuyVolume, foreignSellVolume, rawPayloadId, dataVersionId, true);
    }

    public void setCanonical(boolean canonical) {
        isCanonical = canonical;
    }

    private boolean applyValues(
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal adjustedClose,
            BigDecimal referencePrice,
            BigDecimal ceilingPrice,
            BigDecimal floorPrice,
            BigDecimal volume,
            BigDecimal tradingValue,
            BigDecimal foreignBuyVolume,
            BigDecimal foreignSellVolume,
            UUID rawPayloadId,
            UUID dataVersionId,
            boolean mergeNulls) {
        boolean changed = false;
        changed |= setIfDifferent(this.openPrice, openPrice, value -> this.openPrice = value, mergeNulls);
        changed |= setIfDifferent(this.highPrice, highPrice, value -> this.highPrice = value, mergeNulls);
        changed |= setIfDifferent(this.lowPrice, lowPrice, value -> this.lowPrice = value, mergeNulls);
        changed |= setIfDifferent(this.closePrice, closePrice, value -> this.closePrice = value, mergeNulls);
        changed |= setIfDifferent(this.adjustedClose, adjustedClose, value -> this.adjustedClose = value, mergeNulls);
        changed |= setIfDifferent(this.referencePrice, referencePrice, value -> this.referencePrice = value, mergeNulls);
        changed |= setIfDifferent(this.ceilingPrice, ceilingPrice, value -> this.ceilingPrice = value, mergeNulls);
        changed |= setIfDifferent(this.floorPrice, floorPrice, value -> this.floorPrice = value, mergeNulls);
        changed |= setIfDifferent(this.volume, volume, value -> this.volume = value, mergeNulls);
        changed |= setIfDifferent(this.tradingValue, tradingValue, value -> this.tradingValue = value, mergeNulls);
        changed |= setIfDifferent(this.foreignBuyVolume, foreignBuyVolume,
                value -> this.foreignBuyVolume = value, mergeNulls);
        changed |= setIfDifferent(this.foreignSellVolume, foreignSellVolume,
                value -> this.foreignSellVolume = value, mergeNulls);
        if (changed || !mergeNulls) {
            this.rawPayloadId = rawPayloadId;
            this.dataVersionId = dataVersionId;
            this.updatedAt = Instant.now();
        }
        return changed;
    }

    private boolean setIfDifferent(BigDecimal current, BigDecimal incoming,
            java.util.function.Consumer<BigDecimal> setter, boolean mergeNulls) {
        if (incoming == null && mergeNulls) return false;
        boolean different = current == null ? incoming != null
                : incoming == null || current.compareTo(incoming) != 0;
        if (different) setter.accept(incoming);
        return different;
    }
}
