package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

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

}
