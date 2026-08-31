package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "market_prices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketPriceEntity {

    @Id
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

}
