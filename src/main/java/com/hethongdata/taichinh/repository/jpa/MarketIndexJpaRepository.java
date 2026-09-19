package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.MarketIndexEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MarketIndexJpaRepository extends JpaRepository<MarketIndexEntity, UUID> {
    Optional<MarketIndexEntity> findByCodeIgnoreCase(String code);
}
