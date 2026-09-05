package com.hethongdata.taichinh.dto.ingestion;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Separate activation command so scheduling state is never changed implicitly. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public final class UpdateIngestionJobActivationRequest {

    @NotNull private Boolean active;
}
