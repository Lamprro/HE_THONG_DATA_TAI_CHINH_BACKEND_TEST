package com.hethongdata.taichinh.entity.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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

    public static CompanyEntity create(
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
        CompanyEntity entity = new CompanyEntity();
        entity.apply(
                taxCode,
                companyCode,
                legalName,
                shortName,
                englishName,
                industryCode,
                industryName,
                sectorName,
                website,
                headquarters,
                foundedDate,
                listingStatus,
                description,
                active);
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    /**
     * Updates only the company master record; aliases and securities are managed by their own
     * services.
     */
    public void update(
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
        apply(
                taxCode,
                companyCode,
                legalName,
                shortName,
                englishName,
                industryCode,
                industryName,
                sectorName,
                website,
                headquarters,
                foundedDate,
                listingStatus,
                description,
                active);
        updatedAt = Instant.now();
    }

    private void apply(
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
        this.taxCode = taxCode;
        this.companyCode = companyCode;
        this.legalName = legalName;
        this.shortName = shortName;
        this.englishName = englishName;
        this.industryCode = industryCode;
        this.industryName = industryName;
        this.sectorName = sectorName;
        this.website = website;
        this.headquarters = headquarters;
        this.foundedDate = foundedDate;
        this.listingStatus = listingStatus;
        this.description = description;
        this.isActive = active;
    }
}
