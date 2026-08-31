package com.hethongdata.taichinh.dto.source;

import jakarta.validation.constraints.NotBlank;

public record DataSourceRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String sourceType,
        String baseUrl,
        String provider,
        boolean official,
        String licenseStatus,
        boolean active) {
}
