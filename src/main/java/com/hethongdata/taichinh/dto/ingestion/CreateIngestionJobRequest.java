package com.hethongdata.taichinh.dto.ingestion;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public final class CreateIngestionJobRequest {

    @NotBlank private String dataSourceCode;

    @NotBlank private String code;

    @NotBlank private String name;

    @NotBlank private String datasetType;

    private String cronExpression;

    @NotNull private JsonNode parameters;

    @PositiveOrZero private Short maxRetries;

    @Positive private Integer timeoutSeconds;

    private Boolean active;
}
