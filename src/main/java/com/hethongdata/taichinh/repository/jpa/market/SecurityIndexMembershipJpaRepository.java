package com.hethongdata.taichinh.repository.jpa.market;

import com.hethongdata.taichinh.entity.SecurityIndexMembershipEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SecurityIndexMembershipJpaRepository
        extends JpaRepository<SecurityIndexMembershipEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select membership from SecurityIndexMembershipEntity membership "
            + "where membership.marketIndexId = :indexId and membership.effectiveTo is null")
    List<SecurityIndexMembershipEntity> findOpenForUpdate(@Param("indexId") UUID indexId);

    Optional<SecurityIndexMembershipEntity> findByMarketIndexIdAndSecurityIdAndEffectiveFrom(
            UUID marketIndexId, UUID securityId, LocalDate effectiveFrom);

    Optional<SecurityIndexMembershipEntity> findTopByMarketIndexIdOrderByEffectiveFromDesc(
            UUID marketIndexId);

    Page<SecurityIndexMembershipEntity> findByMarketIndexIdOrderByEffectiveFromDesc(
            UUID marketIndexId, Pageable pageable);
}
