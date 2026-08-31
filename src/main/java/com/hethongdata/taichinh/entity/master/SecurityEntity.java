package com.hethongdata.taichinh.entity.master;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", columnDefinition = "char(3)")
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

    public static SecurityEntity create(
            UUID companyId, String symbol, String exchange, String securityType, String isin, String currency,
            LocalDate listedDate, LocalDate delistedDate, BigDecimal sharesOutstanding, BigDecimal parValue,
            boolean primary, boolean active) {
        SecurityEntity entity = new SecurityEntity();
        entity.apply(companyId, symbol, exchange, securityType, isin, currency, listedDate, delistedDate,
                sharesOutstanding, parValue, primary, active);
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    public void update(
            UUID companyId, String symbol, String exchange, String securityType, String isin, String currency,
            LocalDate listedDate, LocalDate delistedDate, BigDecimal sharesOutstanding, BigDecimal parValue,
            boolean primary, boolean active) {
        apply(companyId, symbol, exchange, securityType, isin, currency, listedDate, delistedDate,
                sharesOutstanding, parValue, primary, active);
        updatedAt = Instant.now();
    }

    public void setActive(boolean active) {
        this.isActive = active;
        this.updatedAt = Instant.now();
    }

    private void apply(
            UUID companyId, String symbol, String exchange, String securityType, String isin, String currency,
            LocalDate listedDate, LocalDate delistedDate, BigDecimal sharesOutstanding, BigDecimal parValue,
            boolean primary, boolean active) {
        this.companyId = companyId;
        this.symbol = symbol;
        this.exchange = exchange;
        this.securityType = securityType;
        this.isin = isin;
        this.currency = currency;
        this.listedDate = listedDate;
        this.delistedDate = delistedDate;
        this.sharesOutstanding = sharesOutstanding;
        this.parValue = parValue;
        this.isPrimary = primary;
        this.isActive = active;
    }

}
