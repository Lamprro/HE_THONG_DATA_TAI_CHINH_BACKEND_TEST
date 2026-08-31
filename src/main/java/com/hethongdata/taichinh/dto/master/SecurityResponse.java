package com.hethongdata.taichinh.dto.master;

import com.hethongdata.taichinh.entity.master.SecurityEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SecurityResponse(UUID id, UUID companyId, String symbol, String exchange, String securityType,
                               String isin, String currency, LocalDate listedDate, LocalDate delistedDate,
                               BigDecimal sharesOutstanding, BigDecimal parValue, boolean primary, boolean active) {
    public static SecurityResponse from(SecurityEntity value) {
        return new SecurityResponse(value.getId(), value.getCompanyId(), value.getSymbol(), value.getExchange(),
                value.getSecurityType(), value.getIsin(), value.getCurrency(), value.getListedDate(), value.getDelistedDate(),
                value.getSharesOutstanding(), value.getParValue(), Boolean.TRUE.equals(value.getIsPrimary()),
                Boolean.TRUE.equals(value.getIsActive()));
    }
}
