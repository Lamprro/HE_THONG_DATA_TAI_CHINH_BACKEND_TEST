package com.hethongdata.taichinh.dto.master;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/** Admin-owned company master record. A blank optional field is normalized to null by the service. */
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
        Boolean active) {
}
