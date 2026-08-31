package com.hethongdata.taichinh.dto.master;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Creating an active security provisions its deterministic raw-ingestion jobs in the same workflow. */
public record SecurityRequest(
        @NotNull UUID companyId,
        @NotBlank String symbol,
        @NotBlank String exchange,
        @NotBlank String securityType,
        String isin,
        @NotBlank String currency,
        LocalDate listedDate,
        LocalDate delistedDate,
        BigDecimal sharesOutstanding,
        BigDecimal parValue,
        Boolean primary,
        Boolean active) {
}
