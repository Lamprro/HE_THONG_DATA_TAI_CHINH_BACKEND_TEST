package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "security_index_memberships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SecurityIndexMembershipEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "security_id")
    private UUID securityId;

    @Column(name = "market_index_id")
    private UUID marketIndexId;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "weight")
    private BigDecimal weight;

    @Column(name = "created_at")
    private Instant createdAt;

    public static SecurityIndexMembershipEntity open(
            UUID securityId, UUID marketIndexId, LocalDate effectiveFrom, BigDecimal weight) {
        SecurityIndexMembershipEntity entity = new SecurityIndexMembershipEntity();
        entity.securityId = securityId;
        entity.marketIndexId = marketIndexId;
        entity.effectiveFrom = effectiveFrom;
        entity.weight = weight;
        entity.createdAt = Instant.now();
        return entity;
    }

    public void correctSameDay(BigDecimal weight) {
        this.weight = weight;
    }

    public void reopenSameDay(BigDecimal weight) {
        this.effectiveTo = null;
        this.weight = weight;
    }

    public void close(LocalDate effectiveTo) {
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must not be before effectiveFrom");
        }
        this.effectiveTo = effectiveTo;
    }
}
