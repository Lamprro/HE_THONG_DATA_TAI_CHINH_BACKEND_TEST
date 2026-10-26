package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.service.financial.HistoricalFinancialMetricService;
import com.hethongdata.taichinh.service.market.TrainingDataQualityService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Financial data quality and recalculation share one admin boundary. */
@RestController
@RequestMapping("/api/admin/financial-data")
public class FinancialDataAdminController {
    private final HistoricalFinancialMetricService metrics;
    private final TrainingDataQualityService quality;
    private final String token;

    public FinancialDataAdminController(HistoricalFinancialMetricService metrics, TrainingDataQualityService quality,
            @Value("${financial.admin.api-token:}") String token) {
        this.metrics = metrics;
        this.quality = quality;
        this.token = token;
    }

    @PostMapping("/metrics/recalculate")
    public HistoricalFinancialMetricService.Result calculate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody HistoricalFinancialMetricService.Request request) {
        authorize(authorization);
        return metrics.calculate(request);
    }

    @GetMapping("/quality/coverage")
    public Map<String, Object> coverage(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authorize(authorization);
        return quality.coverage();
    }

    @PostMapping("/quality/review-legacy/{symbol}")
    public Map<String, Object> review(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String symbol) throws Exception {
        authorize(authorization);
        return quality.reviewLegacy(symbol);
    }

    private void authorize(String authorization) {
        if (token.length() < 32)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Admin token not configured");
        if (authorization == null || !authorization.startsWith("Bearer ") || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), authorization.substring(7).getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin credential required");
    }
}
