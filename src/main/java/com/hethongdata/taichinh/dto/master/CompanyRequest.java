package com.hethongdata.taichinh.dto.master;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Admin-owned company master record. A blank optional field is normalized to null by the service.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public final class CompanyRequest {

    private String taxCode;

    @NotBlank private String companyCode;

    @NotBlank private String legalName;

    private String shortName;

    private String englishName;

    private String industryCode;

    private String industryName;

    private String sectorName;

    private String website;

    private String headquarters;

    private LocalDate foundedDate;

    private String listingStatus;

    private String description;

    private Boolean active;
}
