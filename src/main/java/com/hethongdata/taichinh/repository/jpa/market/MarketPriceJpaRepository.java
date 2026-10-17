package com.hethongdata.taichinh.repository.jpa.market;

import com.hethongdata.taichinh.entity.MarketPriceEntity;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MarketPriceJpaRepository extends JpaRepository<MarketPriceEntity, Long> {
    /** Foreign volumes keep their own lineage when a later price batch adds the same session. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically=true)
    @Query(value="""
UPDATE market_prices m SET foreign_buy_volume=f.buy_volume,foreign_sell_volume=f.sell_volume,
 foreign_data_source_id=f.data_source_id,foreign_raw_payload_id=f.raw_payload_id,foreign_data_version_id=f.data_version_id
FROM security_foreign_observations f WHERE m.security_id=:securityId AND f.security_id=m.security_id
AND m.interval_code='1d' AND f.is_canonical AND (m.price_timestamp AT TIME ZONE 'Asia/Ho_Chi_Minh')::date=f.trading_date
AND (m.foreign_raw_payload_id IS DISTINCT FROM f.raw_payload_id OR m.foreign_buy_volume IS DISTINCT FROM f.buy_volume OR m.foreign_sell_volume IS DISTINCT FROM f.sell_volume)
""",nativeQuery=true)
    int linkKnownForeignObservations(@Param("securityId") UUID securityId);
    List<MarketPriceEntity> findBySecurityIdAndIntervalCodeAndPriceTimestampBetween(
            UUID securityId, String intervalCode, Instant start, Instant end);
    Optional<MarketPriceEntity> findBySecurityIdAndPriceTimestampAndIntervalCodeAndDataSourceId(
            UUID securityId, Instant timestamp, String intervalCode, Long dataSourceId);

    boolean existsBySecurityIdAndPriceTimestampAndIntervalCodeAndDataSourceIdAndIdNot(
            UUID securityId, Instant timestamp, String intervalCode, Long dataSourceId, Long id);

    List<MarketPriceEntity> findByIntervalCodeOrderByPriceTimestampAsc(String intervalCode);

    Optional<MarketPriceEntity> findTopBySecurityIdAndIntervalCodeAndDataSourceIdOrderByPriceTimestampDesc(
            UUID securityId, String intervalCode, Long dataSourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select price from MarketPriceEntity price where price.securityId = :securityId "
            + "and price.priceTimestamp = :timestamp and price.intervalCode = :interval")
    List<MarketPriceEntity> findBucketForUpdate(
            @Param("securityId") UUID securityId,
            @Param("timestamp") Instant timestamp,
            @Param("interval") String interval);

    Page<MarketPriceEntity> findBySecurityIdAndIsCanonicalTrueOrderByPriceTimestampDesc(
            UUID securityId, Pageable pageable);

    Page<MarketPriceEntity> findBySecurityIdAndIntervalCodeAndIsCanonicalTrueOrderByPriceTimestampDesc(
            UUID securityId, String intervalCode, Pageable pageable);
}
