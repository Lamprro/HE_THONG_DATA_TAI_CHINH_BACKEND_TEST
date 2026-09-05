package com.hethongdata.taichinh.dto.source;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public final class DataSourceRequest {

    @NotBlank private String code;

    @NotBlank private String name;

    @NotBlank private String sourceType;

    private String baseUrl;

    private String provider;

    private boolean official;

    private String licenseStatus;

    private boolean active;
}
