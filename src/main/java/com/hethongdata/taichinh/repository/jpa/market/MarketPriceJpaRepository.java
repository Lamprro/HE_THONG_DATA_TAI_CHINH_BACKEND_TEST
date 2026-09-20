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
    Optional<MarketPriceEntity> findBySecurityIdAndPriceTimestampAndIntervalCodeAndDataSourceId(
            UUID securityId, Instant timestamp, String intervalCode, Long dataSourceId);

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
}
