package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
}
