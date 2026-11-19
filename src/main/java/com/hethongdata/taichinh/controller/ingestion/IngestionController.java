package com.hethongdata.taichinh.controller.ingestion;

import com.hethongdata.taichinh.dto.ingestion.CreateIngestionJobRequest;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.dto.ingestion.IngestionJobResponse;
import com.hethongdata.taichinh.dto.ingestion.IngestionRunResponse;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.dto.ingestion.RawPayloadResponse;
import com.hethongdata.taichinh.dto.ingestion.UpdateIngestionJobActivationRequest;
import com.hethongdata.taichinh.dto.source.DataSourceDtos.DataSourceRequest;
import com.hethongdata.taichinh.dto.source.DataSourceDtos.DataSourceResponse;
import com.hethongdata.taichinh.service.ingestion.IngestionJobService;
import com.hethongdata.taichinh.service.ingestion.IngestionService;
import com.hethongdata.taichinh.service.source.DataSourceService;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Raw-ingestion admin routes: data sources ({@code /api/data-sources}), job definitions
 * ({@code /api/ingestion-jobs}), runs ({@code /api/ingestions}) and raw payload diagnostics
 * ({@code /api/raw-payloads}).
 */
@RestController
public class IngestionController {

    private final IngestionService ingestionService;
    private final IngestionJobService ingestionJobService;
    private final DataSourceService dataSourceService;
    private final boolean bodyAccessEnabled;

    public IngestionController(
            IngestionService ingestionService,
            IngestionJobService ingestionJobService,
            DataSourceService dataSourceService,
            @Value("${financial.raw-diagnostics.include-body-enabled:false}")
                    boolean bodyAccessEnabled) {
        this.ingestionService = ingestionService;
        this.ingestionJobService = ingestionJobService;
        this.dataSourceService = dataSourceService;
        this.bodyAccessEnabled = bodyAccessEnabled;
    }

    // --- /api/data-sources

    @GetMapping("/api/data-sources")
    public List<DataSourceResponse> listDataSources() {
        return dataSourceService.list();
    }

    @PostMapping("/api/data-sources")
    public ResponseEntity<DataSourceResponse> upsertDataSource(
            @Valid @RequestBody DataSourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dataSourceService.upsert(request));
    }

    // --- /api/ingestion-jobs

    @GetMapping("/api/ingestion-jobs")
    public List<IngestionJobResponse> listJobs(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return activeOnly ? ingestionJobService.listActive() : ingestionJobService.listAll();
    }

    @PutMapping("/api/ingestion-jobs/{jobId}")
    public IngestionJobResponse updateJob(
            @PathVariable UUID jobId, @Valid @RequestBody CreateIngestionJobRequest request) {
        return ingestionJobService.update(jobId, request);
    }

    @PostMapping("/api/ingestion-jobs")
    public ResponseEntity<IngestionJobResponse> createJob(
            @Valid @RequestBody CreateIngestionJobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ingestionJobService.create(request));
    }

    @PostMapping("/api/ingestion-jobs/{jobId}/run")
    public ResponseEntity<IngestionExecutionResponse> runJobNow(@PathVariable UUID jobId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ingestionJobService.runNow(jobId));
    }

    @PatchMapping("/api/ingestion-jobs/{jobId}/activation")
    public IngestionJobResponse setJobActivation(
            @PathVariable UUID jobId,
            @Valid @RequestBody UpdateIngestionJobActivationRequest request) {
        return ingestionJobService.setActive(jobId, request);
    }

    @PostMapping("/api/ingestion-jobs/{jobId}/versions/{versionId}/build")
    public IngestionExecutionResponse buildVersion(
            @PathVariable UUID jobId, @PathVariable UUID versionId) {
        return ingestionJobService.runVersion(jobId, versionId);
    }

    // --- /api/ingestions

    @PostMapping("/api/ingestions/manual")
    public ResponseEntity<IngestionExecutionResponse> manual(
            @Valid @RequestBody ManualIngestionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ingestionService.ingest(body));
    }

    @GetMapping("/api/ingestions/{runId}")
    public ResponseEntity<IngestionRunResponse> findRun(@PathVariable UUID runId) {
        return ingestionService
                .findRun(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/ingestions")
    public List<IngestionRunResponse> latestRuns(@RequestParam(defaultValue = "20") int limit) {
        return ingestionService.latestRuns(limit);
    }

    // --- /api/raw-payloads

    @GetMapping("/api/raw-payloads/{rawPayloadId}")
    public ResponseEntity<RawPayloadResponse> findPayload(
            @PathVariable UUID rawPayloadId,
            @RequestParam(defaultValue = "false") boolean includeBody) {
        return ingestionService
                .findPayload(rawPayloadId, includeBody && bodyAccessEnabled)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/raw-payloads")
    public List<RawPayloadResponse> findPayloadsByRun(
            @RequestParam UUID runId, @RequestParam(defaultValue = "false") boolean includeBody) {
        return ingestionService.findPayloadsByRun(runId, includeBody && bodyAccessEnabled);
    }
}
