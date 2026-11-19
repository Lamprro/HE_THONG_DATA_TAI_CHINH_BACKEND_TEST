package com.hethongdata.taichinh.dto.source;

import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;

import jakarta.validation.constraints.NotBlank;

/** Admin contracts for {@code /api/data-sources}. */
public final class DataSourceDtos {
    private DataSourceDtos() {}

    public record DataSourceRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotBlank String sourceType,
            String baseUrl,
            String provider,
            boolean official,
            String licenseStatus,
            boolean active) {}

    public record DataSourceResponse(
            long id,
            String code,
            String name,
            String provider,
            String sourceType,
            short priority,
            boolean official,
            String licenseStatus,
            String baseUrl,
            boolean active) {
        public static DataSourceResponse from(DataSourceEntity entity) {
            return new DataSourceResponse(
                    entity.getId(),
                    entity.getCode(),
                    entity.getName(),
                    entity.getProvider(),
                    entity.getSourceType(),
                    entity.getPriority(),
                    entity.isOfficial(),
                    entity.getLicenseStatus(),
                    entity.getBaseUrl(),
                    entity.isActive());
        }
    }
}
