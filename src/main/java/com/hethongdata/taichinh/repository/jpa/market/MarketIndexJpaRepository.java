package com.hethongdata.taichinh.repository.jpa.market;

import com.hethongdata.taichinh.entity.MarketIndexEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface MarketIndexJpaRepository extends JpaRepository<MarketIndexEntity, UUID> {
    Optional<MarketIndexEntity> findByCodeIgnoreCase(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select index from MarketIndexEntity index where upper(index.code) = upper(:code)")
    Optional<MarketIndexEntity> findByCodeForUpdate(@Param("code") String code);
}
