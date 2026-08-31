package com.hethongdata.taichinh.dto.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record CreateIngestionJobRequest(
        @NotBlank String dataSourceCode,
        @NotBlank String code,
        @NotBlank String name,
        @NotBlank String datasetType,
        String cronExpression,
        @NotNull JsonNode parameters,
        @PositiveOrZero Short maxRetries,
        @Positive Integer timeoutSeconds,
        Boolean active) {
}
