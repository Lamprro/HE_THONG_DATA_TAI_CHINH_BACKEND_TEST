package com.hethongdata.taichinh.dto.master;

import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import com.hethongdata.taichinh.entity.master.CompanyEntity;
import com.hethongdata.taichinh.entity.master.SecurityEntity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Admin master-data contracts for {@code /api/companies} and {@code /api/securities}. */
public final class MasterDataDtos {
    private MasterDataDtos() {}

    /**
     * Admin-owned company master record. A blank optional field is normalized to null by the
     * service.
     */
    public record CompanyRequest(
            String taxCode,
            @NotBlank String companyCode,
            @NotBlank String legalName,
            String shortName,
            String englishName,
            String industryCode,
            String industryName,
            String sectorName,
            String website,
            String headquarters,
            LocalDate foundedDate,
            String listingStatus,
            String description,
            Boolean active) {}

    public record CompanyResponse(
            UUID id,
            String taxCode,
            String companyCode,
            String legalName,
            String shortName,
            String englishName,
            String industryCode,
            String industryName,
            String sectorName,
            String website,
            String headquarters,
            LocalDate foundedDate,
            String listingStatus,
            String description,
            boolean active) {
        public static CompanyResponse from(CompanyEntity value) {
            return new CompanyResponse(
                    value.getId(),
                    value.getTaxCode(),
                    value.getCompanyCode(),
                    value.getLegalName(),
                    value.getShortName(),
                    value.getEnglishName(),
                    value.getIndustryCode(),
                    value.getIndustryName(),
                    value.getSectorName(),
                    value.getWebsite(),
                    value.getHeadquarters(),
                    value.getFoundedDate(),
                    value.getListingStatus(),
                    value.getDescription(),
                    Boolean.TRUE.equals(value.getIsActive()));
        }
    }

    public record CompanyAliasRequest(@NotBlank String alias, @NotBlank String aliasType) {}

    public record CompanyAliasResponse(Long id, String alias, String aliasType) {
        public static CompanyAliasResponse from(CompanyAliasEntity value) {
            return new CompanyAliasResponse(value.getId(), value.getAlias(), value.getAliasType());
        }
    }

    /**
     * Creating an active security provisions its deterministic raw-ingestion jobs in the same
     * workflow.
     */
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
            Boolean active) {}

    public record SecurityResponse(
            UUID id,
            UUID companyId,
            String symbol,
            String exchange,
            String securityType,
            String isin,
            String currency,
            LocalDate listedDate,
            LocalDate delistedDate,
            BigDecimal sharesOutstanding,
            BigDecimal parValue,
            boolean primary,
            boolean active) {
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

    public record UpdateActivationRequest(@NotNull Boolean active) {}
}
