package com.hethongdata.taichinh.controller;

import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.dto.ingestion.IngestionRunResponse;
import com.hethongdata.taichinh.dto.ingestion.ManualIngestionRequest;
import com.hethongdata.taichinh.service.ingestion.IngestionReadService;
import com.hethongdata.taichinh.service.ingestion.IngestionService;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingestions")
public class IngestionController {

    private final IngestionService ingestionService;
    private final IngestionReadService ingestionReadService;

    public IngestionController(
            IngestionService ingestionService,
            IngestionReadService ingestionReadService) {
        this.ingestionService = ingestionService;
        this.ingestionReadService = ingestionReadService;
    }

    @PostMapping("/manual")
    public ResponseEntity<IngestionExecutionResponse> manual(@Valid @RequestBody ManualIngestionRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ingestionService.ingest(body));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<IngestionRunResponse> findById(@PathVariable UUID runId) {
        return ingestionReadService.findRun(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<IngestionRunResponse> latest(@RequestParam(defaultValue = "20") int limit) {
        return ingestionReadService.latestRuns(limit);
    }
}
