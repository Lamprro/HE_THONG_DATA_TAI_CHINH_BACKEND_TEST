package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.service.financial.FinancialDataQualityService;
import com.hethongdata.taichinh.service.financial.HistoricalFinancialMetricService;
import com.hethongdata.taichinh.service.market.TrainingDataQualityService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Financial statement quality and metric recalculation. Access is enforced by SecurityConfiguration. */
@RestController
@RequestMapping("/api/admin/financial-data")
public class FinancialDataAdminController {

    private final HistoricalFinancialMetricService metrics;
    private final TrainingDataQualityService trainingQuality;
    private final FinancialDataQualityService quality;

    public FinancialDataAdminController(HistoricalFinancialMetricService metrics, TrainingDataQualityService trainingQuality,
            FinancialDataQualityService quality) {
        this.metrics = metrics;
        this.trainingQuality = trainingQuality;
        this.quality = quality;
    }

    @PostMapping("/metrics/recalculate")
    public HistoricalFinancialMetricService.Result calculate(@RequestBody HistoricalFinancialMetricService.Request request) {
        return metrics.calculate(request);
    }

    @GetMapping("/quality/coverage")
    public Map<String, Object> trainingCoverage() { return trainingQuality.coverage(); }

    @PostMapping("/quality/review-legacy/{symbol}")
    public Map<String, Object> reviewLegacy(@PathVariable String symbol) throws Exception {
        return trainingQuality.reviewLegacy(symbol);
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String symbol) { return quality.status(symbol); }

    @GetMapping("/coverage")
    public List<Map<String, Object>> coverage() { return quality.coverage(); }

    @GetMapping("/gaps")
    public Map<String, Object> gaps(
            @RequestParam String symbol,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false, defaultValue = "PRICE") String type,
            @RequestParam(required = false) String statementType,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to) {
        return quality.gaps(symbol, exchange, type, statementType, from, to);
    }
}
