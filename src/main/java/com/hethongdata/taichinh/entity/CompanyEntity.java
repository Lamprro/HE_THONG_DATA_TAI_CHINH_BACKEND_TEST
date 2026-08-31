package com.hethongdata.taichinh.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "companies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompanyEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "tax_code")
    private String taxCode;

    @Column(name = "company_code")
    private String companyCode;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "short_name")
    private String shortName;

    @Column(name = "english_name")
    private String englishName;

    @Column(name = "industry_code")
    private String industryCode;

    @Column(name = "industry_name")
    private String industryName;

    @Column(name = "sector_name")
    private String sectorName;

    @Column(name = "website")
    private String website;

    @Column(name = "headquarters")
    private String headquarters;

    @Column(name = "founded_date")
    private LocalDate foundedDate;

    @Column(name = "listing_status")
    private String listingStatus;

    @Column(name = "description")
    private String description;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

}
