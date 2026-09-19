package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.SecurityIndexMembershipEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecurityIndexMembershipJpaRepository
        extends JpaRepository<SecurityIndexMembershipEntity, UUID> {
    List<SecurityIndexMembershipEntity> findByMarketIndexIdAndEffectiveToIsNull(UUID marketIndexId);

    Optional<SecurityIndexMembershipEntity> findByMarketIndexIdAndSecurityIdAndEffectiveFrom(
            UUID marketIndexId, UUID securityId, LocalDate effectiveFrom);
}
