package com.hethongdata.taichinh.dto.ingestion;

import com.hethongdata.taichinh.application.port.model.ExternalOperation;

import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public final class ManualIngestionRequest {

    @NotNull private ExternalOperation operation;

    private String provider;

    private String symbol;

    private LocalDate startDate;

    private LocalDate endDate;

    private String interval;

    private Map<String, String> parameters;

    public Map<String, String> safeParameters() {
        return parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
