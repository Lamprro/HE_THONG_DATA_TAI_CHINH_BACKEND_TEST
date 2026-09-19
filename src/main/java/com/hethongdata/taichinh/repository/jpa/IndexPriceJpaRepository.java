package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.IndexPriceEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IndexPriceJpaRepository extends JpaRepository<IndexPriceEntity, Long> {
    Optional<IndexPriceEntity> findByMarketIndexIdAndPriceTimestampAndIntervalCodeAndDataSourceId(
            UUID marketIndexId, Instant priceTimestamp, String intervalCode, Long dataSourceId);

    List<IndexPriceEntity> findByMarketIndexIdAndPriceTimestampAndIntervalCode(
            UUID marketIndexId, Instant priceTimestamp, String intervalCode);
}
