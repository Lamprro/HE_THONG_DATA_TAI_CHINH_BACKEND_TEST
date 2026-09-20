package com.hethongdata.taichinh.repository.jpa.market;

import com.hethongdata.taichinh.entity.IndexPriceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IndexPriceJpaRepository extends JpaRepository<IndexPriceEntity, Long> {
    Optional<IndexPriceEntity> findByMarketIndexIdAndPriceTimestampAndIntervalCodeAndDataSourceId(
            UUID marketIndexId, Instant priceTimestamp, String intervalCode, Long dataSourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select price from IndexPriceEntity price where price.marketIndexId = :indexId "
            + "and price.priceTimestamp = :timestamp and price.intervalCode = :interval")
    List<IndexPriceEntity> findBucketForUpdate(
            @Param("indexId") UUID indexId,
            @Param("timestamp") Instant timestamp,
            @Param("interval") String interval);

    Page<IndexPriceEntity> findByMarketIndexIdAndIsCanonicalTrueOrderByPriceTimestampDesc(
            UUID marketIndexId, Pageable pageable);
}
