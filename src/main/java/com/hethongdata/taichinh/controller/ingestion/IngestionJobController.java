package com.hethongdata.taichinh.controller.ingestion;

import com.hethongdata.taichinh.dto.ingestion.CreateIngestionJobRequest;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.dto.ingestion.IngestionJobResponse;
import com.hethongdata.taichinh.dto.ingestion.UpdateIngestionJobActivationRequest;
import com.hethongdata.taichinh.service.ingestion.IngestionJobService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/ingestion-jobs")
public class IngestionJobController {

    private final IngestionJobService ingestionJobService;

    public IngestionJobController(IngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    @GetMapping
    public List<IngestionJobResponse> listActive() {
        return ingestionJobService.listActive();
    }

    @PostMapping
    public ResponseEntity<IngestionJobResponse> create(
            @Valid @RequestBody CreateIngestionJobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ingestionJobService.create(request));
    }

    @PostMapping("/{jobId}/run")
    public ResponseEntity<IngestionExecutionResponse> runNow(@PathVariable UUID jobId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ingestionJobService.runNow(jobId));
    }

    @PatchMapping("/{jobId}/activation")
    public IngestionJobResponse setActivation(
            @PathVariable UUID jobId,
            @Valid @RequestBody UpdateIngestionJobActivationRequest request) {
        return ingestionJobService.setActive(jobId, request);
    }
}
