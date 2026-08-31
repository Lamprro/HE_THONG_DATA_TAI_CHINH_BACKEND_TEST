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
@Table(name = "securities")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SecurityEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "symbol")
    private String symbol;

    @Column(name = "exchange")
    private String exchange;

    @Column(name = "security_type")
    private String securityType;

    @Column(name = "isin")
    private String isin;

    @Column(name = "currency")
    private String currency;

    @Column(name = "listed_date")
    private LocalDate listedDate;

    @Column(name = "delisted_date")
    private LocalDate delistedDate;

    @Column(name = "shares_outstanding")
    private BigDecimal sharesOutstanding;

    @Column(name = "par_value")
    private BigDecimal parValue;

    @Column(name = "is_primary")
    private Boolean isPrimary;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

}
