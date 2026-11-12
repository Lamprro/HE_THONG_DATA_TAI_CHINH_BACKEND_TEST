package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.service.financial.FinancialDataQualityService;
import com.hethongdata.taichinh.service.financial.FinancialDisclosureDocumentStore;
import com.hethongdata.taichinh.service.financial.FinancialReconciliationEvidenceService;
import com.hethongdata.taichinh.service.financial.FinancialValueBindingService;
import com.hethongdata.taichinh.service.financial.HistoricalFinancialMetricService;
import com.hethongdata.taichinh.service.market.TrainingDataQualityService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Financial statement quality, disclosures, bindings and metric recalculation. Access is enforced by AdminAccessConfiguration. */
@RestController
@RequestMapping("/api/admin/financial-data")
public class FinancialDataAdminController {
    public record RefreshRequest(String symbol, Integer year, Integer page) {}

    private final HistoricalFinancialMetricService metrics;
    private final TrainingDataQualityService trainingQuality;
    private final FinancialDataQualityService quality;
    private final FinancialDisclosureDocumentStore documents;
    private final FinancialValueBindingService bindings;
    private final FinancialReconciliationEvidenceService reconciliation;

    public FinancialDataAdminController(HistoricalFinancialMetricService metrics, TrainingDataQualityService trainingQuality,
            FinancialDataQualityService quality, FinancialDisclosureDocumentStore documents,
            FinancialValueBindingService bindings, FinancialReconciliationEvidenceService reconciliation) {
        this.metrics = metrics;
        this.trainingQuality = trainingQuality;
        this.quality = quality;
        this.documents = documents;
        this.bindings = bindings;
        this.reconciliation = reconciliation;
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

    @PostMapping("/disclosures/refresh")
    public Map<String, Object> refreshDisclosures(@RequestBody RefreshRequest request) {
        return quality.refresh(request.symbol(), request.year(), request.page() == null ? 1 : request.page());
    }

    @GetMapping("/disclosures")
    public List<Map<String, Object>> disclosures(@RequestParam String symbol, @RequestParam(defaultValue = "100") int limit) {
        return documents.list(symbol, limit);
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String symbol) { return quality.status(symbol); }

    @GetMapping("/coverage")
    public List<Map<String, Object>> coverage() { return quality.coverage(); }

    @GetMapping("/reconciliation")
    public List<Map<String, Object>> reconciliation(@RequestParam String symbol) { return reconciliation.list(symbol); }

    @PostMapping("/bindings/reconcile")
    public Map<String, Object> reconcileBindings(@RequestBody RefreshRequest request) { return bindings.reconcile(request.symbol()); }

    @GetMapping("/bindings")
    public List<Map<String, Object>> bindings(@RequestParam String symbol, @RequestParam Instant asOf,
            @RequestParam(defaultValue = "100") int limit) {
        return bindings.list(symbol, asOf, limit);
    }
}
