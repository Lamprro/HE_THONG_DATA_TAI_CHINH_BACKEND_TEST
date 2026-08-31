package com.hethongdata.taichinh.controller;

import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalOperation;
import com.hethongdata.taichinh.domain.ingestion.IngestionRun;
import com.hethongdata.taichinh.repository.IngestionRunRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionExecutionException;
import com.hethongdata.taichinh.service.ingestion.IngestionResult;
import com.hethongdata.taichinh.service.ingestion.IngestionService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    private final IngestionRunRepository ingestionRunRepository;

    public IngestionController(
            IngestionService ingestionService,
            IngestionRunRepository ingestionRunRepository) {
        this.ingestionService = ingestionService;
        this.ingestionRunRepository = ingestionRunRepository;
    }

    @PostMapping("/manual")
    public ResponseEntity<IngestionResult> manual(@RequestBody ManualIngestionRequest body) {
        ExternalFetchRequest request = new ExternalFetchRequest(
                body.operation(),
                body.provider(),
                body.symbol(),
                body.startDate(),
                body.endDate(),
                body.interval(),
                body.parameters());
        return ResponseEntity.status(HttpStatus.CREATED).body(ingestionService.ingest(request));
    }

    @GetMapping("/{runId}")
    public ResponseEntity<IngestionRun> findById(@PathVariable UUID runId) {
        return ingestionRunRepository.findById(runId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<IngestionRun> latest(@RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return ingestionRunRepository.findLatest(safeLimit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                Instant.now(), "VALIDATION", null, null, exception.getMessage()));
    }

    @ExceptionHandler(IngestionExecutionException.class)
    public ResponseEntity<ErrorResponse> ingestionFailure(IngestionExecutionException exception) {
        HttpStatus status = exception.upstreamStatus() == null
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.resolve(exception.upstreamStatus());
        if (status == null || status.is2xxSuccessful()) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return ResponseEntity.status(status).body(new ErrorResponse(
                Instant.now(),
                exception.category().name(),
                exception.runId(),
                exception.upstreamStatus(),
                exception.getMessage()));
    }

    public record ManualIngestionRequest(
            ExternalOperation operation,
            String provider,
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            String interval,
            Map<String, String> parameters) {
    }

    public record ErrorResponse(
            Instant timestamp,
            String category,
            UUID runId,
            Integer upstreamStatus,
            String message) {
    }
}
