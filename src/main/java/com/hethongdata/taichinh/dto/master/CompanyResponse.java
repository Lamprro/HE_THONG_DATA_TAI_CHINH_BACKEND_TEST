package com.hethongdata.taichinh.dto.master;

import com.hethongdata.taichinh.entity.master.CompanyEntity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public final class CompanyResponse {

    private final UUID id;

    private final String taxCode;

    private final String companyCode;

    private final String legalName;

    private final String shortName;

    private final String englishName;

    private final String industryCode;

    private final String industryName;

    private final String sectorName;

    private final String website;

    private final String headquarters;

    private final LocalDate foundedDate;

    private final String listingStatus;

    private final String description;

    private final boolean active;

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
