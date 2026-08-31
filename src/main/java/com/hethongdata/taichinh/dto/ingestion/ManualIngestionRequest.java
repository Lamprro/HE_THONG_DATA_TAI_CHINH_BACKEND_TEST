package com.hethongdata.taichinh.dto.ingestion;

import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Map;

public record ManualIngestionRequest(
        @NotNull ExternalOperation operation,
        String provider,
        String symbol,
        LocalDate startDate,
        LocalDate endDate,
        String interval,
        Map<String, String> parameters) {

    public Map<String, String> safeParameters() {
        return parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
