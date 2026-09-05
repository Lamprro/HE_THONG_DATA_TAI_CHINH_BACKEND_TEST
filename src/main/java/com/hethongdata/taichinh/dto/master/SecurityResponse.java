package com.hethongdata.taichinh.dto.master;

import com.hethongdata.taichinh.entity.master.SecurityEntity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class SecurityResponse {

    private final UUID id;

    private final UUID companyId;

    private final String symbol;

    private final String exchange;

    private final String securityType;

    private final String isin;

    private final String currency;

    private final LocalDate listedDate;

    private final LocalDate delistedDate;

    private final BigDecimal sharesOutstanding;

    private final BigDecimal parValue;

    private final boolean primary;

    private final boolean active;

    public static SecurityResponse from(SecurityEntity value) {
        return new SecurityResponse(
                value.getId(),
                value.getCompanyId(),
                value.getSymbol(),
                value.getExchange(),
                value.getSecurityType(),
                value.getIsin(),
                value.getCurrency(),
                value.getListedDate(),
                value.getDelistedDate(),
                value.getSharesOutstanding(),
                value.getParValue(),
                Boolean.TRUE.equals(value.getIsPrimary()),
                Boolean.TRUE.equals(value.getIsActive()));
    }
}
