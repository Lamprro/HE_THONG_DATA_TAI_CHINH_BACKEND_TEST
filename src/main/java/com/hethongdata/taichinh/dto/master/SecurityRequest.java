package com.hethongdata.taichinh.dto.master;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Creating an active security provisions its deterministic raw-ingestion jobs in the same workflow.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public final class SecurityRequest {

    @NotNull private UUID companyId;

    @NotBlank private String symbol;

    @NotBlank private String exchange;

    @NotBlank private String securityType;

    private String isin;

    @NotBlank private String currency;

    private LocalDate listedDate;

    private LocalDate delistedDate;

    private BigDecimal sharesOutstanding;

    private BigDecimal parValue;

    private Boolean primary;

    private Boolean active;
}
