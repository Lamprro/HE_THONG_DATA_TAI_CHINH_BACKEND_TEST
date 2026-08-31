package com.hethongdata.taichinh.entity.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "data_sources")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "base_url")
    private String baseUrl;

    private String provider;

    @Column(nullable = false)
    private short priority = 100;

    @Column(name = "is_official", nullable = false)
    private boolean official;

    @Column(name = "license_status", nullable = false)
    private String licenseStatus = "UNKNOWN";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "rate_limit_note")
    private String rateLimitNote;

    @Column(name = "terms_note")
    private String termsNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static DataSourceEntity create(
            String code,
            String name,
            String sourceType,
            String baseUrl,
            String provider,
            boolean official,
            String licenseStatus) {
        DataSourceEntity entity = new DataSourceEntity();
        entity.code = code;
        entity.name = name;
        entity.sourceType = sourceType;
        entity.baseUrl = baseUrl;
        entity.provider = provider;
        entity.official = official;
        entity.licenseStatus = licenseStatus;
        entity.active = true;
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    public void update(String name, String baseUrl, String provider, boolean active) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.provider = provider;
        this.active = active;
        this.updatedAt = Instant.now();
    }

}


