package com.hethongdata.taichinh.dto.master;

import com.hethongdata.taichinh.entity.master.CompanyEntity;
import java.time.LocalDate;
import java.util.UUID;

public record CompanyResponse(UUID id, String taxCode, String companyCode, String legalName, String shortName,
                              String englishName, String industryCode, String industryName, String sectorName,
                              String website, String headquarters, LocalDate foundedDate, String listingStatus,
                              String description, boolean active) {
    public static CompanyResponse from(CompanyEntity value) {
        return new CompanyResponse(value.getId(), value.getTaxCode(), value.getCompanyCode(), value.getLegalName(),
                value.getShortName(), value.getEnglishName(), value.getIndustryCode(), value.getIndustryName(),
                value.getSectorName(), value.getWebsite(), value.getHeadquarters(), value.getFoundedDate(),
                value.getListingStatus(), value.getDescription(), Boolean.TRUE.equals(value.getIsActive()));
    }
}
